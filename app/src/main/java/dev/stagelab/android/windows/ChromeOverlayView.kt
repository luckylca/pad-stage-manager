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
            field = value
            invalidate()
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

    /** Desk colour behind the window, used to paint the corner masks. */
    var maskColor: Int = Color.BLACK
        set(value) {
            field = value
            invalidate()
        }

    /** Desk colour under the bottom corners (the desk has a vertical gradient). */
    var maskColorBottom: Int = Color.BLACK
        set(value) {
            field = value
            invalidate()
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

    private var closeRect = RectF()
    private var minimizeRect = RectF()
    private var zoomRect = RectF()

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastTapTime = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(236, 26, 30, 38)
    }
    private val barBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(46, 255, 255, 255)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(236, 238, 243, 252)
        isFakeBoldText = true
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
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

    /** One overlay with the four corner masks and the focus ring. */
    private fun drawDecoration(canvas: Canvas) {
        val r = cornerRadius
        if (r <= 0f) return
        if (maskVisible.getOrElse(0) { true }) dragMask(canvas, 0, 0f, 0f, r, maskColor)
        if (maskVisible.getOrElse(1) { true }) dragMask(canvas, 1, width - r, 0f, r, maskColor)
        if (maskVisible.getOrElse(2) { true }) dragMask(canvas, 2, 0f, height - r, r, maskColorBottom)
        if (maskVisible.getOrElse(3) { true }) dragMask(canvas, 3, width - r, height - r, r, maskColorBottom)
        if (focused) drawRing(canvas)
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
        drawCornerMask(canvas, corner, offsetX, offsetY, cornerRadius, maskColor)
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

    // ------------------------------------------------------------------ focus ring

    private fun drawRing(canvas: Canvas) {
        val stroke = 2.2f * density()
        ringPaint.strokeWidth = stroke
        ringPaint.color = if (focused) Color.argb(230, 138, 160, 255) else Color.argb(40, 255, 255, 255)
        val inset = stroke / 2f + 0.5f
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            cornerRadius,
            cornerRadius,
            ringPaint
        )
    }

    // ------------------------------------------------------------------ title bar

    private fun drawTitleBar(canvas: Canvas) {
        val r = cornerRadius.coerceAtMost(height.toFloat())
        val path = Path().apply {
            addRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f),
                Path.Direction.CW
            )
        }
        canvas.drawPath(path, fill)
        if (width > 4) {
            canvas.drawPath(path, barBorder)
        }
        drawTrafficLights(canvas)
        drawTitle(canvas)
    }

    private fun drawTrafficLights(canvas: Canvas) {
        val r = height * 0.155f
        val cy = height * 0.52f
        val startX = height * 0.46f
        val gap = height * 0.50f
        closeRect = RectF(startX - r, cy - r, startX + r, cy + r)
        minimizeRect = RectF(startX + gap - r, cy - r, startX + gap + r, cy + r)
        zoomRect = RectF(startX + 2 * gap - r, cy - r, startX + 2 * gap + r, cy + r)

        dotPaint.color = Color.rgb(255, 99, 92)
        canvas.drawCircle(closeRect.centerX(), cy, r, dotPaint)
        dotPaint.color = Color.rgb(255, 196, 74)
        canvas.drawCircle(minimizeRect.centerX(), cy, r, dotPaint)
        dotPaint.color = Color.rgb(96, 208, 122)
        canvas.drawCircle(zoomRect.centerX(), cy, r, dotPaint)

        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 50, 18, 16)
            strokeWidth = 3.0f * density()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val d = r * 0.44f
        canvas.drawLine(closeRect.centerX() - d, cy - d, closeRect.centerX() + d, cy + d, glyph)
        canvas.drawLine(closeRect.centerX() + d, cy - d, closeRect.centerX() - d, cy + d, glyph)
        canvas.drawLine(minimizeRect.centerX() - d, cy, minimizeRect.centerX() + d, cy, glyph)
        // Zoom glyph: a small diagonal double arrow.
        canvas.drawLine(zoomRect.centerX() - d, cy + d, zoomRect.centerX() + d, cy - d, glyph)
        canvas.drawLine(zoomRect.centerX() - d, cy + d, zoomRect.centerX() - d * 0.2f, cy + d, glyph)
        canvas.drawLine(zoomRect.centerX() + d, cy - d, zoomRect.centerX() + d * 0.2f, cy - d, glyph)
    }

    private fun drawTitle(canvas: Canvas) {
        if (title.isBlank()) return
        val available = width - height * 3.4f
        var text = title
        textPaint.textSize = height * 0.33f
        while (textPaint.measureText(text) > available && text.length > 4) {
            text = text.dropLast(2)
        }
        val x = width / 2f - textPaint.measureText(text) / 2f
        val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, x, y, textPaint)
    }

    // ------------------------------------------------------------------ resize grips

    private fun drawGrip(canvas: Canvas) {
        // iPad windows have no visible resize affordances: the edges simply respond to drags.
        // A very faint hint is drawn only for the focused window's bottom-right corner.
        if (mode != ChromeMode.RESIZE_CORNER || !focused) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(52, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3f * density()
            strokeCap = Paint.Cap.ROUND
        }
        val inset = 9f * density()
        for (index in 0..1) {
            val offset = index * 9f * density()
            canvas.drawLine(
                width - inset - offset, height - inset,
                width - inset, height - inset - offset,
                paint
            )
        }
    }

    private fun density(): Float = resources.displayMetrics.density

    // ------------------------------------------------------------------ input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downTime = System.currentTimeMillis()
                if (mode == ChromeMode.TITLE) {
                    if (closeRect.contains(event.x, event.y)) {
                        onAction?.invoke(ACTION_CLOSE)
                        return true
                    }
                    if (minimizeRect.contains(event.x, event.y)) {
                        onAction?.invoke(ACTION_MINIMIZE)
                        return true
                    }
                    if (zoomRect.contains(event.x, event.y)) {
                        onAction?.invoke(ACTION_ZOOM)
                        return true
                    }
                }
                onTouch(PHASE_START, 0f, 0f)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                onTouch(PHASE_MOVE, event.rawX - downX, event.rawY - downY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val moved = kotlin.math.abs(event.rawX - downX) + kotlin.math.abs(event.rawY - downY)
                val quick = System.currentTimeMillis() - downTime < 260
                if (mode == ChromeMode.TITLE && moved < 12f && quick) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 280) {
                        onAction?.invoke(ACTION_DOUBLE_TAP)
                        lastTapTime = 0L
                    } else {
                        lastTapTime = now
                        onAction?.invoke(ACTION_LONG_PRESS_CANDIDATE)
                    }
                }
                onTouch(PHASE_END, 0f, 0f)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
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
        const val ACTION_ZOOM = 2
        const val ACTION_DOUBLE_TAP = 3
        const val ACTION_LONG_PRESS_CANDIDATE = 4
    }
}
