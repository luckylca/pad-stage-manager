package dev.stagelab.nativehook.bridge

import android.app.ActivityManager
import android.app.AndroidAppHelper
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedHelpers
import dev.stagelab.nativecore.DockIntegrationController
import dev.stagelab.nativecore.RecentStageController
import dev.stagelab.nativecore.StageRegistry
import dev.stagelab.nativecore.StageSession
import dev.stagelab.nativecore.StageTask
import dev.stagelab.nativecore.StageTransitionController
import dev.stagelab.nativecore.WindowBounds
import dev.stagelab.nativehook.ui.dock.StageDockOverlayController
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

    val dockOverlay = StageDockOverlayController(
        onDockAppClicked = { pkg ->
            pendingLaunches[pkg] = false to System.currentTimeMillis()
            val session = dockController.onDockAppClicked(pkg)
            updateStripUi(session)
        },
        onDockAppDraggedToCenter = { pkg ->
            pendingLaunches[pkg] = true to System.currentTimeMillis()
            val session = dockController.onDockAppDraggedToCenter(pkg)
            updateStripUi(session)
        },
        onBottomSwipeReveal = {
            dockController.onBottomSwipeReveal()
            updateStripUi(registry.snapshot())
        },
    )

    private val receiverRegistered = AtomicBoolean(false)

    @Volatile
    private var isDeviceLocked = false

    @Volatile
    private var stageManagerEnabled = true

    data class SavedGeometry(
        val bounds: WindowBounds,
        val scale: Float = 1.0f,
        val userCustomized: Boolean = false,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val taskGeometryMemory = ConcurrentHashMap<Int, SavedGeometry>()
    private val pkgGeometryMemory = ConcurrentHashMap<String, SavedGeometry>()
    private val taskAppearedAtMs = ConcurrentHashMap<Int, Long>()
    private val lastPromotedAtMs = ConcurrentHashMap<Int, Long>()
    @Volatile
    private var lastHomeGestureAtMs: Long = 0L
    @Volatile
    private var programmaticTransitionUntilMs: Long = 0L

    fun markProgrammaticTransition(durationMs: Long = 1_200L) {
        val until = System.currentTimeMillis() + durationMs
        if (until > programmaticTransitionUntilMs) {
            programmaticTransitionUntilMs = until
        }
    }

    /**
     * Tracks pending programmatic launches: packageName -> Pair(mergeIntoActive, timestampMs).
     * Expires after 8 seconds so unrelated manual launches are never misclassified.
     */
    private val pendingLaunches = ConcurrentHashMap<String, Pair<Boolean, Long>>()

    fun getMemorizedGeometry(taskId: Int, packageName: String): SavedGeometry? =
        taskGeometryMemory[taskId] ?: pkgGeometryMemory[packageName]

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

            // N7 System lifecycle awareness: hide strip on lockscreen, restore on unlock, adapt on rotation.
            val sysFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            }
            ctx.registerReceiver(systemLifecycleReceiver, sysFilter, Context.RECEIVER_EXPORTED)
            HookGuard.info("Registered N6/N7 command & system lifecycle receivers")
        } catch (t: Throwable) {
            receiverRegistered.set(false)
            HookGuard.warn("Failed to register receivers", t)
        }
    }

    private val systemLifecycleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            HookGuard.safeRun("SystemLifecycleReceiver.onReceive") {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isDeviceLocked = true
                        stripOverlay.detach()
                        dockOverlay.detach()
                        bridge.setSystemNavigationLocked(false)
                        HookGuard.info("LIFECYCLE screen off -> Recent Strip & Dock hidden")
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        isDeviceLocked = false
                        updateStripUi(registry.snapshot())
                        HookGuard.info("LIFECYCLE unlocked -> Recent Strip restored")
                    }
                    Intent.ACTION_CONFIGURATION_CHANGED -> {
                        if (!isDeviceLocked) {
                            updateStripUi(registry.snapshot())
                            HookGuard.info("LIFECYCLE config/rotation changed -> Recent Strip re-laid out")
                        }
                    }
                }
            }
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
            "merge_hint" -> {
                if (pkg.isNotBlank()) {
                    pendingLaunches[pkg] = true to System.currentTimeMillis()
                }
                registry.snapshot()
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
            "enable_module" -> {
                stageManagerEnabled = true
                syncFromLiveOrganizer()
            }
            "sync_live" -> syncFromLiveOrganizer()
            "strip_hide" -> {
                val hidden = intent.getBooleanExtra("hidden", false)
                recentController.setHiddenByUserSetting(hidden)
                registry.snapshot()
            }
            "dock_hide" -> {
                val hidden = intent.getBooleanExtra("hidden", false)
                dockController.setDockHiddenBySetting(hidden)
                registry.snapshot()
            }
            "disable_module" -> {
                stageManagerEnabled = false
                registry.clear()
                pendingLaunches.clear()
                stripOverlay.detach()
                dockOverlay.detach()
                bridge.setSystemNavigationLocked(false)
                bridge.notifyLauncherBackdrop(false)
                runCatching {
                    AndroidAppHelper.currentApplication()?.applicationContext?.let {
                        updateExitNotification(it, false)
                    }
                }
                registry.snapshot()
            }
            "reset" -> {
                registry.clear()
                pendingLaunches.clear()
                stripOverlay.detach()
                bridge.notifyLauncherBackdrop(false)
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

        taskAppearedAtMs[taskInfo.taskId] = System.currentTimeMillis()
        val mergeFlag = consumePendingMergeFlag(pkg)
        val stageTask = toStageTask(taskInfo, pkg)
        val session = transitionController.onNativeTaskAppeared(stageTask, mergeIntoActive = mergeFlag)
        if (isTaskFocused(taskInfo)) {
            transitionController.onNativeTaskFocused(taskInfo.taskId)
        }

        // If an app was launched fullscreen from outside (e.g. notification shade / deep link) while
        // Stage Manager is enabled, seamlessly promote it into a widescreen Flexible Stage window.
        if (stageManagerEnabled && isFullScreenBounds(stageTask.bounds) && isTaskFocused(taskInfo)) {
            promoteIfNotDebounced(taskInfo.taskId, pkg)
        }

        logSession("ON_APPEARED(t=${taskInfo.taskId},merge=$mergeFlag)", session)
        updateStripUi(session)
    }

    fun onNativeTaskInfoChanged(taskInfo: ActivityManager.RunningTaskInfo) {
        val pkg = resolvePkg(taskInfo)

        // In desktop-replacement mode (stageManagerEnabled == true), Launcher is our blurred Stage canvas
        // and Home/Recents gestures are locked natively — do not auto-retreat active Stage on Launcher focus.
        if (pkg == "com.android.launcher") {
            if (!stageManagerEnabled && isTaskFocused(taskInfo) && isTaskVisible(taskInfo)) {
                handleHomeGestureRetreat()
            }
            return
        }

        if (!isManagedUserApp(pkg)) return

        val stageTask = toStageTask(taskInfo, pkg)

        // External notification / fullscreen launch detection
        if (stageManagerEnabled && stageTask.isVisible && isTaskFocused(taskInfo) && isFullScreenBounds(stageTask.bounds)) {
            // Park any lingering previous active stage tasks and promote this notification-launched app to Stage
            val currentActive = registry.getActiveStage()
            if (currentActive != null && !currentActive.containsTask(taskInfo.taskId)) {
                val prevTasks = currentActive.tasks
                registry.deactivateStageToRecentTop(currentActive.stageId)
                bridge.parkNativeTasks(prevTasks)
            }
            promoteIfNotDebounced(taskInfo.taskId, pkg)
        }

        var session = transitionController.onNativeTaskInfoChanged(stageTask)
        if (isTaskFocused(taskInfo)) {
            session = transitionController.onNativeTaskFocused(taskInfo.taskId)
        }
        logSession("ON_CHANGED(t=${taskInfo.taskId})", session)
        updateStripUi(session)
    }

    private fun handleHomeGestureRetreat() {
        val now = System.currentTimeMillis()
        if (now < programmaticTransitionUntilMs) return
        if (now - lastHomeGestureAtMs < 600L) return
        val active = registry.getActiveStage() ?: return
        if (active.tasks.isEmpty()) return
        lastHomeGestureAtMs = now

        val tasksToPark = active.tasks
        registry.deactivateStageToRecentTop(active.stageId)
        bridge.parkNativeTasks(tasksToPark)
        val session = registry.snapshot()
        logSession("HOME_GESTURE_RETREAT parked=${tasksToPark.map { it.taskId }}", session)
        updateStripUi(session)
    }

    private fun promoteIfNotDebounced(taskId: Int, pkg: String) {
        val now = System.currentTimeMillis()
        val last = lastPromotedAtMs[taskId] ?: 0L
        if (now - last < 1_500L) return
        lastPromotedAtMs[taskId] = now
        markProgrammaticTransition(1_200L)
        bridge.promoteFullscreenTaskToStage(taskId, pkg)
    }

    fun onNativeTaskVanished(taskInfo: ActivityManager.RunningTaskInfo) {
        val pkg = resolvePkg(taskInfo)
        if (!isManagedUserApp(pkg)) return
        taskGeometryMemory.remove(taskInfo.taskId)
        taskAppearedAtMs.remove(taskInfo.taskId)
        val session = transitionController.onNativeTaskVanished(taskInfo.taskId)
        logSession("ON_VANISHED(t=${taskInfo.taskId})", session)
        updateStripUi(session)
    }

    fun onNativeMenuMinimize(taskId: Int) {
        val session = dockController.onWindowMenuMinimize(taskId)
        logSession("ON_NATIVE_MENU_MINIMIZE(t=$taskId)", session)
        updateStripUi(session)
    }

    private fun updateStripUi(session: StageSession) {
        val ctx = runCatching { AndroidAppHelper.currentApplication()?.applicationContext }.getOrNull()
        if (isDeviceLocked || !stageManagerEnabled) {
            stripOverlay.detach()
            dockOverlay.detach()
            bridge.setSystemNavigationLocked(false)
            bridge.notifyLauncherBackdrop(false)
            if (!stageManagerEnabled && ctx != null) {
                updateExitNotification(ctx, false)
            }
            return
        }

        // Desktop-replacement mode: keep blurred wallpaper backdrop always on, lock Home/Recents gestures,
        // and show a persistent notification so the user can tap once to exit Stage Manager mode.
        bridge.notifyLauncherBackdrop(true)
        bridge.setSystemNavigationLocked(true)

        if (ctx == null) return
        updateExitNotification(ctx, true)
        stripOverlay.update(
            context = ctx,
            session = session,
            hidden = recentController.isHiddenByUserSetting,
            tempRevealed = recentController.isTemporarilyRevealed,
        )
        dockOverlay.update(
            context = ctx,
            session = session,
            enabled = stageManagerEnabled,
            hidden = dockController.isDockHiddenBySetting,
            tempRevealed = dockController.isDockTemporarilyRevealed,
        )
    }

    @Volatile
    private var exitNotificationShown = false

    private fun updateExitNotification(context: Context, show: Boolean) {
        if (show == exitNotificationShown) return
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (!show) {
                nm.cancel(EXIT_NOTIFICATION_ID)
                exitNotificationShown = false
                HookGuard.info("NOTIFICATION exit notice cancelled")
                return
            }
            val channel = NotificationChannel(
                EXIT_CHANNEL_ID,
                "台前调度模式",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "台前调度运行状态与一键退出入口"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)

            val exitIntent = Intent(ACTION_STAGE_CMD).apply {
                setPackage("com.android.systemui")
                putExtra("cmd", "disable_module")
            }
            val pendingExit = PendingIntent.getBroadcast(
                context,
                EXIT_NOTIFICATION_ID,
                exitIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = Notification.Builder(context, EXIT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("台前调度模式运行中")
                .setContentText("已接管桌面与导航手势 · 点击此处退出台前调度模式")
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(pendingExit)
                .build()

            nm.notify(EXIT_NOTIFICATION_ID, notification)
            exitNotificationShown = true
            HookGuard.info("NOTIFICATION exit notice posted (id=$EXIT_NOTIFICATION_ID)")
        }.onFailure {
            HookGuard.warn("NOTIFICATION update failed (show=$show)", it)
        }
    }

    private const val EXIT_CHANNEL_ID = "stagelab_stage_mode"
    private const val EXIT_NOTIFICATION_ID = 20260418

    private fun isFullScreenBounds(b: WindowBounds): Boolean {
        val dm = runCatching { AndroidAppHelper.currentApplication()?.resources?.displayMetrics }.getOrNull()
        val sw = dm?.widthPixels ?: 2520
        val sh = dm?.heightPixels ?: 1680
        return b.left == 0 && b.top == 0 && b.right == sw && b.bottom == sh
    }

    private fun extractFlexibleScale(taskInfo: ActivityManager.RunningTaskInfo): Float {
        return runCatching {
            val fwmCls = Class.forName("com.oplus.flexiblewindow.FlexibleWindowManager")
            val config = XposedHelpers.getObjectField(taskInfo, "configuration") as? Configuration ?: return 1.0f
            val extra = XposedHelpers.callStaticMethod(fwmCls, "getExtraConfiguration", config) ?: return 1.0f
            // Default to 1.0f if extra config does not expose scale directly
            runCatching { XposedHelpers.getFloatField(extra, "mScale") }.getOrNull()
                ?.takeIf { it in 0.35f..1.05f } ?: 1.0f
        }.getOrDefault(1.0f)
    }

    private fun computeVisibleBounds(raw: WindowBounds, scale: Float): WindowBounds {
        if (scale >= 0.99f) return raw
        val w = (raw.width * scale).toInt().coerceAtLeast(300)
        val h = (raw.height * scale).toInt().coerceAtLeast(300)
        return WindowBounds(raw.left, raw.top, raw.left + w, raw.top + h)
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
