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
import dev.stagelab.nativehook.ui.StageLayoutMetrics
import dev.stagelab.nativehook.xposed.HookGuard
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Zero-flash, truly interruptible Apple iPadOS Stage Manager "Magic Move" 3D transition overlay.
 *
 * Rapid-tap smoothness architecture:
 * - Caches GPU-ready snapshots per taskId (`snapshotCache`) AND reuses live in-flight `FrameLayout`
 *   cards in-place without calling `removeAllViews()` on cards still participating in the new switch.
 * - When tapping rapidly mid-flight, bypasses `bgExecutor` completely and retargets every card's
 *   `ViewPropertyAnimator` synchronously on the exact same frame from its live mid-air pose!
 * - Pixel-exact 68px rounded corners (`clipToOutline = true`, baked via `withLayer()`).
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
    private var pendingPark: Runnable? = null
    private var pendingShow: Runnable? = null

    private data class CachedPreview(
        val bitmap: Bitmap?,
        val icon: Drawable?,
    )

    private val previewCache = ConcurrentHashMap<Int, CachedPreview>()
    private val activeCards = ConcurrentHashMap<Int, FrameLayout>()

    @Volatile
    private var animationSeq = 0L

    private data class PreparedCard(
        val task: StageTask,
        val bounds: WindowBounds,
        val preview: CachedPreview,
    )

    fun updateCachedPreview(taskId: Int, bitmap: Bitmap?, icon: Drawable?) {
        if (bitmap != null && !bitmap.isRecycled) {
            previewCache[taskId] = CachedPreview(prepareGpuBitmap(bitmap), icon)
        }
    }

    fun playStageSwitch(
        context: Context,
        outgoingTasks: List<Pair<StageTask, WindowBounds>>,
        incomingTasks: List<Pair<StageTask, WindowBounds>>,
        onParkOutgoing: () -> Unit,
        onShowIncoming: () -> Unit,
    ) {
        if (outgoingTasks.isEmpty() && incomingTasks.isEmpty()) {
            mainHandler.post {
                onParkOutgoing()
                onShowIncoming()
            }
            return
        }

        val seq = ++animationSeq
        val validOut = outgoingTasks.filter { !it.second.isEmpty }
        val validIn = incomingTasks.filter { !it.second.isEmpty }

        // Only use the synchronous cached fast-path when interrupting a mid-flight animation
        // (`activeCards.isNotEmpty()`). For normal switches, always capture a FRESH snapshot
        // (`forceRefresh = true`, ~5-8ms) so the gliding mask shows the EXACT latest content
        // instead of flashing from a stale initial-launch screenshot!
        val isMidFlightInterrupt = activeCards.isNotEmpty()
        val allCached = isMidFlightInterrupt && (validOut + validIn).all { (t, _) ->
            activeCards.containsKey(t.taskId) || previewCache.containsKey(t.taskId)
        }

        if (allCached) {
            val outPrepared = validOut.map { (t, b) ->
                PreparedCard(t, b, previewCache[t.taskId] ?: CachedPreview(null, null))
            }
            val inPrepared = validIn.map { (t, b) ->
                PreparedCard(t, b, previewCache[t.taskId] ?: CachedPreview(null, null))
            }
            mainHandler.post {
                if (seq != animationSeq) return@post
                runInPlaceInterruptibleTransition(context, outPrepared, inPrepared, seq, onParkOutgoing, onShowIncoming)
            }
            return
        }

        // Fresh-snapshot path:
        // - Outgoing (currently live on screen): captureLiveFrame = true (takeTaskSnapshot) + highResolution = true
        //   so SurfaceFlinger captures the EXACT current pixels AND updates WMS's cache for next switch!
        // - Incoming (parked in Recent): highResolution = true (RESOLUTION_HIGH = 1) so the gliding card
        //   is crisp 1:1 full resolution and NEVER flashes from blurry low-res to sharp when landing!
        bgExecutor.execute {
            val outPrepared = validOut.map { (t, b) ->
                val p = snapshotLoader.loadPreview(
                    context = context,
                    taskId = t.taskId,
                    packageName = t.packageName,
                    forceRefresh = true,
                    captureLiveFrame = true,
                    highResolution = true,
                )
                val fresh = if (p.bitmap != null) {
                    CachedPreview(prepareGpuBitmap(p.bitmap), p.appIcon).also { previewCache[t.taskId] = it }
                } else {
                    previewCache[t.taskId] ?: CachedPreview(null, p.appIcon)
                }
                PreparedCard(t, b, fresh)
            }
            val inPrepared = validIn.map { (t, b) ->
                val p = snapshotLoader.loadPreview(
                    context = context,
                    taskId = t.taskId,
                    packageName = t.packageName,
                    forceRefresh = true,
                    captureLiveFrame = false,
                    highResolution = true,
                )
                val fresh = if (p.bitmap != null) {
                    CachedPreview(prepareGpuBitmap(p.bitmap), p.appIcon).also { previewCache[t.taskId] = it }
                } else {
                    previewCache[t.taskId] ?: CachedPreview(null, p.appIcon)
                }
                PreparedCard(t, b, fresh)
            }

            mainHandler.post {
                if (seq != animationSeq) return@post
                runInPlaceInterruptibleTransition(context, outPrepared, inPrepared, seq, onParkOutgoing, onShowIncoming)
            }
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
            // Align (0, 0) to exact physical screen (0, 0) with zero status-bar / cutout inset offset!
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
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

    private fun runInPlaceInterruptibleTransition(
        context: Context,
        outgoing: List<PreparedCard>,
        incoming: List<PreparedCard>,
        seq: Long,
        onParkOutgoing: () -> Unit,
        onShowIncoming: () -> Unit,
    ) {
        runCatching {
            pendingCleanup?.let { mainHandler.removeCallbacks(it) }
            pendingCleanup = null
            pendingPark?.let { mainHandler.removeCallbacks(it) }
            pendingPark = null
            pendingShow?.let { mainHandler.removeCallbacks(it) }
            pendingShow = null

            val root = ensurePersistentRoot(context) ?: run {
                onParkOutgoing()
                onShowIncoming()
                return
            }

            val wasAlreadyVisible = root.visibility == View.VISIBLE
            root.animate().cancel()
            root.alpha = 1f
            root.visibility = View.VISIBLE

            val participatingIds = (outgoing.map { it.task.taskId } + incoming.map { it.task.taskId }).toSet()

            // Remove only unrelated stale cards; keep participating in-flight cards alive in-place!
            val staleIds = activeCards.keys.filter { it !in participatingIds }
            for (id in staleIds) {
                activeCards.remove(id)?.let { staleCard ->
                    staleCard.animate().cancel()
                    root.removeView(staleCard)
                }
            }

            val dm = context.resources.displayMetrics
            val density = dm.density
            val screenH = dm.heightPixels
            val metrics = StageLayoutMetrics.compute(dm.widthPixels, dm.heightPixels, density)
            val stripAnchorX = metrics.stripAnchorX
            val outgoingStripY = screenH * 0.34f
            val incomingStripY = screenH * 0.50f
            val cornerPx = metrics.cornerRadiusPx

            val outgoingViews = mutableListOf<Triple<FrameLayout, PreparedCard, Int>>()
            val incomingViews = mutableListOf<Triple<FrameLayout, PreparedCard, Int>>()
            var addedNewViews = false

            // 1. Prepare outgoing cards (reuse in-place if already flying; sync bounds if user resized!)
            outgoing.forEachIndexed { idx, item ->
                val existing = activeCards[item.task.taskId]
                val card = if (existing != null) {
                    existing.animate().cancel()
                    syncCardBoundsIfChanged(existing, item.bounds)
                    existing
                } else {
                    addedNewViews = true
                    createRoundedCardView(context, item, cornerPx, density).also { c ->
                        c.pivotX = 0f
                        c.pivotY = item.bounds.height * 0.5f
                        root.addView(c)
                        activeCards[item.task.taskId] = c
                    }
                }
                outgoingViews.add(Triple(card, item, idx))
            }

            // 2. Prepare incoming cards (reuse in-place if already flying, and bring to front!)
            incoming.forEachIndexed { idx, item ->
                val b = item.bounds
                val existing = activeCards[item.task.taskId]
                val card = if (existing != null) {
                    existing.animate().cancel()
                    syncCardBoundsIfChanged(existing, b)
                    existing.bringToFront()
                    existing
                } else {
                    addedNewViews = true
                    createRoundedCardView(context, item, cornerPx, density).also { c ->
                        val startScaleX = (dp(context, 148).toFloat() / b.width.coerceAtLeast(1)).coerceIn(0.14f, 0.45f)
                        val startScaleY = (dp(context, 106).toFloat() / b.height.coerceAtLeast(1)).coerceIn(0.14f, 0.45f)
                        val startScale = minOf(startScaleX, startScaleY)
                        c.pivotX = 0f
                        c.pivotY = b.height * 0.5f
                        c.translationX = stripAnchorX - b.left + idx * dp(context, 14)
                        c.translationY = incomingStripY - b.top + idx * dp(context, 10)
                        c.scaleX = startScale
                        c.scaleY = startScale
                        c.rotationY = 26f
                        c.alpha = 0.25f
                        root.addView(c)
                        activeCards[item.task.taskId] = c
                    }
                }
                incomingViews.add(Triple(card, item, idx))
            }

            val startAnimators = {
                if (seq == animationSeq) {
                    // Phase 1 (t=16ms): Hide outgoing Stage right after overlay frame 0 covers it,
                    // leaving clean blurred wallpaper while the incoming card glides in from the left.
                    val parkRunnable = Runnable {
                        if (seq == animationSeq) onParkOutgoing()
                    }
                    pendingPark = parkRunnable
                    mainHandler.postDelayed(parkRunnable, 16L)

                    // Phase 2 (t=INCOMING-30ms): Show real incoming Stage ONLY when the gliding card
                    // has landed in place — eliminates any early static pop-in before/during the glide!
                    val showRunnable = Runnable {
                        if (seq == animationSeq) onShowIncoming()
                    }
                    pendingShow = showRunnable
                    mainHandler.postDelayed(showRunnable, (INCOMING_DURATION_MS - 30L).coerceAtLeast(100L))

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
                }
            }

            if (wasAlreadyVisible && !addedNewViews) {
                // Pure mid-flight interruption: start retargeting on THIS EXACT FRAME (0ms delay!)
                startAnimators()
            } else {
                root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        root.viewTreeObserver.removeOnPreDrawListener(this)
                        startAnimators()
                        return true
                    }
                })
            }
        }.onFailure {
            HookGuard.warn("MAGIC_TRANSITION failed", it)
            hideNow()
            onParkOutgoing()
            onShowIncoming()
        }
    }

    private fun syncCardBoundsIfChanged(card: FrameLayout, bounds: WindowBounds) {
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != bounds.width || lp.height != bounds.height ||
            lp.leftMargin != bounds.left || lp.topMargin != bounds.top
        ) {
            lp.width = bounds.width
            lp.height = bounds.height
            lp.leftMargin = bounds.left
            lp.topMargin = bounds.top
            card.layoutParams = lp
            card.pivotY = bounds.height * 0.5f
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
            if (item.preview.bitmap != null) {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(item.preview.bitmap)
            } else {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = dp(context, 48)
                setPadding(pad, pad, pad, pad)
                setImageDrawable(item.preview.icon)
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
        pendingPark?.let { mainHandler.removeCallbacks(it) }
        pendingPark = null
        pendingShow?.let { mainHandler.removeCallbacks(it) }
        pendingShow = null
        activeCards.clear()
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

    private const val OUTGOING_DURATION_MS = 380L
    private const val INCOMING_DURATION_MS = 400L
    private const val FADE_OUT_MS = 75L
}
