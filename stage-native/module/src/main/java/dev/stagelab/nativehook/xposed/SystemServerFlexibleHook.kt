package dev.stagelab.nativehook.xposed

import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Minimal, strictly isolated `system_server` (`android`) hook for ColorOS 17 (OPD2515).
 *
 * Problem discovered via live DEX + logcat analysis:
 * On OPPO Pad mini with Panorama WorkStation enabled, `OplusPanoramaWorkBranchController.adjustFlexibleTask(Bundle)`
 * short-circuits `FlexibleTaskController.adjustFlexibleTask(Bundle)` and drops external resize/reposition requests.
 *
 * Solution:
 * - ONLY when a Bundle explicitly carries `stagelab.force_adjust == true`, let `OplusPanoramaWorkBranchController`
 *   return `false` so ColorOS's own `FlexibleTaskController.startAdjustFlexibleTask` executes natively with
 *   proper WMS locks, rounded corners, caption relayout, scale sync, and resize handles.
 * - While that exact call is active on the current thread, allow full-stage wide bounds through `isValidAdjustEndRect`.
 * - Zero impact on any system call without `stagelab.force_adjust == true`; bypassed instantly in Safe Mode.
 */
object SystemServerFlexibleHook {

    const val KEY_FORCE_ADJUST = "stagelab.force_adjust"

    private const val PANORAMA_CONTROLLER = "com.android.server.wm.OplusPanoramaWorkBranchController"
    private const val FLEXIBLE_TASK_CONTROLLER = "com.android.server.wm.FlexibleTaskController"

    private val allowWideStageRect = ThreadLocal<Boolean>()

    fun install(classLoader: ClassLoader) {
        hookPanoramaBypass(classLoader)
        hookValidAdjustRect(classLoader)
    }

    private fun hookPanoramaBypass(classLoader: ClassLoader) {
        val cls = try {
            XposedHelpers.findClass(PANORAMA_CONTROLLER, classLoader)
        } catch (t: Throwable) {
            HookGuard.warn("SystemServerFlexibleHook: class not found: $PANORAMA_CONTROLLER", t)
            return
        }

        try {
            XposedHelpers.findAndHookMethod(
                cls,
                "adjustFlexibleTask",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!HookGuard.isActive()) return
                        try {
                            val bundle = param.args[0] as? Bundle ?: return
                            if (bundle.getBoolean(KEY_FORCE_ADJUST, false)) {
                                allowWideStageRect.set(true)
                                param.result = false
                            }
                        } catch (t: Throwable) {
                            allowWideStageRect.remove()
                            HookGuard.warn("SystemServerFlexibleHook.adjustFlexibleTask isolated error", t)
                        }
                    }
                },
            )
            HookGuard.info("SystemServerFlexibleHook: hooked $PANORAMA_CONTROLLER#adjustFlexibleTask")
        } catch (t: Throwable) {
            HookGuard.warn("SystemServerFlexibleHook: failed to hook $PANORAMA_CONTROLLER", t)
        }
    }

    private fun hookValidAdjustRect(classLoader: ClassLoader) {
        val cls = try {
            XposedHelpers.findClass(FLEXIBLE_TASK_CONTROLLER, classLoader)
        } catch (t: Throwable) {
            HookGuard.warn("SystemServerFlexibleHook: class not found: $FLEXIBLE_TASK_CONTROLLER", t)
            return
        }

        try {
            XposedBridge.hookAllMethods(cls, "adjustFlexibleTask", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    allowWideStageRect.remove()
                }
            })
            XposedBridge.hookAllMethods(cls, "isValidAdjustEndRect", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (allowWideStageRect.get() == true && HookGuard.isActive()) {
                        param.result = true
                    }
                }
            })
            HookGuard.info("SystemServerFlexibleHook: hooked $FLEXIBLE_TASK_CONTROLLER#isValidAdjustEndRect")
        } catch (t: Throwable) {
            HookGuard.warn("SystemServerFlexibleHook: failed to hook $FLEXIBLE_TASK_CONTROLLER", t)
        }
    }
}
