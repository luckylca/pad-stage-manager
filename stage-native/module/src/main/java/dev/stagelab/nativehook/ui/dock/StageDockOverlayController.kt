package dev.stagelab.nativehook.ui.dock

import android.annotation.SuppressLint
import android.content.Context
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.stagelab.nativecore.StageSession
import dev.stagelab.nativehook.xposed.HookGuard
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Custom iPadOS-style frosted glass floating Dock inside `com.android.systemui`.
 *
 * Features:
 * - Pinned favorite apps + vertical divider + up to 3 recent apps with active-stage indicator dots.
 * - Tap app icon -> S1 (activate owning Stage or launch new Stage).
 * - Drag icon upward into the center stage -> S3 (merge into current Active Stage / S4 overflow).
 * - Supports hide setting with bottom-edge swipe-up temporary reveal.
 * - Automatically hides on lockscreen and cleanly removes itself when module is disabled.
 */
class StageDockOverlayController(
    private val onDockAppClicked: (packageName: String) -> Unit,
    private val onDockAppDraggedToCenter: (packageName: String) -> Unit,
    private val onDockAppDroppedOnStage: (packageName: String, stageId: String) -> Unit = { pkg, _ -> onDockAppDraggedToCenter(pkg) },
    private val findRecentStageAtPoint: (rawX: Float, rawY: Float) -> String? = { _, _ -> null },
    private val onHoverRecentStage: (stageId: String?) -> Unit = {},
    private val onBottomSwipeReveal: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appSearchOverlay = AppSearchOverlayController(
        onAppSelectedLaunch = onDockAppClicked,
        onAppSelectedMerge = onDockAppDraggedToCenter,
    )

    private var windowManager: WindowManager? = null
    private var rootContainer: FrameLayout? = null
    private var dockCapsule: LinearLayout? = null
    private var bottomEdgeHandle: View? = null
    private var dragGhostRoot: FrameLayout? = null
    private var dragGhostIcon: ImageView? = null
    private var isAttached = false

    private var pinnedPackages: List<String> = emptyList()
    private val recentPackages = ArrayList<String>()
    private var lastSession: StageSession = StageSession.EMPTY
    private var hiddenBySetting = false
    private var temporarilyRevealed = false

    fun update(
        context: Context,
        session: StageSession,
        enabled: Boolean,
        hidden: Boolean,
        tempRevealed: Boolean,
    ) {
        lastSession = session
        hiddenBySetting = hidden
        temporarilyRevealed = tempRevealed

        if (pinnedPackages.isEmpty()) {
            pinnedPackages = resolveDefaultPinnedPackages(context)
        }

        // Collect recent app packages from active & recent stages (excluding pinned apps to prevent duplicates)
        val latestPkgs = buildList {
            session.activeStage?.tasks?.forEach { add(normalizePackageName(it.packageName)) }
            session.allRecentStages.forEach { st -> st.tasks.forEach { add(normalizePackageName(it.packageName)) } }
        }.distinct()
        for (pkg in latestPkgs) {
            if (!pinnedPackages.contains(pkg)) {
                recentPackages.remove(pkg)
                recentPackages.add(0, pkg)
            }
        }
        recentPackages.removeAll { pinnedPackages.contains(it) }
        while (recentPackages.size > MAX_RECENT_ICONS) {
            recentPackages.removeAt(recentPackages.lastIndex)
        }

        mainHandler.post {
            if (!enabled || !HookGuard.isActive()) {
                detach()
                return@post
            }
            ensureAttached(context)
            appSearchOverlay.prewarm(context)
            render(context, session)
        }
    }

    fun detach() {
        appSearchOverlay.destroy()
        mainHandler.post {
            val wm = windowManager
            val root = rootContainer
            if (isAttached && wm != null && root != null) {
                runCatching { wm.removeViewImmediate(root) }
            }
            dragGhostRoot?.let { ghost ->
                if (wm != null) runCatching { wm.removeViewImmediate(ghost) }
            }
            isAttached = false
            rootContainer = null
            dockCapsule = null
            bottomEdgeHandle = null
            dragGhostRoot = null
            dragGhostIcon = null
        }
    }

    private fun ensureDragGhost(context: Context): ImageView? {
        dragGhostIcon?.let { return it }
        val wm = windowManager ?: return null
        val ghostSize = dp(context, 58)
        val ghostRadius = dp(context, 16).toFloat()
        val ghostRoot = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.INVISIBLE
        }
        val ghostIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, ghostRadius)
                }
            }
            clipToOutline = true
        }
        ghostRoot.addView(ghostIcon, FrameLayout.LayoutParams(ghostSize, ghostSize, Gravity.TOP or Gravity.START))

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            title = "StageLabDragGhost"
        }
        return runCatching {
            wm.addView(ghostRoot, lp)
            dragGhostRoot = ghostRoot
            dragGhostIcon = ghostIcon
            ghostIcon
        }.getOrNull()
    }

    private fun ensureAttached(context: Context) {
        if (isAttached && rootContainer != null) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        // Attach StageLabAppSearch FIRST (persistent INVISIBLE window) so StageLabDock ALWAYS sits
        // strictly ABOVE StageLabAppSearch in Z-order and stays clickable to interrupt open/close!
        appSearchOverlay.ensureWindowAttached(context)

        val root = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
        }

        val capsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 26).toFloat()
                setColor(Color.argb(188, 244, 240, 252))
                setStroke(dp(context, 1), Color.argb(145, 255, 255, 255))
            }
            elevation = dp(context, 10).toFloat()
        }

        val edgeHandle = createBottomRevealHandle(context)

        root.addView(
            capsule,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin = dp(context, 14)
                leftMargin = dp(context, 16)
                rightMargin = dp(context, 16)
                topMargin = dp(context, 20)
            },
        )

        root.addView(
            edgeHandle,
            FrameLayout.LayoutParams(
                dp(context, 240),
                dp(context, 18),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
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
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            title = "StageLabDock"
        }

        runCatching {
            wm.addView(root, lp)
            rootContainer = root
            dockCapsule = capsule
            bottomEdgeHandle = edgeHandle
            isAttached = true
            HookGuard.info("STAGE_DOCK attached to SystemUI WindowManager")
        }.onFailure {
            HookGuard.warn("STAGE_DOCK failed to attach", it)
        }
    }

    private fun render(context: Context, session: StageSession) {
        val capsule = dockCapsule ?: return
        val edge = bottomEdgeHandle ?: return

        val showDock = !hiddenBySetting || temporarilyRevealed
        capsule.visibility = if (showDock) View.VISIBLE else View.GONE
        edge.visibility = if (showDock) View.GONE else View.VISIBLE
        if (!showDock) return

        val activePkgs = session.activeStage?.tasks?.map { it.packageName }?.toSet().orEmpty()
        val livePkgs = buildSet {
            addAll(activePkgs)
            session.allRecentStages.forEach { st -> st.tasks.forEach { add(it.packageName) } }
        }

        capsule.removeAllViews()

        // 0. App Search / App Library button on the left of the Dock
        val searchButton = buildAppSearchButton(context)
        capsule.addView(searchButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val leftDivider = View(context).apply {
            setBackgroundColor(Color.argb(72, 28, 27, 31))
        }
        capsule.addView(
            leftDivider,
            LinearLayout.LayoutParams(dp(context, 1), dp(context, 34)).apply {
                leftMargin = dp(context, 10)
                rightMargin = dp(context, 10)
            },
        )

        // 1. Pinned apps
        pinnedPackages.forEachIndexed { idx, pkg ->
            val item = buildDockIconItem(
                context = context,
                pkg = pkg,
                isActive = activePkgs.contains(pkg),
                isOpen = livePkgs.contains(pkg),
            )
            capsule.addView(
                item,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (idx > 0) leftMargin = dp(context, 10)
                },
            )
        }

        // 2. Divider + Recent apps
        val recentsToShow = recentPackages.filterNot { pinnedPackages.contains(it) }.take(MAX_RECENT_ICONS)
        if (recentsToShow.isNotEmpty()) {
            val divider = View(context).apply {
                setBackgroundColor(Color.argb(72, 28, 27, 31))
            }
            capsule.addView(
                divider,
                LinearLayout.LayoutParams(dp(context, 1), dp(context, 34)).apply {
                    leftMargin = dp(context, 12)
                    rightMargin = dp(context, 12)
                },
            )

            recentsToShow.forEachIndexed { idx, pkg ->
                val item = buildDockIconItem(
                    context = context,
                    pkg = pkg,
                    isActive = activePkgs.contains(pkg),
                    isOpen = livePkgs.contains(pkg),
                )
                capsule.addView(
                    item,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (idx > 0) leftMargin = dp(context, 10)
                    },
                )
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildAppSearchButton(context: Context): View {
        val btnSize = dp(context, 48)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val circle = TextView(context).apply {
            text = "⌕"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(103, 80, 164))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(232, 222, 248))
            }
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                appSearchOverlay.updateAnchorView(v)
            }
        }
        col.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).start()
                    HookGuard.info("STAGE_DOCK app search button ACTION_DOWN")
                    appSearchOverlay.toggle(context, circle)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    true
                }
                else -> false
            }
        }
        col.addView(circle, LinearLayout.LayoutParams(btnSize, btnSize))
        // Spacer matching the 5dp indicator dot under app icons for vertical alignment
        col.addView(
            View(context),
            LinearLayout.LayoutParams(dp(context, 5), dp(context, 5)).apply {
                topMargin = dp(context, 4)
            },
        )
        return col
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildDockIconItem(
        context: Context,
        pkg: String,
        isActive: Boolean,
        isOpen: Boolean,
    ): View {
        val iconPx = dp(context, 48)
        val radiusPx = dp(context, 13).toFloat()

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        val iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            runCatching { setImageDrawable(context.packageManager.getApplicationIcon(pkg)) }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
            clipToOutline = true
        }
        col.addView(iconView, LinearLayout.LayoutParams(iconPx, iconPx))

        // Activity indicator dot below icon (purple = in Active Stage, subtle gray = in Recent Strip)
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    when {
                        isActive -> Color.rgb(103, 80, 164)
                        isOpen -> Color.argb(130, 73, 69, 79)
                        else -> Color.TRANSPARENT
                    },
                )
            }
        }
        col.addView(
            dot,
            LinearLayout.LayoutParams(dp(context, 5), dp(context, 5)).apply {
                topMargin = dp(context, 4)
            },
        )

        var downX = 0f
        var downY = 0f
        var isDragging = false
        var lastHoveredStageId: String? = null
        val dragSlopPx = dp(context, 14).toFloat()
        val ghostHalfPx = dp(context, 58) / 2f
        val dockCancelMarginPx = dp(context, 110)

        col.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    isDragging = false
                    lastHoveredStageId = null
                    ensureDragGhost(context)
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > dragSlopPx) {
                        isDragging = true
                        v.animate().cancel()
                        v.scaleX = 0.92f
                        v.scaleY = 0.92f
                        v.alpha = 0.28f
                        val ghost = ensureDragGhost(context)
                        if (ghost != null) {
                            ghost.setImageDrawable(iconView.drawable)
                            ghost.scaleX = 1.08f
                            ghost.scaleY = 1.08f
                            ghost.elevation = dp(context, 12).toFloat()
                            ghost.translationX = event.rawX - ghostHalfPx
                            ghost.translationY = event.rawY - ghostHalfPx
                            dragGhostRoot?.visibility = View.VISIBLE
                        }
                    }
                    if (isDragging) {
                        // Direct GPU translation on hardware-layered fullscreen ghost: zero layout/WM IPC, locked 120fps
                        dragGhostIcon?.let { ghost ->
                            ghost.translationX = event.rawX - ghostHalfPx
                            ghost.translationY = event.rawY - ghostHalfPx
                        }
                        val hoveredId = findRecentStageAtPoint(event.rawX, event.rawY)
                        if (hoveredId != lastHoveredStageId) {
                            lastHoveredStageId = hoveredId
                            onHoverRecentStage(hoveredId)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start()
                    if (isDragging) {
                        isDragging = false
                        dragGhostRoot?.visibility = View.INVISIBLE
                        onHoverRecentStage(null)
                        val targetStageId = findRecentStageAtPoint(event.rawX, event.rawY)
                        val screenH = context.resources.displayMetrics.heightPixels
                        when {
                            targetStageId != null -> {
                                HookGuard.info("STAGE_DOCK free-drop pkg=$pkg -> recentStage=$targetStageId")
                                onDockAppDroppedOnStage(pkg, targetStageId)
                            }
                            event.rawY < screenH - dockCancelMarginPx -> {
                                HookGuard.info("STAGE_DOCK free-drop pkg=$pkg -> activeStage")
                                onDockAppDraggedToCenter(pkg)
                            }
                            else -> {
                                HookGuard.info("STAGE_DOCK free-drop cancelled in dock zone pkg=$pkg")
                            }
                        }
                    } else if (hypot(dx.toDouble(), dy.toDouble()) <= dragSlopPx) {
                        HookGuard.info("STAGE_DOCK tap launch pkg=$pkg")
                        onDockAppClicked(pkg)
                    }
                    lastHoveredStageId = null
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    lastHoveredStageId = null
                    dragGhostRoot?.visibility = View.INVISIBLE
                    onHoverRecentStage(null)
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
                    true
                }
                else -> false
            }
        }

        return col
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBottomRevealHandle(context: Context): View {
        return View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            var startY = 0f
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (startY - event.rawY > dp(context, 20)) {
                            onBottomSwipeReveal()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun resolveDefaultPinnedPackages(context: Context): List<String> {
        val pm = context.packageManager
        val candidates = listOf(
            "com.heytap.browser",
            "mark.via",
            "com.coloros.note",
            "com.coloros.filemanager",
            "tv.danmaku.bili",
            "com.coloros.calendar",
            "com.coloros.calculator",
            "com.coloros.alarmclock",
            "com.android.settings",
        )
        return candidates.filter { pkg ->
            runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        }.take(MAX_PINNED_ICONS)
    }

    private fun normalizePackageName(pkg: String): String = when (pkg) {
        "com.oplus.wirelesssettings", "com.oplus.settings" -> "com.android.settings"
        else -> pkg
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    companion object {
        private const val MAX_PINNED_ICONS = 6
        private const val MAX_RECENT_ICONS = 3
    }
}
