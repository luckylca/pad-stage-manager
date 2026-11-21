package dev.stagelab.nativehook.xposed

import android.app.ActivityManager
import android.content.res.Configuration
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.stagelab.nativehook.bridge.StageNativeRuntime

/**
 * StageLab Native LSPosed Entry Point for ColorOS 17 (OPD2515 / Android 17 API 37).
 *
 * Scope: `com.android.systemui` ONLY (zero system_server injection).
 * Connects `ShellTaskOrganizer` & `FullscreenTaskListener` live events into `StageNativeRuntime`.
 */
class StageLabHookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "dev.stagelab.nativehook") {
            markSelfActive(lpparam.classLoader)
            return
        }
        if (lpparam.packageName != SYSTEMUI_PKG) return

        HookGuard.info("Loaded in $SYSTEMUI_PKG (process=${lpparam.processName}) — installing N4 SystemUI hooks")
        installShellTaskOrganizerHooks(lpparam.classLoader)
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

    private fun installShellTaskOrganizerHooks(classLoader: ClassLoader) {
        val clsName = "com.android.wm.shell.ShellTaskOrganizer"
        val organizerClass = try {
            XposedHelpers.findClass(clsName, classLoader)
        } catch (t: Throwable) {
            HookGuard.warn("Class not found: $clsName", t)
            return
        }

        // Exact single-overload hook: onTaskAppeared(android.window.TaskAppearedInfo)
        try {
            val taskAppearedInfoCls = XposedHelpers.findClass("android.window.TaskAppearedInfo", classLoader)
            XposedHelpers.findAndHookMethod(
                organizerClass,
                "onTaskAppeared",
                taskAppearedInfoCls,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        HookGuard.safeRun("ShellTaskOrganizer.onTaskAppeared") {
                            StageNativeRuntime.attachOrganizerIfNeeded(param.thisObject)
                            val taskInfo = extractTaskInfo(param.args[0]) ?: return@safeRun
                            StageNativeRuntime.onNativeTaskAppeared(taskInfo)
                        }
                    }
                },
            )
            HookGuard.info("Hooked $clsName#onTaskAppeared(TaskAppearedInfo)")
        } catch (t: Throwable) {
            HookGuard.warn("Failed to hook $clsName#onTaskAppeared(TaskAppearedInfo)", t)
        }

        // onTaskInfoChanged(android.app.ActivityManager$RunningTaskInfo)
        try {
            XposedHelpers.findAndHookMethod(
                organizerClass,
                "onTaskInfoChanged",
                ActivityManager.RunningTaskInfo::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        HookGuard.safeRun("ShellTaskOrganizer.onTaskInfoChanged") {
                            StageNativeRuntime.attachOrganizerIfNeeded(param.thisObject)
                            val taskInfo = param.args[0] as? ActivityManager.RunningTaskInfo ?: return@safeRun
                            StageNativeRuntime.onNativeTaskInfoChanged(taskInfo)
                        }
                    }
                },
            )
            HookGuard.info("Hooked $clsName#onTaskInfoChanged(RunningTaskInfo)")
        } catch (t: Throwable) {
            HookGuard.warn("Failed to hook $clsName#onTaskInfoChanged", t)
        }

        // onTaskVanished(android.app.ActivityManager$RunningTaskInfo)
        try {
            XposedHelpers.findAndHookMethod(
                organizerClass,
                "onTaskVanished",
                ActivityManager.RunningTaskInfo::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        HookGuard.safeRun("ShellTaskOrganizer.onTaskVanished") {
                            StageNativeRuntime.attachOrganizerIfNeeded(param.thisObject)
                            val taskInfo = param.args[0] as? ActivityManager.RunningTaskInfo ?: return@safeRun
                            StageNativeRuntime.onNativeTaskVanished(taskInfo)
                        }
                    }
                },
            )
            HookGuard.info("Hooked $clsName#onTaskVanished(RunningTaskInfo)")
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
            XposedBridge.hookAllMethods(listenerClass, "onTaskInfoChanged", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    HookGuard.safeRun("FullscreenTaskListener.onTaskInfoChanged") {
                        val taskInfo = param.args.firstOrNull() as? ActivityManager.RunningTaskInfo ?: return@safeRun
                        val scenario = extractColorOsScenario(param.thisObject, taskInfo)
                        if (scenario == 1) {
                            HookGuard.info(
                                "FTL.FLEXIBLE task=${taskInfo.taskId} pkg=${StageNativeRuntime.resolvePkg(taskInfo)} " +
                                    "vis=${StageNativeRuntime.isTaskVisible(taskInfo)} foc=${StageNativeRuntime.isTaskFocused(taskInfo)} " +
                                    "bounds=${StageNativeRuntime.extractBounds(taskInfo)}"
                            )
                        }
                    }
                }
            })
            HookGuard.info("Hooked $clsName#onTaskInfoChanged (Flexible telemetry)")
        } catch (t: Throwable) {
            HookGuard.warn("Failed to hook $clsName", t)
        }
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

    companion object {
        private const val SYSTEMUI_PKG = "com.android.systemui"
    }
}
