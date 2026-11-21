package dev.stagelab.android.windows

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/** Lightweight iPadOS Stage Manager window chrome pieces (3 tiny overlays per window, zero corner masks). */
enum class ChromeMode {
    TITLE,
    RESIZE_CORNER,
    RESIZE_BOTTOM_LEFT
}

@SuppressLint("ViewConstructor")
class ChromeOverlayView(
    context: Context,
    private val mode: ChromeMode,
    private val onTouch: (phase: Int, dx: Float, dy: Float) -> Unit,
    private val onAction: ((Int) -> Unit)? = null
) : View(context) {

    var focused: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Hidden when another window in front occludes this window's top-center `•••` pill. */
    var pillVisible: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var title: String = ""

    private var pillRect = RectF()
    private var pillHitRect = RectF()

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastTapTime = 0L
    private var pressedPill = false
    private var activeHandle = false
    private var dragging = false
    private var longPressed = false

    private val longPressRunnable = Runnable {
        if (!dragging && mode == ChromeMode.TITLE) {
            longPressed = true
            pressedPill = false
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onAction?.invoke(ACTION_LONG_PRESS)
        }
    }

    var lastRawPos: Pair<Int, Int> = 0 to 0
        private set

    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        when (mode) {
            ChromeMode.TITLE -> drawTitlePill(canvas)
            ChromeMode.RESIZE_CORNER, ChromeMode.RESIZE_BOTTOM_LEFT -> drawCurvedCornerArc(canvas)
        }
    }

    private fun drawTitlePill(canvas: Canvas) {
        if (!pillVisible) {
            pillRect = RectF()
            pillHitRect = RectF()
            return
        }
        val d = density()
        val pillW = (34f * d).coerceAtMost(width * 0.36f)
        val pillH = 13f * d
        val cx = width / 2f
        val top = 2.5f * d
        pillRect = RectF(cx - pillW / 2f, top, cx + pillW / 2f, top + pillH)

        val hitPadX = 16f * d
        val hitPadY = 9f * d
        pillHitRect = RectF(
            pillRect.left - hitPadX,
            0f,
            pillRect.right + hitPadX,
            (pillRect.bottom + hitPadY).coerceAtMost(height.toFloat())
        )

        pillFill.color = when {
            pressedPill -> Color.argb(208, 38, 44, 58)
            focused -> Color.argb(132, 24, 28, 38)
            else -> Color.argb(64, 24, 28, 38)
        }
        pillBorder.strokeWidth = 0.7f * d
        pillBorder.color = if (focused) Color.argb(62, 255, 255, 255) else Color.argb(28, 255, 255, 255)

        val pr = pillH / 2f
        canvas.drawRoundRect(pillRect, pr, pr, pillFill)
        canvas.drawRoundRect(pillRect, pr, pr, pillBorder)

        val dotR = 1.8f * d
        val dotGap = 6.4f * d
        val cy = pillRect.centerY()
        dotPaint.color = if (focused) Color.argb(242, 246, 249, 255) else Color.argb(145, 232, 238, 248)
        canvas.drawCircle(cx - dotGap, cy, dotR, dotPaint)
        canvas.drawCircle(cx, cy, dotR, dotPaint)
        canvas.drawCircle(cx + dotGap, cy, dotR, dotPaint)
    }

    private fun drawCurvedCornerArc(canvas: Canvas) {
        if (!focused && !activeHandle) return
        val d = density()
        arcPaint.strokeWidth = (if (activeHandle) 4.0f else 3.0f) * d
        arcPaint.color = if (activeHandle) {
            Color.argb(235, 245, 248, 255)
        } else {
            Color.argb(120, 236, 242, 255)
        }
        val arcR = 13f * d
        val inset = 4.5f * d
        if (mode == ChromeMode.RESIZE_CORNER) {
            val cornerX = width / 2f - inset
            val cornerY = height / 2f - inset
            val oval = RectF(cornerX - arcR * 2f, cornerY - arcR * 2f, cornerX, cornerY)
            canvas.drawArc(oval, 12f, 66f, false, arcPaint)
        } else {
            val cornerX = width / 2f + inset
            val cornerY = height / 2f - inset
            val oval = RectF(cornerX, cornerY - arcR * 2f, cornerX + arcR * 2f, cornerY)
            canvas.drawArc(oval, 102f, 66f, false, arcPaint)
        }
    }

    private fun density(): Float = resources.displayMetrics.density

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val touchSlop = 10f * density()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downTime = System.currentTimeMillis()
                lastRawPos = event.rawX.roundToInt() to event.rawY.roundToInt()
                longPressed = false
                dragging = false
                pressedPill = false
                activeHandle = mode != ChromeMode.TITLE
                if (activeHandle) invalidate()
                if (mode == ChromeMode.TITLE) {
                    if ((event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                        longPressed = true
                        onAction?.invoke(ACTION_LONG_PRESS)
                        return true
                    }
                    pressedPill = pillHitRect.contains(event.x, event.y)
                    if (pressedPill) invalidate()
                    removeCallbacks(longPressRunnable)
                    postDelayed(longPressRunnable, LONG_PRESS_MS)
                    if (!pressedPill) {
                        onTouch(PHASE_START, 0f, 0f)
                    }
                    return true
                }
                onTouch(PHASE_START, 0f, 0f)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastRawPos = event.rawX.roundToInt() to event.rawY.roundToInt()
                if (longPressed) return true
                val moved = kotlin.math.abs(event.rawX - downX) + kotlin.math.abs(event.rawY - downY)
                if (moved > touchSlop) {
                    removeCallbacks(longPressRunnable)
                    if (!dragging) {
                        dragging = true
                        if (mode == ChromeMode.TITLE && pressedPill) {
                            pressedPill = false
                            invalidate()
                            onTouch(PHASE_START, 0f, 0f)
                        }
                    }
                }
                if (dragging || mode != ChromeMode.TITLE) {
                    onTouch(PHASE_MOVE, event.rawX - downX, event.rawY - downY)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (activeHandle) {
                    activeHandle = false
                    invalidate()
                }
                if (longPressed) {
                    longPressed = false
                    return true
                }
                val moved = kotlin.math.abs(event.rawX - downX) + kotlin.math.abs(event.rawY - downY)
                val quick = System.currentTimeMillis() - downTime < 350
                if (mode == ChromeMode.TITLE && pressedPill && moved < touchSlop) {
                    pressedPill = false
                    invalidate()
                    onAction?.invoke(ACTION_MENU_TAP)
                    return true
                }
                if (pressedPill) {
                    pressedPill = false
                    invalidate()
                }
                if (mode == ChromeMode.TITLE && moved < touchSlop && quick) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 280) {
                        onAction?.invoke(ACTION_DOUBLE_TAP)
                        lastTapTime = 0L
                    } else {
                        lastTapTime = now
                    }
                }
                if (dragging || mode != ChromeMode.TITLE) {
                    onTouch(PHASE_END, 0f, 0f)
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                val wasDragging = dragging
                dragging = false
                longPressed = false
                if (pressedPill || activeHandle) {
                    pressedPill = false
                    activeHandle = false
                    invalidate()
                }
                if (wasDragging || mode != ChromeMode.TITLE) {
                    onTouch(PHASE_CANCEL, 0f, 0f)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        const val PHASE_START = 0
        const val PHASE_MOVE = 1
        const val PHASE_END = 2
        const val PHASE_CANCEL = 3

        const val ACTION_DOUBLE_TAP = 3
        const val ACTION_LONG_PRESS = 4
        const val ACTION_MENU_TAP = 5

        private const val LONG_PRESS_MS = 420L
    }
}
