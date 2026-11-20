package dev.stagelab.android.windows

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dev.stagelab.android.core.stage.StageRect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Owns every floating piece of window chrome that lives *above* the real application tasks.
 *
 * Why overlays: the stage host window sits behind the tasks, so it can never receive a touch that
 * lands on an application window. `TYPE_APPLICATION_OVERLAY` windows are composited above the tasks;
 * the touchable ones are only a few dozen dp (title bar + eight resize handles), while a single
 * non touchable overlay paints the four corner masks and the focus ring. The application window
 * therefore keeps 100% native input, IME, Back and accessibility.
 */
class WindowChromeController(
    private val activity: Activity,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onGeometryChanged(id: String, rect: StageRect, final: Boolean)
        fun onWindowDragging(id: String, x: Int, y: Int)
        fun onWindowDropped(id: String, x: Int, y: Int)
        fun onFocusRequested(id: String)
        fun onCloseRequested(id: String)
        fun onMinimizeRequested(id: String)
        fun onZoomToggled(id: String, maximized: Boolean)
        fun onLeftEdgeSwipe()
        fun onChromeTapOutside()
    }

    data class ChromeWindow(
        val id: String,
        val rect: StageRect,
        val title: String,
        val focused: Boolean,
        val minimized: Boolean,
        val deskTopColor: Int = 0xFF141922.toInt(),
        val deskBottomColor: Int = 0xFF0A0D13.toInt(),
        val workspace: StageRect = rect
    )

    private class Holder(
        val titleBar: ChromeOverlayView,
        val decoration: ChromeOverlayView,
        val handles: List<Pair<ChromeMode, ChromeOverlayView>>,
        var rect: StageRect,
        var workspace: StageRect
    ) {
        var gestureBase: StageRect = rect
        var restoredRect: StageRect? = null
        /** Raw pointer position at gesture start, for drop-point routing. */
        var touchStart: Pair<Int, Int> = 0 to 0
        /** Latest pointer position during the gesture. */
        var touchLast: Pair<Int, Int> = 0 to 0
    }

    private val wm: WindowManager = activity.getSystemService(WindowManager::class.java)
    private val holders = LinkedHashMap<String, Holder>()
    private var edgeStrip: View? = null
    private var railOverlay: View? = null
    private var dropHighlightView: DropHighlightOverlayView? = null

    /** A tappable (and optionally draggable) region above the parked rail windows. */
    data class TapTarget(
        val rect: StageRect,
        val groupId: String? = null,
        val action: () -> Unit
    )

    private var railTargets: List<TapTarget> = emptyList()
    private var railScrollHandler: (Float) -> Unit = {}
    private var railDragMoveHandler: (String, Int, Int) -> Unit = { _, _, _ -> }
    private var railDragEndHandler: (String, Int, Int) -> Unit = { _, _, _ -> }

    private val dp: Float get() = activity.resources.displayMetrics.density
    private fun px(value: Float): Int = (value * dp).roundToInt()

    private val titleHeight = px(34f)
    private val gripSize = px(28f)
    private val edgeSize = px(14f)
    private val minWidth = px(232f)
    private val minHeight = px(200f)
    private val cornerRadius = px(22f)

    // ------------------------------------------------------------------ public API

    fun update(windows: List<ChromeWindow>, visible: Boolean) {
        if (!visible) {
            holders.keys.toList().forEach { remove(it) }
            return
        }
        val ids = windows.map { it.id }.toSet()
        holders.keys.filter { it !in ids }.forEach { remove(it) }
        // A corner mask must be hidden when a window in front covers that corner; otherwise it
        // would paint desk colour on top of the window in front.
        windows.forEachIndexed { index, window ->
            val above = windows.drop(index + 1)
            val flags = BooleanArray(4) { corner ->
                val square = cornerSquare(window.rect, corner)
                above.none { candidate -> intersects(square, candidate.rect) }
            }
            addOrUpdate(window, flags)
        }
    }

    private fun cornerSquare(rect: StageRect, corner: Int): StageRect = when (corner) {
        0 -> StageRect(rect.left, rect.top, rect.left + cornerRadius, rect.top + cornerRadius)
        1 -> StageRect(rect.right - cornerRadius, rect.top, rect.right, rect.top + cornerRadius)
        2 -> StageRect(rect.left, rect.bottom - cornerRadius, rect.left + cornerRadius, rect.bottom)
        else -> StageRect(rect.right - cornerRadius, rect.bottom - cornerRadius, rect.right, rect.bottom)
    }

    private fun intersects(a: StageRect, b: StageRect): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    /** Called by the host when a window's own "zoom" action is triggered elsewhere. */
    fun toggleZoom(id: String) {
        val holder = holders[id] ?: return
        val saved = holder.restoredRect
        if (saved == null) {
            holder.restoredRect = holder.rect
            val ws = holder.workspace
            val maximized = StageRect(ws.left + px(4f), ws.top + px(4f), ws.right - px(4f), ws.bottom - px(4f))
            holder.rect = maximized
            position(holder)
            callbacks.onGeometryChanged(id, maximized, true)
            callbacks.onZoomToggled(id, true)
        } else {
            holder.rect = saved
            holder.restoredRect = null
            position(holder)
            callbacks.onGeometryChanged(id, saved, true)
            callbacks.onZoomToggled(id, false)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun ensureEdgeStrip() {
        if (edgeStrip != null) return
        val strip = View(activity).apply {
            setBackgroundColor(0x00000000)
            setOnTouchListener(object : android.view.View.OnTouchListener {
                private var startX = 0f
                private var startY = 0f
                override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            startX = event.rawX
                            startY = event.rawY
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            val dx = event.rawX - startX
                            val dy = event.rawY - startY
                            if (dx > px(24f) && kotlin.math.abs(dy) < px(80f)) {
                                callbacks.onLeftEdgeSwipe()
                            }
                        }
                    }
                    return true
                }
            })
        }
        val params = overlayParams(
            width = px(18f),
            height = WindowManager.LayoutParams.MATCH_PARENT,
            x = 0,
            y = 0,
            touchable = true
        )
        runCatching { wm.addView(strip, params) }.onSuccess { edgeStrip = strip }
            .onFailure { Log.w(TAG, "edge strip failed: ${it.message}") }
    }

    /**
     * Shows or hides a non-touchable drop-zone highlight overlay above all real task windows so
     * drag feedback is never occluded by live previews or workspace windows.
     */
    fun updateDropHighlight(rect: StageRect?, label: String) {
        if (rect == null || rect.width <= 0 || rect.height <= 0 || label.isEmpty()) {
            dropHighlightView?.let { runCatching { wm.removeView(it) } }
            dropHighlightView = null
            return
        }
        val existing = dropHighlightView
        if (existing == null) {
            val view = DropHighlightOverlayView(activity).apply {
                this.label = label
            }
            val params = overlayParams(rect.width, rect.height, rect.left, rect.top, touchable = false)
            runCatching { wm.addView(view, params) }.onSuccess { dropHighlightView = view }
                .onFailure { Log.w(TAG, "drop highlight overlay failed: ${it.message}") }
        } else {
            existing.label = label
            layout(existing, rect.left, rect.top, rect.width, rect.height)
        }
    }

    /**
     * The parked group previews are real windows above the stage host, so the rail's own tap
     * targets only work through an overlay that sits on top of them.
     *
     * Supports three gestures:
     *  - Tap on a tile -> switch group (or trigger "+" / scroll chevron);
     *  - Vertical drag -> scroll the rail;
     *  - Horizontal right drag on a group tile -> drag the group into the central workspace.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun updateRailTargets(
        targets: List<TapTarget>,
        bounds: StageRect?,
        visible: Boolean,
        onScroll: (Float) -> Unit = {},
        onRailDragMove: (groupId: String, x: Int, y: Int) -> Unit = { _, _, _ -> },
        onRailDragEnd: (groupId: String, x: Int, y: Int) -> Unit = { _, _, _ -> }
    ) {
        railTargets = targets
        railScrollHandler = onScroll
        railDragMoveHandler = onRailDragMove
        railDragEndHandler = onRailDragEnd
        if (!visible || bounds == null || bounds.width <= 0) {
            railOverlay?.let { runCatching { wm.removeView(it) } }
            railOverlay = null
            return
        }
        if (railOverlay == null) {
            val view = View(activity).apply {
                setBackgroundColor(0x01000000)
                isClickable = true
                setOnTouchListener(object : android.view.View.OnTouchListener {
                    private var startX = 0f
                    private var startY = 0f
                    private var lastY = 0f
                    private var totalDx = 0f
                    private var totalDy = 0f
                    private var gesture = 0 // 0=none, 1=scroll, 2=dragOut
                    private var touchedGroupId: String? = null

                    override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                startX = event.rawX
                                startY = event.rawY
                                lastY = event.rawY
                                totalDx = 0f
                                totalDy = 0f
                                gesture = 0
                                val x = event.rawX.toInt()
                                val y = event.rawY.toInt()
                                touchedGroupId = railTargets.firstOrNull { target ->
                                    target.groupId != null &&
                                        x >= target.rect.left && x <= target.rect.right &&
                                        y >= target.rect.top && y <= target.rect.bottom
                                }?.groupId
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                val dy = event.rawY - lastY
                                lastY = event.rawY
                                totalDx = event.rawX - startX
                                totalDy = event.rawY - startY
                                if (gesture == 0) {
                                    if (touchedGroupId != null && totalDx > px(22f) && kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy)) {
                                        gesture = 2
                                    } else if (kotlin.math.abs(totalDy) > px(18f)) {
                                        gesture = 1
                                    }
                                }
                                when (gesture) {
                                    1 -> railScrollHandler(dy)
                                    2 -> touchedGroupId?.let { gid ->
                                        railDragMoveHandler(gid, event.rawX.roundToInt(), event.rawY.roundToInt())
                                    }
                                }
                            }
                            android.view.MotionEvent.ACTION_UP -> {
                                when (gesture) {
                                    0 -> {
                                        val x = event.rawX.toInt()
                                        val y = event.rawY.toInt()
                                        railTargets.firstOrNull { target ->
                                            x >= target.rect.left && x <= target.rect.right &&
                                                y >= target.rect.top && y <= target.rect.bottom
                                        }?.action?.invoke()
                                    }
                                    2 -> touchedGroupId?.let { gid ->
                                        railDragEndHandler(gid, event.rawX.roundToInt(), event.rawY.roundToInt())
                                    }
                                }
                                gesture = 0
                                touchedGroupId = null
                            }
                            android.view.MotionEvent.ACTION_CANCEL -> {
                                if (gesture == 2) {
                                    touchedGroupId?.let { gid ->
                                        railDragEndHandler(gid, -1, -1)
                                    }
                                }
                                gesture = 0
                                touchedGroupId = null
                            }
                        }
                        return true
                    }
                })
            }
            val params = overlayParams(bounds.width, bounds.height, bounds.left, bounds.top, touchable = true)
            runCatching { wm.addView(view, params) }.onSuccess { railOverlay = view }
                .onFailure { Log.w(TAG, "rail overlay failed: ${it.message}") }
        } else {
            layout(railOverlay!!, bounds.left, bounds.top, bounds.width, bounds.height)
        }
    }

    // ------------------------------------------------------------------ window chrome

    private fun addOrUpdate(window: ChromeWindow, maskFlags: BooleanArray = booleanArrayOf(true, true, true, true)) {
        val existing = holders[window.id]
        if (existing != null) {
            existing.rect = window.rect
            existing.workspace = window.workspace
            existing.titleBar.focused = window.focused
            existing.titleBar.title = window.title
            existing.decoration.focused = window.focused
            existing.decoration.maskColor = window.deskTopColor
            existing.decoration.maskColorBottom = window.deskBottomColor
            existing.decoration.maskVisible = maskFlags
            existing.handles.forEach { (_, view) -> view.focused = window.focused }
            position(existing)
            return
        }

        val titleBar = ChromeOverlayView(
            activity,
            ChromeMode.TITLE,
            onTouch = { phase, dx, dy -> handleTouch(window.id, ChromeMode.TITLE, phase, dx, dy) },
            onAction = { action ->
                when (action) {
                    ChromeOverlayView.ACTION_CLOSE -> callbacks.onCloseRequested(window.id)
                    ChromeOverlayView.ACTION_MINIMIZE -> callbacks.onMinimizeRequested(window.id)
                    ChromeOverlayView.ACTION_ZOOM -> toggleZoom(window.id)
                    ChromeOverlayView.ACTION_DOUBLE_TAP -> toggleZoom(window.id)
                    else -> Unit
                }
            }
        ).apply {
            focused = window.focused
            title = window.title
            cornerRadius = this@WindowChromeController.cornerRadius.toFloat()
        }

        val decoration = ChromeOverlayView(
            activity,
            ChromeMode.DECORATION,
            onTouch = { _, _, _ -> }
        ).apply {
            focused = window.focused
            cornerRadius = this@WindowChromeController.cornerRadius.toFloat()
            maskColor = window.deskTopColor
            maskColorBottom = window.deskBottomColor
            maskVisible = maskFlags
        }

        val handleModes = listOf(
            ChromeMode.RESIZE_TOP_LEFT,
            ChromeMode.RESIZE_TOP_RIGHT,
            ChromeMode.RESIZE_BOTTOM_LEFT,
            ChromeMode.RESIZE_CORNER,
            ChromeMode.RESIZE_LEFT,
            ChromeMode.RESIZE_RIGHT,
            ChromeMode.RESIZE_TOP,
            ChromeMode.RESIZE_BOTTOM
        )
        val handles = handleModes.map { mode ->
            mode to ChromeOverlayView(
                activity,
                mode,
                onTouch = { phase, dx, dy -> handleTouch(window.id, mode, phase, dx, dy) }
            ).apply { focused = window.focused; cornerRadius = this@WindowChromeController.cornerRadius.toFloat() }
        }

        val holder = Holder(titleBar, decoration, handles, window.rect, window.workspace)
        holders[window.id] = holder

        runCatching { wm.addView(decoration, overlayParams(1, 1, 0, 0, touchable = false)) }
            .onFailure { Log.w(TAG, "decoration add failed: ${it.message}") }
        runCatching { wm.addView(titleBar, overlayParams(1, 1, 0, 0, touchable = true)) }
            .onFailure { Log.w(TAG, "title add failed: ${it.message}") }
        handles.forEach { (_, view) ->
            runCatching { wm.addView(view, overlayParams(1, 1, 0, 0, touchable = true)) }
                .onFailure { Log.w(TAG, "handle add failed: ${it.message}") }
        }
        position(holder)
    }

    private fun remove(id: String) {
        val holder = holders.remove(id) ?: return
        val views = ArrayList<View>(2 + holder.handles.size)
        views += holder.titleBar
        views += holder.decoration
        holder.handles.forEach { views += it.second }
        views.forEach { view -> runCatching { wm.removeView(view) } }
    }

    private fun position(holder: Holder) {
        val rect = holder.rect
        layout(holder.decoration, rect.left, rect.top, rect.width, rect.height)
        layout(holder.titleBar, rect.left, rect.top, rect.width, titleHeight)

        val half = gripSize / 2
        layout(holder.handle(ChromeMode.RESIZE_TOP_LEFT), rect.left - half, rect.top - half, gripSize, gripSize)
        layout(holder.handle(ChromeMode.RESIZE_TOP_RIGHT), rect.right - half, rect.top - half, gripSize, gripSize)
        layout(holder.handle(ChromeMode.RESIZE_BOTTOM_LEFT), rect.left - half, rect.bottom - half, gripSize, gripSize)
        layout(holder.handle(ChromeMode.RESIZE_CORNER), rect.right - half, rect.bottom - half, gripSize, gripSize)

        layout(
            holder.handle(ChromeMode.RESIZE_LEFT),
            rect.left - edgeSize / 2,
            rect.top + half,
            edgeSize,
            (rect.height - gripSize).coerceAtLeast(edgeSize)
        )
        layout(
            holder.handle(ChromeMode.RESIZE_RIGHT),
            rect.right - edgeSize / 2,
            rect.top + half,
            edgeSize,
            (rect.height - gripSize).coerceAtLeast(edgeSize)
        )
        layout(
            holder.handle(ChromeMode.RESIZE_TOP),
            rect.left + half,
            rect.top - edgeSize / 2,
            (rect.width - gripSize).coerceAtLeast(edgeSize),
            edgeSize
        )
        layout(
            holder.handle(ChromeMode.RESIZE_BOTTOM),
            rect.left + half,
            rect.bottom - edgeSize / 2,
            (rect.width - gripSize).coerceAtLeast(edgeSize),
            edgeSize
        )
    }

    private fun Holder.handle(mode: ChromeMode): ChromeOverlayView =
        handles.first { it.first == mode }.second

    private fun layout(view: View, x: Int, y: Int, width: Int, height: Int) {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = x
        params.y = y
        params.width = max(1, width)
        params.height = max(1, height)
        runCatching { wm.updateViewLayout(view, params) }
    }

    // ------------------------------------------------------------------ gestures

    private fun handleTouch(id: String, mode: ChromeMode, phase: Int, dx: Float, dy: Float) {
        val holder = holders[id] ?: return
        when (phase) {
            ChromeOverlayView.PHASE_START -> {
                holder.gestureBase = holder.rect
                holder.touchStart = holder.titleBar.lastRawPos
                holder.touchLast = holder.touchStart
                callbacks.onFocusRequested(id)
            }
            ChromeOverlayView.PHASE_MOVE -> {
                val base = holder.gestureBase
                val left: Int
                val top: Int
                val right: Int
                val bottom: Int
                when (mode) {
                    ChromeMode.TITLE -> {
                        left = base.left + dx.roundToInt()
                        top = base.top + dy.roundToInt()
                        right = base.right + dx.roundToInt()
                        bottom = base.bottom + dy.roundToInt()
                    }
                    ChromeMode.RESIZE_LEFT, ChromeMode.RESIZE_TOP_LEFT, ChromeMode.RESIZE_BOTTOM_LEFT -> {
                        left = min(base.right - minWidth, base.left + dx.roundToInt())
                        right = base.right
                        top = if (mode == ChromeMode.RESIZE_TOP_LEFT) {
                            min(base.bottom - minHeight, base.top + dy.roundToInt())
                        } else base.top
                        bottom = if (mode == ChromeMode.RESIZE_BOTTOM_LEFT) {
                            max(base.top + minHeight, base.bottom + dy.roundToInt())
                        } else base.bottom
                    }
                    ChromeMode.RESIZE_RIGHT, ChromeMode.RESIZE_TOP_RIGHT, ChromeMode.RESIZE_CORNER -> {
                        right = max(base.left + minWidth, base.right + dx.roundToInt())
                        left = base.left
                        top = if (mode == ChromeMode.RESIZE_TOP_RIGHT) {
                            min(base.bottom - minHeight, base.top + dy.roundToInt())
                        } else base.top
                        bottom = if (mode == ChromeMode.RESIZE_CORNER) {
                            max(base.top + minHeight, base.bottom + dy.roundToInt())
                        } else base.bottom
                    }
                    ChromeMode.RESIZE_TOP -> {
                        left = base.left
                        right = base.right
                        top = min(base.bottom - minHeight, base.top + dy.roundToInt())
                        bottom = base.bottom
                    }
                    ChromeMode.RESIZE_BOTTOM -> {
                        left = base.left
                        right = base.right
                        top = base.top
                        bottom = max(base.top + minHeight, base.bottom + dy.roundToInt())
                    }
                    else -> return
                }
                val clamped = clampToScreen(StageRect(left, top, right, bottom))
                holder.rect = clamped
                holder.restoredRect = null
                position(holder)
                callbacks.onGeometryChanged(id, clamped, false)
                if (mode == ChromeMode.TITLE) {
                    holder.touchLast = holder.titleBar.lastRawPos
                    callbacks.onWindowDragging(id, holder.touchLast.first, holder.touchLast.second)
                }
            }
            ChromeOverlayView.PHASE_END -> {
                holder.touchLast = holder.titleBar.lastRawPos
                callbacks.onGeometryChanged(id, holder.rect, true)
                if (mode == ChromeMode.TITLE) {
                    callbacks.onWindowDropped(id, holder.touchLast.first, holder.touchLast.second)
                }
            }
        }
    }

    private fun clampToScreen(rect: StageRect): StageRect {
        val metrics = activity.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val w = min(rect.width, width - px(8f))
        val h = min(rect.height, height - px(8f))
        val left = rect.left.coerceIn(px(4f), width - w - px(4f))
        val top = rect.top.coerceIn(px(4f), height - h - px(4f))
        return StageRect(left, top, left + w, top + h)
    }

    private fun overlayParams(
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        touchable: Boolean
    ): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!touchable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    fun destroy() {
        holders.keys.toList().forEach { remove(it) }
        edgeStrip?.let { runCatching { wm.removeView(it) } }
        edgeStrip = null
        railOverlay?.let { runCatching { wm.removeView(it) } }
        railOverlay = null
        dropHighlightView?.let { runCatching { wm.removeView(it) } }
        dropHighlightView = null
    }

    private companion object {
        const val TAG = "WindowChrome"
    }
}

/**
 * Non-touchable translucent highlight rendered above real task windows during drag-and-drop so
 * the user always sees the active drop zone and action label.
 */
