package dev.stagelab.nativehook.xposed

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * ColorOS 17 `system_server` (`system/0` -> `lpparam.packageName == "android"`) hook.
 *
 * Delivers two core native Stage Manager capabilities cleanly inside ColorOS FlexibleTaskController:
 * 1. Wide Primary-Stage Default Mode:
 *    Hooks `FlexibleTaskController#collectFlexibleTaskInfo` so newly opened single-window Flexible Tasks
 *    start directly at full primary-stage widescreen bounds (excluding left slanted strip & bottom Dock)
 *    with `mScale = 1.0f` instead of narrow `0.618` portrait phone ratio.
 * 2. Persistent Window Size & Position Memory:
 *    - Persists real user-adjusted `Task.getBounds()` + `scale` into `/data/local/tmp/stagelab_window_memory.properties`
 *      whenever ColorOS finishes moving or resizing a Flexible Task (`finishMovingFlexibleTask` / `updateResizedTaskParams`).
 *    - Restores memorized `mLaunchBounds` & `mScale` automatically on relaunch or Stage switch, and allows
 *      `stagelab.force_adjust=true` calls through `OplusPanoramaWorkBranchController.adjustFlexibleTask`.
 */
object SystemServerFlexibleHook {

    const val KEY_FORCE_ADJUST = "stagelab.force_adjust"
    private const val MEMORY_FILE_PATH = "/data/system/stagelab_window_memory.properties"

    private const val PANORAMA_CONTROLLER = "com.android.server.wm.OplusPanoramaWorkBranchController"
    private const val FLEXIBLE_TASK_CONTROLLER = "com.android.server.wm.FlexibleTaskController"

    private val allowWideStageRect = ThreadLocal<Boolean>()
    private val memoryMap = ConcurrentHashMap<String, MemorizedFrame>()

    data class MemorizedFrame(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val scale: Float,
    ) {
        fun toRect(): Rect = Rect(left, top, right, bottom)
        fun serialize(): String = "$left,$top,$right,$bottom,$scale"

        companion object {
            fun parse(raw: String): MemorizedFrame? {
                val parts = raw.split(",")
                if (parts.size < 5) return null
                val l = parts[0].toIntOrNull() ?: return null
                val t = parts[1].toIntOrNull() ?: return null
                val r = parts[2].toIntOrNull() ?: return null
                val b = parts[3].toIntOrNull() ?: return null
                val s = parts[4].toFloatOrNull() ?: 1.0f
                if (r <= l || b <= t) return null
                return MemorizedFrame(l, t, r, b, s.coerceIn(0.40f, 1.0f))
            }
        }
    }

