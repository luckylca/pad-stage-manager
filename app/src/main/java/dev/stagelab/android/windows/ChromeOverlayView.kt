package dev.stagelab.android.windows

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Which gesture a chrome overlay implements. */
enum class ChromeMode { TITLE, RESIZE_CORNER, RESIZE_RIGHT, RESIZE_BOTTOM }

/**
 * A tiny overlay view drawn above the real application window.
 *
 * The title bar is both the window decoration (iPad-like traffic lights) and the drag handle; the
 * grips resize the window. All of them are `TYPE_APPLICATION_OVERLAY` windows so they receive touch
 * while the application window underneath keeps native input, IME and focus.
 */
@SuppressLint("ViewConstructor")
class ChromeOverlayView(
    context: Context,
    private val mode: ChromeMode,
    private val onTouch: (phase: Int, dx: Float, dy: Float) -> Unit,
    private val onAction: ((Int) -> Unit)? = null
) : View(context) {

    var focused: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var title: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var closeRect: RectF = RectF()
    var minimizeRect: RectF = RectF()

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(232, 28, 32, 40)
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.argb(60, 255, 255, 255)
    }
    private val accentBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.argb(215, 138, 160, 255)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 236, 241, 250)
        textSize = 30f
        isFakeBoldText = true
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val radius = when (mode) {
            ChromeMode.TITLE -> 26f
            ChromeMode.RESIZE_CORNER -> 20f
            else -> 0f
        }
        val rect = RectF(1f, 1f, width - 1f, height - 1f)
        if (mode == ChromeMode.TITLE) {
            canvas.drawRoundRect(rect, radius, radius, fill)
            canvas.drawRoundRect(rect, radius, radius, if (focused) accentBorder else border)
            drawTrafficLights(canvas)
            drawTitle(canvas)
        } else {
            drawGrip(canvas, rect)
        }
    }

    private fun drawTrafficLights(canvas: Canvas) {
        val r = height * 0.16f
        val cy = height / 2f
        val startX = height * 0.42f
        val gap = height * 0.52f
        closeRect = RectF(startX - r, cy - r, startX + r, cy + r)
        minimizeRect = RectF(startX + gap - r, cy - r, startX + gap + r, cy + r)
        dotPaint.color = Color.rgb(255, 106, 97)
        canvas.drawCircle(closeRect.centerX(), cy, r, dotPaint)
        dotPaint.color = Color.rgb(255, 199, 76)
        canvas.drawCircle(minimizeRect.centerX(), cy, r, dotPaint)
        // Close glyph
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 60, 20, 18)
            strokeWidth = 3.2f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(
            closeRect.centerX() - r * 0.45f, cy - r * 0.45f,
            closeRect.centerX() + r * 0.45f, cy + r * 0.45f, glyph
        )
        canvas.drawLine(
            closeRect.centerX() + r * 0.45f, cy - r * 0.45f,
            closeRect.centerX() - r * 0.45f, cy + r * 0.45f, glyph
        )
        canvas.drawLine(
            minimizeRect.centerX() - r * 0.45f, cy,
            minimizeRect.centerX() + r * 0.45f, cy, glyph
        )
    }

    private fun drawTitle(canvas: Canvas) {
        if (title.isBlank()) return
        val available = width - height * 2.4f
        var text = title
        textPaint.textSize = height * 0.34f
        while (textPaint.measureText(text) > available && text.length > 4) {
            text = text.dropLast(2)
        }
        val x = width / 2f - textPaint.measureText(text) / 2f
        val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, x, y, textPaint)
    }

    private fun drawGrip(canvas: Canvas, rect: RectF) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
        }
        val inset = 8f
        when (mode) {
            ChromeMode.RESIZE_CORNER -> {
                for (index in 0..2) {
                    val offset = index * 9f
                    canvas.drawLine(
                        rect.right - inset - offset, rect.bottom - inset,
                        rect.right - inset, rect.bottom - inset - offset,
                        paint
                    )
                }
            }
            ChromeMode.RESIZE_RIGHT -> {
                canvas.drawLine(rect.centerX(), rect.top + inset, rect.centerX(), rect.bottom - inset, paint)
            }
            ChromeMode.RESIZE_BOTTOM -> {
                canvas.drawLine(rect.left + inset, rect.centerY(), rect.right - inset, rect.centerY(), paint)
            }
            else -> Unit
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastX = downX
                lastY = downY
                if (mode == ChromeMode.TITLE) {
                    if (closeRect.contains(event.x, event.y)) {
                        onAction?.invoke(ACTION_CLOSE)
                        return true
                    }
                    if (minimizeRect.contains(event.x, event.y)) {
                        onAction?.invoke(ACTION_MINIMIZE)
                        return true
                    }
                }
                onTouch(PHASE_START, 0f, 0f)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Report the offset relative to the gesture start, not the per-event delta, so the
                // controller can always recompute from the rectangle captured on ACTION_DOWN.
                onTouch(PHASE_MOVE, event.rawX - downX, event.rawY - downY)
                lastX = event.rawX
                lastY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onTouch(PHASE_END, 0f, 0f)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        const val PHASE_START = 0
        const val PHASE_MOVE = 1
        const val PHASE_END = 2
        const val ACTION_CLOSE = 0
        const val ACTION_MINIMIZE = 1
    }
}
