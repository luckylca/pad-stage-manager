package dev.stagelab.nativehook.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import dev.stagelab.nativehook.bridge.StageNativeRuntime

/**
 * N6 Native ColorOS Dock / Taskbar integration inside `com.android.launcher`.
 *
 * Reuses OPPO/ColorOS native glass Taskbar & icons completely (zero custom Dock UI):
 * - Tap Dock app icon -> forwards S1 `launch` intent to `StageNativeRuntime` in SystemUI
 *   (activates existing Stage if already open, or creates a new Flexible Window Stage).
 * - Long-press drag from Dock -> signals pending `merge_hint` so when ColorOS drops the window
 *   onto the desktop, it joins the current Active Stage (S3 / S4 overflow) instead of spawning a new Stage.
 */
object LauncherDockHook {

    private const val TASKBAR_ACTIVITY_CONTEXT = "com.android.launcher3.taskbar.TaskbarActivityContext"
    private const val TASKBAR_DRAG_CONTROLLER = "com.android.launcher3.taskbar.TaskbarDragController"
    private const val OPLUS_ITEM_CLICK_HANDLER = "com.android.launcher3.touch.OplusItemClickHandler"

    @Volatile
    private var stageManagerEnabled: Boolean = false

    fun setEnabled(enabled: Boolean) {
        stageManagerEnabled = enabled
    }

    private fun isActive(): Boolean = HookGuard.isActive() && stageManagerEnabled

    fun install(classLoader: ClassLoader) {
        hookTaskbarItemClick(classLoader)
        hookWorkspaceIconClick(classLoader)
        hookTaskbarDragMergeHint(classLoader)
    }

    private fun hookWorkspaceIconClick(classLoader: ClassLoader) {
        val cls = try {
            XposedHelpers.findClass(OPLUS_ITEM_CLICK_HANDLER, classLoader)
        } catch (t: Throwable) {
            HookGuard.warn("LauncherDockHook: class not found: $OPLUS_ITEM_CLICK_HANDLER", t)
            return
        }

        try {
            XposedBridge.hookAllMethods(cls, "onClick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    try {
                        val view = param.args.firstOrNull() as? View ?: return
                        val tag = view.tag ?: return
                        // Only intercept real app icons (WorkspaceItemInfo / AppInfo), never folders or widgets.
                        val tagClassName = tag.javaClass.name
                        if (tagClassName.contains("Folder", ignoreCase = true) ||
                            tagClassName.contains("Widget", ignoreCase = true)
                        ) {
                            return
                        }
                        val pkg = resolveItemPackage(tag) ?: return
                        if (!StageNativeRuntime.isManagedUserApp(pkg) || pkg == "dev.stagelab.nativehook") {
                            return
                        }

                        val cmd = Intent(StageNativeRuntime.ACTION_STAGE_CMD).apply {
                            setPackage("com.android.systemui")
                            putExtra("cmd", "launch")
                            putExtra("pkg", pkg)
                        }
                        view.context.sendBroadcast(cmd)
                        HookGuard.info("LAUNCHER.ICON_CLICK routed pkg=$pkg to StageNativeRuntime (S1 widescreen)")
                        param.result = null
                    } catch (t: Throwable) {
                        HookGuard.warn("LauncherDockHook.OplusItemClickHandler isolated failure", t)
                    }
                }
            })
            HookGuard.info("LauncherDockHook: hooked $OPLUS_ITEM_CLICK_HANDLER#onClick")
        } catch (t: Throwable) {
            HookGuard.warn("LauncherDockHook: failed to hook $OPLUS_ITEM_CLICK_HANDLER#onClick", t)
        }
    }

    private fun hookTaskbarItemClick(classLoader: ClassLoader) {
        val cls = try {
            XposedHelpers.findClass(TASKBAR_ACTIVITY_CONTEXT, classLoader)
        } catch (t: Throwable) {
            HookGuard.warn("LauncherDockHook: class not found: $TASKBAR_ACTIVITY_CONTEXT", t)
            return
        }

        try {
            XposedBridge.hookAllMethods(cls, "startItemInfoActivity", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    try {
                        val ctx = param.thisObject as? Context ?: return
                        val itemInfo = param.args.getOrNull(1) ?: return
                        val pkg = resolveItemPackage(itemInfo) ?: return
                        if (!StageNativeRuntime.isManagedUserApp(pkg) || pkg == "dev.stagelab.nativehook") {
                            return
                        }

                        // Route Dock click into Stage Manager S1 flow (activate existing Stage or create new Stage).
                        val cmd = Intent(StageNativeRuntime.ACTION_STAGE_CMD).apply {
                            setPackage("com.android.systemui")
                            putExtra("cmd", "launch")
                            putExtra("pkg", pkg)
                        }
                        ctx.sendBroadcast(cmd)
                        HookGuard.info("DOCK.CLICK routed pkg=$pkg to StageNativeRuntime (S1)")
                        // Cancel default fullscreen launch so StageNativeRuntime handles native Flexible launch/switch.
                        param.result = null
                    } catch (t: Throwable) {
                        HookGuard.warn("LauncherDockHook.startItemInfoActivity isolated failure", t)
                    }
                }
            })
            HookGuard.info("LauncherDockHook: hooked $TASKBAR_ACTIVITY_CONTEXT#startItemInfoActivity")
        } catch (t: Throwable) {
            HookGuard.warn("LauncherDockHook: failed to hook startItemInfoActivity", t)
        }
    }

    private fun hookTaskbarDragMergeHint(classLoader: ClassLoader) {
        val cls = try {
            XposedHelpers.findClass(TASKBAR_DRAG_CONTROLLER, classLoader)
        } catch (t: Throwable) {
            HookGuard.warn("LauncherDockHook: class not found: $TASKBAR_DRAG_CONTROLLER", t)
            return
        }

        try {
            XposedBridge.hookAllMethods(cls, "startDragOnLongClick", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    HookGuard.safeRun("TaskbarDragController.startDragOnLongClick") {
                        val view = param.args.firstOrNull() as? View ?: return@safeRun
                        val tag = view.tag ?: return@safeRun
                        val pkg = resolveItemPackage(tag) ?: return@safeRun
                        if (!StageNativeRuntime.isManagedUserApp(pkg)) return@safeRun

                        val cmd = Intent(StageNativeRuntime.ACTION_STAGE_CMD).apply {
                            setPackage("com.android.systemui")
                            putExtra("cmd", "merge_hint")
                            putExtra("pkg", pkg)
                        }
                        view.context.sendBroadcast(cmd)
                        HookGuard.info("DOCK.DRAG sent S3 merge_hint for pkg=$pkg")
                    }
                }
            })
            HookGuard.info("LauncherDockHook: hooked $TASKBAR_DRAG_CONTROLLER#startDragOnLongClick")
        } catch (t: Throwable) {
            HookGuard.warn("LauncherDockHook: failed to hook TaskbarDragController", t)
        }
    }

    private fun resolveItemPackage(itemInfo: Any): String? {
        val targetComp = runCatching {
            XposedHelpers.callMethod(itemInfo, "getTargetComponent") as? ComponentName
        }.getOrNull()
        if (targetComp != null && targetComp.packageName.isNotBlank()) {
            return targetComp.packageName
        }
        val intent = runCatching {
            XposedHelpers.callMethod(itemInfo, "getIntent") as? Intent
        }.getOrNull()
        return intent?.component?.packageName ?: intent?.`package`
    }
}
