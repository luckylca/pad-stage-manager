package dev.stagelab.nativehook.ui.dock

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
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
import dev.stagelab.nativehook.ui.StageLayoutMetrics
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
    private val onRecentStageClickedFromSearch: (stageId: String) -> Unit = {},
    private val onBottomSwipeReveal: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContextRef: Context? = null
    private val appSearchOverlay: AppSearchOverlayController = AppSearchOverlayController(
        onAppSelectedLaunch = { pkg ->
            appSearchOverlay.dismiss()
            onDockAppClicked(pkg)
        },
        onAppSelectedMerge = { pkg ->
            appSearchOverlay.dismiss()
            onDockAppDraggedToCenter(pkg)
        },
        onStartAppDrag = { pkg, icon, rawX, rawY ->
            appContextRef?.let { ctx -> beginUnifiedDrag(ctx, icon, rawX, rawY, sourceIsPinnedDock = false) }
        },
        onMoveAppDrag = { pkg, rawX, rawY ->
            appContextRef?.let { ctx -> updateUnifiedDragMove(ctx, pkg, rawX, rawY, sourceIsPinnedDock = false) }
        },
        onEndAppDrag = { pkg, rawX, rawY, cancelled ->
            appContextRef?.let { ctx -> finishUnifiedDragDrop(ctx, pkg, rawX, rawY, sourceIsPinnedDock = false, cancelled = cancelled) }
        },
        findRecentStageAtPoint = findRecentStageAtPoint,
        onRecentStageTapped = onRecentStageClickedFromSearch,
    )

    private var windowManager: WindowManager? = null
    private var rootContainer: FrameLayout? = null
    private var dockCapsule: LinearLayout? = null
    private var bottomEdgeHandle: View? = null
    private var insertBarView: View? = null
    private var dragGhostRoot: FrameLayout? = null
    private var dragGhostIcon: ImageView? = null
    private var unpinPillView: TextView? = null
    private var isAttached = false

    private val pinnedPackages = ArrayList<String>()
    private var pinnedLoadedFromPrefs = false
    private val recentPackages = ArrayList<String>()
    private val pinnedSlotViews = ArrayList<Pair<String, View>>()
    private var lastSession: StageSession = StageSession.EMPTY
    private var hiddenBySetting = false
    private var temporarilyRevealed = false
    private var lastHoveredStageId: String? = null
    private var activePreviewInsertIdx: Int = -1
    private var unpinPillHovered = false

    fun update(
        context: Context,
        session: StageSession,
        enabled: Boolean,
        hidden: Boolean,
        tempRevealed: Boolean,
    ) {
        appContextRef = context.applicationContext
        lastSession = session
        hiddenBySetting = hidden
        temporarilyRevealed = tempRevealed

        ensurePinnedLoaded(context)

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

    fun dismissAppSearchIfShowing(): Boolean = appSearchOverlay.dismissIfShowing()

    fun onDisplayConfigChanged(context: Context) {
        mainHandler.post {
            appSearchOverlay.onDisplayConfigChanged(context)
            if (isAttached) {
                render(context, lastSession)
            }
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
            insertBarView = null
            dragGhostRoot = null
            dragGhostIcon = null
            unpinPillView = null
            pinnedSlotViews.clear()
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

        val unpinPill = TextView(context).apply {
            text = "拖到此处移出 Dock"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(73, 69, 79))
            setPadding(dp(context, 22), dp(context, 10), dp(context, 22), dp(context, 10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 999).toFloat()
                setColor(Color.argb(242, 243, 237, 247))
                setStroke(dp(context, 1), Color.argb(120, 121, 116, 126))
            }
            elevation = dp(context, 10).toFloat()
            visibility = View.GONE
        }
        ghostRoot.addView(
            unpinPill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply {
                topMargin = dp(context, 34)
            },
        )

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
            unpinPillView = unpinPill
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

        val root = object : FrameLayout(context) {
            override fun onConfigurationChanged(newConfig: Configuration) {
                super.onConfigurationChanged(newConfig)
                onDisplayConfigChanged(context)
            }
        }.apply {
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

        val metrics = StageLayoutMetrics.fromContext(context)
        root.addView(
            capsule,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin = dp(context, 14) + metrics.extraNavBottomPx
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
            ).apply {
                bottomMargin = metrics.extraNavBottomPx
            },
        )

        val insertBar = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 999).toFloat()
                setColor(Color.rgb(103, 80, 164))
            }
            visibility = View.INVISIBLE
        }
        root.addView(
            insertBar,
            FrameLayout.LayoutParams(dp(context, 4), dp(context, 44), Gravity.BOTTOM or Gravity.START).apply {
                bottomMargin = dp(context, 29) + metrics.extraNavBottomPx
            },
        )
        insertBarView = insertBar

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
        pinnedSlotViews.clear()
        val allCandidateRecents = recentPackages.filterNot { pinnedPackages.contains(it) }
        val metrics = StageLayoutMetrics.fromContext(context)
        val screenW = metrics.screenW
        val maxCapsuleW = (screenW - dp(context, 36) - metrics.safeLeftPx - metrics.safeRightPx).coerceAtLeast(dp(context, 280))
        (capsule.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin = dp(context, 14) + metrics.extraNavBottomPx
        (insertBarView?.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin = dp(context, 29) + metrics.extraNavBottomPx
        (bottomEdgeHandle?.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin = metrics.extraNavBottomPx

        // Adapt icon size, spacing, and visible recent slots cleanly so portrait <-> landscape rotations
        // NEVER overflow screen edges or squish Dock icons together.
        var recentsToShow = allCandidateRecents.take(MAX_RECENT_ICONS)
        var iconDp = 48
        var gapDp = if (pinnedPackages.size + recentsToShow.size >= 9) 7 else 10
        var padHdp = 14

        fun estimateWidth(iconSizeDp: Int, spacingDp: Int, padDp: Int, recentCount: Int): Int {
            val totalIcons = 1 + pinnedPackages.size + recentCount
            val dividers = if (recentCount > 0) 2 else 1
            val gaps = (totalIcons - 1 - dividers) + dividers * 2
            return dp(context, padDp * 2 + totalIcons * iconSizeDp + gaps * spacingDp + dividers * 3)
        }

        if (estimateWidth(iconDp, gapDp, padHdp, recentsToShow.size) > maxCapsuleW) {
            iconDp = 44
            gapDp = 6
            padHdp = 12
        }
        if (estimateWidth(iconDp, gapDp, padHdp, recentsToShow.size) > maxCapsuleW) {
            iconDp = 40
            gapDp = 5
            padHdp = 10
        }
        while (recentsToShow.isNotEmpty() && estimateWidth(iconDp, gapDp, padHdp, recentsToShow.size) > maxCapsuleW) {
            recentsToShow = recentsToShow.dropLast(1)
        }
        if (estimateWidth(iconDp, gapDp, padHdp, recentsToShow.size) > maxCapsuleW) {
            iconDp = 36
            gapDp = 4
        }

        capsule.setPadding(dp(context, padHdp), dp(context, 10), dp(context, padHdp), dp(context, 10))
        val iconPx = dp(context, iconDp)
        val iconGapPx = dp(context, gapDp)

        // 0. App Search / App Library button on the left of the Dock
        val searchButton = buildAppSearchButton(context, iconPx)
        capsule.addView(searchButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val leftDivider = View(context).apply {
            setBackgroundColor(Color.argb(72, 28, 27, 31))
        }
        capsule.addView(
            leftDivider,
            LinearLayout.LayoutParams(dp(context, 1), (iconPx * 0.72f).toInt()).apply {
                leftMargin = iconGapPx
                rightMargin = iconGapPx
            },
        )

        // 1. Pinned apps
        pinnedPackages.forEachIndexed { idx, pkg ->
            val item = buildDockIconItem(
                context = context,
                pkg = pkg,
                isPinned = true,
                isActive = activePkgs.contains(pkg),
                isOpen = livePkgs.contains(pkg),
                iconPx = iconPx,
            )
            pinnedSlotViews.add(pkg to item)
            capsule.addView(
                item,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (idx > 0) leftMargin = iconGapPx
                },
            )
        }

        // 2. Divider + Recent apps
        if (recentsToShow.isNotEmpty()) {
            val divider = View(context).apply {
                setBackgroundColor(Color.argb(72, 28, 27, 31))
            }
            capsule.addView(
                divider,
                LinearLayout.LayoutParams(dp(context, 1), (iconPx * 0.72f).toInt()).apply {
                    leftMargin = iconGapPx + dp(context, 2)
                    rightMargin = iconGapPx + dp(context, 2)
                },
            )

            recentsToShow.forEachIndexed { idx, pkg ->
                val item = buildDockIconItem(
                    context = context,
                    pkg = pkg,
                    isPinned = false,
                    isActive = activePkgs.contains(pkg),
                    isOpen = livePkgs.contains(pkg),
                    iconPx = iconPx,
                )
                capsule.addView(
                    item,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (idx > 0) leftMargin = iconGapPx
                    },
                )
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildAppSearchButton(context: Context, btnSize: Int = dp(context, 48)): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val circle = TextView(context).apply {
            text = "⌕"
            textSize = if (btnSize < dp(context, 42)) 20f else 24f
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
        isPinned: Boolean,
        isActive: Boolean,
        isOpen: Boolean,
        iconPx: Int = dp(context, 48),
    ): View {
        val radiusPx = (iconPx * 0.27f)

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
        val dragSlopPx = dp(context, 14).toFloat()

        col.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    isDragging = false
                    ensureDragGhost(context)
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > dragSlopPx) {
                        isDragging = true
                        appSearchOverlay.dismissIfShowing()
                        v.animate().cancel()
                        v.scaleX = 0.92f
                        v.scaleY = 0.92f
                        v.alpha = 0.28f
                        beginUnifiedDrag(context, iconView.drawable, event.rawX, event.rawY, sourceIsPinnedDock = isPinned)
                    }
                    if (isDragging) {
                        updateUnifiedDragMove(context, pkg, event.rawX, event.rawY, sourceIsPinnedDock = isPinned)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start()
                    if (isDragging) {
                        isDragging = false
                        finishUnifiedDragDrop(context, pkg, event.rawX, event.rawY, sourceIsPinnedDock = isPinned, cancelled = false)
                    } else if (hypot(dx.toDouble(), dy.toDouble()) <= dragSlopPx) {
                        appSearchOverlay.dismissIfShowing()
                        HookGuard.info("STAGE_DOCK tap launch pkg=$pkg")
                        onDockAppClicked(pkg)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        finishUnifiedDragDrop(context, pkg, event.rawX, event.rawY, sourceIsPinnedDock = isPinned, cancelled = true)
                    }
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
                    true
                }
                else -> false
            }
        }

        return col
    }

    /**
     * Unified 120fps drag-and-drop engine shared between Dock icons & App Search icons.
     */
    private fun beginUnifiedDrag(
        context: Context,
        icon: Drawable?,
        rawX: Float,
        rawY: Float,
        sourceIsPinnedDock: Boolean,
    ) {
        val ghost = ensureDragGhost(context) ?: return
        val ghostHalfPx = dp(context, 58) / 2f
        lastHoveredStageId = null
        activePreviewInsertIdx = -1
        unpinPillHovered = false

        ghost.setImageDrawable(icon)
        ghost.scaleX = 1.08f
        ghost.scaleY = 1.08f
        ghost.elevation = dp(context, 12).toFloat()
        ghost.translationX = rawX - ghostHalfPx
        ghost.translationY = rawY - ghostHalfPx

        unpinPillView?.let { pill ->
            pill.visibility = if (sourceIsPinnedDock) View.VISIBLE else View.GONE
            if (sourceIsPinnedDock) {
                pill.alpha = 0f
                pill.translationY = -dp(context, 16).toFloat()
                updateUnpinPillStyle(pill, hovered = false)
                pill.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(160).start()
            }
        }
        dragGhostRoot?.visibility = View.VISIBLE
    }

    private fun updateUnifiedDragMove(
        context: Context,
        @Suppress("UNUSED_PARAMETER") pkg: String,
        rawX: Float,
        rawY: Float,
        sourceIsPinnedDock: Boolean,
    ) {
        val ghostHalfPx = dp(context, 58) / 2f
        dragGhostIcon?.let { ghost ->
            ghost.translationX = rawX - ghostHalfPx
            ghost.translationY = rawY - ghostHalfPx
        }

        val inUnpinPill = sourceIsPinnedDock && isPointInTopUnpinPill(context, rawX, rawY)
        val inSideUnpinZone = sourceIsPinnedDock && isPointInSideUnpinBottomZone(context, rawX, rawY)
        val inDockZone = isPointInDockDropZone(context, rawX, rawY)
        val hoveredStage = if (!inUnpinPill && !inDockZone) findRecentStageAtPoint(rawX, rawY) else null

        if (inUnpinPill != unpinPillHovered) {
            unpinPillHovered = inUnpinPill
            unpinPillView?.let { updateUnpinPillStyle(it, hovered = inUnpinPill) }
        }
        dragGhostIcon?.alpha = if (inUnpinPill || inSideUnpinZone) 0.55f else 1.0f

        if (inDockZone && !inUnpinPill && !inSideUnpinZone) {
            val insertIdx = computeDockInsertIndex(rawX)
            applyDockInsertPreview(context, insertIdx)
        } else {
            clearDockInsertPreview(context)
        }

        if (hoveredStage != lastHoveredStageId) {
            lastHoveredStageId = hoveredStage
            onHoverRecentStage(hoveredStage)
        }
    }

    private fun finishUnifiedDragDrop(
        context: Context,
        rawPkg: String,
        rawX: Float,
        rawY: Float,
        sourceIsPinnedDock: Boolean,
        cancelled: Boolean,
    ) {
        val pkg = normalizePackageName(rawPkg)
        dragGhostRoot?.visibility = View.INVISIBLE
        dragGhostIcon?.alpha = 1.0f
        unpinPillView?.visibility = View.GONE
        unpinPillHovered = false
        clearDockInsertPreview(context)
        if (lastHoveredStageId != null) {
            lastHoveredStageId = null
            onHoverRecentStage(null)
        }
        if (cancelled) return

        val dropUnpinTop = sourceIsPinnedDock && isPointInTopUnpinPill(context, rawX, rawY)
        val dropUnpinSide = sourceIsPinnedDock && isPointInSideUnpinBottomZone(context, rawX, rawY)
        val dropDock = isPointInDockDropZone(context, rawX, rawY)
        val targetStageId = if (!dropDock) findRecentStageAtPoint(rawX, rawY) else null
        val metrics = StageLayoutMetrics.fromContext(context)
        val screenH = metrics.screenH

        when {
            dropUnpinTop || dropUnpinSide -> {
                unpinPackageFromDock(context, pkg)
            }
            dropDock -> {
                val insertIdx = computeDockInsertIndex(rawX)
                pinOrReorderInDock(context, pkg, insertIdx)
            }
            targetStageId != null -> {
                HookGuard.info("STAGE_DOCK free-drop pkg=$pkg -> recentStage=$targetStageId")
                onDockAppDroppedOnStage(pkg, targetStageId)
            }
            rawY < screenH - dp(context, 118) - metrics.extraNavBottomPx -> {
                HookGuard.info("STAGE_DOCK free-drop pkg=$pkg -> activeStage")
                onDockAppDraggedToCenter(pkg)
            }
            else -> {
                HookGuard.info("STAGE_DOCK free-drop cancelled in bottom zone pkg=$pkg")
            }
        }
    }

    private fun updateUnpinPillStyle(pill: TextView, hovered: Boolean) {
        val bg = pill.background as? GradientDrawable ?: return
        if (hovered) {
            pill.text = "松手移出 Dock"
            pill.setTextColor(Color.rgb(186, 26, 26))
            bg.setColor(Color.argb(248, 255, 218, 214))
            bg.setStroke(dp(pill.context, 1), Color.argb(200, 186, 26, 26))
            pill.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
        } else {
            pill.text = "拖到此处移出 Dock"
            pill.setTextColor(Color.rgb(73, 69, 79))
            bg.setColor(Color.argb(242, 243, 237, 247))
            bg.setStroke(dp(pill.context, 1), Color.argb(120, 121, 116, 126))
            pill.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
    }

    private fun isPointInTopUnpinPill(context: Context, rawX: Float, rawY: Float): Boolean {
        val metrics = StageLayoutMetrics.fromContext(context)
        val topZone = maxOf(dp(context, 112), metrics.safeTopPx + dp(context, 84))
        return rawY <= topZone && abs(rawX - metrics.screenW * 0.5f) <= dp(context, 160)
    }

    private fun isPointInSideUnpinBottomZone(context: Context, rawX: Float, rawY: Float): Boolean {
        val capsule = dockCapsule ?: return false
        val metrics = StageLayoutMetrics.fromContext(context)
        val screenH = metrics.screenH
        if (rawY < screenH - dp(context, 132) - metrics.extraNavBottomPx) return false
        val loc = IntArray(2)
        capsule.getLocationOnScreen(loc)
        val margin = dp(context, 78)
        return rawX < loc[0] - margin || rawX > loc[0] + capsule.width + margin
    }

    private fun isPointInDockDropZone(context: Context, rawX: Float, rawY: Float): Boolean {
        val capsule = dockCapsule ?: return false
        if (capsule.visibility != View.VISIBLE || capsule.width <= 0) return false
        val metrics = StageLayoutMetrics.fromContext(context)
        val screenH = metrics.screenH
        val loc = IntArray(2)
        capsule.getLocationOnScreen(loc)
        val padX = dp(context, 38)
        val dockTop = (loc[1] - dp(context, 44)).coerceAtMost(screenH - dp(context, 125) - metrics.extraNavBottomPx)
        return rawY >= dockTop && rawX >= loc[0] - padX && rawX <= loc[0] + capsule.width + padX
    }

    private fun computeDockInsertIndex(rawX: Float): Int {
        if (pinnedSlotViews.isEmpty()) return 0
        val loc = IntArray(2)
        pinnedSlotViews.forEachIndexed { idx, (_, view) ->
            view.getLocationOnScreen(loc)
            val centerX = loc[0] + view.width * 0.5f
            if (rawX < centerX) return idx
        }
        return pinnedSlotViews.size
    }

    private fun applyDockInsertPreview(context: Context, insertIdx: Int) {
        if (insertIdx == activePreviewInsertIdx) return
        activePreviewInsertIdx = insertIdx

        val shiftPx = dp(context, 6).toFloat()
        pinnedSlotViews.forEachIndexed { idx, (_, view) ->
            val targetShift = if (idx < insertIdx) -shiftPx else shiftPx
            view.animate().translationX(targetShift).setDuration(110).start()
        }

        val root = rootContainer ?: return
        val bar = insertBarView ?: return
        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)

        val boundaryScreenX: Float = when {
            pinnedSlotViews.isEmpty() -> {
                val cap = dockCapsule
                if (cap != null) {
                    val cLoc = IntArray(2)
                    cap.getLocationOnScreen(cLoc)
                    cLoc[0] + dp(context, 80).toFloat()
                } else {
                    rootLoc[0] + root.width * 0.5f
                }
            }
            insertIdx <= 0 -> {
                val loc = IntArray(2)
                pinnedSlotViews.first().second.getLocationOnScreen(loc)
                loc[0] - dp(context, 6).toFloat()
            }
            insertIdx >= pinnedSlotViews.size -> {
                val last = pinnedSlotViews.last().second
                val loc = IntArray(2)
                last.getLocationOnScreen(loc)
                loc[0] + last.width + dp(context, 6).toFloat()
            }
            else -> {
                val leftView = pinnedSlotViews[insertIdx - 1].second
                val rightView = pinnedSlotViews[insertIdx].second
                val lLoc = IntArray(2)
                val rLoc = IntArray(2)
                leftView.getLocationOnScreen(lLoc)
                rightView.getLocationOnScreen(rLoc)
                ((lLoc[0] + leftView.width) + rLoc[0]) * 0.5f
            }
        }
        bar.translationX = boundaryScreenX - rootLoc[0] - bar.width * 0.5f
        bar.visibility = View.VISIBLE

        (dockCapsule?.background as? GradientDrawable)?.setStroke(dp(context, 2), Color.argb(210, 103, 80, 164))
    }

    private fun clearDockInsertPreview(context: Context) {
        if (activePreviewInsertIdx == -1) return
        activePreviewInsertIdx = -1
        insertBarView?.visibility = View.INVISIBLE
        pinnedSlotViews.forEach { (_, view) ->
            view.animate().translationX(0f).setDuration(120).start()
        }
        (dockCapsule?.background as? GradientDrawable)?.setStroke(dp(context, 1), Color.argb(145, 255, 255, 255))
    }

    private fun pinOrReorderInDock(context: Context, rawPkg: String, rawInsertIdx: Int) {
        val pkg = normalizePackageName(rawPkg)
        val oldIdx = pinnedPackages.indexOf(pkg)
        if (oldIdx >= 0) {
            val targetIdx = (if (rawInsertIdx > oldIdx) rawInsertIdx - 1 else rawInsertIdx)
                .coerceIn(0, (pinnedPackages.size - 1).coerceAtLeast(0))
            if (targetIdx != oldIdx) {
                pinnedPackages.removeAt(oldIdx)
                pinnedPackages.add(targetIdx, pkg)
                savePinnedPackages(context)
                render(context, lastSession)
                HookGuard.info("STAGE_DOCK reordered pkg=$pkg $oldIdx -> $targetIdx")
            }
            return
        }

        val maxCap = maxPinnedCapacity(context)
        while (pinnedPackages.size >= maxCap && pinnedPackages.isNotEmpty()) {
            pinnedPackages.removeAt(pinnedPackages.lastIndex)
        }
        val insertIdx = rawInsertIdx.coerceIn(0, pinnedPackages.size)
        pinnedPackages.add(insertIdx, pkg)
        recentPackages.remove(pkg)
        savePinnedPackages(context)
        render(context, lastSession)
        HookGuard.info("STAGE_DOCK pinned pkg=$pkg at idx=$insertIdx (total=${pinnedPackages.size})")
    }

    private fun unpinPackageFromDock(context: Context, rawPkg: String) {
        val pkg = normalizePackageName(rawPkg)
        if (pinnedPackages.remove(pkg)) {
            savePinnedPackages(context)
            val isLive = lastSession.activeStage?.tasks?.any { normalizePackageName(it.packageName) == pkg } == true ||
                lastSession.allRecentStages.any { st -> st.tasks.any { normalizePackageName(it.packageName) == pkg } }
            if (isLive && !recentPackages.contains(pkg)) {
                recentPackages.add(0, pkg)
                while (recentPackages.size > MAX_RECENT_ICONS) recentPackages.removeAt(recentPackages.lastIndex)
            }
            render(context, lastSession)
            HookGuard.info("STAGE_DOCK unpinned pkg=$pkg (remaining=${pinnedPackages.size})")
        }
    }

    private fun maxPinnedCapacity(context: Context): Int =
        StageLayoutMetrics.fromContext(context).maxPinnedDockCount

    private fun prefsContext(context: Context): Context {
        return if (context.isDeviceProtectedStorage) {
            context
        } else {
            runCatching { context.createDeviceProtectedStorageContext() }.getOrDefault(context)
        }
    }

    private fun ensurePinnedLoaded(context: Context) {
        if (pinnedLoadedFromPrefs) return
        pinnedLoadedFromPrefs = true
        val prefs = prefsContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        pinnedPackages.clear()
        if (prefs.contains(KEY_PINNED_PACKAGES)) {
            val saved = prefs.getString(KEY_PINNED_PACKAGES, "").orEmpty()
                .split(',')
                .map { normalizePackageName(it.trim()) }
                .filter { it.isNotBlank() }
                .distinct()
            pinnedPackages.addAll(saved)
        } else {
            pinnedPackages.addAll(resolveDefaultPinnedPackages(context))
            savePinnedPackages(context)
        }
    }

    private fun savePinnedPackages(context: Context) {
        val prefs = prefsContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PINNED_PACKAGES, pinnedPackages.joinToString(",")).apply()
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
        // Broad CN + Global candidate list across OPPO ColorOS, OnePlus OxygenOS, realme UI & Google Tablets
        val candidates = listOf(
            "com.heytap.browser",
            "com.android.chrome",
            "mark.via",
            "com.coloros.note",
            "com.oneplus.note",
            "com.oplus.notes",
            "com.google.android.keep",
            "com.coloros.filemanager",
            "com.oneplus.filemanager",
            "com.google.android.apps.nbu.files",
            "com.google.android.documentsui",
            "tv.danmaku.bili",
            "com.coloros.calendar",
            "com.google.android.calendar",
            "com.coloros.calculator",
            "com.oneplus.calculator",
            "com.google.android.calculator",
            "com.coloros.alarmclock",
            "com.android.settings",
        )
        return candidates.filter { pkg ->
            runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        }.take(MAX_PINNED_ICONS)
    }

    private fun normalizePackageName(pkg: String): String = when (pkg) {
        "com.oplus.wirelesssettings",
        "com.oplus.settings",
        "com.coloros.settings",
        -> "com.android.settings"
        else -> pkg
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    companion object {
        private const val PREFS_NAME = "stagelab_dock_prefs"
        private const val KEY_PINNED_PACKAGES = "pinned_packages_v1"
        private const val MAX_PINNED_ICONS = 6
        private const val MAX_RECENT_ICONS = 3
    }
}
