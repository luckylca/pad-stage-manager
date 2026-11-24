package dev.stagelab.nativehook.xposed

import android.app.ActivityManager
import android.content.res.Configuration
import android.view.SurfaceControl
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.stagelab.nativehook.bridge.StageNativeRuntime

/**
 * StageLab Native LSPosed Entry Point for ColorOS 17 (OPD2515 / Android 17 API 37).
 *
 * Scope:
 * - `com.android.systemui`: ShellTaskOrganizer, FullscreenTaskListener, FlexibleMenuManager, Recent Strip.
 * - `com.android.launcher`: Native ColorOS Taskbar/Dock click (S1) and drag-merge hint (S3).
 * Zero `system_server` injection.
 */
class StageLabHookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            SELF_PKG -> markSelfActive(lpparam.classLoader)
            ANDROID_PKG -> {
                HookGuard.info("Loaded in $ANDROID_PKG — installing minimal FlexibleTaskController adjust pass-through")
                SystemServerFlexibleHook.install(lpparam.classLoader)
            }
            SYSTEMUI_PKG -> {
                HookGuard.info("Loaded in $SYSTEMUI_PKG (process=${lpparam.processName}) — installing N6/N7 SystemUI hooks")
                installShellTaskOrganizerHooks(lpparam.classLoader)
                installFullscreenTaskListenerObserver(lpparam.classLoader)
                FlexibleMenuHook.install(lpparam.classLoader)
            }
            LAUNCHER_PKG -> {
                HookGuard.info("Loaded in $LAUNCHER_PKG (process=${lpparam.processName}) — installing N6 Dock & Blur Backdrop hooks")
                LauncherDockHook.install(lpparam.classLoader)
                LauncherBackdropHook.install(lpparam.classLoader)
            }
        }
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
        val organizerClass = dev.stagelab.nativehook.compat.OplusCompatAdapter.findClassAny(
            "ui.class.ShellTaskOrganizer",
            classLoader,
            clsName,
        ) ?: return

        val taskAppearedHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                HookGuard.safeRun("ShellTaskOrganizer.onTaskAppeared") {
                    StageNativeRuntime.attachOrganizerIfNeeded(param.thisObject)
                    val firstArg = param.args.getOrNull(0)
                    val taskInfo = extractTaskInfo(firstArg) ?: return@safeRun
                    val leash = runCatching {
                        if (firstArg != null && firstArg !is ActivityManager.RunningTaskInfo) {
                            XposedHelpers.callMethod(firstArg, "getLeash") as? SurfaceControl
                        } else {
                            param.args.getOrNull(1) as? SurfaceControl
                        }
                    }.getOrNull()
                    if (leash != null) {
                        StageNativeRuntime.registerTaskLeash(taskInfo.taskId, leash)
                    }
                    StageNativeRuntime.onNativeTaskAppeared(taskInfo)
                }
            }
        }
        dev.stagelab.nativehook.compat.OplusCompatAdapter.hookMethodAny(
            probeKey = "ui.organizer.onTaskAppeared",
            cls = organizerClass,
            nameCandidates = listOf("onTaskAppeared"),
            hook = taskAppearedHook,
        )

        dev.stagelab.nativehook.compat.OplusCompatAdapter.hookMethodAny(
            probeKey = "ui.organizer.onTaskInfoChanged",
            cls = organizerClass,
            nameCandidates = listOf("onTaskInfoChanged"),
            hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    HookGuard.safeRun("ShellTaskOrganizer.onTaskInfoChanged") {
                        StageNativeRuntime.attachOrganizerIfNeeded(param.thisObject)
                        val taskInfo = param.args.firstOrNull() as? ActivityManager.RunningTaskInfo ?: return@safeRun
                        StageNativeRuntime.onNativeTaskInfoChanged(taskInfo)
                    }
                }
            },
        )

        dev.stagelab.nativehook.compat.OplusCompatAdapter.hookMethodAny(
            probeKey = "ui.organizer.onTaskVanished",
            cls = organizerClass,
            nameCandidates = listOf("onTaskVanished"),
            hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    HookGuard.safeRun("ShellTaskOrganizer.onTaskVanished") {
                        StageNativeRuntime.attachOrganizerIfNeeded(param.thisObject)
                        val taskInfo = param.args.firstOrNull() as? ActivityManager.RunningTaskInfo ?: return@safeRun
                        StageNativeRuntime.onNativeTaskVanished(taskInfo)
                    }
                }
            },
        )
    }

    private fun installFullscreenTaskListenerObserver(classLoader: ClassLoader) {
        val listenerClass = dev.stagelab.nativehook.compat.OplusCompatAdapter.findClassAny(
            "ui.class.FullscreenTaskListener",
            classLoader,
            "com.android.wm.shell.fullscreen.FullscreenTaskListener",
            "com.android.wm.shell.freeform.FreeformTaskListener",
        ) ?: return

        dev.stagelab.nativehook.compat.OplusCompatAdapter.hookMethodAny(
            probeKey = "ui.ftl.onTaskInfoChanged",
            cls = listenerClass,
            nameCandidates = listOf("onTaskInfoChanged"),
            hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    HookGuard.safeRun("FullscreenTaskListener.onTaskInfoChanged") {
                        val taskInfo = param.args.firstOrNull() as? ActivityManager.RunningTaskInfo ?: return@safeRun
                        val scenario = extractColorOsScenario(param.thisObject, taskInfo)
                        if (scenario == 1) {
                            HookGuard.info(
                                "FTL.FLEXIBLE task=${taskInfo.taskId} pkg=${StageNativeRuntime.resolvePkg(taskInfo)} " +
                                    "vis=${StageNativeRuntime.isTaskVisible(taskInfo)} foc=${StageNativeRuntime.isTaskFocused(taskInfo)} " +
                                    "bounds=${StageNativeRuntime.extractBounds(taskInfo)}",
                            )
                        }
                    }
                }
            },
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

    companion object {
        private const val SELF_PKG = "dev.stagelab.nativehook"
        private const val ANDROID_PKG = "android"
        private const val SYSTEMUI_PKG = "com.android.systemui"
        private const val LAUNCHER_PKG = "com.android.launcher"
    }
}
