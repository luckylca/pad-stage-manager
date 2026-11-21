package dev.stagelab.nativehook.bridge

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AndroidAppHelper
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedHelpers
import dev.stagelab.nativecore.NativeWindowBridge
import dev.stagelab.nativecore.StageTask
import dev.stagelab.nativecore.WindowBounds
import dev.stagelab.nativehook.xposed.HookGuard
import dev.stagelab.nativehook.xposed.LauncherBackdropHook

/**
 * N4 NativeWindowBridge backed by ColorOS 17's real `ShellTaskOrganizer`,
 * `WindowContainerTransaction`, and Flexible Window (`windowingMode = 100`).
 *
 * Strictly delegates all window rendering, rounded corners, shadows, drag/resize,
 * IME, Back, and task lifecycle to Android / ColorOS native mechanisms.
 */
class ColorOsNativeWindowBridge : NativeWindowBridge {

    @Volatile
    private var shellTaskOrganizer: Any? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun attachShellTaskOrganizer(organizer: Any) {
        if (shellTaskOrganizer !== organizer) {
            shellTaskOrganizer = organizer
            HookGuard.info("ColorOsNativeWindowBridge attached to ${organizer.javaClass.name}")
        }
    }

    fun getShellTaskOrganizer(): Any? = shellTaskOrganizer

    private fun appContext(): Context? = runCatching {
        AndroidAppHelper.currentApplication()?.applicationContext
    }.getOrNull()