@SuppressLint("ViewConstructor")
private class DropHighlightOverlayView(context: android.content.Context) : View(context) {

    var label: String = ""
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density

    private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(46, 122, 162, 255)
        style = android.graphics.Paint.Style.FILL
    }
    private val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(235, 138, 174, 255)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val pillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(228, 20, 26, 38)
        style = android.graphics.Paint.Style.FILL
    }
    private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(245, 240, 245, 255)
        textSize = 13f * density
        isFakeBoldText = true
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = 22f * density
        val inset = 2f * density
        val box = android.graphics.RectF(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(box, r, r, fillPaint)
        canvas.drawRoundRect(box, r, r, strokePaint)

        if (label.isNotEmpty()) {
            val textW = textPaint.measureText(label)
            val padH = 14f * density
            val pillH = 30f * density
            val pillW = textW + padH * 2
            val cx = width / 2f
            val cy = (height / 2f).coerceIn(pillH, (height - pillH).coerceAtLeast(pillH))
            val pill = android.graphics.RectF(cx - pillW / 2f, cy - pillH / 2f, cx + pillW / 2f, cy + pillH / 2f)
            canvas.drawRoundRect(pill, pillH / 2f, pillH / 2f, pillPaint)
            canvas.drawRoundRect(pill, pillH / 2f, pillH / 2f, strokePaint)
            val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, cx - textW / 2f, textY, textPaint)
        }
    }
}
