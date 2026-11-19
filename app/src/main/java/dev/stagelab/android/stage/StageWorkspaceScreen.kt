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
    onToggleRail: () -> Unit,
    onDockApp: (DockApp) -> Unit,
    onDockDrop: (DockApp, android.graphics.PointF) -> Unit,
    onAddApp: () -> Unit,
    onExit: () -> Unit,
    onRestoreMinimized: (String) -> Unit,
    onCloseMinimized: (String) -> Unit,
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
            .clickable(indication = null, interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource()) {
                onEmptyTap()
            }
    ) {
        DeskGlow()

        AnimatedVisibility(
            visible = state.railVisible,
            enter = slideInHorizontally(tween(260)) { -it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(220)) { -it } + fadeOut(tween(160))
        ) {
            RecentGroupsRail(
                state = state,
                onGroupClick = onGroupClick,
                onNewGroup = onNewGroup
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
            onRestoreMinimized = onRestoreMinimized,
            onCloseMinimized = onCloseMinimized,
            onDragState = { app, offset, origin ->
                dragging = app
                dragOffset = offset
                dragOrigin = origin
            },
            onDragLost = {
                val app = dragging
                if (app != null) {
                    val position = dragOrigin + dragOffset
                    val area = state.workspace
                    if (position.x >= area.left && position.x <= area.right &&
                        position.y >= area.top && position.y <= area.bottom
                    ) {
                        onDockDrop(app, android.graphics.PointF(position.x, position.y))
                    }
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
    onNewGroup: () -> Unit
) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        // Rail backdrop so the slide-in reads as a panel even when there is only one group.
        val railHeightDp = with(density) { (state.workspace.height + 4).toDp() }
        Box(
            modifier = Modifier
                .offset(x = with(density) { (state.workspace.left - 14).toDp() }, y = with(density) { (state.workspace.top - 4).toDp() })
                .width(250.dp)
                .height(railHeightDp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xB30E131B))
                .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(26.dp))
        )
        state.groups.forEach { group ->
            val widthDp = with(density) { group.rect.width.toDp() }
            val heightDp = with(density) { group.rect.height.toDp() }
            val xDp = with(density) { group.rect.left.toDp() }
            val yDp = with(density) { group.rect.top.toDp() }
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

        // New-group button sits just under the last tile (or at the rail top when it is empty).
        val railLeft = state.groups.firstOrNull()?.rect?.left
            ?: with(density) { 10.dp.roundToPx() }
        val railBottom = state.groups.maxOfOrNull { it.rect.bottom }
            ?: (state.workspace.top + with(density) { 8.dp.roundToPx() })
        Box(
            modifier = Modifier
                .offset(
                    x = with(density) { railLeft.toDp() },
                    y = with(density) { (railBottom + 10).toDp() }
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
    onRestoreMinimized: (String) -> Unit,
    onCloseMinimized: (String) -> Unit,
    onDragState: (DockApp, Offset, Offset) -> Unit,
    onDragLost: () -> Unit
) {
    val density = LocalDensity.current
    Column(
        modifier = modifier.padding(bottom = with(density) { 14.dp }),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (state.minimized.isNotEmpty()) {
            MinimizedTray(state.minimized, onRestoreMinimized, onCloseMinimized)
            Spacer(Modifier.height(8.dp))
        }
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
private fun MinimizedTray(
    windows: List<WindowTile>,
    onRestore: (String) -> Unit,
    onClose: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xCC11161F))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        windows.forEach { window ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x22FFFFFF))
                    .clickable { onRestore(window.id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(window.title.take(6), color = Ink, fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable { onClose(window.id) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = InkMuted, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun DockIcon(
    app: DockApp,
    onClick: () -> Unit,
    onDragState: (DockApp, Offset, Offset) -> Unit,
    onDragLost: () -> Unit
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
                        onDragLost()
                    },
                    onDragCancel = {
                        dragging = false
                        onDragLost()
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
