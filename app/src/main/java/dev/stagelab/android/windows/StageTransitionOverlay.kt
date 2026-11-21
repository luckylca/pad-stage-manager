package dev.stagelab.android.windows

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import dev.stagelab.android.core.stage.StageRect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pre-attached hardware-accelerated 120fps iPadOS Stage Manager spring transition overlay.
 *
 * Pre-attaching the non-touchable overlay window (initially GONE) eliminates the 50-80ms
 * WindowManager.addView startup latency so every group switch, minimize, and window arrangement
 * begins gliding on the very next VSync with zero stutter.
 */
class StageTransitionOverlay(private val activity: Activity) {

    data class TransitionCard(
        val bitmap: Bitmap?,
        val fallbackColor: Int = Color.rgb(32, 38, 52),
        val fromRect: StageRect,
        val toRect: StageRect,
        val fromTiltY: Float = 0f,
        val toTiltY: Float = 0f,
        val fromAlpha: Float = 1f,
        val toAlpha: Float = 1f,
        val fadeOutTail: Boolean = true
    )

    private val wm: WindowManager = activity.getSystemService(WindowManager::class.java)
    private var hostView: TransitionView? = null
    private var activeAnimator: ValueAnimator? = null

    fun attach() {
        if (hostView != null) return
        val metrics = activity.resources.displayMetrics
        val view = TransitionView(activity).apply {
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            metrics.widthPixels,
            metrics.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        runCatching { wm.addView(view, params) }.onSuccess { hostView = view }
    }

    /**
     * Plays a smooth 120fps spring transition for [cards] and suspends until finished.
     */
    suspend fun play(cards: List<TransitionCard>, durationMs: Long = 380L) {
        if (cards.isEmpty()) return
        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            if (hostView == null) attach()
            val view = hostView
            if (view == null) {
                done.complete(Unit)
                return@withContext
            }
            activeAnimator?.cancel()
            view.cards = cards
            view.progress = 0f
            view.visibility = View.VISIBLE
            view.invalidate()

            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                // Critically damped iPadOS spring deceleration curve.
                interpolator = PathInterpolator(0.18f, 0.92f, 0.24f, 1.0f)
                addUpdateListener { anim ->
                    view.progress = anim.animatedValue as Float
                    view.invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.visibility = View.GONE
                        view.cards = emptyList()
                        activeAnimator = null
                        if (!done.isCompleted) done.complete(Unit)
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        view.visibility = View.GONE
                        view.cards = emptyList()
                        activeAnimator = null
                        if (!done.isCompleted) done.complete(Unit)
                    }
                })
            }
            activeAnimator = animator
            animator.start()
        }
        done.await()
    }

    fun cancelImmediate() {
        activeAnimator?.cancel()
        activeAnimator = null
        hostView?.let {
            it.visibility = View.GONE
            it.cards = emptyList()
        }
    }

    fun destroy() {
        cancelImmediate()
        hostView?.let { runCatching { wm.removeView(it) } }
        hostView = null
    }

    @SuppressLint("ViewConstructor")
    private class TransitionView(context: Activity) : View(context) {

        var cards: List<TransitionCard> = emptyList()
        var progress: Float = 0f

        private val density = context.resources.displayMetrics.density
        private val camera = Camera()
        private val matrix3d = Matrix()
        private val clipPath = Path()
        private val dstRect = RectF()
        private val srcRect = Rect()

        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = Color.argb(66, 255, 255, 255)
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            if (cards.isEmpty()) return
            val t = progress.coerceIn(0f, 1f)
            val cornerR = 15f * density
            // Subtle iPadOS vertical arc lift during flight.
            val arcLift = (-10f * density) * sin(t * Math.PI).toFloat()

            for (card in cards) {
                val left = lerp(card.fromRect.left, card.toRect.left, t)
                val top = lerp(card.fromRect.top, card.toRect.top, t) + arcLift
                val right = lerp(card.fromRect.right, card.toRect.right, t)
                val bottom = lerp(card.fromRect.bottom, card.toRect.bottom, t) + arcLift
                val w = (right - left).coerceAtLeast(2f)
                val h = (bottom - top).coerceAtLeast(2f)
                val cx = left + w / 2f
                val cy = top + h / 2f
                val tiltY = lerp(card.fromTiltY, card.toTiltY, t)

                val baseAlpha = lerp(card.fromAlpha, card.toAlpha, t).coerceIn(0f, 1f)
                // Smooth crossfade over the final 16% so real Tasks underneath emerge seamlessly.
                val tailFade = if (card.fadeOutTail && t > 0.84f) {
                    ((1f - t) / 0.16f).coerceIn(0f, 1f)
                } else 1f
                val alpha = (baseAlpha * tailFade * 255f).roundToInt()
                if (alpha <= 2) continue

                canvas.save()
                if (kotlin.math.abs(tiltY) > 0.1f) {
                    camera.save()
                    camera.setLocation(0f, 0f, -14f)
                    camera.rotateY(tiltY)
                    camera.getMatrix(matrix3d)
                    camera.restore()
                    matrix3d.preTranslate(-cx, -cy)
                    matrix3d.postTranslate(cx, cy)
                    canvas.concat(matrix3d)
                }

                dstRect.set(left, top, right, bottom)
                val alphaRatio = alpha / 255f
                for (s in 4 downTo 1) {
                    val spread = s * 5f * density
                    shadowPaint.color = Color.argb((alphaRatio * (5 - s) * 15f).roundToInt(), 0, 0, 0)
                    canvas.drawRoundRect(
                        left - spread,
                        top - spread + 5f * density,
                        right + spread,
                        bottom + spread + 5f * density,
                        cornerR + spread * 0.5f,
                        cornerR + spread * 0.5f,
                        shadowPaint
                    )
                }

                clipPath.reset()
                clipPath.addRoundRect(dstRect, cornerR, cornerR, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clipPath)

                val bmp = card.bitmap
                if (bmp != null && !bmp.isRecycled) {
                    bitmapPaint.alpha = alpha
                    srcRect.set(0, 0, bmp.width, bmp.height)
                    canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
                } else {
                    fallbackPaint.color = card.fallbackColor
                    fallbackPaint.alpha = alpha
                    canvas.drawRect(dstRect, fallbackPaint)
                }
                canvas.restore()

                borderPaint.alpha = (alpha * 0.30f).roundToInt()
                canvas.drawRoundRect(dstRect, cornerR, cornerR, borderPaint)
                canvas.restore()
            }
        }

        private fun lerp(a: Int, b: Int, t: Float): Float = a + (b - a) * t
        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }
}
