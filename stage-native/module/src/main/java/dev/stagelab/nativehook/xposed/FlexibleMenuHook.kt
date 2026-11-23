package dev.stagelab.nativehook.xposed

import android.app.ActivityManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import dev.stagelab.nativehook.bridge.StageNativeRuntime

/**
 * N6 ColorOS Native Window `···` Menu integration inside `com.android.systemui`.
 *
 * Hooks `com.oplus.flexibletask.menu.FlexibleMenuManager` so native window menu actions
 * (Minimize / Split out to Recent Strip, Close, Add window) stay strictly synchronized
 * with the frozen Stage state machine (S3 / S5 / S6) without replacing the native title bar.
 */
object FlexibleMenuHook {

    private const val FLEXIBLE_MENU_MANAGER = "com.oplus.flexibletask.menu.FlexibleMenuManager"
    private const val EVENT_MINI = 2002
    private const val EVENT_CLOSE = 2003
    private const val EVENT_SPLIT = 2100

    fun install(classLoader: ClassLoader) {
        val cls = dev.stagelab.nativehook.compat.OplusCompatAdapter.findClassAny(
            "ui.class.FlexibleMenuManager",
            classLoader,
            FLEXIBLE_MENU_MANAGER,
            "com.android.wm.shell.flexibletask.menu.FlexibleMenuManager",
        ) ?: return

        dev.stagelab.nativehook.compat.OplusCompatAdapter.hookMethodAny(
            probeKey = "ui.menu.notifyEvent",
            cls = cls,
            nameCandidates = listOf("notifySystemMenuEvent", "onMenuEvent", "handleMenuEvent"),
            fuzzyKeyword = "MenuEvent",
            hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!HookGuard.isActive() || !StageNativeRuntime.isStageManagerEnabled()) return
                    HookGuard.safeRun("FlexibleMenuManager.notifySystemMenuEvent.before") {
                        val event = param.args.firstOrNull() as? Int ?: return@safeRun
                        if (event == EVENT_SPLIT) {
                            val taskInfo = resolveCurrentTaskInfo(param.thisObject) ?: return@safeRun
                            // Intercept Split Screen BEFORE ColorOS wakes Launcher SplitRecents (zero stutter!)
                            param.result = null
                            HookGuard.info("NATIVE_MENU intercept SPLIT task=${taskInfo.taskId} pkg=${StageNativeRuntime.resolvePkg(taskInfo)}")
                            StageNativeRuntime.onNativeMenuSplitLeft(taskInfo.taskId)
                        }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!HookGuard.isActive() || !StageNativeRuntime.isStageManagerEnabled()) return
                    HookGuard.safeRun("FlexibleMenuManager.notifySystemMenuEvent") {
                        val event = param.args.firstOrNull() as? Int ?: return@safeRun
                        if (event == EVENT_SPLIT) return@safeRun
                        val taskInfo = resolveCurrentTaskInfo(param.thisObject) ?: return@safeRun
                        val taskId = taskInfo.taskId
                        HookGuard.info("NATIVE_MENU event=$event task=$taskId pkg=${StageNativeRuntime.resolvePkg(taskInfo)}")

                        when (event) {
                            EVENT_MINI -> {
                                // S5: Minimize / split task into its own single-window Stage in Recent Strip
                                StageNativeRuntime.onNativeMenuMinimize(taskId)
                            }
                            EVENT_CLOSE -> {
                                // Close cleanly: remove task from StageRegistry instead of parking a ghost card
                                StageNativeRuntime.onNativeMenuClose(taskId)
                            }
                        }
                    }
                }
            },
        )
    }

    private fun resolveCurrentTaskInfo(menuManager: Any): ActivityManager.RunningTaskInfo? {
        return runCatching {
            val fields = menuManager.javaClass.declaredFields
            for (f in fields) {
                if (ActivityManager.RunningTaskInfo::class.java.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    val v = f.get(menuManager) as? ActivityManager.RunningTaskInfo
                    if (v != null) return v
                }
            }
            null
        }.getOrNull()
    }
}
