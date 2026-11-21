package dev.stagelab.android.stage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.key
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stagelab.android.ui.AppIconCache
import dev.stagelab.android.ui.DockApp
import dev.stagelab.android.ui.DockModel
import dev.stagelab.android.ui.WallpaperBackdrop
import dev.stagelab.android.windows.TaskSnapshotStore
import kotlin.math.hypot

private val Ink = Color(0xFFEAF0FA)
private val InkMuted = Color(0xFFA6B2C6)

/**
 * The iPadOS Stage Manager desktop surface: wallpaper backdrop, layered window shadows,
 * floating 3D-tilted recent-group thumbnail rail, and frosted-glass Dock.
 *
 * The real application windows are composited above this by WindowManager, while non-active
 * groups stay parked behind it at their original bounds and appear as crisp 3D snapshot cards.
 */
@Composable
fun StageWorkspaceScreen(
    state: WorkspaceState,
    dockModel: DockModel,
    wallpaper: WallpaperBackdrop,
    onGroupClick: (String) -> Unit,
    onNewGroup: () -> Unit,
    onRailScroll: (Int) -> Unit,
    onDockApp: (DockApp) -> Unit,
    onDockLongPress: (DockApp, android.graphics.PointF) -> Unit,
    onDockDismissMenu: () -> Unit,
    onDockDragMove: (android.graphics.PointF) -> Unit,
    onDockDrop: (DockApp, android.graphics.PointF) -> Unit,
    onDockDragCancel: () -> Unit,
    onAddApp: () -> Unit,
    onEmptyTap: () -> Unit
) {
    val snapshotVersion by TaskSnapshotStore.version.collectAsState()
    var dragging by remember { mutableStateOf<DockApp?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onEmptyTap
            )
    ) {
        // 1. Real system wallpaper (or atmospheric iPadOS twilight fallback).
        WallpaperLayer(wallpaper)

        // 2. Layered iPadOS window shadows (deeper for focused window, softer for unfocused).
        WindowShadows(state)

        // 3. Floating 3D-tilted recent-group snapshot thumbnails (no container box, no text labels).
        if (state.railVisible && state.groups.isNotEmpty()) {
            RecentGroupsRail(
                state = state,
                snapshotVersion = snapshotVersion,
                onGroupClick = onGroupClick
            )
        }

        if (state.windows.isEmpty()) {
            EmptyWorkspaceHint()
        }

        // 4. Frosted-glass floating iPadOS Dock.
        AnimatedVisibility(
            visible = state.dockVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(220)) { it } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(150))
        ) {
            StageDock(
                state = state,
                dockModel = dockModel,
                onDockApp = onDockApp,
                onDockLongPress = onDockLongPress,
                onDockDismissMenu = onDockDismissMenu,
                onAddApp = onAddApp,
                onDragState = { app, offset, origin ->
                    dragging = app
                    dragOffset = offset
                    dragOrigin = origin
                    val position = origin + offset
                    onDockDragMove(android.graphics.PointF(position.x, position.y))
                },
                onDragLost = { cancelled ->
                    val app = dragging
                    if (app != null && !cancelled) {
                        val position = dragOrigin + dragOffset
                        onDockDrop(app, android.graphics.PointF(position.x, position.y))
                    } else {
                        onDockDragCancel()
                    }
                    dragging = null
                }
            )
        }

        // 5. Floating Dock drag preview.
        dragging?.let { app ->
            val position = dragOrigin + dragOffset
            val dragDensity = LocalDensity.current
            Box(
                modifier = Modifier
                    .offset(
                        x = with(dragDensity) { position.x.toDp() } - 30.dp,
                        y = with(dragDensity) { position.y.toDp() } - 30.dp
                    )
                    .size(60.dp)
                    .alpha(0.92f)
                    .shadow(22.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                app.icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(60.dp)) }
            }
        }
    }
}

@Composable
private fun WallpaperLayer(wallpaper: WallpaperBackdrop) {
    val img = wallpaper.imageBitmap
    if (img != null) {
        Image(
            bitmap = img,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Subtle atmospheric scrim so window shadows and frosted surfaces read cleanly on bright wallpapers.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.14f))
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(WallpaperBackdrop.FALLBACK_TOP),
                            Color(WallpaperBackdrop.FALLBACK_MID),
                            Color(WallpaperBackdrop.FALLBACK_BOTTOM)
                        )
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0x2A4C66B8), Color(0x00000000)),
                            center = Offset(0.28f, 0.22f),
                            radius = 1600f
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0x206848A8), Color(0x00000000)),
                            center = Offset(0.78f, 0.72f),
                            radius = 1500f
                        )
                    )
            )
        }
    }
}

/**
 * Layered ambient + key shadows behind real application windows (G11).
 * Focused windows cast a noticeably deeper, wider shadow than background windows — matching iPadOS
 * without relying on coloured focus outlines.
 */