    fun install(classLoader: ClassLoader) {
        loadMemoryFromDisk()
        hookPanoramaBypass(classLoader)
        hookFlexibleTaskController(classLoader)
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

    private fun hookFlexibleTaskController(classLoader: ClassLoader) {
        val cls = try {
            XposedHelpers.findClass(FLEXIBLE_TASK_CONTROLLER, classLoader)
        } catch (t: Throwable) {
            HookGuard.warn("SystemServerFlexibleHook: class not found: $FLEXIBLE_TASK_CONTROLLER", t)
            return
        }

        // 1. Wide Primary-Stage Default + Memorized Launch Bounds in collectFlexibleTaskInfo
        try {
            XposedBridge.hookAllMethods(cls, "collectFlexibleTaskInfo", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    HookGuard.safeRun("FlexibleTaskController.collectFlexibleTaskInfo") {
                        val info = param.result ?: return@safeRun
                        val pkg = extractPackageFromArgs(param.args) ?: return@safeRun
                        if (isIgnoredSystemPkg(pkg)) return@safeRun

                        val (screenW, screenH) = extractDisplaySizeFromArgs(param.args)
                        val memorized = memoryMap[pkg]
                        val targetRect: Rect
                        val targetScale: Float

                        if (memorized != null) {
                            targetRect = memorized.toRect()
                            targetScale = memorized.scale
                            HookGuard.info("SYS.LAUNCH_MEMORY pkg=$pkg rect=$targetRect scale=$targetScale")
                        } else {
                            targetRect = computeWidePrimaryStageRect(screenW, screenH)
                            targetScale = 1.0f
                            HookGuard.info("SYS.LAUNCH_WIDE_STAGE pkg=$pkg rect=$targetRect scale=$targetScale (${screenW}x${screenH})")
                        }

                        runCatching { XposedHelpers.setObjectField(info, "mLaunchBounds", Rect(targetRect)) }
                        runCatching { XposedHelpers.setFloatField(info, "mScale", targetScale) }
                        if (targetRect.height() > 0) {
                            val ratio = targetRect.width().toFloat() / targetRect.height().toFloat()
                            runCatching { XposedHelpers.setFloatField(info, "mRatio", ratio) }
                        }
                        runCatching { XposedHelpers.setBooleanField(info, "mSkipPosInMemoryAdjust", true) }
                        runCatching { XposedHelpers.setBooleanField(info, "mSkipMemoryRectToDefault", true) }
                    }
                }
            })
            HookGuard.info("SystemServerFlexibleHook: hooked $FLEXIBLE_TASK_CONTROLLER#collectFlexibleTaskInfo")
        } catch (t: Throwable) {
            HookGuard.warn("SystemServerFlexibleHook: failed to hook collectFlexibleTaskInfo", t)
        }

