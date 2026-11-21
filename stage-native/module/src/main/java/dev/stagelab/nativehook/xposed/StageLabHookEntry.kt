package dev.stagelab.nativehook.xposed

import android.app.ActivityManager
import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Rect
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.stagelab.nativecore.NoOpNativeWindowBridge
import dev.stagelab.nativecore.StageRegistry
import dev.stagelab.nativecore.StageTask
import dev.stagelab.nativecore.StageTransitionController
import dev.stagelab.nativecore.WindowBounds

/**
 * N2 Read-Only LSPosed Entry Point for ColorOS 17 (OPD2515 / Android 17 API 37).
 *
 * Scope: `com.android.systemui` ONLY (never touches system_server in N2).
 * Behavior: Strictly read-only observation of `ShellTaskOrganizer` and `FullscreenTaskListener`.
 * Feeds observed standard app tasks into an isolated shadow `StageTransitionController` for
 * real-device telemetry without altering any native window behavior.
 */
class StageLabHookEntry : IXposedHookLoadPackage {

    private val shadowRegistry = StageRegistry()
    private val shadowController = StageTransitionController(shadowRegistry, NoOpNativeWindowBridge())

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "dev.stagelab.nativehook") {
            markSelfActive(lpparam.classLoader)
            return
        }
        if (lpparam.packageName != SYSTEMUI_PKG) return

        HookGuard.info("Loaded in $SYSTEMUI_PKG (process=${lpparam.processName}) — installing N2 read-only observers")
        installShellTaskOrganizerObserver(lpparam.classLoader)
        installFullscreenTaskListenerObserver(lpparam.classLoader)
    }

    private fun markSelfActive(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "dev.stagelab.nativehook.ui.ModuleStatus",
                classLoader,
                "isXposedModuleActive",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                },
            )
        } catch (t: Throwable) {
            HookGuard.warn("Unable to hook ModuleStatus self-check", t)
        }
    }

    private fun installShellTaskOrganizerObserver(classLoader: ClassLoader) {
        val clsName = "com.android.wm.shell.ShellTaskOrganizer"
        val organizerClass = try {
            XposedHelpers.findClass(clsName, classLoader)
        } catch (t: Throwable) {
            HookGuard.warn("Class not found: $clsName", t)
            return
        }

        // 1. onTaskAppeared(android.window.TaskAppearedInfo)
        try {
            XposedBridge.hookAllMethods(organizerClass, "onTaskAppeared", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    HookGuard.safeRun("ShellTaskOrganizer.onTaskAppeared") {
                        val taskInfo = extractTaskInfo(param.args.firstOrNull()) ?: return@safeRun
                        observeTaskEvent("STO.APPEARED", taskInfo)
                    }
                }
            })
            HookGuard.info("Hooked $clsName#onTaskAppeared (read-only)")
        } catch (t: Throwable) {
            HookGuard.warn("Failed to hook $clsName#onTaskAppeared", t)
        }

        // 2. onTaskInfoChanged(android.app.ActivityManager$RunningTaskInfo)
        try {
            XposedBridge.hookAllMethods(organizerClass, "onTaskInfoChanged", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    HookGuard.safeRun("ShellTaskOrganizer.onTaskInfoChanged") {
                        val taskInfo = extractTaskInfo(param.args.firstOrNull()) ?: return@safeRun
                        observeTaskEvent("STO.CHANGED", taskInfo)
                    }
                }
            })
            HookGuard.info("Hooked $clsName#onTaskInfoChanged (read-only)")
        } catch (t: Throwable) {
            HookGuard.warn("Failed to hook $clsName#onTaskInfoChanged", t)
        }

        // 3. onTaskVanished(android.app.ActivityManager$RunningTaskInfo)
        try {
            XposedBridge.hookAllMethods(organizerClass, "onTaskVanished", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    HookGuard.safeRun("ShellTaskOrganizer.onTaskVanished") {
                        val taskInfo = extractTaskInfo(param.args.firstOrNull()) ?: return@safeRun
                        observeTaskVanished("STO.VANISHED", taskInfo)
                    }
                }
            })
            HookGuard.info("Hooked $clsName#onTaskVanished (read-only)")
        } catch (t: Throwable) {
            HookGuard.warn("Failed to hook $clsName#onTaskVanished", t)
        }
    }

    private fun installFullscreenTaskListenerObserver(classLoader: ClassLoader) {
        val clsName = "com.android.wm.shell.fullscreen.FullscreenTaskListener"
        val listenerClass = try {
            XposedHelpers.findClass(clsName, classLoader)
        } catch (t: Throwable) {
            HookGuard.warn("Class not found: $clsName", t)
            return
        }

        try {
            XposedBridge.hookAllMethods(listenerClass, "onTaskAppeared", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    HookGuard.safeRun("FullscreenTaskListener.onTaskAppeared") {
                        val taskInfo = extractTaskInfo(param.args.firstOrNull()) ?: return@safeRun
                        val visibleFlexible = runCatching {
                            XposedHelpers.callMethod(param.thisObject, "getVisibleFlexibleTasks") as? Int
                        }.getOrNull()
                        val scenario = extractColorOsScenario(param.thisObject, taskInfo)
                        HookGuard.info(
                            "FTL.APPEARED task=${taskInfo.taskId} pkg=${resolvePkg(taskInfo)} " +
                                "scenario=$scenario visibleFlexible=$visibleFlexible bounds=${extractBounds(taskInfo)}"
                        )
                    }
                }
            })
            XposedBridge.hookAllMethods(listenerClass, "onTaskInfoChanged", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    HookGuard.safeRun("FullscreenTaskListener.onTaskInfoChanged") {
                        val taskInfo = extractTaskInfo(param.args.firstOrNull()) ?: return@safeRun
                        val visibleFlexible = runCatching {
                            XposedHelpers.callMethod(param.thisObject, "getVisibleFlexibleTasks") as? Int
                        }.getOrNull()
                        val scenario = extractColorOsScenario(param.thisObject, taskInfo)
                        HookGuard.info(
                            "FTL.CHANGED task=${taskInfo.taskId} pkg=${resolvePkg(taskInfo)} " +
                                "scenario=$scenario visibleFlexible=$visibleFlexible " +
                                "vis=${isTaskVisible(taskInfo)} foc=${isTaskFocused(taskInfo)} bounds=${extractBounds(taskInfo)}"
                        )
                    }
                }
            })
            XposedBridge.hookAllMethods(listenerClass, "onTaskVanished", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    HookGuard.safeRun("FullscreenTaskListener.onTaskVanished") {
                        val taskInfo = extractTaskInfo(param.args.firstOrNull()) ?: return@safeRun
                        HookGuard.info("FTL.VANISHED task=${taskInfo.taskId} pkg=${resolvePkg(taskInfo)}")
                    }
                }
            })
            HookGuard.info("Hooked $clsName (read-only)")
        } catch (t: Throwable) {
            HookGuard.warn("Failed to hook $clsName", t)
        }
    }

    private fun observeTaskEvent(event: String, taskInfo: ActivityManager.RunningTaskInfo) {
        val pkg = resolvePkg(taskInfo)
        val bounds = extractBounds(taskInfo)
        val wmMode = extractWindowingMode(taskInfo)
        val visible = isTaskVisible(taskInfo)
        val focused = isTaskFocused(taskInfo)

        HookGuard.info(
            "$event task=${taskInfo.taskId} pkg=$pkg wmMode=$wmMode vis=$visible foc=$focused bounds=$bounds"
        )

        if (!isUserAppTask(pkg)) return
        val stageTask = StageTask(
            taskId = taskInfo.taskId,
            packageName = pkg,
            componentName = taskInfo.topActivity?.flattenToShortString().orEmpty(),
            bounds = WindowBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            windowingMode = wmMode,
            isRunning = true,
            isVisible = visible,
        )
        val session = if (event.endsWith("APPEARED")) {
            shadowController.onNativeTaskAppeared(stageTask, mergeIntoActive = visible && shadowRegistry.getActiveStage() != null)
        } else {
            shadowController.onNativeTaskInfoChanged(stageTask)
        }
        if (focused) {
            shadowController.onNativeTaskFocused(taskInfo.taskId)
        }
        HookGuard.info(
            "SHADOW_STATE active=${session.activeStageId}:${session.activeStage?.taskIds} " +
                "recent=${session.visibleRecentStages.map { "${it.stageId}:${it.taskIds}" }}"
        )
    }

    private fun observeTaskVanished(event: String, taskInfo: ActivityManager.RunningTaskInfo) {
        val pkg = resolvePkg(taskInfo)
        HookGuard.info("$event task=${taskInfo.taskId} pkg=$pkg")
        if (!isUserAppTask(pkg)) return
        val session = shadowController.onNativeTaskVanished(taskInfo.taskId)
        HookGuard.info(
            "SHADOW_STATE active=${session.activeStageId}:${session.activeStage?.taskIds} " +
                "recent=${session.visibleRecentStages.map { "${it.stageId}:${it.taskIds}" }}"
        )
    }

    private fun extractTaskInfo(arg: Any?): ActivityManager.RunningTaskInfo? {
        if (arg == null) return null
        if (arg is ActivityManager.RunningTaskInfo) return arg
        return runCatching {
            XposedHelpers.callMethod(arg, "getTaskInfo") as? ActivityManager.RunningTaskInfo
        }.getOrNull()
    }

    private fun extractColorOsScenario(ftlInstance: Any, taskInfo: ActivityManager.RunningTaskInfo): Int {
        return runCatching {
            val config = XposedHelpers.getObjectField(taskInfo, "configuration") as? Configuration ?: return -1
            XposedHelpers.callMethod(ftlInstance, "getScenario", config) as? Int ?: -1
        }.getOrDefault(-1)
    }

    private fun extractBounds(taskInfo: ActivityManager.RunningTaskInfo): Rect {
        return runCatching {
            val config = XposedHelpers.getObjectField(taskInfo, "configuration") as? Configuration ?: return Rect()
            val winConfig = XposedHelpers.getObjectField(config, "windowConfiguration") ?: return Rect()
            XposedHelpers.callMethod(winConfig, "getBounds") as? Rect ?: Rect()
        }.getOrDefault(Rect())
    }

    private fun extractWindowingMode(taskInfo: ActivityManager.RunningTaskInfo): Int {
        return runCatching {
            val config = XposedHelpers.getObjectField(taskInfo, "configuration") as? Configuration ?: return 0
            val winConfig = XposedHelpers.getObjectField(config, "windowConfiguration") ?: return 0
            XposedHelpers.callMethod(winConfig, "getWindowingMode") as? Int ?: 0
        }.getOrDefault(0)
    }

    private fun isTaskVisible(taskInfo: ActivityManager.RunningTaskInfo): Boolean =
        runCatching { XposedHelpers.getBooleanField(taskInfo, "isVisible") }.getOrDefault(false)

    private fun isTaskFocused(taskInfo: ActivityManager.RunningTaskInfo): Boolean =
        runCatching { XposedHelpers.getBooleanField(taskInfo, "isFocused") }.getOrDefault(false)

    private fun resolvePkg(taskInfo: ActivityManager.RunningTaskInfo): String {
        val top: ComponentName? = taskInfo.topActivity ?: taskInfo.baseActivity
        return top?.packageName ?: taskInfo.baseIntent?.component?.packageName ?: ""
    }

    private fun isUserAppTask(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return pkg != "com.android.launcher" &&
            pkg != "com.android.systemui" &&
            pkg != "com.android.permissioncontroller" &&
            pkg != "com.android.packageinstaller"
    }

    companion object {
        private const val SYSTEMUI_PKG = "com.android.systemui"
    }
}
