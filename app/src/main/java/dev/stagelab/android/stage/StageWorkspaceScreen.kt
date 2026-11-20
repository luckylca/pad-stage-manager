package dev.stagelab.android.stage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
    dockApps: List<DockApp>,
    onGroupClick: (String) -> Unit,
    onNewGroup: () -> Unit,
    onRailScroll: (Int) -> Unit,
    onToggleRail: () -> Unit,
    onDockApp: (DockApp) -> Unit,
    onDockDragMove: (android.graphics.PointF) -> Unit,
    onDockDrop: (DockApp, android.graphics.PointF) -> Unit,
    onDockDragCancel: () -> Unit,
    onAddApp: () -> Unit,
    onExit: () -> Unit,
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

        StageDock(
            modifier = Modifier.align(Alignment.BottomCenter),
            state = state,
            dockApps = dockApps,
            onDockApp = onDockApp,
            onAddApp = onAddApp,
            onExit = onExit,
            onToggleRail = onToggleRail,
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
    dockApps: List<DockApp>,
    onDockApp: (DockApp) -> Unit,
    onAddApp: () -> Unit,
    onExit: () -> Unit,
    onToggleRail: () -> Unit,
    onDragState: (DockApp, Offset, Offset) -> Unit,
    onDragLost: (Boolean) -> Unit
) {
    val density = LocalDensity.current
    Column(
        modifier = modifier.padding(bottom = with(density) { 14.dp }),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .shadow(28.dp, RoundedCornerShape(28.dp), ambientColor = Color.Black)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xE6171D27))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(28.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DockAction(icon = "◧", label = "应用组") { onToggleRail() }
            Spacer(Modifier.width(2.dp))
            dockApps.take(6).forEach { app ->
                DockIcon(
                    app = app,
                    onClick = { onDockApp(app) },
                    onDragState = onDragState,
                    onDragLost = onDragLost
                )
            }
            DockAction(icon = "＋", label = "应用库") { onAddApp() }
            Spacer(Modifier.width(2.dp))
            DockAction(icon = "⏻", label = "关闭 Stage Manager", danger = true) { onExit() }
        }
    }
}

@Composable
private fun DockIcon(
    app: DockApp,
    onClick: () -> Unit,
    onDragState: (DockApp, Offset, Offset) -> Unit,
    onDragLost: (Boolean) -> Unit
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (dragging) 1.12f else 1f, tween(120), label = "dock-scale")

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
                        dragging = true
                        offset = Offset.Zero
                        onDragState(app, offset, origin)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        offset += amount
                        onDragState(app, offset, origin)
                    },
                    onDragEnd = {
                        dragging = false
                        onDragLost(false)
                    },
                    onDragCancel = {
                        dragging = false
                        onDragLost(true)
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
                    .clip(RoundedCornerShape(14.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                Text(app.label.take(1), color = Ink, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun DockAction(icon: String, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (danger) Color(0x26FF8E8E) else Color(0x14FFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(icon, color = if (danger) Color(0xFFFF9B9B) else Ink, fontSize = 17.sp)
    }
}
