package dev.stagelab.nativehook.ui.strip

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.stagelab.nativecore.Stage
import dev.stagelab.nativecore.StageSession
import dev.stagelab.nativecore.StageTask
import dev.stagelab.nativehook.bridge.StageNativeRuntime
import dev.stagelab.nativehook.ui.StageLayoutMetrics
import dev.stagelab.nativehook.xposed.HookGuard
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * N5 Left Recent Strip integrated directly inside `com.android.systemui`.
 *
 * Strictly follows iPad Stage Manager visual & behavioral specs:
 * - Shows at most 4 recent Stages floating naturally over the native desktop wallpaper.
 * - NO giant rounded sidebar container, NO "Workspace 1/3" text cards, NO "+" button, NO arrows.
 * - Multi-window Stages render spatially arranged mini window thumbnails.
 * - Click switches Stage (S2); horizontal drag toward center merges top task into Active Stage (S3).
 * - Supports user-configured hide with a slim left-edge swipe-to-reveal zone.
 */
class RecentStripOverlayController(
    private val onStageClicked: (stageId: String) -> Unit,
    private val onStageDraggedToCenter: (stageId: String) -> Unit,
    private val onLeftEdgeSwipeReveal: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshotExecutor = Executors.newSingleThreadExecutor()
    private val snapshotLoader = NativeTaskSnapshotLoader()
    private val thumbImageViews = ConcurrentHashMap<Int, ImageView>()
    private val stageCardViews = ConcurrentHashMap<String, View>()
    private var hoveredStageId: String? = null

    private var windowManager: WindowManager? = null
    private var rootContainer: FrameLayout? = null
    private var stripColumn: LinearLayout? = null
    private var edgeRevealHandle: View? = null
    private var isAttached = false

    private var lastSession: StageSession = StageSession.EMPTY
    private var lastRenderedSignature: String = ""
    private var hiddenBySetting = false
    private var temporarilyRevealed = false
    private var livePollRunning = false
    private var appContextRef: Context? = null

    private val livePollRunnable = object : Runnable {
        override fun run() {
            if (!isAttached || !StageNativeRuntime.livePreviewEnabled) {
                livePollRunning = false
                return
            }
            val ctx = appContextRef
            val tasks = lastSession.visibleRecentStages.flatMap { it.tasks }
            if (ctx != null && tasks.isNotEmpty() && !StageNativeRuntime.isMagicAnimationBusy()) {
                refreshSnapshotsForTasks(ctx, tasks)
            }
            mainHandler.postDelayed(this, LIVE_PREVIEW_INTERVAL_MS)
        }
    }

    fun update(context: Context, session: StageSession, hidden: Boolean, tempRevealed: Boolean) {
        appContextRef = context.applicationContext
        lastSession = session
        hiddenBySetting = hidden
        temporarilyRevealed = tempRevealed

        mainHandler.post {
            if (!HookGuard.isActive()) {
                detach()
                return@post
            }
            val recent = session.visibleRecentStages
            if (recent.isEmpty()) {
                detach()
                return@post
            }
            ensureAttached(context)
            val sig = buildSignature(recent, hidden, tempRevealed)
            if (sig != lastRenderedSignature) {
                lastRenderedSignature = sig
                render(context, recent)
            }
            if (StageNativeRuntime.livePreviewEnabled && !livePollRunning) {
                livePollRunning = true
                mainHandler.postDelayed(livePollRunnable, LIVE_PREVIEW_INTERVAL_MS)
            }
        }
    }

    private fun buildSignature(stages: List<Stage>, hidden: Boolean, tempRevealed: Boolean): String =
        "$hidden|$tempRevealed|" + stages.joinToString(";") { st ->
            "${st.stageId}:${st.tasks.joinToString(",") { it.taskId.toString() }}"
        }

    fun refreshSnapshotsForTasks(context: Context, tasks: List<StageTask>) {
        if (tasks.isEmpty()) return
        // Fetch fresh snapshots OFF the main thread and update ImageViews IN-PLACE (zero removeAllViews flash!)
        snapshotExecutor.execute {
            for (t in tasks) {
                val res = snapshotLoader.loadPreview(context, t.taskId, t.packageName, forceRefresh = true)
                val bmp = res.bitmap ?: continue
                StageMagicTransitionOverlay.updateCachedPreview(t.taskId, bmp, res.appIcon)
                mainHandler.post {
                    thumbImageViews[t.taskId]?.let { iv ->
                        if (!bmp.isRecycled) {
                            iv.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }
    }

    fun detach() {
        mainHandler.post {
            livePollRunning = false
            mainHandler.removeCallbacks(livePollRunnable)
            val wm = windowManager
            val root = rootContainer
            if (isAttached && wm != null && root != null) {
                runCatching { wm.removeViewImmediate(root) }
            }
            isAttached = false
            lastRenderedSignature = ""
            thumbImageViews.clear()
            stageCardViews.clear()
            hoveredStageId = null
            rootContainer = null
            stripColumn = null
            edgeRevealHandle = null
        }
    }

    private fun ensureAttached(context: Context) {
        if (isAttached && rootContainer != null) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val root = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            clipChildren = false
            clipToPadding = false
            setPadding(dp(context, 4), dp(context, 24), dp(context, 24), dp(context, 24))
        }

        val edgeHandle = createEdgeRevealHandle(context)
        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START,
            ),
        )
        root.addView(
            edgeHandle,
            FrameLayout.LayoutParams(
                dp(context, 18),
                dp(context, 320),
                Gravity.CENTER_VERTICAL or Gravity.START,
            ),
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val dm = context.resources.displayMetrics
            // Responsive left Back-gesture safe zone (~28dp) across any tablet DPI
            x = StageLayoutMetrics.compute(dm.widthPixels, dm.heightPixels, dm.density).backSafePx
            title = "StageLabRecentStrip"
        }

        runCatching {
            wm.addView(root, lp)
            rootContainer = root
            stripColumn = column
            edgeRevealHandle = edgeHandle
            isAttached = true
            HookGuard.info("RECENT_STRIP attached to SystemUI WindowManager")
        }.onFailure {
            HookGuard.warn("RECENT_STRIP failed to attach", it)
        }
    }

    private fun render(context: Context, recentStages: List<Stage>) {
        val column = stripColumn ?: return
        val edge = edgeRevealHandle ?: return

        val showStrip = !hiddenBySetting || temporarilyRevealed
        column.visibility = if (showStrip) View.VISIBLE else View.GONE
        edge.visibility = if (showStrip) View.GONE else View.VISIBLE

        if (!showStrip) return

        column.removeAllViews()
        thumbImageViews.clear()
        stageCardViews.clear()
        hoveredStageId = null
        val maxCards = recentStages.take(StageSession.MAX_VISIBLE_RECENT_STAGES)
        val total = maxCards.size
        val dm = context.resources.displayMetrics
        val metrics = StageLayoutMetrics.compute(dm.widthPixels, dm.heightPixels, dm.density)
        maxCards.forEachIndexed { index, stage ->
            val card = buildStageThumbnailCard(context, stage, index, total, metrics.stripCardWidthPx, metrics.stripCardHeightPx)
            stageCardViews[stage.stageId] = card
            val params = LinearLayout.LayoutParams(metrics.stripCardWidthPx, metrics.stripCardHeightPx).apply {
                if (index > 0) topMargin = dp(context, 16)
            }
            column.addView(card, params)
        }
    }

    /**
     * Hit-tests which Recent Stage card sits under screen coordinates `(rawX, rawY)`
     * so users can freely drag a Dock app directly onto any workspace in the left strip!
     */
    fun findStageIdAtScreenPoint(rawX: Float, rawY: Float): String? {
        if (!isAttached) return null
        val loc = IntArray(2)
        val slop = 36
        for ((stageId, view) in stageCardViews) {
            if (!view.isAttachedToWindow) continue
            view.getLocationOnScreen(loc)
            val left = loc[0] - slop
            val top = loc[1] - slop
            val right = loc[0] + view.width + slop
            val bottom = loc[1] + view.height + slop
            if (rawX >= left && rawX <= right && rawY >= top && rawY <= bottom) {
                return stageId
            }
        }
        return null
    }

    fun highlightHoverStage(stageId: String?) {
        if (hoveredStageId == stageId) return
        val prev = hoveredStageId
        hoveredStageId = stageId
        mainHandler.post {
            if (prev != null) {
                stageCardViews[prev]?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140L)?.start()
            }
            if (stageId != null) {
                stageCardViews[stageId]?.animate()?.scaleX(1.10f)?.scaleY(1.10f)?.setDuration(140L)?.start()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildStageThumbnailCard(
        context: Context,
        stage: Stage,
        index: Int,
        total: Int,
        cardW: Int,
        cardH: Int,
    ): View {
        val radiusPx = dp(context, 18).toFloat()

        // Official iPadOS Stage Manager slanted 3D perspective:
        // hinged on left edge (pivotX = 0), angled inward with strong 3D depth + vertical fan tilt.
        val centerOffset = if (total <= 1) 0f else (index - (total - 1) / 2f) / ((total - 1) / 2f)
        val tiltX = centerOffset * 4.5f

        val container = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            pivotX = 0f
            pivotY = cardH / 2f
            cameraDistance = 2100f * context.resources.displayMetrics.density
            rotationY = 28f
            rotationX = tiltX
            elevation = dp(context, 12).toFloat()
        }

        val orderedTasks = stage.tasksByZOrder
        if (orderedTasks.size == 1) {
            val task = orderedTasks.first()
            container.addView(
                buildSingleWindowThumb(context, task, cardW, cardH, radiusPx),
                FrameLayout.LayoutParams(cardW, cardH),
            )
        } else {
            // Multi-window Stage: render spatially arranged layered mini windows
            val count = orderedTasks.size.coerceAtMost(Stage.MAX_TASKS_PER_STAGE)
            val miniW = (cardW * 0.70f).toInt()
            val miniH = (cardH * 0.74f).toInt()
            orderedTasks.takeLast(count).forEachIndexed { i, task ->
                val dx = if (count == 2) {
                    if (i == 0) 0 else cardW - miniW
                } else {
                    ((cardW - miniW) * (i.toFloat() / (count - 1).coerceAtLeast(1))).toInt()
                }
                val dy = if (count == 2) {
                    if (i == 0) 0 else cardH - miniH
                } else {
                    ((cardH - miniH) * (i.toFloat() / (count - 1).coerceAtLeast(1))).toInt()
                }
                val mini = buildSingleWindowThumb(context, task, miniW, miniH, dp(context, 12).toFloat()).apply {
                    elevation = dp(context, 4 + i * 2).toFloat()
                }
                container.addView(
                    mini,
                    FrameLayout.LayoutParams(miniW, miniH).apply {
                        leftMargin = dx
                        topMargin = dy
                    },
                )
            }
        }

        // Touch handling: tap -> S2 switch Stage; drag right into center -> S3 merge into Active Stage
        var downX = 0f
        var downY = 0f
        var draggingToCenter = false
        val mergeThresholdPx = dp(context, 72).toFloat()

        container.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    draggingToCenter = false
                    v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(120).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dx > dp(context, 16) && abs(dx) > abs(dy)) {
                        draggingToCenter = true
                        v.translationX = dx.coerceIn(0f, dp(context, 110).toFloat())
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    v.animate().scaleX(1f).scaleY(1f).translationX(0f).setDuration(180).start()
                    if (draggingToCenter && dx >= mergeThresholdPx) {
                        HookGuard.info("RECENT_STRIP drag-to-center merge stage=${stage.stageId}")
                        onStageDraggedToCenter(stage.stageId)
                    } else if (!draggingToCenter && abs(dx) < dp(context, 16)) {
                        HookGuard.info("RECENT_STRIP click switch stage=${stage.stageId}")
                        onStageClicked(stage.stageId)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).translationX(0f).setDuration(160).start()
                    true
                }
                else -> false
            }
        }

        return container
    }

    private fun buildSingleWindowThumb(
        context: Context,
        task: StageTask,
        widthPx: Int,
        heightPx: Int,
        radiusPx: Float,
    ): View {
        val preview = snapshotLoader.loadPreview(context, task.taskId, task.packageName, forceRefresh = false)

        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx
                setColor(Color.argb(215, 243, 237, 247)) // Soft tonal surface fallback
                setStroke(dp(context, 1), Color.argb(90, 255, 255, 255))
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
            clipToOutline = true
        }

        // Always attach an ImageView so in-place live preview updates preserve true 3D perspective slant (rotationY=28°)
        // and rounded corners with ZERO SurfaceView hole-punch or removeAllViews flash!
        val img = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (preview.mode == PreviewMode.NATIVE_TASK_SNAPSHOT && preview.bitmap != null) {
                setImageBitmap(preview.bitmap)
            }
        }
        thumbImageViews[task.taskId] = img
        frame.addView(img, FrameLayout.LayoutParams(widthPx, heightPx))

        // Subtle app icon badge in bottom-right corner (or centered when snapshot is unavailable/FLAG_SECURE)
        preview.appIcon?.let { iconDrawable ->
            val iconSize = if (preview.bitmap != null) dp(context, 22) else dp(context, 34)
            val iconView = ImageView(context).apply {
                setImageDrawable(iconDrawable)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val lp = if (preview.bitmap != null) {
                FrameLayout.LayoutParams(iconSize, iconSize, Gravity.BOTTOM or Gravity.END).apply {
                    rightMargin = dp(context, 6)
                    bottomMargin = dp(context, 6)
                }
            } else {
                FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            }
            frame.addView(iconView, lp)
        }

        return frame
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createEdgeRevealHandle(context: Context): View {
        return View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            var startX = 0f
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (event.rawX - startX > dp(context, 24)) {
                            onLeftEdgeSwipeReveal()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    companion object {
        private const val LIVE_PREVIEW_INTERVAL_MS = 350L
    }
}
