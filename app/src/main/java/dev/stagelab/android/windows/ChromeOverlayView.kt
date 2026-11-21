package dev.stagelab.android.windows

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/** Which piece of window chrome an overlay implements. */
enum class ChromeMode {
    TITLE,
    RESIZE_CORNER,
    RESIZE_RIGHT,
    RESIZE_BOTTOM,
    RESIZE_LEFT,
    RESIZE_TOP,
    RESIZE_TOP_LEFT,
    RESIZE_TOP_RIGHT,
    RESIZE_BOTTOM_LEFT,
    /** Covers the square corner of the real task with the desk colour, producing a rounded corner. */
    MASK,
    /** Rounded focus ring drawn on top of the task edge. */
    RING,
    /** One non touchable overlay that draws all four corner masks plus the focus ring. */
    DECORATION
}

/**
 * A small overlay drawn above the real application window.
 *
 * The overlays are the only way an app-level process can decorate a WindowManager-owned task without
 * touching its Surface (and therefore without breaking native input, IME or Back):
 *
 *  * TITLE  – iPad style title bar with traffic lights, also the drag handle;
 *  * RESIZE_* – the eight resize handles;
 *  * MASK   – a quarter-square filled with the desk colour, subtracted by a quarter circle, which
 *             visually rounds a corner of the real task (non touchable);
 *  * RING   – the focus border, non touchable.
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
        set(value) {
            field = value
            invalidate()
        }

    /** Window corner radius in pixels (shared by the title bar, masks and ring). */
    var cornerRadius: Float = 48f
        set(value) {
            field = value
            invalidate()
        }

    /** Per-corner desk colours sampled from the exact wallpaper behind each corner. */
    var maskColors: IntArray = intArrayOf(Color.BLACK, Color.BLACK, Color.BLACK, Color.BLACK)
        set(value) {
            if (!field.contentEquals(value)) {
                field = value
                invalidate()
            }
        }

    /** 0 = top-left, 1 = top-right, 2 = bottom-left, 3 = bottom-right (MASK only). */
    var cornerIndex: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Per corner switch: a mask must be hidden when another window sits on top of that corner,
     * otherwise it would paint the desk colour over the window in front.
     */
    var maskVisible: BooleanArray = booleanArrayOf(true, true, true, true)
        set(value) {
            field = value
            invalidate()
        }

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
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onAction?.invoke(ACTION_LONG_PRESS)
        }
    }

    /** Latest raw pointer position, read by the chrome controller for drop routing. */
    var lastRawPos: Pair<Int, Int> = 0 to 0
        private set

    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        when (mode) {
            ChromeMode.MASK -> drawCornerMask(canvas, 0, 0f, 0f)
            ChromeMode.RING -> drawRing(canvas)
            ChromeMode.DECORATION -> drawDecoration(canvas)
            ChromeMode.TITLE -> drawTitleBar(canvas)
            else -> drawGrip(canvas)
        }
    }

    // ------------------------------------------------------------------ corner masks

    /** One overlay with the four corner masks and a hairline specular edge (no blue focus border). */
    private fun drawDecoration(canvas: Canvas) {
        val r = cornerRadius
        if (r <= 0f) return
        if (maskVisible.getOrElse(0) { true }) dragMask(canvas, 0, 0f, 0f, r, maskColors[0])
        if (maskVisible.getOrElse(1) { true }) dragMask(canvas, 1, width - r, 0f, r, maskColors[1])
        if (maskVisible.getOrElse(2) { true }) dragMask(canvas, 2, 0f, height - r, r, maskColors[2])
        if (maskVisible.getOrElse(3) { true }) dragMask(canvas, 3, width - r, height - r, r, maskColors[3])
        drawRing(canvas)
    }

    private fun dragMask(
        canvas: Canvas,
        corner: Int,
        offsetX: Float,
        offsetY: Float,
        radius: Float,
        color: Int
    ) = drawCornerMask(canvas, corner, offsetX, offsetY, radius, color)

    private fun drawCornerMask(canvas: Canvas, corner: Int, offsetX: Float, offsetY: Float) {
        drawCornerMask(canvas, corner, offsetX, offsetY, cornerRadius, maskColors[0])
    }

    /**
     * Paints the square minus a quarter circle. [corner] 0=TL, 1=TR, 2=BL, 3=BR.
     */
    private fun drawCornerMask(
        canvas: Canvas,
        corner: Int,
        offsetX: Float,
        offsetY: Float,
        radius: Float,
        color: Int
    ) {
        if (radius <= 0f) return
        val square = Path().apply {
            addRect(offsetX, offsetY, offsetX + radius, offsetY + radius, Path.Direction.CW)
        }
        // The arc centre is the point of the rounded window corner, one radius inside the edges.
        val centerX: Float
        val centerY: Float
        when (corner) {
            0 -> { centerX = offsetX + radius; centerY = offsetY + radius }
            1 -> { centerX = offsetX; centerY = offsetY + radius }
            2 -> { centerX = offsetX + radius; centerY = offsetY }
            else -> { centerX = offsetX; centerY = offsetY }
        }
        val circle = Path().apply {
            addCircle(centerX, centerY, radius, Path.Direction.CW)
        }
        val mask = Path().apply { op(square, circle, Path.Op.DIFFERENCE) }
        maskPaint.color = color
        canvas.drawPath(mask, maskPaint)
    }

    // ------------------------------------------------------------------ hairline edge (no blue ring)

    /**
     * iPadOS Stage Manager windows never use a coloured focus outline; focus is expressed through
     * shadow depth. Only a whisper-thin translucent specular edge keeps overlapping windows crisp.
     */
    private fun drawRing(canvas: Canvas) {
        val d = density()
        val stroke = 0.8f * d
        ringPaint.strokeWidth = stroke
        ringPaint.color = if (focused) Color.argb(48, 255, 255, 255) else Color.argb(24, 255, 255, 255)
        val inset = stroke / 2f + 0.5f
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            cornerRadius,
            cornerRadius,
            ringPaint
        )
    }

    // ------------------------------------------------------------------ iPad top multitasking pill

    /**
     * Authentic iPadOS Stage Manager top chrome: completely transparent drag strip with only the
     * iconic centered `•••` multitasking pill. No dark macOS titlebar, no traffic lights.
     */
    private fun drawTitleBar(canvas: Canvas) {
        if (!pillVisible) {
            pillRect = RectF()
            pillHitRect = RectF()
            return
        }
        val d = density()
        val pillW = (32f * d).coerceAtMost(width * 0.36f)
        val pillH = 12.5f * d
        val cx = width / 2f
        val top = 2.5f * d
        pillRect = RectF(cx - pillW / 2f, top, cx + pillW / 2f, top + pillH)
        // Generous invisible touch slop around the `•••` pill for reliable finger taps.
        val hitPadX = 16f * d
        val hitPadY = 9f * d
        pillHitRect = RectF(
            pillRect.left - hitPadX,
            0f,
            pillRect.right + hitPadX,
            (pillRect.bottom + hitPadY).coerceAtMost(height.toFloat())
        )

        pillFill.color = when {
            pressedPill -> Color.argb(200, 38, 44, 58)
            focused -> Color.argb(128, 24, 28, 38)
            else -> Color.argb(64, 24, 28, 38)
        }
        pillBorder.strokeWidth = 0.7f * d
        pillBorder.color = if (focused) Color.argb(58, 255, 255, 255) else Color.argb(26, 255, 255, 255)

        val pr = pillH / 2f
        canvas.drawRoundRect(pillRect, pr, pr, pillFill)
        canvas.drawRoundRect(pillRect, pr, pr, pillBorder)

        val dotR = 1.75f * d
        val dotGap = 6.2f * d
        val cy = pillRect.centerY()
        dotPaint.color = if (focused) Color.argb(240, 246, 249, 255) else Color.argb(145, 232, 238, 248)
        canvas.drawCircle(cx - dotGap, cy, dotR, dotPaint)
        canvas.drawCircle(cx, cy, dotR, dotPaint)
        canvas.drawCircle(cx + dotGap, cy, dotR, dotPaint)
    }

    // ------------------------------------------------------------------ iPad curved corner resize arc

    private fun drawGrip(canvas: Canvas) {
        if (mode != ChromeMode.RESIZE_CORNER && mode != ChromeMode.RESIZE_BOTTOM_LEFT) return
        if (!focused && !activeHandle) return
        val d = density()
        arcPaint.strokeWidth = (if (activeHandle) 3.8f else 3.0f) * d
        arcPaint.color = if (activeHandle) {
            Color.argb(225, 245, 248, 255)
        } else {
            Color.argb(115, 236, 242, 255)
        }
        // The grip overlay is centered on the window corner, so the window corner lies at (width/2, height/2).
        val arcR = 13f * d
        val inset = 4.5f * d
        if (mode == ChromeMode.RESIZE_CORNER) {
            // Bottom-right curved quarter-arc hugging the window's bottom-right rounded corner.
            val cornerX = width / 2f - inset
            val cornerY = height / 2f - inset
            val oval = RectF(cornerX - arcR * 2f, cornerY - arcR * 2f, cornerX, cornerY)
            canvas.drawArc(oval, 12f, 66f, false, arcPaint)
        } else {
            // Bottom-left curved quarter-arc (shown when actively grabbing bottom-left).
            if (!activeHandle) return
            val cornerX = width / 2f + inset
            val cornerY = height / 2f - inset
            val oval = RectF(cornerX, cornerY - arcR * 2f, cornerX + arcR * 2f, cornerY)
            canvas.drawArc(oval, 102f, 66f, false, arcPaint)
        }
    }

    private fun density(): Float = resources.displayMetrics.density

    // ------------------------------------------------------------------ input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val touchSlop = 12f * density()
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
                    // Mouse/trackpad right-click opens the window control menu immediately (P9).
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

        const val ACTION_CLOSE = 0
        const val ACTION_MINIMIZE = 1
        const val ACTION_ZOOM = 2
        const val ACTION_DOUBLE_TAP = 3
        const val ACTION_LONG_PRESS = 4
        const val ACTION_MENU_TAP = 5

        private const val LONG_PRESS_MS = 420L
    }
}
