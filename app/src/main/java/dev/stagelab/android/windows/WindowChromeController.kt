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
        fun onWindowMovedLive(id: String, rect: StageRect)
        fun onGeometryChanged(id: String, rect: StageRect, final: Boolean, resized: Boolean = false)
        fun onFullScreenToggleRequested(id: String)
        fun onGestureStateChanged(id: String, active: Boolean)
        fun onWindowDragging(id: String, x: Int, y: Int)
        fun onWindowDropped(id: String, x: Int, y: Int)
        fun onWindowDragCancelled(id: String)
        fun onFocusRequested(id: String)
        fun onCloseRequested(id: String)
        fun onMinimizeRequested(id: String)
        fun onZoomToggled(id: String, maximized: Boolean)
        fun onWindowMenuRequested(id: String, anchorX: Int, anchorY: Int)
        fun onLeftEdgeSwipe()
        fun onBottomEdgeSwipeUp()
        fun onBottomSwipeUpHold()
        fun onBottomHorizontalSwipe(direction: Int)
        fun onGlobalMenuBarClick(anchorX: Int, anchorY: Int)
        fun onChromeTapOutside()
    }

    /** One item in the floating window control menu. */
    data class MenuItem(
        val label: String,
        val danger: Boolean = false,
        val onClick: () -> Unit
    )

    data class ChromeWindow(
        val id: String,
        val rect: StageRect,
        val title: String,
        val focused: Boolean,
        val minimized: Boolean,
        val workspace: StageRect = rect,
        val fullScreenBounds: StageRect = rect
    )

    private class Holder(
        val titleBar: ChromeOverlayView,
        val bottomRight: ChromeOverlayView,
        val bottomLeft: ChromeOverlayView,
        var rect: StageRect,
        var workspace: StageRect,
        var fullScreenBounds: StageRect
    ) {
        var gestureBase: StageRect = rect
        var restoredRect: StageRect? = null
        var inGesture: Boolean = false
        var lastCommitMs: Long = 0L
        var lastSnapId: String = ""
        var lastSnapRect: StageRect? = null
        var lastSnapFullScreen: Boolean = false
        var touchStart: Pair<Int, Int> = 0 to 0
        var touchLast: Pair<Int, Int> = 0 to 0
    }

    private val wm: WindowManager = activity.getSystemService(WindowManager::class.java)
    private val holders = LinkedHashMap<String, Holder>()
    private var edgeStrip: View? = null
    private var bottomEdgeStrip: View? = null
    private var menuBarPill: View? = null
    private var railOverlay: View? = null
    private var dropHighlightView: DropHighlightOverlayView? = null
    private var resizePreviewView: ResizePreviewOverlayView? = null
    private var menuScrimView: View? = null
    private var menuPopupView: View? = null

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

    private val titleHeight = px(28f)
    private val gripSize = px(38f)
    private val edgeSize = px(14f)
    private val minWidth = px(232f)
    private val minHeight = px(200f)
    private val cornerRadius = px(15f)

    // ------------------------------------------------------------------ public API

    fun update(windows: List<ChromeWindow>, visible: Boolean) {
        if (!visible) {
            holders.keys.toList().forEach { remove(it) }
            return
        }
        val ids = windows.map { it.id }.toSet()
        holders.keys.filter { it !in ids }.forEach { remove(it) }
        windows.forEachIndexed { index, window ->
            val above = windows.drop(index + 1)
            val pillCenterX = (window.rect.left + window.rect.right) / 2
            val pillBox = StageRect(
                pillCenterX - px(24f),
                window.rect.top,
                pillCenterX + px(24f),
                window.rect.top + titleHeight
            )
            val showPill = above.none { candidate -> intersects(pillBox, candidate.rect) }
            addOrUpdate(window, showPill)
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
     * Centered bottom-edge gesture strip (P5/P7):
     *  - Quick swipe up -> reveal auto-hidden Dock;
     *  - Swipe up & pause (or tall upward swipe) -> Stage Overview;
     *  - Horizontal swipe left/right -> switch adjacent workspace.
     *
     * Kept narrow (36% of screen width, 20dp tall) so it does not interfere with Android system
     * Back/Home edges or IME.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun updateBottomEdgeStrip(enabled: Boolean, bottomInsetPx: Int = 0) {
        if (!enabled) {
            bottomEdgeStrip?.let { runCatching { wm.removeView(it) } }
            bottomEdgeStrip = null
            return
        }
        val metrics = activity.resources.displayMetrics
        val stripW = (metrics.widthPixels * 0.36f).roundToInt().coerceAtLeast(px(180f))
        val stripH = px(22f)
        val x = (metrics.widthPixels - stripW) / 2
        // Sit just above the OS navigation gesture zone so ColorOS Home/Dock does not steal swipes.
        val safeInset = max(bottomInsetPx, px(24f))
        val y = metrics.heightPixels - safeInset - stripH
        val existing = bottomEdgeStrip
        if (existing != null) {
            layout(existing, x, y, stripW, stripH)
            return
        }
        val strip = object : View(activity) {
            private val pillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(90, 234, 240, 250)
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                val pillW = px(56f).toFloat()
                val pillH = px(3.5f).toFloat()
                val left = (width - pillW) / 2f
                val top = height - pillH - px(3f)
                canvas.drawRoundRect(left, top, left + pillW, top + pillH, pillH / 2f, pillH / 2f, pillPaint)
            }
        }.apply {
            setBackgroundColor(0x00000000)
            setOnTouchListener(object : android.view.View.OnTouchListener {
                private var startX = 0f
                private var startY = 0f
                private var downTime = 0L
                private var holdTriggered = false
                private val holdRunnable = Runnable {
                    holdTriggered = true
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    callbacks.onBottomSwipeUpHold()
                }

                override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            startX = event.rawX
                            startY = event.rawY
                            downTime = System.currentTimeMillis()
                            holdTriggered = false
                            v.removeCallbacks(holdRunnable)
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val upDy = startY - event.rawY
                            val absDx = kotlin.math.abs(event.rawX - startX)
                            if (!holdTriggered && upDy > px(40f) && upDy > absDx * 1.3f) {
                                v.removeCallbacks(holdRunnable)
                                v.postDelayed(holdRunnable, 240L)
                            } else {
                                v.removeCallbacks(holdRunnable)
                            }
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            v.removeCallbacks(holdRunnable)
                            if (holdTriggered) {
                                holdTriggered = false
                                return true
                            }
                            val dx = event.rawX - startX
                            val upDy = startY - event.rawY
                            val elapsed = System.currentTimeMillis() - downTime
                            when {
                                kotlin.math.abs(dx) > px(48f) && kotlin.math.abs(dx) > kotlin.math.abs(upDy) * 1.3f -> {
                                    callbacks.onBottomHorizontalSwipe(if (dx < 0) 1 else -1)
                                }
                                upDy > px(96f) || (upDy > px(40f) && elapsed >= 340L) -> {
                                    callbacks.onBottomSwipeUpHold()
                                }
                                upDy > px(18f) -> {
                                    callbacks.onBottomEdgeSwipeUp()
                                }
                            }
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            v.removeCallbacks(holdRunnable)
                            holdTriggered = false
                        }
                    }
                    return true
                }
            })
        }
        val params = overlayParams(stripW, stripH, x, y, touchable = true)
        runCatching { wm.addView(strip, params) }.onSuccess { bottomEdgeStrip = strip }
            .onFailure { Log.w(TAG, "bottom edge strip failed: ${it.message}") }
    }

    /**
     * Subtle iPad-style top-center Window menu trigger pill (P9).
     * Tapping opens StageLab's global Window menu (arrangement presets, overview, external display info).
     */
    fun updateMenuBarPill(enabled: Boolean, statusInsetPx: Int = 0) {
        if (!enabled) {
            menuBarPill?.let { runCatching { wm.removeView(it) } }
            menuBarPill = null
            return
        }
        val metrics = activity.resources.displayMetrics
        val pillW = px(54f)
        val pillH = px(18f)
        val x = (metrics.widthPixels - pillW) / 2
        val y = px(4f)
        val existing = menuBarPill
        if (existing != null) {
            layout(existing, x, y, pillW, pillH)
            return
        }
        val pill = android.widget.TextView(activity).apply {
            text = "•••"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.argb(185, 234, 240, 250))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(120, 24, 30, 42))
                cornerRadius = px(11f).toFloat()
                setStroke(max(1, px(0.8f)), android.graphics.Color.argb(38, 255, 255, 255))
            }
            setOnClickListener {
                callbacks.onGlobalMenuBarClick(x + pillW / 2 - px(114f), y + pillH + px(8f))
            }
        }
        val params = overlayParams(pillW, pillH, x, y, touchable = true)
        runCatching { wm.addView(pill, params) }.onSuccess { menuBarPill = pill }
            .onFailure { Log.w(TAG, "menu bar pill failed: ${it.message}") }
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

    // ------------------------------------------------------------------ window chrome (3 lightweight overlays / window)

    private fun addOrUpdate(window: ChromeWindow, pillVisible: Boolean = true) {
        val existing = holders[window.id]
        if (existing != null) {
            existing.workspace = window.workspace
            existing.fullScreenBounds = window.fullScreenBounds
            existing.titleBar.focused = window.focused
            existing.titleBar.pillVisible = pillVisible
            existing.titleBar.title = window.title
            existing.bottomRight.focused = window.focused
            existing.bottomLeft.focused = window.focused
            if (!existing.inGesture) {
                existing.rect = window.rect
                position(existing)
            }
            return
        }

        val titleBar = ChromeOverlayView(
            activity,
            ChromeMode.TITLE,
            onTouch = { phase, dx, dy -> handleTouch(window.id, ChromeMode.TITLE, phase, dx, dy) },
            onAction = { action ->
                when (action) {
                    ChromeOverlayView.ACTION_DOUBLE_TAP -> callbacks.onFullScreenToggleRequested(window.id)
                    ChromeOverlayView.ACTION_MENU_TAP -> {
                        val h = holders[window.id]
                        val r = h?.rect ?: window.rect
                        val anchorX = (r.left + r.right) / 2 - px(114f)
                        val anchorY = r.top + titleHeight + px(4f)
                        callbacks.onWindowMenuRequested(window.id, anchorX, anchorY)
                    }
                    ChromeOverlayView.ACTION_LONG_PRESS -> {
                        val pos = holders[window.id]?.titleBar?.lastRawPos
                            ?: (window.rect.left + px(48f) to window.rect.top + titleHeight)
                        callbacks.onWindowMenuRequested(window.id, pos.first, pos.second)
                    }
                    else -> Unit
                }
            }
        ).apply {
            focused = window.focused
            this.pillVisible = pillVisible
            title = window.title
        }

        val bottomRight = ChromeOverlayView(
            activity,
            ChromeMode.RESIZE_CORNER,
            onTouch = { phase, dx, dy -> handleTouch(window.id, ChromeMode.RESIZE_CORNER, phase, dx, dy) }
        ).apply { focused = window.focused }

        val bottomLeft = ChromeOverlayView(
            activity,
            ChromeMode.RESIZE_BOTTOM_LEFT,
            onTouch = { phase, dx, dy -> handleTouch(window.id, ChromeMode.RESIZE_BOTTOM_LEFT, phase, dx, dy) }
        ).apply { focused = window.focused }

        val holder = Holder(titleBar, bottomRight, bottomLeft, window.rect, window.workspace, window.fullScreenBounds)
        holders[window.id] = holder

        runCatching { wm.addView(titleBar, overlayParams(1, 1, 0, 0, touchable = true)) }
            .onFailure { Log.w(TAG, "title add failed: ${it.message}") }
        runCatching { wm.addView(bottomRight, overlayParams(1, 1, 0, 0, touchable = true)) }
            .onFailure { Log.w(TAG, "br handle add failed: ${it.message}") }
        runCatching { wm.addView(bottomLeft, overlayParams(1, 1, 0, 0, touchable = true)) }
            .onFailure { Log.w(TAG, "bl handle add failed: ${it.message}") }
        position(holder)
    }

    private fun remove(id: String) {
        val holder = holders.remove(id) ?: return
        runCatching { wm.removeView(holder.titleBar) }
        runCatching { wm.removeView(holder.bottomRight) }
        runCatching { wm.removeView(holder.bottomLeft) }
    }

    private fun position(holder: Holder) {
        val rect = holder.rect
        layout(holder.titleBar, rect.left, rect.top, rect.width, titleHeight)
        val half = gripSize / 2
        layout(holder.bottomLeft, rect.left - half, rect.bottom - half, gripSize, gripSize)
        layout(holder.bottomRight, rect.right - half, rect.bottom - half, gripSize, gripSize)
    }

    private fun ensureResizePreview(): ResizePreviewOverlayView? {
        resizePreviewView?.let { return it }
        val metrics = activity.resources.displayMetrics
        val view = ResizePreviewOverlayView(activity).apply { visibility = View.GONE }
        val params = overlayParams(metrics.widthPixels, metrics.heightPixels, 0, 0, touchable = false)
        return runCatching { wm.addView(view, params) }.map {
            resizePreviewView = view
            view
        }.getOrNull()
    }

    private fun showResizePreview(rect: StageRect?, label: String = "") {
        val view = if (rect != null) ensureResizePreview() else resizePreviewView
        view ?: return
        if (rect == null) {
            view.visibility = View.GONE
            view.previewRect = null
        } else {
            view.previewRect = rect
            view.previewLabel = label
            view.visibility = View.VISIBLE
            view.invalidate()
        }
    }

    private fun layout(view: View, x: Int, y: Int, width: Int, height: Int) {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val w = max(1, width)
        val h = max(1, height)
        if (params.x == x && params.y == y && params.width == w && params.height == h) return
        params.x = x
        params.y = y
        params.width = w
        params.height = h
        runCatching { wm.updateViewLayout(view, params) }
    }

    // ------------------------------------------------------------------ gestures (120fps drag + preset-grid snap resize)

    private fun handleTouch(id: String, mode: ChromeMode, phase: Int, dx: Float, dy: Float) {
        val holder = holders[id] ?: return
        when (phase) {
            ChromeOverlayView.PHASE_START -> {
                holder.inGesture = true
                holder.gestureBase = holder.rect
                holder.lastSnapId = ""
                holder.lastSnapRect = null
                holder.lastSnapFullScreen = false
                holder.touchStart = holder.titleBar.lastRawPos
                holder.touchLast = holder.touchStart
                callbacks.onGestureStateChanged(id, true)
                callbacks.onFocusRequested(id)
            }
            ChromeOverlayView.PHASE_MOVE -> {
                val base = holder.gestureBase
                if (mode == ChromeMode.TITLE) {
                    // Pure translation: 120fps overlay + direct 60Hz live task offset (zero Compose recomposition!).
                    val moved = clampToScreen(
                        StageRect(
                            base.left + dx.roundToInt(),
                            base.top + dy.roundToInt(),
                            base.right + dx.roundToInt(),
                            base.bottom + dy.roundToInt()
                        )
                    )
                    holder.rect = moved
                    holder.restoredRect = null
                    position(holder)
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - holder.lastCommitMs >= 16L) {
                        holder.lastCommitMs = nowMs
                        callbacks.onWindowMovedLive(id, moved)
                    }
                    holder.touchLast = holder.titleBar.lastRawPos
                    callbacks.onWindowDragging(id, holder.touchLast.first, holder.touchLast.second)
                } else {
                    // Preset-tier / grid-snap resize: zero app relayout during drag, 120fps snap preview + haptic tick!
                    val anchorRight = mode == ChromeMode.RESIZE_BOTTOM_LEFT
                    val rawRect = if (anchorRight) {
                        val left = min(base.right - minWidth, base.left + dx.roundToInt())
                        val bottom = max(base.top + minHeight, base.bottom + dy.roundToInt())
                        StageRect(left, base.top, base.right, bottom)
                    } else {
                        val right = max(base.left + minWidth, base.right + dx.roundToInt())
                        val bottom = max(base.top + minHeight, base.bottom + dy.roundToInt())
                        StageRect(base.left, base.top, right, bottom)
                    }
                    val snapped = dev.stagelab.android.stage.StageSnapGrid.snap(
                        rawRect = rawRect,
                        anchorRight = anchorRight,
                        workspace = holder.workspace,
                        fullScreenBounds = holder.fullScreenBounds,
                        minWidthPx = minWidth,
                        minHeightPx = minHeight
                    )
                    if (snapped.preset.id != holder.lastSnapId) {
                        holder.lastSnapId = snapped.preset.id
                        val handleView = if (anchorRight) holder.bottomLeft else holder.bottomRight
                        handleView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    }
                    holder.lastSnapRect = snapped.rect
                    holder.lastSnapFullScreen = snapped.preset.fullScreen
                    showResizePreview(
                        snapped.rect,
                        if (snapped.preset.fullScreen) "全屏幕" else ""
                    )
                }
            }
            ChromeOverlayView.PHASE_END -> {
                holder.inGesture = false
                holder.lastCommitMs = 0L
                showResizePreview(null)
                if (mode == ChromeMode.TITLE) {
                    position(holder)
                    holder.touchLast = holder.titleBar.lastRawPos
                    callbacks.onGestureStateChanged(id, false)
                    callbacks.onGeometryChanged(id, holder.rect, true, resized = false)
                    callbacks.onWindowDropped(id, holder.touchLast.first, holder.touchLast.second)
                } else {
                    val target = holder.lastSnapRect
                    val goFull = holder.lastSnapFullScreen
                    holder.lastSnapRect = null
                    holder.lastSnapFullScreen = false
                    callbacks.onGestureStateChanged(id, false)
                    if (goFull) {
                        callbacks.onFullScreenToggleRequested(id)
                    } else if (target != null) {
                        holder.rect = target
                        holder.restoredRect = null
                        position(holder)
                        callbacks.onGeometryChanged(id, target, true, resized = true)
                    } else {
                        position(holder)
                    }
                }
            }
            ChromeOverlayView.PHASE_CANCEL -> {
                holder.inGesture = false
                holder.lastCommitMs = 0L
                holder.lastSnapRect = null
                holder.lastSnapFullScreen = false
                showResizePreview(null)
                holder.rect = holder.gestureBase
                position(holder)
                callbacks.onGestureStateChanged(id, false)
                if (mode == ChromeMode.TITLE) {
                    callbacks.onGeometryChanged(id, holder.gestureBase, true, resized = false)
                }
                callbacks.onWindowDragCancelled(id)
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

    /**
     * Shows a floating iPad-style window control menu above all real tasks (P4).
     * Tapping anywhere outside dismisses it without disturbing the underlying windows.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showWindowMenu(title: String, anchorX: Int, anchorY: Int, items: List<MenuItem>) {
        dismissWindowMenu()
        if (items.isEmpty()) return

        val metrics = activity.resources.displayMetrics
        val scrim = View(activity).apply {
            setBackgroundColor(0x01000000)
            setOnClickListener { dismissWindowMenu() }
        }
        val scrimParams = overlayParams(
            metrics.widthPixels,
            metrics.heightPixels,
            0,
            0,
            touchable = true
        )

        val menuWidth = px(228f)
        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(244, 24, 29, 39))
                cornerRadius = px(18f).toFloat()
                setStroke(px(1f), android.graphics.Color.argb(46, 255, 255, 255))
            }
            background = bg
            elevation = px(18f).toFloat()
            setPadding(px(8f), px(10f), px(8f), px(10f))
        }

        if (title.isNotBlank()) {
            val header = android.widget.TextView(activity).apply {
                text = title
                setTextColor(android.graphics.Color.argb(175, 196, 206, 224))
                textSize = 12f
                setPadding(px(12f), px(4f), px(12f), px(8f))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            container.addView(header)
        }

        items.forEachIndexed { idx, item ->
            val row = android.widget.TextView(activity).apply {
                text = item.label
                textSize = 14f
                setTextColor(
                    if (item.danger) android.graphics.Color.rgb(255, 118, 112)
                    else android.graphics.Color.rgb(238, 243, 252)
                )
                setPadding(px(12f), px(10f), px(12f), px(10f))
                val rowBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    cornerRadius = px(10f).toFloat()
                }
                background = rowBg
                setOnClickListener {
                    dismissWindowMenu()
                    item.onClick()
                }
            }
            container.addView(
                row,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            if (idx < items.lastIndex) {
                val divider = View(activity).apply {
                    setBackgroundColor(android.graphics.Color.argb(24, 255, 255, 255))
                }
                container.addView(
                    divider,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        max(1, px(0.8f))
                    )
                )
            }
        }

        val estHeight = px(44f) * items.size + px(48f)
        val x = anchorX.coerceIn(px(12f), (metrics.widthPixels - menuWidth - px(12f)).coerceAtLeast(px(12f)))
        val y = anchorY.coerceIn(px(12f), (metrics.heightPixels - estHeight - px(12f)).coerceAtLeast(px(12f)))
        val popupParams = overlayParams(
            menuWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            x,
            y,
            touchable = true
        )

        runCatching { wm.addView(scrim, scrimParams) }.onSuccess { menuScrimView = scrim }
        runCatching { wm.addView(container, popupParams) }.onSuccess { menuPopupView = container }
            .onFailure {
                dismissWindowMenu()
                Log.w(TAG, "window menu overlay failed: ${it.message}")
            }
    }

    fun dismissWindowMenu() {
        menuPopupView?.let { runCatching { wm.removeView(it) } }
        menuPopupView = null
        menuScrimView?.let { runCatching { wm.removeView(it) } }
        menuScrimView = null
    }

    fun destroy() {
        dismissWindowMenu()
        holders.keys.toList().forEach { remove(it) }
        edgeStrip?.let { runCatching { wm.removeView(it) } }
        edgeStrip = null
        bottomEdgeStrip?.let { runCatching { wm.removeView(it) } }
        bottomEdgeStrip = null
        menuBarPill?.let { runCatching { wm.removeView(it) } }
        menuBarPill = null
        railOverlay?.let { runCatching { wm.removeView(it) } }
        railOverlay = null
        dropHighlightView?.let { runCatching { wm.removeView(it) } }
        dropHighlightView = null
        resizePreviewView?.let { runCatching { wm.removeView(it) } }
        resizePreviewView = null
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

/**
 * Translucent iPadOS-style snap-grid resize preview rendered at 120fps while dragging a corner
 * resize handle, preventing choppy per-pixel Android app relayouts.
 */
@SuppressLint("ViewConstructor")
private class ResizePreviewOverlayView(context: android.content.Context) : View(context) {

    var previewRect: StageRect? = null
    var previewLabel: String = ""

    private val density = resources.displayMetrics.density

    private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(38, 255, 255, 255)
        style = android.graphics.Paint.Style.FILL
    }
    private val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(215, 232, 240, 255)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val pillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(215, 20, 26, 38)
        style = android.graphics.Paint.Style.FILL
    }
    private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(245, 242, 246, 255)
        textSize = 13f * density
        isFakeBoldText = true
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val rect = previewRect ?: return
        val r = 16f * density
        val box = android.graphics.RectF(
            rect.left.toFloat(),
            rect.top.toFloat(),
            rect.right.toFloat(),
            rect.bottom.toFloat()
        )
        canvas.drawRoundRect(box, r, r, fillPaint)
        canvas.drawRoundRect(box, r, r, strokePaint)

        if (previewLabel.isNotEmpty()) {
            val textW = textPaint.measureText(previewLabel)
            val padH = 14f * density
            val pillH = 28f * density
            val pillW = textW + padH * 2
            val cx = box.centerX()
            val cy = box.top + 38f * density
            val pill = android.graphics.RectF(cx - pillW / 2f, cy - pillH / 2f, cx + pillW / 2f, cy + pillH / 2f)
            canvas.drawRoundRect(pill, pillH / 2f, pillH / 2f, pillPaint)
            canvas.drawRoundRect(pill, pillH / 2f, pillH / 2f, strokePaint)
            val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(previewLabel, cx - textW / 2f, textY, textPaint)
        }
    }
}
