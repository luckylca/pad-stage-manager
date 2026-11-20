package dev.stagelab.android.stage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import dev.stagelab.android.ui.DockApp
import dev.stagelab.android.ui.DockModel
import kotlin.math.hypot
import kotlin.math.roundToInt

private val SurfaceTop = Color(0xFF141922)
private val SurfaceBottom = Color(0xFF0A0D13)
private val Ink = Color(0xFFEAF0FA)
private val InkMuted = Color(0xFF94A0B2)
private val Accent = Color(0xFF8AA0FF)

/**
 * The iPad-flavoured Stage Manager surface: desk, recent-group rail, window frames and the dock.
 *
 * The real application windows are composited above this by WindowManager, so everything here is
 * chrome: desk, tile cards, dock, and the space between windows.
 */
@Composable
fun StageWorkspaceScreen(
    state: WorkspaceState,
    dockModel: DockModel,
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
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf<DockApp?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SurfaceTop, SurfaceBottom)))
    ) {
        // Soft shadows behind the real windows. Drawn by the host, so they are visible around the
        // task instead of being covered by it.
        WindowShadows(state)

        if (state.railVisible) {
            RecentGroupsRail(
                state = state,
                onGroupClick = onGroupClick,
                onNewGroup = onNewGroup,
                onRailScroll = onRailScroll
            )
        }

        if (state.windows.isEmpty()) {
            EmptyWorkspaceHint()
        }

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

        dragging?.let { app ->
            val position = dragOrigin + dragOffset
            val dragDensity = LocalDensity.current
            Box(
                modifier = Modifier
                    .offset(
                        x = with(dragDensity) { position.x.toDp() } - 28.dp,
                        y = with(dragDensity) { position.y.toDp() } - 28.dp
                    )
                    .size(56.dp)
                    .alpha(0.85f)
                    .shadow(18.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                app.icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(56.dp)) }
            }
        }
    }
}

@Composable
private fun WindowShadows(state: WorkspaceState) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val radius = 22.dp.toPx()
        state.windows.forEach { window ->
            val rect = window.rect
            if (rect.width <= 0 || rect.height <= 0) return@forEach
            // Concentric rounded rectangles approximate a soft drop shadow without a blur filter.
            for (step in 9 downTo 1) {
                val spread = step * 1.4.dp.toPx()
                val alpha = 0.018f * (10 - step)
                drawRoundRect(
                    color = Color.Black.copy(alpha = alpha),
                    topLeft = Offset(rect.left - spread, rect.top - spread + 2.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(
                        rect.width + spread * 2,
                        rect.height + spread * 2
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius + spread)
                )
            }
        }
    }
}

@Composable
private fun DeskGlow() {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0x14384A80), Color(0x00000000)),
                    center = Offset(0.5f, 0.35f),
                    radius = 1800f
                )
            )
    )
}

