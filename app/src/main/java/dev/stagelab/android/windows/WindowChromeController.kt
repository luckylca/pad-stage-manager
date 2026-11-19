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
 * Owns the floating chrome (title bar, resize grips, left edge strip) that lives *above* the real
 * application tasks.
 *
 * Why overlays: the stage host window sits behind the tasks, so it can never receive a touch that
 * lands on an application window. `TYPE_APPLICATION_OVERLAY` windows are composited above the tasks
 * and can be limited to a few dozen dp, which gives us a real drag handle while the application
 * keeps 100% native input everywhere else.
 */
class WindowChromeController(
    private val activity: Activity,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onGeometryChanged(id: String, rect: StageRect, final: Boolean)
        fun onFocusRequested(id: String)
        fun onCloseRequested(id: String)
        fun onMinimizeRequested(id: String)
        fun onLeftEdgeSwipe()
        fun onChromeTapOutside()
    }

    data class ChromeWindow(
        val id: String,
        val rect: StageRect,
        val title: String,
        val focused: Boolean,
        val minimized: Boolean
    )

    private data class Holder(
        val titleBar: ChromeOverlayView,
        val corner: ChromeOverlayView,
        val right: ChromeOverlayView,
        val bottom: ChromeOverlayView,
        var rect: StageRect,
        var gestureBase: StageRect = rect
    )

    private val wm: WindowManager = activity.getSystemService(WindowManager::class.java)
    private val holders = LinkedHashMap<String, Holder>()
    private var edgeStrip: View? = null
    private var railOverlay: View? = null

    /** A tappable region above the parked rail windows. */
    data class TapTarget(val rect: StageRect, val action: () -> Unit)

    private var railTargets: List<TapTarget> = emptyList()

    private val dp: Float get() = activity.resources.displayMetrics.density
    private fun px(value: Float): Int = (value * dp).roundToInt()

    private val titleHeight = px(32f)
    private val gripSize = px(26f)
    private val edgeSize = px(12f)
    private val minWidth = px(232f)
    private val minHeight = px(200f)

    fun update(windows: List<ChromeWindow>, visible: Boolean) {
        if (!visible) {
            holders.keys.toList().forEach { remove(it) }
            return
        }
        val ids = windows.map { it.id }.toSet()
        holders.keys.filter { it !in ids }.forEach { remove(it) }
        windows.forEach { window -> addOrUpdate(window) }
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
            y = 0
        )
        runCatching { wm.addView(strip, params) }.onSuccess { edgeStrip = strip }
            .onFailure { Log.w(TAG, "edge strip failed: ${it.message}") }
    }

    /**
     * The parked group previews are real windows above the stage host, so the rail's own tap
     * targets only work through an overlay that sits on top of them.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun updateRailTargets(targets: List<TapTarget>, bounds: StageRect?, visible: Boolean) {
        railTargets = targets
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
                    override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                        if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                            val x = event.rawX.toInt()
                            val y = event.rawY.toInt()
                            railTargets.firstOrNull { target ->
                                x >= target.rect.left && x <= target.rect.right &&
                                    y >= target.rect.top && y <= target.rect.bottom
                            }?.action?.invoke()
                        }
                        return true
                    }
                })
            }
            val params = overlayParams(bounds.width, bounds.height, bounds.left, bounds.top)
            runCatching { wm.addView(view, params) }.onSuccess { railOverlay = view }
                .onFailure { Log.w(TAG, "rail overlay failed: ${it.message}") }
        } else {
            layout(railOverlay!!, bounds.left, bounds.top, bounds.width, bounds.height)
        }
    }

    private fun addOrUpdate(window: ChromeWindow) {
        val existing = holders[window.id]
        if (existing != null) {
            existing.rect = window.rect
            existing.titleBar.focused = window.focused
            existing.titleBar.title = window.title
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
                }
            }
        ).apply {
            focused = window.focused
            title = window.title
        }
        val corner = ChromeOverlayView(
            activity,
            ChromeMode.RESIZE_CORNER,
            onTouch = { phase, dx, dy -> handleTouch(window.id, ChromeMode.RESIZE_CORNER, phase, dx, dy) }
        )
        val right = ChromeOverlayView(
            activity,
            ChromeMode.RESIZE_RIGHT,
            onTouch = { phase, dx, dy -> handleTouch(window.id, ChromeMode.RESIZE_RIGHT, phase, dx, dy) }
        )
        val bottom = ChromeOverlayView(
            activity,
            ChromeMode.RESIZE_BOTTOM,
            onTouch = { phase, dx, dy -> handleTouch(window.id, ChromeMode.RESIZE_BOTTOM, phase, dx, dy) }
        )
        val holder = Holder(titleBar, corner, right, bottom, window.rect)
        holders[window.id] = holder
        listOf(titleBar, corner, right, bottom).forEach { view ->
            runCatching { wm.addView(view, overlayParams(1, 1, 0, 0)) }
                .onFailure { Log.w(TAG, "overlay add failed: ${it.message}") }
        }
        position(holder)
    }

    private fun remove(id: String) {
        val holder = holders.remove(id) ?: return
        listOf(holder.titleBar, holder.corner, holder.right, holder.bottom).forEach { view ->
            runCatching { wm.removeView(view) }
        }
    }

    private fun position(holder: Holder) {
        val rect = holder.rect
        layout(holder.titleBar, rect.left, rect.top, rect.width, titleHeight)
        // The corner grip owns the bottom-right corner exclusively: the edge strips stop short of
        // it, otherwise they would swallow the diagonal resize gesture.
        layout(holder.corner, rect.right - gripSize / 2, rect.bottom - gripSize / 2, gripSize, gripSize)
        layout(
            holder.right,
            rect.right - edgeSize / 2,
            rect.top + gripSize / 2,
            edgeSize,
            (rect.height - gripSize).coerceAtLeast(edgeSize)
        )
        layout(
            holder.bottom,
            rect.left + gripSize / 2,
            rect.bottom - edgeSize / 2,
            (rect.width - gripSize).coerceAtLeast(edgeSize),
            edgeSize
        )
    }

    private fun layout(view: View, x: Int, y: Int, width: Int, height: Int) {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = x
        params.y = y
        params.width = max(1, width)
        params.height = max(1, height)
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun handleTouch(id: String, mode: ChromeMode, phase: Int, dx: Float, dy: Float) {
        val holder = holders[id] ?: return
        if (phase == ChromeOverlayView.PHASE_START) {
            Log.d(TAG, "touch $mode on $id rect=${holder.rect}")
        }
        when (phase) {
            ChromeOverlayView.PHASE_START -> {
                holder.gestureBase = holder.rect
                callbacks.onFocusRequested(id)
            }
            ChromeOverlayView.PHASE_MOVE -> {
                val base = holder.gestureBase
                val newRect = when (mode) {
                    ChromeMode.TITLE -> StageRect(
                        base.left + dx.roundToInt(),
                        base.top + dy.roundToInt(),
                        base.right + dx.roundToInt(),
                        base.bottom + dy.roundToInt()
                    )
                    ChromeMode.RESIZE_CORNER -> StageRect(
                        base.left,
                        base.top,
                        max(base.left + minWidth, base.right + dx.roundToInt()),
                        max(base.top + minHeight, base.bottom + dy.roundToInt())
                    )
                    ChromeMode.RESIZE_RIGHT -> StageRect(
                        base.left,
                        base.top,
                        max(base.left + minWidth, base.right + dx.roundToInt()),
                        base.bottom
                    )
                    ChromeMode.RESIZE_BOTTOM -> StageRect(
                        base.left,
                        base.top,
                        base.right,
                        max(base.top + minHeight, base.bottom + dy.roundToInt())
                    )
                }
                val clamped = clampToScreen(newRect)
                holder.rect = clamped
                position(holder)
                callbacks.onGeometryChanged(id, clamped, false)
            }
            ChromeOverlayView.PHASE_END -> {
                callbacks.onGeometryChanged(id, holder.rect, true)
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

    private fun overlayParams(width: Int, height: Int, x: Int, y: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
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
    }

    private companion object {
        const val TAG = "WindowChrome"
    }
}
