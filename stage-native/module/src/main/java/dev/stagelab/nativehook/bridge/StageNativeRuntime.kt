package dev.stagelab.nativehook.bridge

import android.app.ActivityManager
import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import de.robv.android.xposed.XposedHelpers
import dev.stagelab.nativecore.DockIntegrationController
import dev.stagelab.nativecore.RecentStageController
import dev.stagelab.nativecore.StageRegistry
import dev.stagelab.nativecore.StageSession
import dev.stagelab.nativecore.StageTask
import dev.stagelab.nativecore.StageTransitionController
import dev.stagelab.nativecore.WindowBounds
import dev.stagelab.nativehook.ui.strip.RecentStripOverlayController
import dev.stagelab.nativehook.xposed.HookGuard
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-SystemUI runtime coordinator connecting ColorOS native task events to the
 * frozen Stage state machine (`StageTransitionController`) and `ColorOsNativeWindowBridge`.
 */
object StageNativeRuntime {

    const val ACTION_STAGE_CMD = "dev.stagelab.nativehook.ACTION_STAGE_CMD"

    val registry = StageRegistry()
    val bridge = ColorOsNativeWindowBridge()
    val transitionController = StageTransitionController(registry, bridge)
    val recentController = RecentStageController(transitionController)
    val dockController = DockIntegrationController(transitionController, bridge)

    val stripOverlay = RecentStripOverlayController(
        onStageClicked = { stageId ->
            val session = recentController.onStageThumbnailClicked(stageId)
            updateStripUi(session)
        },
        onStageDraggedToCenter = { stageId ->
            val session = recentController.onStageTaskDraggedToCenter(stageId)
            updateStripUi(session)
        },
        onLeftEdgeSwipeReveal = {
            recentController.onLeftEdgeSwipeReveal()
            updateStripUi(registry.snapshot())
        },
    )

    private val receiverRegistered = AtomicBoolean(false)

    /**
     * Tracks pending programmatic launches: packageName -> Pair(mergeIntoActive, timestampMs).
     * Expires after 8 seconds so unrelated manual launches are never misclassified.
     */
    private val pendingLaunches = ConcurrentHashMap<String, Pair<Boolean, Long>>()

    fun attachOrganizerIfNeeded(organizer: Any) {
        bridge.attachShellTaskOrganizer(organizer)
        ensureCommandReceiverRegistered()
    }

