package dev.stagelab.nativehook.ui.strip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Interruptible, GPU-accelerated Apple iPadOS Stage Manager "Magic Move" 3D transition overlay.
 *
 * Key guarantees:
 * - Pixel-exact 68px rounded corners (`clipToOutline = true` baked into GPU hardware layer via `withLayer()`)
 *   from frame 0 to the final frame — never flashes as a sharp rectangle.
 * - Fully interruptible: tapping another Stage mid-flight captures each card's live transform
 *   (`translationX/Y`, `scaleX/Y`, `rotationY`, `alpha`) and smoothly redirects from that exact position.
 * - Persistent `INVISIBLE`/`VISIBLE` container + zero-copy `Bitmap.Config.HARDWARE` snapshots.
 */
object StageMagicTransitionOverlay {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val snapshotLoader = NativeTaskSnapshotLoader()

    private val outgoingInterpolator = PathInterpolator(0.36f, 0.0f, 0.20f, 1.0f)
    private val incomingInterpolator = PathInterpolator(0.32f, 0.0f, 0.16f, 1.0f)

    private var persistentRoot: FrameLayout? = null
    private var attachedWm: WindowManager? = null
    private var pendingCleanup: Runnable? = null

    private data class LiveCardPose(
        val translationX: Float,
        val translationY: Float,
        val scaleX: Float,
        val scaleY: Float,
        val rotationY: Float,
        val alpha: Float,
    )

    private val activeCards = ConcurrentHashMap<Int, FrameLayout>()
    private val interruptedPoses = ConcurrentHashMap<Int, LiveCardPose>()

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

        // Immediately interrupt any in-flight animation on the main thread and snapshot each card's live pose
        mainHandler.post {
            captureAndInterruptInFlightCards()
        }