@Composable
private fun WindowShadows(state: WorkspaceState) {
    Canvas(Modifier.fillMaxSize()) {
        val radius = 15.dp.toPx()
        state.windows.forEach { window ->
            val rect = window.rect
            if (rect.width <= 0 || rect.height <= 0) return@forEach
            val layers = if (window.focused) 12 else 7
            val stepDp = if (window.focused) 2.2f else 1.7f
            val baseAlpha = if (window.focused) 0.025f else 0.017f
            val yOffset = (if (window.focused) 5.5f else 2.5f).dp.toPx()

            for (step in layers downTo 1) {
                val spread = step * stepDp.dp.toPx()
                val weight = (layers + 1 - step).toFloat() / layers.toFloat()
                val alpha = baseAlpha * (0.4f + weight * 1.1f)
                drawRoundRect(
                    color = Color.Black.copy(alpha = alpha),
                    topLeft = Offset(rect.left - spread, rect.top - spread * 0.7f + yOffset),
                    size = Size(rect.width + spread * 2, rect.height + spread * 1.85f),
                    cornerRadius = CornerRadius(radius + spread * 0.65f)
                )
            }
        }
    }
}

/**
 * Authentic iPadOS Stage Manager left recent-apps strip (G1-G5):
 * - No dark container box: thumbnails float directly over the wallpaper.
 * - No text titles, window count squares, scroll chevrons, or "+" button.
 * - Each group tile is rendered with iPadOS 3D perspective tilt (rotationY = +14°) facing the stage.
 * - Multi-window groups fan/stack subtly inside the slot with crisp app icon badges.
 */
