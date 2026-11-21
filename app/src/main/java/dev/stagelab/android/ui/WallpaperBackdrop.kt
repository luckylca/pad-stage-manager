package dev.stagelab.android.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt

/**
 * Loads the device's system wallpaper when permitted, falling back to an iPadOS-inspired
 * atmospheric wallpaper gradient. Also provides fast pixel sampling at normalized coordinates so
 * window corner masks blend seamlessly with the desktop behind them (G16).
 */
class WallpaperBackdrop(
    val bitmap: Bitmap?,
    val imageBitmap: ImageBitmap?
) {
    /** Samples the exact desktop pixel colour (including scrim / radial glows) at normalized (0..1, 0..1). */
    fun sampleColor(xNorm: Float, yNorm: Float): Int {
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
            val px = (xNorm.coerceIn(0f, 1f) * (bmp.width - 1)).roundToInt()
            val py = (yNorm.coerceIn(0f, 1f) * (bmp.height - 1)).roundToInt()
            val raw = runCatching { bmp.getPixel(px, py) }.getOrDefault(fallbackColorAt(xNorm, yNorm))
            // Match the 0.14f black scrim drawn in WallpaperLayer.
            return alphaOver(raw, Color.argb(36, 0, 0, 0))
        }
        return fallbackColorAt(xNorm, yNorm)
    }

    companion object {
        // iPadOS-inspired deep twilight gradient (used when system wallpaper read is restricted).
        const val FALLBACK_TOP = 0xFF1A2236.toInt()
        const val FALLBACK_MID = 0xFF141B2D.toInt()
        const val FALLBACK_BOTTOM = 0xFF0E1320.toInt()

        fun fallbackColorAt(xNorm: Float, yNorm: Float): Int {
            val t = yNorm.coerceIn(0f, 1f)
            var color = if (t < 0.5f) {
                blendColor(FALLBACK_TOP, FALLBACK_MID, t * 2f)
            } else {
                blendColor(FALLBACK_MID, FALLBACK_BOTTOM, (t - 0.5f) * 2f)
            }
            // Match the two radial ambient glows in WallpaperLayer so corner masks are 100% seamless.
            val d1 = kotlin.math.hypot((xNorm - 0.28f).toDouble(), (yNorm - 0.22f).toDouble()).toFloat()
            val a1 = ((1f - d1 / 0.65f).coerceIn(0f, 1f) * 42f).roundToInt()
            if (a1 > 0) color = alphaOver(color, Color.argb(a1, 0x4C, 0x66, 0xB8))

            val d2 = kotlin.math.hypot((xNorm - 0.78f).toDouble(), (yNorm - 0.72f).toDouble()).toFloat()
            val a2 = ((1f - d2 / 0.60f).coerceIn(0f, 1f) * 32f).roundToInt()
            if (a2 > 0) color = alphaOver(color, Color.argb(a2, 0x68, 0x48, 0xA8))

            return color
        }

        private fun alphaOver(dst: Int, src: Int): Int {
            val a = Color.alpha(src) / 255f
            val r = (Color.red(dst) * (1f - a) + Color.red(src) * a).roundToInt().coerceIn(0, 255)
            val g = (Color.green(dst) * (1f - a) + Color.green(src) * a).roundToInt().coerceIn(0, 255)
            val b = (Color.blue(dst) * (1f - a) + Color.blue(src) * a).roundToInt().coerceIn(0, 255)
            return Color.rgb(r, g, b)
        }

        private fun blendColor(a: Int, b: Int, t: Float): Int {
            val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt()
            val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt()
            val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt()
            return Color.rgb(r, g, bl)
        }

        fun load(context: Context): WallpaperBackdrop {
            val wm = runCatching { WallpaperManager.getInstance(context) }.getOrNull()
            val drawable = runCatching { wm?.peekDrawable() ?: wm?.drawable }.getOrNull()
            val bmp = when (drawable) {
                is BitmapDrawable -> drawable.bitmap?.let { src ->
                    downscale(src, 1280)
                }
                null -> null
                else -> runCatching {
                    val w = drawable.intrinsicWidth.coerceIn(1, 1280)
                    val h = drawable.intrinsicHeight.coerceIn(1, 1280)
                    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(out)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    out
                }.getOrNull()
            }
            return WallpaperBackdrop(bmp, bmp?.asImageBitmap())
        }

        private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
            val maxSide = maxOf(src.width, src.height)
            if (maxSide <= maxDim) {
                return if (src.config == Bitmap.Config.HARDWARE) {
                    src.copy(Bitmap.Config.ARGB_8888, false) ?: src
                } else src
            }
            val scale = maxDim.toFloat() / maxSide.toFloat()
            val w = (src.width * scale).roundToInt().coerceAtLeast(1)
            val h = (src.height * scale).roundToInt().coerceAtLeast(1)
            val safeSrc = if (src.config == Bitmap.Config.HARDWARE) {
                src.copy(Bitmap.Config.ARGB_8888, false) ?: src
            } else src
            return Bitmap.createScaledBitmap(safeSrc, w, h, true)
        }
    }
}
