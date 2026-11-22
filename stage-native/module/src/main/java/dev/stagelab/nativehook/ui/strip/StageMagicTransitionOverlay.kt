package dev.stagelab.nativehook.ui.strip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import dev.stagelab.nativecore.StageTask
import dev.stagelab.nativecore.WindowBounds
import dev.stagelab.nativehook.xposed.HookGuard
import java.util.concurrent.Executors

/**
 * Silky 120fps Apple iPadOS Stage Manager "Magic Move" 3D transition overlay inside `com.android.systemui`.
 *
 * Performance & fluidity architecture:
 * - Preloads & downsamples task snapshots on a dedicated background thread (zero main-thread Binder/GPU stall).
 * - Waits for `OnPreDrawListener` so layout & GPU texture upload finish BEFORE frame 0 of the animation starts.
 * - Uses `LAYER_TYPE_HARDWARE` on each animated card (no expensive per-frame dynamic 3D shadow recomputation).
 * - Unhurried Apple-like timing (~450-480ms) with smooth decelerating cubic-bezier curves.
 */
object StageMagicTransitionOverlay {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val snapshotLoader = NativeTaskSnapshotLoader()

    // Smooth, unhurried Apple iPadOS Magic Move easing curves
    private val outgoingInterpolator = PathInterpolator(0.28f, 0.86f, 0.30f, 1.0f)
    private val incomingInterpolator = PathInterpolator(0.20f, 0.90f, 0.24f, 1.0f)

    private var activeRoot: FrameLayout? = null
    private var activeWm: WindowManager? = null
    private var pendingCleanup: Runnable? = null

    @Volatile
    private var animationSeq = 0L

    private data class PreparedCard(
        val task: StageTask,
        val bounds: WindowBounds,
        val bitmap: Bitmap?,
        val icon: Drawable?,
    )

    fun playStageSwitch(
        context: Context,
        outgoingTasks: List<Pair<StageTask, WindowBounds>>,
        incomingTasks: List<Pair<StageTask, WindowBounds>>,
        onOverlayReadyForAtomicSwap: () -> Unit,
    ) {
        if (outgoingTasks.isEmpty() && incomingTasks.isEmpty()) {
            mainHandler.post(onOverlayReadyForAtomicSwap)
            return
        }

        val seq = ++animationSeq

        // 1. Load & downsample snapshots OFF the main thread so frame 1 never stalls.
        bgExecutor.execute {
            val outPrepared = outgoingTasks.mapNotNull { (t, b) ->
                if (b.isEmpty) return@mapNotNull null
                val p = snapshotLoader.loadPreview(context, t.taskId, t.packageName, forceRefresh = true)
                PreparedCard(t, b, downsampleIfLarge(p.bitmap, 960), p.appIcon)
            }
            val inPrepared = incomingTasks.mapNotNull { (t, b) ->
                if (b.isEmpty) return@mapNotNull null
                val p = snapshotLoader.loadPreview(context, t.taskId, t.packageName, forceRefresh = false)
                PreparedCard(t, b, downsampleIfLarge(p.bitmap, 960), p.appIcon)
            }

            mainHandler.post {
                if (seq != animationSeq) return@post
                startHardwareAnimatedOverlay(context, outPrepared, inPrepared, seq, onOverlayReadyForAtomicSwap)
            }
        }
    }