    private fun ensureCommandReceiverRegistered() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val ctx = runCatching { AndroidAppHelper.currentApplication()?.applicationContext }.getOrNull()
        if (ctx == null) {
            receiverRegistered.set(false)
            return
        }
        try {
            val filter = IntentFilter(ACTION_STAGE_CMD)
            ctx.registerReceiver(commandReceiver, filter, Context.RECEIVER_EXPORTED)
            HookGuard.info("Registered N4 command receiver: $ACTION_STAGE_CMD")
        } catch (t: Throwable) {
            receiverRegistered.set(false)
            HookGuard.warn("Failed to register command receiver", t)
        }
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_STAGE_CMD) return
            HookGuard.safeRun("CommandReceiver.onReceive") {
                handleCommand(intent)
            }
        }
    }

    private fun handleCommand(intent: Intent) {
        val cmd = intent.getStringExtra("cmd").orEmpty()
        val pkg = intent.getStringExtra("pkg").orEmpty()
        val stageId = intent.getStringExtra("stageId").orEmpty()
        val taskId = intent.getIntExtra("taskId", -1)

        HookGuard.info("CMD received: cmd=$cmd pkg=$pkg stageId=$stageId taskId=$taskId")
        val session: StageSession = when (cmd) {
            "dump" -> registry.snapshot()
            "launch" -> {
                if (pkg.isNotBlank()) {
                    pendingLaunches[pkg] = false to System.currentTimeMillis()
                    dockController.onDockAppClicked(pkg)
                } else {
                    registry.snapshot()
                }
            }
            "merge" -> {
                when {
                    taskId > 0 -> transitionController.mergeExistingTaskIntoActiveStage(taskId)
                    pkg.isNotBlank() -> {
                        pendingLaunches[pkg] = true to System.currentTimeMillis()
                        dockController.onDockAppDraggedToCenter(pkg)
                    }
                    else -> registry.snapshot()
                }
            }
            "switch" -> {
                if (stageId.isNotBlank()) {
                    recentController.onStageThumbnailClicked(stageId)
                } else {
                    registry.snapshot()
                }
            }
            "switch_next" -> recentController.onFourFingerSwipeNext()
            "minimize" -> {
                if (taskId > 0) dockController.onWindowMenuMinimize(taskId) else registry.snapshot()
            }
            "close" -> {
                if (taskId > 0) dockController.onWindowMenuClose(taskId) else registry.snapshot()
            }
            "sync_live" -> syncFromLiveOrganizer()
            "strip_hide" -> {
                val hidden = intent.getBooleanExtra("hidden", false)
                recentController.setHiddenByUserSetting(hidden)
                registry.snapshot()
            }
            "reset" -> {
                registry.clear()
                pendingLaunches.clear()
                stripOverlay.detach()
                registry.snapshot()
            }
            else -> {
                HookGuard.warn("Unknown CMD: $cmd")
                registry.snapshot()
            }
        }
        logSession("CMD_RESULT($cmd)", session)
        updateStripUi(session)
    }

    fun onNativeTaskAppeared(taskInfo: ActivityManager.RunningTaskInfo) {
        val pkg = resolvePkg(taskInfo)
        if (!isManagedUserApp(pkg)) return

        val mergeFlag = consumePendingMergeFlag(pkg)
        val stageTask = toStageTask(taskInfo, pkg)
        val session = transitionController.onNativeTaskAppeared(stageTask, mergeIntoActive = mergeFlag)
        if (isTaskFocused(taskInfo)) {
            transitionController.onNativeTaskFocused(taskInfo.taskId)
        }
        logSession("ON_APPEARED(t=${taskInfo.taskId},merge=$mergeFlag)", session)
        updateStripUi(session)
    }

    fun onNativeTaskInfoChanged(taskInfo: ActivityManager.RunningTaskInfo) {
        val pkg = resolvePkg(taskInfo)
        if (!isManagedUserApp(pkg)) return

        val stageTask = toStageTask(taskInfo, pkg)
        var session = transitionController.onNativeTaskInfoChanged(stageTask)
        if (isTaskFocused(taskInfo)) {
            session = transitionController.onNativeTaskFocused(taskInfo.taskId)
        }
        logSession("ON_CHANGED(t=${taskInfo.taskId})", session)
        updateStripUi(session)
    }

    fun onNativeTaskVanished(taskInfo: ActivityManager.RunningTaskInfo) {
        val pkg = resolvePkg(taskInfo)
        if (!isManagedUserApp(pkg)) return
        val session = transitionController.onNativeTaskVanished(taskInfo.taskId)
        logSession("ON_VANISHED(t=${taskInfo.taskId})", session)
        updateStripUi(session)
    }

    private fun updateStripUi(session: StageSession) {
        val ctx = runCatching { AndroidAppHelper.currentApplication()?.applicationContext }.getOrNull() ?: return
        stripOverlay.update(
            context = ctx,
            session = session,
            hidden = recentController.isHiddenByUserSetting,
            tempRevealed = recentController.isTemporarilyRevealed,
        )
    }

    fun syncFromLiveOrganizer(): StageSession {
        val live = bridge.queryLiveRunningTasks()
            .mapNotNull { info ->
                val pkg = resolvePkg(info)
                if (!isManagedUserApp(pkg)) null else toStageTask(info, pkg)
            }
        return transitionController.restoreFromSystemTasks(live)
    }

    private fun consumePendingMergeFlag(pkg: String): Boolean {
        val entry = pendingLaunches.remove(pkg) ?: return false
        val (merge, ts) = entry
        return if (System.currentTimeMillis() - ts <= 8_000L) merge else false
    }

    private fun toStageTask(taskInfo: ActivityManager.RunningTaskInfo, pkg: String): StageTask {
        val bounds = extractBounds(taskInfo)
        // Ignore off-screen parked bounds (e.g. Rect(-2520, 0 - 0, 1680)) so original on-screen bounds are preserved.
        val validBounds = if (bounds.right > 0 && bounds.left < 2450 && bounds.width() > 0 && bounds.height() > 0) {
            WindowBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
        } else {
            WindowBounds.EMPTY
        }
        return StageTask(
            taskId = taskInfo.taskId,
            packageName = pkg,
            componentName = taskInfo.topActivity?.flattenToShortString().orEmpty(),
            bounds = validBounds,
            windowingMode = extractWindowingMode(taskInfo),
            isRunning = isTaskRunning(taskInfo),
            isVisible = isTaskVisible(taskInfo),
        )
    }

    fun logSession(prefix: String, session: StageSession) {
        val activeDesc = session.activeStage?.let { st ->
            "${st.stageId}:[${st.tasks.joinToString(",") { "${it.taskId}:${it.packageName}" }}]"
        } ?: "null"
        val recentDesc = session.visibleRecentStages.joinToString("; ") { st ->
            "${st.stageId}:[${st.tasks.joinToString(",") { "${it.taskId}:${it.packageName}" }}]"
        }
        HookGuard.info("$prefix active=$activeDesc | recent=[$recentDesc] (totalRecent=${session.allRecentStages.size})")
    }

    fun resolvePkg(taskInfo: ActivityManager.RunningTaskInfo): String {
        val top = taskInfo.topActivity ?: taskInfo.baseActivity
        return top?.packageName ?: taskInfo.baseIntent.component?.packageName ?: ""
    }

    fun extractBounds(taskInfo: ActivityManager.RunningTaskInfo): Rect {
        return runCatching {
            val config = XposedHelpers.getObjectField(taskInfo, "configuration") as? Configuration ?: return Rect()
            val winConfig = XposedHelpers.getObjectField(config, "windowConfiguration") ?: return Rect()
            XposedHelpers.callMethod(winConfig, "getBounds") as? Rect ?: Rect()
        }.getOrDefault(Rect())
    }

    fun extractWindowingMode(taskInfo: ActivityManager.RunningTaskInfo): Int {
        return runCatching {
            val config = XposedHelpers.getObjectField(taskInfo, "configuration") as? Configuration ?: return 0
            val winConfig = XposedHelpers.getObjectField(config, "windowConfiguration") ?: return 0
            XposedHelpers.callMethod(winConfig, "getWindowingMode") as? Int ?: 0
        }.getOrDefault(0)
    }

    fun isTaskVisible(taskInfo: ActivityManager.RunningTaskInfo): Boolean {
        return runCatching {
            XposedHelpers.callMethod(taskInfo, "isVisible") as? Boolean
        }.getOrNull() ?: runCatching {
            XposedHelpers.getBooleanField(taskInfo, "isVisible")
        }.getOrDefault(false)
    }

    fun isTaskRunning(taskInfo: ActivityManager.RunningTaskInfo): Boolean {
        return runCatching {
            XposedHelpers.getBooleanField(taskInfo, "isRunning")
        }.getOrDefault(true)
    }

    fun isTaskFocused(taskInfo: ActivityManager.RunningTaskInfo): Boolean =
        runCatching { XposedHelpers.getBooleanField(taskInfo, "isFocused") }.getOrDefault(false)

    fun isManagedUserApp(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return pkg != "com.android.launcher" &&
            pkg != "com.android.systemui" &&
            pkg != "com.android.permissioncontroller" &&
            pkg != "com.android.packageinstaller" &&
            pkg != "com.oplus.appdetail"
    }
}