@Composable
private fun RecentGroupsRail(
    state: WorkspaceState,
    onGroupClick: (String) -> Unit,
    onNewGroup: () -> Unit,
    onRailScroll: (Int) -> Unit
) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        // Rail panel: a persistent column in landscape, a drawer in portrait.
        Box(
            modifier = Modifier
                .offset(
                    x = with(density) { (state.rail.left - 4).toDp() },
                    y = with(density) { state.rail.top.toDp() }
                )
                .width(with(density) { (state.rail.width + 8).toDp() })
                .height(with(density) { state.rail.height.toDp() })
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xB30E131B))
                .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(26.dp))
        )
        if (state.groups.isEmpty()) {
            Column(
                modifier = Modifier
                    .offset(
                        x = with(density) { (state.rail.left + 12).toDp() },
                        y = with(density) { (state.rail.top + 18).toDp() }
                    )
                    .width(with(density) { (state.rail.width - 24).toDp() })
            ) {
                Text("最近工作台", color = InkMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("切换后会出现在这里", color = InkMuted.copy(alpha = 0.7f), fontSize = 9.sp)
            }
        }
        state.groups.forEach { group ->
            val widthDp = with(density) { group.rect.width.toDp() }
            val heightDp = with(density) { group.rect.height.toDp() }
            val xDp = with(density) { group.rect.left.toDp() }
            val yDp = with(density) { group.rect.top.toDp() }
            if (!group.live) {
                // Compact tile: Android's minimum window size does not fit a second live preview,
                // so older groups are represented by identity + window count.
                Box(
                    modifier = Modifier
                        .offset(x = xDp, y = yDp)
                        .width(widthDp)
                        .height(heightDp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x14FFFFFF))
                        .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(16.dp))
                        .clickable { onGroupClick(group.id) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(0x2AFFFFFF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${group.windowCount}",
                                color = Ink,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                group.title,
                                color = Ink,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (group.windowCount > 0) "轻点切换" else "空工作区",
                                color = InkMuted,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
                return@forEach
            }
            Box(
                modifier = Modifier
                    .offset(x = xDp, y = yDp)
                    .width(widthDp)
                    .height(heightDp)
            ) {
                // Tile card: the real (parked) task window is inset inside this card, so the ring
                // and the caption strip drawn here stay visible around it.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .shadow(16.dp, RoundedCornerShape(22.dp))
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0x1FFFFFFF))
                        .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(22.dp))
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                ) {
                    Text(
                        group.title,
                        color = Ink,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (group.windowCount > 0) "${group.windowCount} 个窗口 · 轻点切换" else "空工作区",
                        color = InkMuted,
                        fontSize = 9.sp
                    )
                }
                // Transparent tap target on top of the live preview.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(indication = null, interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource()) {
                            onGroupClick(group.id)
                        }
                )
            }
        }

        // Scroll chevrons when the rail has more groups than fit.
        listOf(
            state.railUpRect to "▲",
            state.railDownRect to "▼"
        ).forEach { (rect, glyph) ->
            if (rect.width > 0) {
                Box(
                    modifier = Modifier
                        .offset(
                            x = with(density) { rect.left.toDp() },
                            y = with(density) { rect.top.toDp() }
                        )
                        .width(with(density) { rect.width.toDp() })
                        .height(with(density) { rect.height.toDp() })
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x33FFFFFF))
                        .clickable {
                            if (glyph == "▲") onRailScroll(-1) else onRailScroll(1)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(glyph, color = Ink, fontSize = 12.sp)
                }
            }
        }

        // New-group button, positioned by the workspace (shared with the rail touch overlay).
        val addRect = state.addGroupRect
        if (addRect.width > 0) {
            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { addRect.left.toDp() },
                        y = with(density) { addRect.top.toDp() }
                    )
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0x22FFFFFF))
                    .border(1.dp, Color(0x22FFFFFF), CircleShape)
                    .clickable { onNewGroup() },
                contentAlignment = Alignment.Center
            ) {
                Text("＋", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Light)
            }
        }
    }
}

@Composable
private fun EmptyWorkspaceHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("工作区为空", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "从 Dock 选择应用，或长按图标拖入工作区",
                color = InkMuted,
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
    val density = LocalDensity.current
    val pinnedLimit = if (state.landscape) 7 else 4
    val recentLimit = if (state.landscape) 3 else 2
    val pinned = dockModel.pinned.take(pinnedLimit)
    val recent = dockModel.recent.take(recentLimit)

    Column(
        modifier = modifier.padding(bottom = with(density) { 14.dp }),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .shadow(28.dp, RoundedCornerShape(28.dp), ambientColor = Color.Black)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xD919202D))
                .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(28.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
            if (pinned.isNotEmpty() && (recent.isNotEmpty() || true)) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .width(1.dp)
                        .height(34.dp)
                        .background(Color(0x2EFFFFFF))
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
    val scale by animateFloatAsState(if (dragging) 1.12f else 1f, tween(120), label = "dock-scale")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
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
                        .size((48 * scale).dp)
                        .clip(RoundedCornerShape(13.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color(0x33FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(app.label.take(1), color = Ink, fontSize = 18.sp)
                }
            }
        }
        // iPad-style activity indicator dot.
        val dotColor = when {
            activeInStage -> Ink
            runningElsewhere -> Ink.copy(alpha = 0.42f)
            else -> Color.Transparent
        }
        Box(
            modifier = Modifier
                .size(5.dp)
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
                .size(52.dp)
                .padding(2.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color(0x24FFFFFF))
                .border(1.dp, Color(0x1EFFFFFF), RoundedCornerShape(13.dp))
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
        Spacer(Modifier.size(5.dp))
    }
}

@Composable
private fun MiniAppTile(color: Color) {
    Box(
        modifier = Modifier
            .size(13.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.88f))
    )
}