    private fun startHardwareAnimatedOverlay(
        context: Context,
        outgoing: List<PreparedCard>,
        incoming: List<PreparedCard>,
        seq: Long,
        onOverlayReadyForAtomicSwap: () -> Unit,
    ) {
        runCatching {
            dismissImmediate()
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run {
                onOverlayReadyForAtomicSwap()
                return
            }
            val dm = context.resources.displayMetrics
            val density = dm.density
            val screenH = dm.heightPixels

            val root = FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                setBackgroundColor(Color.TRANSPARENT)
            }

            val stripAnchorX = dp(context, 94).toFloat()
            val outgoingStripY = screenH * 0.34f
            val incomingStripY = screenH * 0.50f
            val cornerPx = dp(context, 24).toFloat()

            val outgoingViews = mutableListOf<Triple<FrameLayout, PreparedCard, Int>>()
            val incomingViews = mutableListOf<Triple<FrameLayout, PreparedCard, Int>>()

            // Build outgoing cards at their live bounds
            outgoing.forEachIndexed { idx, item ->
                val card = createHardwareCard(context, item, cornerPx, density)
                card.pivotX = 0f
                card.pivotY = item.bounds.height * 0.5f
                root.addView(card)
                outgoingViews.add(Triple(card, item, idx))
            }

            // Build incoming cards starting from the left slanted 3D Recent Strip
            incoming.forEachIndexed { idx, item ->
                val card = createHardwareCard(context, item, cornerPx, density)
                val b = item.bounds
                val startScaleX = (dp(context, 148).toFloat() / b.width.coerceAtLeast(1)).coerceIn(0.14f, 0.45f)
                val startScaleY = (dp(context, 106).toFloat() / b.height.coerceAtLeast(1)).coerceIn(0.14f, 0.45f)
                val startScale = minOf(startScaleX, startScaleY)

                card.pivotX = 0f
                card.pivotY = b.height * 0.5f
                card.translationX = stripAnchorX - b.left + idx * dp(context, 14)
                card.translationY = incomingStripY - b.top + idx * dp(context, 10)
                card.scaleX = startScale
                card.scaleY = startScale
                card.rotationY = 28f
                card.alpha = 0.28f
                root.addView(card)
                incomingViews.add(Triple(card, item, idx))
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "StageLabMagicTransition"
            }

            wm.addView(root, lp)
            activeRoot = root
            activeWm = wm

            // Wait for layout & GPU upload before starting frame 0 of the animations (eliminates initial frame drop!).
            root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    root.viewTreeObserver.removeOnPreDrawListener(this)
                    if (seq != animationSeq) return true

                    // Swap real live windows atomically right after the overlay covers frame 0.
                    mainHandler.postDelayed({
                        if (seq == animationSeq) onOverlayReadyForAtomicSwap()
                    }, 32L)

                    outgoingViews.forEach { (card, item, idx) ->
                        val b = item.bounds
                        val targetScaleX = (dp(context, 148).toFloat() / b.width.coerceAtLeast(1)).coerceIn(0.12f, 0.45f)
                        val targetScaleY = (dp(context, 106).toFloat() / b.height.coerceAtLeast(1)).coerceIn(0.12f, 0.45f)
                        val targetScale = minOf(targetScaleX, targetScaleY)
                        val dx = stripAnchorX - b.left + idx * dp(context, 14)
                        val dy = outgoingStripY - b.top + idx * dp(context, 10)

                        card.animate()
                            .translationX(dx)
                            .translationY(dy)
                            .scaleX(targetScale)
                            .scaleY(targetScale)
                            .rotationY(28f)
                            .alpha(0f)
                            .setDuration(OUTGOING_DURATION_MS)
                            .setInterpolator(outgoingInterpolator)
                            .start()
                    }

                    incomingViews.forEach { (card, _, _) ->
                        card.animate()
                            .translationX(0f)
                            .translationY(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .rotationY(0f)
                            .alpha(1f)
                            .setDuration(INCOMING_DURATION_MS)
                            .setInterpolator(incomingInterpolator)
                            .start()
                    }

                    val cleanup = Runnable {
                        if (seq != animationSeq) return@Runnable
                        root.animate()
                            .alpha(0f)
                            .setDuration(FADE_OUT_MS)
                            .withEndAction {
                                if (seq == animationSeq) dismissImmediate()
                            }
                            .start()
                    }
                    pendingCleanup = cleanup
                    mainHandler.postDelayed(cleanup, INCOMING_DURATION_MS - 10L)
                    return true
                }
            })
        }.onFailure {
            HookGuard.warn("MAGIC_TRANSITION failed", it)
            dismissImmediate()
            onOverlayReadyForAtomicSwap()
        }
    }

    private fun createHardwareCard(
        context: Context,
        item: PreparedCard,
        cornerPx: Float,
        density: Float,
    ): FrameLayout {
        val card = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(item.bounds.width, item.bounds.height).apply {
                leftMargin = item.bounds.left
                topMargin = item.bounds.top
            }
            // Cache each card as a GPU hardware texture — avoids per-frame shadow & outline re-rasterization!
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            cameraDistance = 7200f * density
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(Color.argb(242, 245, 242, 250))
                setStroke(dp(context, 1), Color.argb(120, 255, 255, 255))
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
                }
            }
            clipToOutline = true
        }

        val img = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            if (item.bitmap != null) {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(item.bitmap)
            } else {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = dp(context, 48)
                setPadding(pad, pad, pad, pad)
                setImageDrawable(item.icon)
            }
        }
        card.addView(img)
        return card
    }

    private fun downsampleIfLarge(src: Bitmap?, maxDim: Int): Bitmap? {
        if (src == null || src.isRecycled) return null
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = maxDim.toFloat() / maxOf(w, h).toFloat()
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(src, nw, nh, true) }.getOrDefault(src)
    }

    fun dismissImmediate() {
        pendingCleanup?.let { mainHandler.removeCallbacks(it) }
        pendingCleanup = null
        val wm = activeWm
        val root = activeRoot
        if (wm != null && root != null) {
            runCatching { wm.removeViewImmediate(root) }
        }
        activeRoot = null
        activeWm = null
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    private const val OUTGOING_DURATION_MS = 440L
    private const val INCOMING_DURATION_MS = 480L
    private const val FADE_OUT_MS = 85L
}
