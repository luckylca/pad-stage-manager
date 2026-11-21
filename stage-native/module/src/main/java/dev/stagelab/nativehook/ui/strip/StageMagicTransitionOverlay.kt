package dev.stagelab.nativehook.ui.strip

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import dev.stagelab.nativecore.StageTask
import dev.stagelab.nativecore.WindowBounds
import dev.stagelab.nativehook.xposed.HookGuard

/**
 * Apple iPadOS Stage Manager "Magic Move" 3D transition overlay inside `com.android.systemui`.
 *
 * When switching Stages:
 * - Outgoing Stage snapshot card(s) smoothly tilt in 3D (`rotationY: 0 -> 26°`), scale down, and
 *   swoop into the left Recent Strip while fading out.
 * - Incoming Stage snapshot card(s) simultaneously emerge from the left Recent Strip (`rotationY: 26° -> 0`),
 *   scale up, and glide into their exact live on-screen bounds.
 * - Strictly `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` so it never intercepts any user input,
 *   and detaches cleanly right as the 280ms animation finishes.
 */
object StageMagicTransitionOverlay {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshotLoader = NativeTaskSnapshotLoader()
    private val magicInterpolator = PathInterpolator(0.20f, 0.96f, 0.28f, 1.0f)

    private var activeRoot: FrameLayout? = null
    private var activeWm: WindowManager? = null
    private var pendingCleanup: Runnable? = null

    fun playStageSwitch(
        context: Context,
        outgoingTasks: List<Pair<StageTask, WindowBounds>>,
        incomingTasks: List<Pair<StageTask, WindowBounds>>,
    ) {
        if (outgoingTasks.isEmpty() && incomingTasks.isEmpty()) return

        mainHandler.post {
            runCatching {
                dismissImmediate()
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                val dm = context.resources.displayMetrics
                val density = dm.density
                val screenH = dm.heightPixels

                val root = FrameLayout(context).apply {
                    clipChildren = false
                    clipToPadding = false
                    setBackgroundColor(Color.TRANSPARENT)
                }

                // Target anchor in the left slanted 3D Recent Strip
                val stripAnchorX = dp(context, 92).toFloat()
                val outgoingStripY = screenH * 0.34f
                val incomingStripY = screenH * 0.50f
                val cornerPx = dp(context, 24).toFloat()

                // 1. Outgoing Stage cards: glide from live bounds -> tilted 3D left strip
                outgoingTasks.forEachIndexed { idx, (task, bounds) ->
                    if (bounds.isEmpty) return@forEachIndexed
                    val card = createSnapshotCard(context, task, bounds, cornerPx, density, forceRefresh = true)
                    root.addView(card)

                    val targetScaleX = (dp(context, 142).toFloat() / bounds.width.coerceAtLeast(1)).coerceIn(0.12f, 0.45f)
                    val targetScaleY = (dp(context, 102).toFloat() / bounds.height.coerceAtLeast(1)).coerceIn(0.12f, 0.45f)
                    val targetScale = minOf(targetScaleX, targetScaleY)
                    val dx = stripAnchorX - bounds.left + idx * dp(context, 14)
                    val dy = outgoingStripY - bounds.top + idx * dp(context, 10)

                    card.pivotX = 0f
                    card.pivotY = bounds.height * 0.5f
                    card.animate()
                        .translationX(dx)
                        .translationY(dy)
                        .scaleX(targetScale)
                        .scaleY(targetScale)
                        .rotationY(26f)
                        .alpha(0f)
                        .setDuration(270L)
                        .setInterpolator(magicInterpolator)
                        .start()
                }

                // 2. Incoming Stage cards: emerge from tilted 3D left strip -> live bounds
                incomingTasks.forEachIndexed { idx, (task, bounds) ->
                    if (bounds.isEmpty) return@forEachIndexed
                    val card = createSnapshotCard(context, task, bounds, cornerPx, density, forceRefresh = false)
                    root.addView(card)

                    val startScaleX = (dp(context, 142).toFloat() / bounds.width.coerceAtLeast(1)).coerceIn(0.14f, 0.45f)
                    val startScaleY = (dp(context, 102).toFloat() / bounds.height.coerceAtLeast(1)).coerceIn(0.14f, 0.45f)
                    val startScale = minOf(startScaleX, startScaleY)
                    val startDx = stripAnchorX - bounds.left + idx * dp(context, 14)
                    val startDy = incomingStripY - bounds.top + idx * dp(context, 10)

                    card.pivotX = 0f
                    card.pivotY = bounds.height * 0.5f
                    card.translationX = startDx
                    card.translationY = startDy
                    card.scaleX = startScale
                    card.scaleY = startScale
                    card.rotationY = 26f
                    card.alpha = 0.25f

                    card.animate()
                        .translationX(0f)
                        .translationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .rotationY(0f)
                        .alpha(1f)
                        .setDuration(290L)
                        .setInterpolator(magicInterpolator)
                        .start()
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

                val cleanup = Runnable {
                    root.animate()
                        .alpha(0f)
                        .setDuration(55L)
                        .withEndAction { dismissImmediate() }
                        .start()
                }
                pendingCleanup = cleanup
                mainHandler.postDelayed(cleanup, 285L)
            }.onFailure {
                HookGuard.warn("MAGIC_TRANSITION failed", it)
                dismissImmediate()
            }
        }
    }

    private fun createSnapshotCard(
        context: Context,
        task: StageTask,
        bounds: WindowBounds,
        cornerPx: Float,
        density: Float,
        forceRefresh: Boolean,
    ): FrameLayout {
        val card = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(bounds.width, bounds.height).apply {
                leftMargin = bounds.left
                topMargin = bounds.top
            }
            cameraDistance = 6400f * density
            elevation = dp(context, 16).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(Color.argb(240, 245, 242, 250))
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
                }
            }
            clipToOutline = true
        }

        val preview = snapshotLoader.loadPreview(context, task.taskId, task.packageName, forceRefresh = forceRefresh)
        val img = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            if (preview.bitmap != null) {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(preview.bitmap)
            } else {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = dp(context, 48)
                setPadding(pad, pad, pad, pad)
                setImageDrawable(preview.appIcon)
            }
        }
        card.addView(img)
        return card
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
}
