package dev.stagelab.android.stage

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PointF
import android.os.Build
import dev.stagelab.android.core.stage.StageRect
import kotlin.math.roundToInt

/**
 * iPad Stage Manager per-app window sizing & remembered preferences (G13).
 *
 * Replaces the uniform 0.52 x 0.72 default ratio with:
 *  - remembered per-package (w, h) whenever the user manually resizes an app;
 *  - portrait-locked / non-resizable apps -> taller, narrower (~0.44 x 0.80);
 *  - video / PiP apps -> landscape widescreen (~0.66 x 0.60);
 *  - general resizable apps -> comfortable iPad Stage proportion (~0.66 x 0.78).
 */
object WindowSizing {

    private const val PREFS_NAME = "stagelab_window_sizes_v1"

    private val VIDEO_PACKAGE_HINTS = setOf(
        "tv.danmaku.bili",
        "com.bilibili.app.in",
        "com.google.android.youtube",
        "com.netflix.mediaclient",
        "com.qiyi.video",
        "com.tencent.qqlive",
        "com.youku.phone",
        "com.ss.android.ugc.aweme"
    )

    fun rememberSize(context: Context?, packageName: String, w: Float, h: Float) {
        if (context == null || packageName.isBlank()) return
        val cw = w.coerceIn(WindowGeometry.MIN_W, 0.98f)
        val ch = h.coerceIn(WindowGeometry.MIN_H, 0.98f)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat("${packageName}_w", cw)
            .putFloat("${packageName}_h", ch)
            .apply()
    }

    fun defaultGeometry(
        context: Context?,
        packageName: String,
        component: String,
        workspace: StageRect,
        index: Int,
        dropPoint: PointF?
    ): WindowGeometry {
        val landscapeWorkspace = workspace.width >= workspace.height
        val (w, h) = preferredDimensions(context, packageName, component, landscapeWorkspace)

        if (dropPoint != null && workspace.width > 0 && workspace.height > 0) {
            val left = (dropPoint.x - workspace.width * w / 2f).roundToInt()
            val top = (dropPoint.y - workspace.height * h / 2f).roundToInt()
            val g = WindowGeometry(
                x = (left - workspace.left).toFloat() / workspace.width,
                y = (top - workspace.top).toFloat() / workspace.height,
                w = w,
                h = h
            )
            g.clamp()
            return g
        }

        // Subtle iPad-style cascading offset so multiple windows fan naturally.
        val step = index % 4
        val baseX = ((1f - w) / 2f - 0.04f + step * 0.045f).coerceIn(0.02f, (1f - w - 0.02f).coerceAtLeast(0f))
        val baseY = ((1f - h) / 2f - 0.03f + step * 0.035f).coerceIn(0.02f, (1f - h - 0.02f).coerceAtLeast(0f))
        return WindowGeometry(baseX, baseY, w, h).apply { clamp() }
    }

    private fun preferredDimensions(
        context: Context?,
        packageName: String,
        component: String,
        landscapeWorkspace: Boolean
    ): Pair<Float, Float> {
        // 1. User-resized preference for this package takes priority.
        if (context != null && packageName.isNotBlank()) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains("${packageName}_w") && prefs.contains("${packageName}_h")) {
                val rw = prefs.getFloat("${packageName}_w", 0.66f).coerceIn(WindowGeometry.MIN_W, 0.96f)
                val rh = prefs.getFloat("${packageName}_h", 0.78f).coerceIn(WindowGeometry.MIN_H, 0.96f)
                return rw to rh
            }
        }

        if (!landscapeWorkspace) {
            return 0.74f to 0.56f
        }

        // 2. Video / PiP apps.
        if (packageName in VIDEO_PACKAGE_HINTS) {
            return 0.66f to 0.62f
        }

        // 3. Inspect ActivityInfo for portrait lock or PiP capability.
        if (context != null && packageName.isNotBlank()) {
            val info = resolveActivityInfo(context, packageName, component)
            if (info != null) {
                val supportsPip = if (Build.VERSION.SDK_INT >= 26) {
                    (info.flags and 0x00400000) != 0 // ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE
                } else false
                if (supportsPip) {
                    return 0.66f to 0.62f
                }
                val portraitLocked = info.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
                    info.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT ||
                    info.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                if (portraitLocked) {
                    return 0.44f to 0.80f
                }
            }
        }

        // 4. General resizable iPad Stage Manager window proportion.
        return 0.66f to 0.78f
    }

    private fun resolveActivityInfo(context: Context, packageName: String, component: String): ActivityInfo? {
        val pm = context.packageManager
        if (component.isNotBlank()) {
            val cn = ComponentName(packageName, component)
            runCatching { return pm.getActivityInfo(cn, 0) }
        }
        return runCatching {
            val intent = pm.getLaunchIntentForPackage(packageName) ?: return null
            val resolved = pm.resolveActivity(intent, 0) ?: return null
            resolved.activityInfo
        }.getOrNull()
    }
}
