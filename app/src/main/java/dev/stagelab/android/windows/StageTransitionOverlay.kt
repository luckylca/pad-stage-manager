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

/**
 * Hardware-accelerated 60/120fps iPadOS Stage Manager spring transition overlay.
 *
 * Animates real [TaskSnapshotStore] window bitmaps with continuous position, scale, corner radius,
 * shadow, and 3D Y-axis perspective tilt (0° <-> 14° toward the left rail) so group switches and
 * minimizes feel indistinguishable from iPadOS — without triggering choppy per-frame app relayouts.
 */
class StageTransitionOverlay(private val activity: Activity) {

    data class TransitionCard(
        val bitmap: Bitmap?,
        val fallbackColor: Int = Color.rgb(34, 40, 52),
        val fromRect: StageRect,
        val toRect: StageRect,
        val fromTiltY: Float = 0f,
        val toTiltY: Float = 0f,
        val fromAlpha: Float = 1f,
        val toAlpha: Float = 1f
    )

    private val wm: WindowManager = activity.getSystemService(WindowManager::class.java)
    private var activeView: TransitionView? = null
    private var activeAnimator: ValueAnimator? = null

    /**
     * Plays a smooth spring-curved animation for [cards] and suspends until finished (or cancelled).
     */
    suspend fun play(cards: List<TransitionCard>, durationMs: Long = 260L) {
        if (cards.isEmpty()) return
        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            cancelImmediate()
            val metrics = activity.resources.displayMetrics
            val view = TransitionView(activity, cards)
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
            val added = runCatching { wm.addView(view, params) }.isSuccess
            if (!added) {
                done.complete(Unit)
                return@withContext
            }
            activeView = view

            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                // iPadOS-style critically damped spring deceleration curve.
                interpolator = PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f)
                addUpdateListener { anim ->
                    view.progress = anim.animatedValue as Float
                    view.invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        removeViewIfMatches(view)
                        if (!done.isCompleted) done.complete(Unit)
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        removeViewIfMatches(view)
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
        activeView?.let { runCatching { wm.removeView(it) } }
        activeView = null
    }

    private fun removeViewIfMatches(view: TransitionView) {
        if (activeView === view) {
            runCatching { wm.removeView(view) }
            activeView = null
            activeAnimator = null
        }
    }

    @SuppressLint("ViewConstructor")
    private class TransitionView(
        context: Activity,
        private val cards: List<TransitionCard>
    ) : View(context) {

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
            color = Color.argb(60, 255, 255, 255)
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val t = progress.coerceIn(0f, 1f)
            val cornerR = 15f * density
            for (card in cards) {
                val left = lerp(card.fromRect.left, card.toRect.left, t)
                val top = lerp(card.fromRect.top, card.toRect.top, t)
                val right = lerp(card.fromRect.right, card.toRect.right, t)
                val bottom = lerp(card.fromRect.bottom, card.toRect.bottom, t)
                val w = (right - left).coerceAtLeast(2f)
                val h = (bottom - top).coerceAtLeast(2f)
                val cx = left + w / 2f
                val cy = top + h / 2f
                val tiltY = lerp(card.fromTiltY, card.toTiltY, t)
                val alpha = (lerp(card.fromAlpha, card.toAlpha, t).coerceIn(0f, 1f) * 255f).roundToInt()

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

                // Soft layered shadow behind the gliding card.
                dstRect.set(left, top, right, bottom)
                for (s in 4 downTo 1) {
                    val spread = s * 4.5f * density
                    shadowPaint.color = Color.argb(((alpha / 255f) * (5 - s) * 14f).roundToInt(), 0, 0, 0)
                    canvas.drawRoundRect(
                        left - spread,
                        top - spread + 4f * density,
                        right + spread,
                        bottom + spread + 4f * density,
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

                borderPaint.alpha = (alpha * 0.28f).roundToInt()
                canvas.drawRoundRect(dstRect, cornerR, cornerR, borderPaint)
                canvas.restore()
            }
        }

        private fun lerp(a: Int, b: Int, t: Float): Float = a + (b - a) * t
        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }
}