        // 2. Allow wide stage bounds during stagelab.force_adjust
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
            HookGuard.info("SystemServerFlexibleHook: hooked adjustFlexibleTask & isValidAdjustEndRect")
        } catch (t: Throwable) {
            HookGuard.warn("SystemServerFlexibleHook: failed to hook adjustFlexibleTask", t)
        }

        // 3. Persist real user drag/resize bounds & scale directly inside system_server
        val persistHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                HookGuard.safeRun("FlexibleTaskController.persistGeometry") {
                    val taskObj = param.args.firstOrNull { it != null && it.javaClass.name.endsWith(".Task") } ?: return@safeRun
                    recordTaskGeometry(taskObj)
                }
            }
        }
        for (methodName in listOf("finishMovingFlexibleTask", "updateResizedTaskParams", "resizeTaskByVisibleBounds")) {
            try {
                XposedBridge.hookAllMethods(cls, methodName, persistHook)
                HookGuard.info("SystemServerFlexibleHook: hooked $FLEXIBLE_TASK_CONTROLLER#$methodName")
            } catch (t: Throwable) {
                HookGuard.warn("SystemServerFlexibleHook: optional hook $methodName unavailable", t)
            }
        }
    }

    private fun recordTaskGeometry(taskObj: Any) {
        val pkg = runCatching {
            XposedHelpers.callMethod(taskObj, "getBasePackageName") as? String
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        if (isIgnoredSystemPkg(pkg)) return

        val bounds = runCatching {
            XposedHelpers.callMethod(taskObj, "getBounds") as? Rect
        }.getOrNull() ?: return
        if (bounds.isEmpty || bounds.right <= 0 || bounds.left < -100) return

        val scale = runCatching {
            val wrapper = XposedHelpers.callMethod(taskObj, "getWrapper")
            val ext = XposedHelpers.callMethod(wrapper, "getExtImpl")
            (XposedHelpers.callMethod(ext, "getScale") as? Float)?.takeIf { it in 0.40f..1.05f }
        }.getOrNull() ?: 1.0f

        val frame = MemorizedFrame(bounds.left, bounds.top, bounds.right, bounds.bottom, scale)
        val prev = memoryMap[pkg]
        if (prev != frame) {
            memoryMap[pkg] = frame
            saveMemoryToDisk()
            HookGuard.info("SYS.MEMORY_SAVED pkg=$pkg rect=${frame.toRect()} scale=$scale")
        }
    }

    private fun computeWidePrimaryStageRect(screenW: Int, screenH: Int): Rect {
        val isLandscape = screenW >= screenH
        return if (isLandscape) {
            // OPPO Pad mini landscape 2520x1680: leave ~440px on left for slanted strip, ~84px top, ~236px bottom for Dock
            val left = (screenW * 0.175f).toInt()
            val top = (screenH * 0.052f).toInt()
            val right = screenW - (screenW * 0.018f).toInt()
            val bottom = screenH - (screenH * 0.145f).toInt()
            Rect(left, top, right, bottom)
        } else {
            val left = (screenW * 0.21f).toInt()
            val top = (screenH * 0.04f).toInt()
            val right = screenW - (screenW * 0.025f).toInt()
            val bottom = screenH - (screenH * 0.11f).toInt()
            Rect(left, top, right, bottom)
        }
    }

    private fun extractPackageFromArgs(args: Array<Any?>?): String? {
        if (args == null) return null
        for (arg in args) {
            when (arg) {
                is ActivityInfo -> if (!arg.packageName.isNullOrBlank()) return arg.packageName
                is Intent -> {
                    val p = arg.component?.packageName ?: arg.`package`
                    if (!p.isNullOrBlank()) return p
                }
                else -> {
                    if (arg != null && arg.javaClass.name.endsWith("ActivityRecord")) {
                        val p = runCatching { XposedHelpers.getObjectField(arg, "packageName") as? String }.getOrNull()
                        if (!p.isNullOrBlank()) return p
                    }
                    if (arg != null && arg.javaClass.name.endsWith(".Task")) {
                        val p = runCatching { XposedHelpers.callMethod(arg, "getBasePackageName") as? String }.getOrNull()
                        if (!p.isNullOrBlank()) return p
                    }
                }
            }
        }
        return null
    }

    private fun extractDisplaySizeFromArgs(args: Array<Any?>?): Pair<Int, Int> {
        if (args != null) {
            for (arg in args) {
                if (arg != null && arg.javaClass.name.endsWith("DisplayContent")) {
                    val rect = runCatching {
                        XposedHelpers.callMethod(arg, "getBounds") as? Rect
                    }.getOrNull()
                    if (rect != null && rect.width() > 0 && rect.height() > 0) {
                        return rect.width() to rect.height()
                    }
                }
            }
        }
        return 2520 to 1680
    }

    private fun isIgnoredSystemPkg(pkg: String): Boolean {
        return pkg.isBlank() ||
            pkg == "com.android.launcher" ||
            pkg == "com.android.systemui" ||
            pkg == "com.android.permissioncontroller" ||
            pkg == "com.android.packageinstaller" ||
            pkg == "com.oplus.appdetail"
    }

    private fun loadMemoryFromDisk() {
        runCatching {
            val file = File(MEMORY_FILE_PATH)
            if (!file.exists()) return
            val props = Properties()
            file.inputStream().use { props.load(it) }
            for (name in props.stringPropertyNames()) {
                val parsed = MemorizedFrame.parse(props.getProperty(name).orEmpty())
                if (parsed != null) {
                    memoryMap[name] = parsed
                }
            }
            HookGuard.info("SYS.MEMORY_LOADED count=${memoryMap.size}")
        }.onFailure {
            HookGuard.warn("SYS.MEMORY_LOADED failed", it)
        }
    }

    private fun saveMemoryToDisk() {
        runCatching {
            val props = Properties()
            for ((k, v) in memoryMap) {
                props.setProperty(k, v.serialize())
            }
            val file = File(MEMORY_FILE_PATH)
            file.outputStream().use { props.store(it, "StageLab Native Window Geometry Memory") }
            file.setReadable(true, false)
        }.onFailure {
            HookGuard.warn("SYS.MEMORY_SAVE failed", it)
        }
    }
}