    override fun launchNativeWindow(packageName: String, preferredBounds: WindowBounds?): Int? {
        val ctx = appContext()
        if (ctx == null) {
            HookGuard.warn("launchNativeWindow($packageName): no SystemUI context available")
            return null
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            HookGuard.warn("launchNativeWindow($packageName): no launch intent found")
            return null
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val options = ActivityOptions.makeBasic()
        runCatching {
            XposedHelpers.callMethod(
                options,
                "setLaunchWindowingMode",
                StageTask.WINDOWING_MODE_COLOROS_FLEXIBLE,
            )
        }.onFailure {
            HookGuard.warn("setLaunchWindowingMode(100) reflection failed", it)
        }

        if (preferredBounds != null && !preferredBounds.isEmpty) {
            runCatching {
                options.setLaunchBounds(
                    Rect(
                        preferredBounds.left,
                        preferredBounds.top,
                        preferredBounds.right,
                        preferredBounds.bottom,
                    ),
                )
            }
        }

        mainHandler.post {
            runCatching {
                ctx.startActivity(intent, options.toBundle())
                HookGuard.info("BRIDGE.LAUNCH pkg=$packageName mode=${StageTask.WINDOWING_MODE_COLOROS_FLEXIBLE}")
            }.onFailure {
                HookGuard.warn("BRIDGE.LAUNCH failed for $packageName", it)
            }
        }
        // Task ID arrives asynchronously via ShellTaskOrganizer.onTaskAppeared.
        return null
    }

    override fun switchNativeStage(
        tasksToShow: List<StageTask>,
        tasksToPark: List<StageTask>,
    ) {
        val organizer = shellTaskOrganizer ?: run {
            HookGuard.warn("switchNativeStage: ShellTaskOrganizer not attached yet")
            return
        }
        runCatching {
            val orderedShow = tasksToShow.sortedWith(compareBy<StageTask> { it.zOrder }.thenBy { it.joinedAtMs })
            var delayMs = 0L

            // 1. Serially park outgoing Stage tasks so ColorOS FlexibleTaskController does not drop concurrent animations.
            for (task in tasksToPark) {
                val targetId = task.taskId
                mainHandler.postDelayed({ parkSingleFlexibleTask(targetId) }, delayMs)
                delayMs += FLEXIBLE_STEP_DELAY_MS
            }

            // 2. Serially restore incoming Stage tasks in bottom-to-top Z-order preserving Task IDs & saved bounds.
            if (tasksToPark.isNotEmpty() && orderedShow.isNotEmpty()) {
                delayMs += FLEXIBLE_PHASE_GAP_MS
            }
            for (task in orderedShow) {
                val snapshot = task
                mainHandler.postDelayed({ restoreSingleFlexibleTask(organizer, snapshot) }, delayMs)
                delayMs += FLEXIBLE_STEP_DELAY_MS
            }

            HookGuard.info(
                "BRIDGE.SWITCH scheduled show=${orderedShow.map { it.taskId }} park=${tasksToPark.map { it.taskId }} totalMs=$delayMs"
            )
        }.onFailure {
            HookGuard.warn("BRIDGE.SWITCH failed", it)
        }
    }

    override fun parkNativeTasks(tasks: List<StageTask>) {
        if (tasks.isEmpty()) return
        runCatching {
            var delayMs = 0L
            for (task in tasks) {
                val targetId = task.taskId
                mainHandler.postDelayed({ parkSingleFlexibleTask(targetId) }, delayMs)
                delayMs += FLEXIBLE_STEP_DELAY_MS
            }
            HookGuard.info("BRIDGE.PARK scheduled tasks=${tasks.map { it.taskId }} totalMs=$delayMs")
        }.onFailure {
            HookGuard.warn("BRIDGE.PARK failed", it)
        }
    }

    override fun bringTaskToActiveStage(task: StageTask) {
        val organizer = shellTaskOrganizer ?: return
        runCatching {
            restoreSingleFlexibleTask(organizer, task)
            HookGuard.info("BRIDGE.BRING_TO_ACTIVE task=${task.taskId}")
        }.onFailure {
            HookGuard.warn("BRIDGE.BRING_TO_ACTIVE failed for task=${task.taskId}", it)
        }
    }

    override fun updateTaskBounds(taskId: Int, bounds: WindowBounds) {
        if (bounds.isEmpty) return
        adjustFlexibleTaskFrame(
            taskId = taskId,
            targetBounds = bounds,
            targetScale = 1.0f,
        )
    }

    /**
     * Compute the official iPadOS-style single-window stage bounds:
     * occupies the entire main stage excluding the left slanted Recent Strip, top status bar, and bottom Dock.
     */
    fun computePrimaryStageBounds(stripVisible: Boolean = true): WindowBounds {
        val ctx = appContext()
        val dm = ctx?.resources?.displayMetrics
        val screenW = dm?.widthPixels ?: 2520
        val screenH = dm?.heightPixels ?: 1680
        val density = dm?.density ?: 2.625f
        val isLandscape = screenW >= screenH

        val leftPx = if (stripVisible) {
            if (isLandscape) (168f * density).toInt() else (136f * density).toInt()
        } else {
            (18f * density).toInt()
        }
        val topPx = (34f * density).toInt()
        val rightPx = screenW - (18f * density).toInt()
        val bottomPx = screenH - (82f * density).toInt()
        return WindowBounds(leftPx, topPx, rightPx.coerceAtLeast(leftPx + 400), bottomPx.coerceAtLeast(topPx + 400))
    }

    /**
     * Adjust a ColorOS 17 Flexible Window's exact visible bounds & scale using verified native
     * `FlexibleWindowManager.adjustFlexibleTask(Bundle)` keys.
     */
    fun adjustFlexibleTaskFrame(taskId: Int, targetBounds: WindowBounds, targetScale: Float = 1.0f) {
        if (targetBounds.isEmpty) return
        val rect = Rect(targetBounds.left, targetBounds.top, targetBounds.right, targetBounds.bottom)
        runCatching {
            val fwmCls = Class.forName("com.oplus.flexiblewindow.FlexibleWindowManager")
            val fwm = XposedHelpers.callStaticMethod(fwmCls, "getInstance")
            val bundle = Bundle().apply {
                putInt(KEY_LAUNCH_FLEXIBLE_TASK_ID, taskId)
                putParcelable(KEY_FLEXIBLE_LAUNCH_BOUNDS, rect)
                putFloat(KEY_LAUNCH_SCALE, targetScale.coerceIn(0.45f, 1.0f))
            }
            val result = XposedHelpers.callMethod(fwm, "adjustFlexibleTask", bundle) as? Boolean ?: false
            HookGuard.info("BRIDGE.ADJUST_FLEXIBLE task=$taskId rect=$rect scale=$targetScale ok=$result")
        }.onFailure {
            HookGuard.warn("BRIDGE.ADJUST_FLEXIBLE failed for task=$taskId", it)
        }
    }

    fun notifyLauncherBackdrop(visible: Boolean) {
        val ctx = appContext() ?: return
        runCatching {
            val intent = Intent(LauncherBackdropHook.ACTION_STAGE_BACKDROP).apply {
                setPackage("com.android.launcher")
                putExtra("visible", visible)
            }
            ctx.sendBroadcast(intent)
        }
    }

    override fun showNativeDesktop() {
        val organizer = shellTaskOrganizer
        if (organizer != null) {
            val reorderedHome = runCatching {
                val homeInfo = XposedHelpers.callMethod(organizer, "getHomeOrRecentsTaskInfo")
                    as? ActivityManager.RunningTaskInfo ?: return@runCatching false
                val homeToken = XposedHelpers.getObjectField(homeInfo, "token") ?: return@runCatching false
                val wct = newWindowContainerTransaction() ?: return@runCatching false
                XposedHelpers.callMethod(wct, "reorder", homeToken, true)
                applyTransaction(organizer, wct)
                HookGuard.info("BRIDGE.SHOW_DESKTOP via homeTask=${homeInfo.taskId}")
                true
            }.getOrDefault(false)
            if (reorderedHome) return
        }

        val ctx = appContext() ?: return
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        mainHandler.post {
            runCatching {
                ctx.startActivity(homeIntent)
                HookGuard.info("BRIDGE.SHOW_DESKTOP via ACTION_MAIN/HOME")
            }.onFailure {
                HookGuard.warn("BRIDGE.SHOW_DESKTOP failed", it)
            }
        }
    }

    override fun closeNativeTask(taskId: Int) {
        runCatching {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val service = XposedHelpers.callStaticMethod(atmClass, "getService")
            val removed = XposedHelpers.callMethod(service, "removeTask", taskId) as? Boolean ?: false
            HookGuard.info("BRIDGE.CLOSE_TASK task=$taskId removed=$removed")
        }.onFailure {
            HookGuard.warn("BRIDGE.CLOSE_TASK failed for task=$taskId", it)
        }
    }

    /**
     * Query all currently running user tasks from `ShellTaskOrganizer` for crash/restart recovery.
     */
    fun queryLiveRunningTasks(): List<ActivityManager.RunningTaskInfo> {
        val organizer = shellTaskOrganizer ?: return emptyList()
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val list = XposedHelpers.callMethod(organizer, "getRunningTasks") as? List<ActivityManager.RunningTaskInfo>
            list.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun newWindowContainerTransaction(): Any? = runCatching {
        val wctClass = Class.forName("android.window.WindowContainerTransaction")
        wctClass.getDeclaredConstructor().newInstance()
    }.onFailure {
        HookGuard.warn("Failed to instantiate WindowContainerTransaction", it)
    }.getOrNull()

    private fun parkSingleFlexibleTask(taskId: Int) {
        runCatching {
            val fwmCls = Class.forName("com.oplus.flexiblewindow.FlexibleWindowManager")
            val fwm = XposedHelpers.callStaticMethod(fwmCls, "getInstance")
            val closeEvent = runCatching {
                XposedHelpers.getStaticIntField(fwmCls, "FLEXIBLE_TASK_CLOSE_EVENT")
            }.getOrDefault(2003)
            // Verified signature on ColorOS 17: notifyFlexibleTaskEvent(int taskId, int event, Bundle extra)
            XposedHelpers.callMethod(fwm, "notifyFlexibleTaskEvent", taskId, closeEvent, null)
            HookGuard.info("BRIDGE.PARK_FLEXIBLE task=$taskId event=$closeEvent")
        }.onFailure {
            HookGuard.warn("BRIDGE.PARK_FLEXIBLE failed for task=$taskId", it)
        }
    }

    private fun restoreSingleFlexibleTask(organizer: Any, task: StageTask) {
        val restored = runCatching {
            val options = ActivityOptions.makeBasic()
            runCatching {
                XposedHelpers.callMethod(
                    options,
                    "setLaunchWindowingMode",
                    StageTask.WINDOWING_MODE_COLOROS_FLEXIBLE,
                )
            }
            if (!task.bounds.isEmpty) {
                runCatching {
                    options.setLaunchBounds(
                        Rect(task.bounds.left, task.bounds.top, task.bounds.right, task.bounds.bottom),
                    )
                }
            }
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val service = XposedHelpers.callStaticMethod(atmClass, "getService")
            val rc = XposedHelpers.callMethod(
                service,
                "startActivityFromRecents",
                task.taskId,
                options.toBundle(),
            ) as? Int ?: -1
            HookGuard.info("BRIDGE.RESTORE_RECENTS task=${task.taskId} bounds=${task.bounds} rc=$rc")
            rc >= 0
        }.onFailure {
            HookGuard.warn("BRIDGE.RESTORE_RECENTS failed for task=${task.taskId}", it)
        }.getOrDefault(false)

        if (!restored) {
            runCatching {
                val token = resolveTaskToken(organizer, task.taskId) ?: return
                val wct = newWindowContainerTransaction() ?: return
                XposedHelpers.callMethod(wct, "reorder", token, true)
                applyTransaction(organizer, wct)
                HookGuard.info("BRIDGE.RESTORE_FALLBACK_REORDER task=${task.taskId}")
            }
        }

        // Re-apply memorized bounds & scale once ColorOS restore transition settles.
        val memorized = StageNativeRuntime.getMemorizedGeometry(task.taskId, task.packageName)
        val targetBounds = memorized?.bounds?.takeIf { !it.isEmpty } ?: task.bounds
        val targetScale = memorized?.scale ?: 1.0f
        if (!targetBounds.isEmpty) {
            mainHandler.postDelayed({
                adjustFlexibleTaskFrame(task.taskId, targetBounds, targetScale)
            }, RESTORE_GEOMETRY_DELAY_MS)
        }
    }

    private fun resolveHomeToken(organizer: Any): Any? = runCatching {
        val homeInfo = XposedHelpers.callMethod(organizer, "getHomeOrRecentsTaskInfo")
            as? ActivityManager.RunningTaskInfo ?: return null
        XposedHelpers.getObjectField(homeInfo, "token")
    }.getOrNull()

    private fun resolveTaskToken(organizer: Any, taskId: Int): Any? = runCatching {
        val info = XposedHelpers.callMethod(organizer, "getRunningTaskInfo", taskId)
            as? ActivityManager.RunningTaskInfo ?: return null
        XposedHelpers.getObjectField(info, "token")
    }.getOrNull()

    private fun applyTransaction(organizer: Any, wct: Any) {
        XposedHelpers.callMethod(organizer, "applyTransaction", wct)
    }

    companion object {
        private const val FLEXIBLE_STEP_DELAY_MS = 180L
        private const val FLEXIBLE_PHASE_GAP_MS = 120L
        private const val RESTORE_GEOMETRY_DELAY_MS = 320L
        private const val KEY_LAUNCH_FLEXIBLE_TASK_ID = "androidx.activity.LaunchFlexibleTaskId"
        private const val KEY_FLEXIBLE_LAUNCH_BOUNDS = "androidx.flexible.LaunchBounds"
        private const val KEY_LAUNCH_SCALE = "androidx.activity.LaunchScale"
    }
}
