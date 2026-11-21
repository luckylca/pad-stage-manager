package dev.stagelab.nativehook.bridge

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AndroidAppHelper
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedHelpers
import dev.stagelab.nativecore.NativeWindowBridge
import dev.stagelab.nativecore.StageTask
import dev.stagelab.nativecore.WindowBounds
import dev.stagelab.nativehook.xposed.HookGuard
import dev.stagelab.nativehook.xposed.LauncherBackdropHook
import dev.stagelab.nativehook.xposed.SystemServerFlexibleHook

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
    private val navLockToken = Binder()
    @Volatile
    private var lastNavLocked: Boolean? = null

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

        // Resolve memorized bounds/scale or official single-window primary stage bounds (full stage minus left strip & Dock).
        val memorized = StageNativeRuntime.getMemorizedGeometry(-1, packageName)
        val targetBounds = preferredBounds?.takeIf { !it.isEmpty }
            ?: memorized?.bounds?.takeIf { !it.isEmpty }
            ?: computePrimaryStageBounds(stripVisible = !StageNativeRuntime.recentController.isHiddenByUserSetting)
        val targetScale = memorized?.scale ?: 1.0f

        applyColorOsLaunchBounds(options, targetBounds, targetScale)

        StageNativeRuntime.markProgrammaticTransition(1_400L)
        mainHandler.post {
            runCatching {
                ctx.startActivity(intent, options.toBundle())
                HookGuard.info(
                    "BRIDGE.LAUNCH pkg=$packageName mode=${StageTask.WINDOWING_MODE_COLOROS_FLEXIBLE} " +
                        "bounds=$targetBounds scale=$targetScale"
                )
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
        StageNativeRuntime.markProgrammaticTransition(1_200L)
        runCatching {
            val orderedShow = tasksToShow.sortedWith(compareBy<StageTask> { it.zOrder }.thenBy { it.joinedAtMs })
            val wct = newWindowContainerTransaction()
            var atomicOps = 0
            val fallbackPark = mutableListOf<StageTask>()
            val fallbackShow = mutableListOf<StageTask>()

            if (wct != null) {
                // 1. Atomically hide + reorder ALL outgoing tasks in ONE single WCT (zero transition queueing!)
                for (task in tasksToPark) {
                    val token = resolveTaskToken(organizer, task.taskId)
                    if (token != null) {
                        runCatching { XposedHelpers.callMethod(wct, "setHidden", token, true) }
                        XposedHelpers.callMethod(wct, "reorder", token, false)
                        atomicOps++
                    } else {
                        fallbackPark.add(task)
                    }
                }
                // 2. Atomically unhide + raise ALL incoming Stage tasks in the EXACT SAME WCT!
                for (task in orderedShow) {
                    val token = resolveTaskToken(organizer, task.taskId)
                    if (token != null && isTaskCurrentlyFlexibleBounds(organizer, task.taskId)) {
                        runCatching { XposedHelpers.callMethod(wct, "setHidden", token, false) }
                        XposedHelpers.callMethod(wct, "reorder", token, true)
                        atomicOps++
                    } else {
                        fallbackShow.add(task)
                    }
                }
            } else {
                fallbackPark.addAll(tasksToPark)
                fallbackShow.addAll(orderedShow)
            }

            mainHandler.post {
                if (wct != null && atomicOps > 0) {
                    runCatching {
                        applyTransaction(organizer, wct)
                        HookGuard.info(
                            "BRIDGE.SWITCH_ATOMIC ops=$atomicOps show=${orderedShow.map { it.taskId }} park=${tasksToPark.map { it.taskId }}"
                        )
                    }.onFailure {
                        HookGuard.warn("BRIDGE.SWITCH_ATOMIC failed, falling back", it)
                        for (t in tasksToPark) parkSingleFlexibleTask(t.taskId)
                        for (t in orderedShow) restoreSingleFlexibleTask(organizer, t)
                    }
                }
                for (t in fallbackPark) parkSingleFlexibleTask(t.taskId)
                for (t in fallbackShow) restoreSingleFlexibleTask(organizer, t)
            }
        }.onFailure {
            HookGuard.warn("BRIDGE.SWITCH failed", it)
        }
    }

    override fun parkNativeTasks(tasks: List<StageTask>) {
        if (tasks.isEmpty()) return
        val organizer = shellTaskOrganizer
        runCatching {
            val wct = if (organizer != null) newWindowContainerTransaction() else null
            var atomicOps = 0
            val fallbackPark = mutableListOf<StageTask>()
            if (organizer != null && wct != null) {
                for (task in tasks) {
                    val token = resolveTaskToken(organizer, task.taskId)
                    if (token != null) {
                        runCatching { XposedHelpers.callMethod(wct, "setHidden", token, true) }
                        XposedHelpers.callMethod(wct, "reorder", token, false)
                        atomicOps++
                    } else {
                        fallbackPark.add(task)
                    }
                }
            } else {
                fallbackPark.addAll(tasks)
            }
            mainHandler.post {
                if (organizer != null && wct != null && atomicOps > 0) {
                    runCatching {
                        applyTransaction(organizer, wct)
                        HookGuard.info("BRIDGE.PARK_ATOMIC ops=$atomicOps tasks=${tasks.map { it.taskId }}")
                    }.onFailure {
                        HookGuard.warn("BRIDGE.PARK_ATOMIC failed, falling back", it)
                        for (t in tasks) parkSingleFlexibleTask(t.taskId)
                    }
                }
                for (t in fallbackPark) parkSingleFlexibleTask(t.taskId)
            }
        }.onFailure {
            HookGuard.warn("BRIDGE.PARK failed", it)
        }
    }

    /**
     * Promote an externally launched fullscreen task (e.g. opened from notification shade)
     * directly into a widescreen Flexible Stage window.
     */
    fun promoteFullscreenTaskToStage(taskId: Int, packageName: String) {
        val organizer = shellTaskOrganizer ?: return
        val dummyTask = StageTask(
            taskId = taskId,
            packageName = packageName,
            bounds = WindowBounds.EMPTY,
            windowingMode = StageTask.WINDOWING_MODE_COLOROS_FLEXIBLE,
        )
        mainHandler.post {
            restoreSingleFlexibleTask(organizer, dummyTask)
            HookGuard.info("BRIDGE.PROMOTE_TO_STAGE task=$taskId pkg=$packageName")
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
        val isLandscape = screenW >= screenH

        return if (isLandscape) {
            val leftPx = if (stripVisible) (screenW * 0.185f).toInt() else (screenW * 0.032f).toInt()
            val topPx = (screenH * 0.058f).toInt()
            val rightPx = screenW - (screenW * 0.032f).toInt()
            val bottomPx = screenH - (screenH * 0.195f).toInt()
            WindowBounds(leftPx, topPx, rightPx.coerceAtLeast(leftPx + 400), bottomPx.coerceAtLeast(topPx + 400))
        } else {
            val leftPx = if (stripVisible) (screenW * 0.21f).toInt() else (screenW * 0.03f).toInt()
            val topPx = (screenH * 0.045f).toInt()
            val rightPx = screenW - (screenW * 0.03f).toInt()
            val bottomPx = screenH - (screenH * 0.145f).toInt()
            WindowBounds(leftPx, topPx, rightPx.coerceAtLeast(leftPx + 400), bottomPx.coerceAtLeast(topPx + 400))
        }
    }

    /**
     * Compute equal-height side-by-side bounds (left + right) for a 2-window Active Stage,
     * with a crisp 28px gap between the two windows and zero overlap.
     */
    fun computeSideBySideBounds(stripVisible: Boolean = true): Pair<WindowBounds, WindowBounds> {
        val stage = computePrimaryStageBounds(stripVisible)
        val gap = 28
        val halfW = ((stage.width - gap) / 2).coerceAtLeast(420)
        val left = WindowBounds(stage.left, stage.top, stage.left + halfW, stage.bottom)
        val right = WindowBounds(stage.left + halfW + gap, stage.top, stage.right, stage.bottom)
        return left to right
    }

    /**
     * Injects ColorOS 17 Flexible Window launch bounds & scale directly into `ActivityOptions`
     * via `FlexibleWindowManager.setExtraBundle`, so newly launched & restored windows immediately
     * open at the exact target size and position without scale/handle desync.
     */
    private fun applyColorOsLaunchBounds(options: ActivityOptions, bounds: WindowBounds, scale: Float) {
        if (bounds.isEmpty) return
        val rect = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        runCatching { options.setLaunchBounds(rect) }
        runCatching {
            val fwmCls = Class.forName("com.oplus.flexiblewindow.FlexibleWindowManager")
            val fwm = XposedHelpers.callStaticMethod(fwmCls, "getInstance")
            val extra = Bundle().apply {
                putInt(KEY_LAUNCH_SCENARIO, 1)
                putParcelable(KEY_FLEXIBLE_LAUNCH_BOUNDS, rect)
                putParcelable(KEY_FLEXIBLE_LAUNCH_MAX_BOUNDS, rect)
                putParcelable(KEY_FLEXIBLE_LAUNCH_FILLIN_BOUNDS, rect)
                putParcelable(KEY_EXTRA_FLEXIBLE_WINDOW_RECT, rect)
                putFloat(KEY_LAUNCH_SCALE, scale.coerceIn(0.45f, 1.0f))
            }
            XposedHelpers.callMethod(fwm, "setExtraBundle", options, extra)
        }.onFailure {
            HookGuard.warn("applyColorOsLaunchBounds setExtraBundle failed", it)
        }
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
                putBoolean(SystemServerFlexibleHook.KEY_FORCE_ADJUST, true)
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

    /**
     * Locks or unlocks system Home & Recents/Overview navigation gestures natively via
     * `IStatusBarService.disable(DISABLE_HOME | DISABLE_RECENT, token, pkg)`.
     * Because the lock is bound to `navLockToken`, if SystemUI ever restarts, Android
     * automatically unlinks and restores normal navigation.
     */
    fun setSystemNavigationLocked(locked: Boolean) {
        if (lastNavLocked == locked) return
        runCatching {
            val smCls = Class.forName("android.os.ServiceManager")
            val binder = XposedHelpers.callStaticMethod(smCls, "getService", "statusbar")
                ?: return@runCatching
            val stubCls = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            val service = XposedHelpers.callStaticMethod(stubCls, "asInterface", binder)
                ?: return@runCatching
            val flags = if (locked) (DISABLE_HOME or DISABLE_RECENT) else 0
            XposedHelpers.callMethod(service, "disable", flags, navLockToken, "com.android.systemui")
            lastNavLocked = locked
            HookGuard.info("BRIDGE.NAV_LOCK locked=$locked flags=0x${flags.toString(16)}")
        }.onFailure {
            HookGuard.warn("BRIDGE.NAV_LOCK failed (locked=$locked)", it)
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
        val memorized = StageNativeRuntime.getMemorizedGeometry(task.taskId, task.packageName)
        val targetBounds = memorized?.bounds?.takeIf { !it.isEmpty }
            ?: task.bounds.takeIf { !it.isEmpty }
            ?: computePrimaryStageBounds(stripVisible = !StageNativeRuntime.recentController.isHiddenByUserSetting)
        val targetScale = memorized?.scale ?: 1.0f

        val restored = runCatching {
            val options = ActivityOptions.makeBasic()
            runCatching {
                XposedHelpers.callMethod(
                    options,
                    "setLaunchWindowingMode",
                    StageTask.WINDOWING_MODE_COLOROS_FLEXIBLE,
                )
            }
            applyColorOsLaunchBounds(options, targetBounds, targetScale)

            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val service = XposedHelpers.callStaticMethod(atmClass, "getService")
            val rc = XposedHelpers.callMethod(
                service,
                "startActivityFromRecents",
                task.taskId,
                options.toBundle(),
            ) as? Int ?: -1
            HookGuard.info("BRIDGE.RESTORE_RECENTS task=${task.taskId} bounds=$targetBounds scale=$targetScale rc=$rc")
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
        // NOTE: Do NOT schedule a second delayed adjustFlexibleTaskFrame here — SystemServerFlexibleHook
        // already applies memorized/widescreen bounds on frame 1 inside collectFlexibleTaskInfo, so a second
        // adjust call only causes a visible flicker.
    }

    private fun resolveHomeToken(organizer: Any): Any? = runCatching {
        val homeInfo = XposedHelpers.callMethod(organizer, "getHomeOrRecentsTaskInfo")
            as? ActivityManager.RunningTaskInfo ?: return null
        XposedHelpers.getObjectField(homeInfo, "token")
    }.getOrNull()

    private fun isTaskCurrentlyFlexibleBounds(organizer: Any, taskId: Int): Boolean = runCatching {
        val info = XposedHelpers.callMethod(organizer, "getRunningTaskInfo", taskId)
            as? ActivityManager.RunningTaskInfo ?: return false
        val bounds = StageNativeRuntime.extractBounds(info)
        if (bounds.isEmpty) return false
        val dm = appContext()?.resources?.displayMetrics
        val sw = dm?.widthPixels ?: 2520
        val sh = dm?.heightPixels ?: 1680
        // Flexible tasks have non-fullscreen window bounds (not 0,0..sw,sh)
        !(bounds.left == 0 && bounds.top == 0 && bounds.right == sw && bounds.bottom == sh)
    }.getOrDefault(false)

    private fun resolveTaskToken(organizer: Any, taskId: Int): Any? = runCatching {
        val info = XposedHelpers.callMethod(organizer, "getRunningTaskInfo", taskId)
            as? ActivityManager.RunningTaskInfo ?: return null
        XposedHelpers.getObjectField(info, "token")
    }.getOrNull()

    private fun applyTransaction(organizer: Any, wct: Any) {
        XposedHelpers.callMethod(organizer, "applyTransaction", wct)
    }

    companion object {
        private const val CONCURRENT_HANDOFF_MS = 25L
        private const val DISABLE_HOME = 0x00200000
        private const val DISABLE_RECENT = 0x01000000
        private const val KEY_LAUNCH_SCENARIO = "androidx.activity.LaunchScenario"
        private const val KEY_LAUNCH_FLEXIBLE_TASK_ID = "androidx.activity.LaunchFlexibleTaskId"
        private const val KEY_FLEXIBLE_LAUNCH_BOUNDS = "androidx.flexible.LaunchBounds"
        private const val KEY_FLEXIBLE_LAUNCH_MAX_BOUNDS = "androidx.flexible.LaunchMaxBounds"
        private const val KEY_FLEXIBLE_LAUNCH_FILLIN_BOUNDS = "androidx.flexible.LaunchFillInBounds"
        private const val KEY_EXTRA_FLEXIBLE_WINDOW_RECT = "androidx.flexible.extraWindowRect"
        private const val KEY_LAUNCH_SCALE = "androidx.activity.LaunchScale"
    }
}