        bgExecutor.execute {
            val outPrepared = outgoingTasks.mapNotNull { (t, b) ->
                if (b.isEmpty) return@mapNotNull null
                val p = snapshotLoader.loadPreview(context, t.taskId, t.packageName, forceRefresh = false)
                PreparedCard(t, b, prepareGpuBitmap(p.bitmap), p.appIcon)
            }
            val inPrepared = incomingTasks.mapNotNull { (t, b) ->
                if (b.isEmpty) return@mapNotNull null
                val p = snapshotLoader.loadPreview(context, t.taskId, t.packageName, forceRefresh = false)
                PreparedCard(t, b, prepareGpuBitmap(p.bitmap), p.appIcon)
            }

            mainHandler.post {
                if (seq != animationSeq) return@post
                runSmoothTransition(context, outPrepared, inPrepared, seq, onOverlayReadyForAtomicSwap)
            }
        }
    }

    private fun captureAndInterruptInFlightCards() {
        pendingCleanup?.let { mainHandler.removeCallbacks(it) }
        pendingCleanup = null
        persistentRoot?.animate()?.cancel()
        interruptedPoses.clear()
        for ((taskId, card) in activeCards) {
            card.animate().cancel()
            interruptedPoses[taskId] = LiveCardPose(
                translationX = card.translationX,
                translationY = card.translationY,
                scaleX = card.scaleX,
                scaleY = card.scaleY,
                rotationY = card.rotationY,
                alpha = card.alpha,
            )
        }
    }

    private fun ensurePersistentRoot(context: Context): FrameLayout? {
        persistentRoot?.let { return it }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        val root = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.INVISIBLE
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
        return runCatching {
            wm.addView(root, lp)
            persistentRoot = root
            attachedWm = wm
            root
        }.onFailure {
            HookGuard.warn("MAGIC_TRANSITION attach failed", it)
        }.getOrNull()
    }

    private fun runSmoothTransition(
        context: Context,
        outgoing: List<PreparedCard>,
        incoming: List<PreparedCard>,
        seq: Long,
        onOverlayReadyForAtomicSwap: () -> Unit,
    ) {
        runCatching {
            pendingCleanup?.let { mainHandler.removeCallbacks(it) }
            pendingCleanup = null

            val root = ensurePersistentRoot(context) ?: run {
                onOverlayReadyForAtomicSwap()
                return
            }

            root.animate().cancel()
            root.removeAllViews()
            activeCards.clear()
            root.alpha = 1f
            root.visibility = View.VISIBLE

            val dm = context.resources.displayMetrics
            val density = dm.density
            val screenH = dm.heightPixels
            val stripAnchorX = dp(context, 94).toFloat()
            val outgoingStripY = screenH * 0.34f
            val incomingStripY = screenH * 0.50f
            // Exact match to ColorOS Flexible Window `LaunchCornerRadius = 68px` (~26dp at 420dpi)
            val cornerPx = 68f

            val outgoingViews = mutableListOf<Triple<FrameLayout, PreparedCard, Int>>()
            val incomingViews = mutableListOf<Triple<FrameLayout, PreparedCard, Int>>()

            outgoing.forEachIndexed { idx, item ->
                val card = createRoundedCardView(context, item, cornerPx, density)
                card.pivotX = 0f
                card.pivotY = item.bounds.height * 0.5f
                // If this card was interrupted mid-flight, continue smoothly from its live pose!
                interruptedPoses.remove(item.task.taskId)?.let { pose ->
                    card.translationX = pose.translationX
                    card.translationY = pose.translationY
                    card.scaleX = pose.scaleX
                    card.scaleY = pose.scaleY
                    card.rotationY = pose.rotationY
                    card.alpha = pose.alpha
                }
                root.addView(card)
                activeCards[item.task.taskId] = card
                outgoingViews.add(Triple(card, item, idx))
            }

            incoming.forEachIndexed { idx, item ->
                val card = createRoundedCardView(context, item, cornerPx, density)
                val b = item.bounds
                card.pivotX = 0f
                card.pivotY = b.height * 0.5f

                val pose = interruptedPoses.remove(item.task.taskId)
                if (pose != null) {
                    // Interrupted mid-flight: smoothly redirect back toward the main stage from live pose!
                    card.translationX = pose.translationX
                    card.translationY = pose.translationY
                    card.scaleX = pose.scaleX
                    card.scaleY = pose.scaleY
                    card.rotationY = pose.rotationY
                    card.alpha = pose.alpha.coerceAtLeast(0.35f)
                } else {
                    val startScaleX = (dp(context, 148).toFloat() / b.width.coerceAtLeast(1)).coerceIn(0.14f, 0.45f)
                    val startScaleY = (dp(context, 106).toFloat() / b.height.coerceAtLeast(1)).coerceIn(0.14f, 0.45f)
                    val startScale = minOf(startScaleX, startScaleY)
                    card.translationX = stripAnchorX - b.left + idx * dp(context, 14)
                    card.translationY = incomingStripY - b.top + idx * dp(context, 10)
                    card.scaleX = startScale
                    card.scaleY = startScale
                    card.rotationY = 26f
                    card.alpha = 0.25f
                }
                root.addView(card)
                activeCards[item.task.taskId] = card
                incomingViews.add(Triple(card, item, idx))
            }
            interruptedPoses.clear()

            root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    root.viewTreeObserver.removeOnPreDrawListener(this)
                    if (seq != animationSeq) return true

                    mainHandler.postDelayed({
                        if (seq == animationSeq) onOverlayReadyForAtomicSwap()
                    }, 24L)

                    outgoingViews.forEach { (card, item, idx) ->
                        val b = item.bounds
                        val targetScaleX = (dp(context, 148).toFloat() / b.width.coerceAtLeast(1)).coerceIn(0.12f, 0.45f)
                        val targetScaleY = (dp(context, 106).toFloat() / b.height.coerceAtLeast(1)).coerceIn(0.12f, 0.45f)
                        val targetScale = minOf(targetScaleX, targetScaleY)
                        val dx = stripAnchorX - b.left + idx * dp(context, 14)
                        val dy = outgoingStripY - b.top + idx * dp(context, 10)

                        card.animate()
                            .withLayer()
                            .translationX(dx)
                            .translationY(dy)
                            .scaleX(targetScale)
                            .scaleY(targetScale)
                            .rotationY(26f)
                            .alpha(0f)
                            .setDuration(OUTGOING_DURATION_MS)
                            .setInterpolator(outgoingInterpolator)
                            .start()
                    }

                    incomingViews.forEach { (card, _, _) ->
                        card.animate()
                            .withLayer()
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
                                if (seq == animationSeq) {
                                    activeCards.clear()
                                    root.removeAllViews()
                                    root.visibility = View.INVISIBLE
                                    root.alpha = 1f
                                }
                            }
                            .start()
                    }
                    pendingCleanup = cleanup
                    mainHandler.postDelayed(cleanup, INCOMING_DURATION_MS)
                    return true
                }
            })
        }.onFailure {
            HookGuard.warn("MAGIC_TRANSITION failed", it)
            hideNow()
            onOverlayReadyForAtomicSwap()
        }
    }

    private fun createRoundedCardView(
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
            cameraDistance = 7600f * density
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(Color.argb(242, 245, 242, 250))
            }
            // Clip snapshot cleanly to ColorOS's exact 68px rounded corners from frame 0!
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

    private fun prepareGpuBitmap(src: Bitmap?): Bitmap? {
        if (src == null || src.isRecycled) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && src.config == Bitmap.Config.HARDWARE) {
            return src
        }
        val w = src.width
        val h = src.height
        if (w <= 960 && h <= 960) return src
        val ratio = 960f / maxOf(w, h).toFloat()
        return runCatching {
            Bitmap.createScaledBitmap(src, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
        }.getOrDefault(src)
    }

    fun hideNow() {
        pendingCleanup?.let { mainHandler.removeCallbacks(it) }
        pendingCleanup = null
        activeCards.clear()
        interruptedPoses.clear()
        persistentRoot?.let { root ->
            root.animate().cancel()
            root.removeAllViews()
            root.visibility = View.INVISIBLE
            root.alpha = 1f
        }
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    private const val OUTGOING_DURATION_MS = 520L
    private const val INCOMING_DURATION_MS = 580L
    private const val FADE_OUT_MS = 95L
}