@Composable
private fun RecentGroupsRail(
    state: WorkspaceState,
    snapshotVersion: Int,
    onGroupClick: (String) -> Unit
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    // Touch snapshotVersion so Compose recomposes when fresh TaskSnapshots arrive.
    val _version = snapshotVersion

    Box(Modifier.fillMaxSize()) {
        state.groups.forEach { group ->
            key(group.id) {
                val widthDp = with(density) { group.rect.width.toDp() }
                val heightDp = with(density) { group.rect.height.toDp() }
                val animOffset by animateIntOffsetAsState(
                    targetValue = IntOffset(group.rect.left, group.rect.top),
                    animationSpec = spring(
                        dampingRatio = 0.86f,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "rail-tile-pos"
                )

                Box(
                    modifier = Modifier
                        .offset { animOffset }
                        .width(widthDp)
                        .height(heightDp)
                        .graphicsLayer {
                            rotationY = 16f
                            cameraDistance = 12f * density.density
                            transformOrigin = TransformOrigin(0.05f, 0.5f)
                        }
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onGroupClick(group.id) }
                ) {
                    val previews = group.previews
                    if (previews.size <= 1) {
                        val primary = previews.firstOrNull()
                        RailWindowThumbnail(
                            preview = primary,
                            modifier = Modifier.fillMaxSize(),
                            showIconBadge = true,
                            context = context,
                            snapshotVersion = _version
                        )
                    } else {
                        // Multi-window group: fan up to 3 windows subtly inside the thumbnail slot.
                        val visible = previews.takeLast(3)
                        val count = visible.size
                        visible.forEachIndexed { idx, preview ->
                            val frac = idx.toFloat() / (count - 1).coerceAtLeast(1)
                            val offsetX = (widthDp.value * (0.04f + frac * 0.16f)).dp
                            val offsetY = (heightDp.value * (0.10f - frac * 0.08f)).dp
                            val cardW = (widthDp.value * 0.80f).dp
                            val cardH = (heightDp.value * 0.86f).dp
                            RailWindowThumbnail(
                                preview = preview,
                                modifier = Modifier
                                    .offset(x = offsetX, y = offsetY)
                                    .width(cardW)
                                    .height(cardH),
                                showIconBadge = idx == visible.lastIndex,
                                context = context,
                                snapshotVersion = _version
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RailWindowThumbnail(
    preview: GroupWindowPreview?,
    modifier: Modifier,
    showIconBadge: Boolean,
    context: android.content.Context,
    snapshotVersion: Int
) {
    val snap = remember(preview?.taskId, snapshotVersion) {
        preview?.let { TaskSnapshotStore.imageBitmapFor(it.taskId) }
    }
    val appIcon = remember(preview?.packageName) {
        preview?.let { AppIconCache.icon(context, it.packageName) }
    }
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .shadow(14.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Color(0x661C2230))
            .border(0.8.dp, Color(0x38FFFFFF), shape)
    ) {
        if (snap != null) {
            Image(
                bitmap = snap,
                contentDescription = preview?.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Subtle bottom-left scrim so the app icon badge pops cleanly over light app content.
            if (showIconBadge && appIcon != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .size(22.dp)
                        .shadow(6.dp, RoundedCornerShape(6.dp))
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC141922))
                ) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            // FLAG_SECURE or not yet captured: translucent frosted glass card with centered app icon.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x88242C3E), Color(0xAA161B26))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = preview?.label,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyWorkspaceHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("工作区为空", color = Ink.copy(alpha = 0.88f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "从 Dock 选择应用，或长按图标拖入工作区",
                color = InkMuted.copy(alpha = 0.85f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun StageDock(
    modifier: Modifier = Modifier,
    state: WorkspaceState,
    dockModel: DockModel,
    onDockApp: (DockApp) -> Unit,
    onDockLongPress: (DockApp, android.graphics.PointF) -> Unit,
    onDockDismissMenu: () -> Unit,
    onAddApp: () -> Unit,
    onDragState: (DockApp, Offset, Offset) -> Unit,
    onDragLost: (Boolean) -> Unit
) {
    val pinnedLimit = if (state.landscape) 7 else 4
    val recentLimit = if (state.landscape) 3 else 2
    val pinned = dockModel.pinned.take(pinnedLimit)
    val recent = dockModel.recent.take(recentLimit)

    Column(
        modifier = modifier.padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .shadow(28.dp, RoundedCornerShape(26.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xB8181E2B))
                .border(1.dp, Color(0x30FFFFFF), RoundedCornerShape(26.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            pinned.forEach { app ->
                DockIcon(
                    app = app,
                    activeInStage = app.packageName in state.activePackages,
                    runningElsewhere = app.packageName in state.runningPackages,
                    onClick = { onDockApp(app) },
                    onLongPress = { pos -> onDockLongPress(app, pos) },
                    onDismissMenu = onDockDismissMenu,
                    onDragState = onDragState,
                    onDragLost = onDragLost
                )
            }
            if (pinned.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .width(1.dp)
                        .height(36.dp)
                        .background(Color(0x33FFFFFF))
                )
            }
            recent.forEach { app ->
                DockIcon(
                    app = app,
                    activeInStage = app.packageName in state.activePackages,
                    runningElsewhere = app.packageName in state.runningPackages,
                    onClick = { onDockApp(app) },
                    onLongPress = { pos -> onDockLongPress(app, pos) },
                    onDismissMenu = onDockDismissMenu,
                    onDragState = onDragState,
                    onDragLost = onDragLost
                )
            }
            AppLibraryIcon(onClick = onAddApp)
        }
    }
}

@Composable
private fun DockIcon(
    app: DockApp,
    activeInStage: Boolean,
    runningElsewhere: Boolean,
    onClick: () -> Unit,
    onLongPress: (android.graphics.PointF) -> Unit,
    onDismissMenu: () -> Unit,
    onDragState: (DockApp, Offset, Offset) -> Unit,
    onDragLost: (Boolean) -> Unit
) {
    val density = LocalDensity.current
    val dragThresholdPx = with(density) { 18.dp.toPx() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var menuShown by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (dragging) 1.14f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "dock-scale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .onGloballyPositioned { coordinates ->
                    origin = coordinates.positionInRoot() + Offset(
                        coordinates.size.width / 2f,
                        coordinates.size.height / 2f
                    )
                }
                .pointerInput(app.packageName) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            offset = Offset.Zero
                            dragging = false
                            menuShown = true
                            onLongPress(android.graphics.PointF(origin.x, origin.y))
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            offset += amount
                            val dist = hypot(offset.x.toDouble(), offset.y.toDouble()).toFloat()
                            if (!dragging && dist > dragThresholdPx) {
                                dragging = true
                                if (menuShown) {
                                    menuShown = false
                                    onDismissMenu()
                                }
                            }
                            if (dragging) {
                                onDragState(app, offset, origin)
                            }
                        },
                        onDragEnd = {
                            if (dragging) {
                                dragging = false
                                onDragLost(false)
                            }
                            menuShown = false
                        },
                        onDragCancel = {
                            if (dragging) {
                                dragging = false
                                onDragLost(true)
                            }
                            menuShown = false
                        }
                    )
                }
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            val icon = app.icon
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = app.label,
                    modifier = Modifier
                        .size((50 * scale).dp)
                        .clip(RoundedCornerShape(13.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color(0x33FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(app.label.take(1), color = Ink, fontSize = 18.sp)
                }
            }
        }
        val dotColor = when {
            activeInStage -> Ink
            runningElsewhere -> Ink.copy(alpha = 0.42f)
            else -> Color.Transparent
        }
        Box(
            modifier = Modifier
                .size(4.5.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}

/** iPad-style 2x2 mini-grid App Library tile at the right end of the Dock. */
@Composable
private fun AppLibraryIcon(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .padding(2.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color(0x28FFFFFF))
                .border(0.8.dp, Color(0x24FFFFFF), RoundedCornerShape(13.dp))
                .clickable(onClick = onClick)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MiniAppTile(Color(0xFF6FA8FF))
                    MiniAppTile(Color(0xFFFFB74D))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MiniAppTile(Color(0xFF7CE09B))
                    MiniAppTile(Color(0xFFFF7A90))
                }
            }
        }
        Spacer(Modifier.size(4.5.dp))
    }
}

@Composable
private fun MiniAppTile(color: Color) {
    Box(
        modifier = Modifier
            .size(13.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.90f))
    )
}
