package dev.stagelab.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stagelab.android.core.stage.BackendKind
import dev.stagelab.android.core.stage.StageRect
import dev.stagelab.android.core.stage.StageStateView
import dev.stagelab.android.core.stage.StageWindowView
import kotlin.math.roundToInt

private val StageBackground = Color(0xFF070A0F)
private val StageSurface = Color(0xFF10151D)
private val Accent = Color(0xFF7C9CFF)
private val AccentSoft = Color(0xFF9DB4FF)
private val Mint = Color(0xFF8FE3C9)
private val Muted = Color(0xFF8B97A8)
private val Danger = Color(0xFFFF8E8E)

/**
 * The Stage Manager surface.
 *
 * This window sits *behind* the real application windows. Everything drawn here is chrome: the
 * desk, focus halos, the rail labels and the dock. The app content itself is never captured or
 * re-rendered — it is the real Android task composited by SurfaceFlinger at the bounds this UI
 * asks for.
 */
@Composable
fun StageManagerScreen(
    state: StageStateView,
    density: Float,
    onConnect: (BackendKind?) -> Unit,
    onOpenPicker: () -> Unit,
    onFocus: (String) -> Unit,
    onClose: (String) -> Unit,
    onReopen: (String) -> Unit,
    onCloseAll: () -> Unit,
    onReflow: () -> Unit,
    onDemo: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onShowHelp: () -> Unit
) {
    var helpVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF06090E), Color(0xFF0C121B), Color(0xFF06090E))
                )
            )
    ) {
        DeskBackdrop(state)
        StageFrames(state)
        TopBar(state, onOpenDiagnostics = onOpenDiagnostics, onShowHelp = { helpVisible = true })
        Dock(
            state = state,
            onOpenPicker = onOpenPicker,
            onFocus = onFocus,
            onClose = onClose,
            onReopen = onReopen,
            onCloseAll = onCloseAll,
            onReflow = onReflow,
            onDemo = onDemo
        )
        if (!state.connected) {
            ConnectPanel(state = state, onConnect = onConnect, onShowHelp = { helpVisible = true })
        }
        if (state.windows.isEmpty() && state.connected) {
            EmptyStage(onOpenPicker = onOpenPicker)
        }
        AnimatedVisibility(
            visible = helpVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            HelpOverlay(onDismiss = { helpVisible = false })
        }
    }
}

@Composable
private fun DeskBackdrop(state: StageStateView) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        // Soft glow behind the main window.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x227C9CFF), Color(0x00000000)),
                        center = Offset(0.38f, 0.42f),
                        radius = 1400f
                    )
                )
        )
        // Fine grid for a "desk" feel.
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val step = with(density) { 32.dp.toPx() }
            var x = 0f
            while (x < size.width) {
                drawLine(Color(0x0DFFFFFF), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(Color(0x0DFFFFFF), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
        }
    }
}

/** Draws a rounded halo around every live task window. The window content itself is on top. */
@Composable
private fun StageFrames(state: StageStateView) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        state.windows.filter { it.alive }.forEach { window ->
            val rect = window.bounds ?: return@forEach
            val focused = window.taskId == state.focusedTaskId
            WindowFrame(rect = rect, focused = focused, density = density.density)
        }
    }
}

@Composable
private fun WindowFrame(rect: StageRect, focused: Boolean, density: Float) {
    val leftDp = (rect.left / density).dp
    val topDp = (rect.top / density).dp
    val widthDp = (rect.width / density).dp
    val heightDp = (rect.height / density).dp
    val glow by animateFloatAsState(if (focused) 1f else 0.35f, tween(320), label = "glow")
    Box(
        modifier = Modifier
            .offset(x = leftDp - 3.dp, y = topDp - 3.dp)
            .width(widthDp + 6.dp)
            .height(heightDp + 6.dp)
            .shadow(
                elevation = if (focused) 26.dp else 10.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color.Black,
                spotColor = Accent.copy(alpha = 0.55f * glow)
            )
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Accent.copy(alpha = 0.95f) else Color(0x22FFFFFF),
                shape = RoundedCornerShape(20.dp)
            )
    )
}

@Composable
private fun TopBar(state: StageStateView, onOpenDiagnostics: () -> Unit, onShowHelp: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Brush.linearGradient(listOf(Accent, Color(0xFF5A78E8)))),
            contentAlignment = Alignment.Center
        ) {
            Text("S", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
        }
        Column {
            Text(
                "STAGE LAB",
                color = Color(0xFFE9EEF8),
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp
            )
            Text(
                "Android multitasking stage",
                color = Muted,
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.weight(1f))
        StatusPill(
            text = state.backend,
            color = if (state.connected) Mint else Danger,
            background = if (state.connected) Color(0x1F8FE3C9) else Color(0x1FFF8E8E)
        )
        PillButton("诊断", onOpenDiagnostics)
        PillButton("?", onShowHelp)
    }
}

@Composable
private fun StatusPill(text: String, color: Color, background: Color) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .border(1.dp, color.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0x14FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label, color = Color(0xFFD5DEEE), fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Dock(
    state: StageStateView,
    onOpenPicker: () -> Unit,
    onFocus: (String) -> Unit,
    onClose: (String) -> Unit,
    onReopen: (String) -> Unit,
    onCloseAll: () -> Unit,
    onReflow: () -> Unit,
    onDemo: () -> Unit
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = with(density) { 16.dp })
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .shadow(30.dp, RoundedCornerShape(26.dp), ambientColor = Color.Black)
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xE61A222E), Color(0xE60E141C))
                    )
                )
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(26.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(state.windows, key = { it.id }) { window ->
                    WindowChip(
                        window = window,
                        focused = window.taskId == state.focusedTaskId,
                        onFocus = { onFocus(window.id) },
                        onClose = { onClose(window.id) },
                        onReopen = { onReopen(window.id) }
                    )
                }
                if (state.windows.isEmpty()) {
                    item {
                        Text(
                            "尚未打开应用",
                            color = Muted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                    }
                }
            }
            DockAction("演示", enabled = state.connected, onClick = onDemo)
            DockAction("重整", enabled = state.connected && state.windows.isNotEmpty(), onClick = onReflow)
            DockAction("全部关闭", enabled = state.connected && state.windows.isNotEmpty(), onClick = onCloseAll, danger = true)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(Accent, Color(0xFF5A78E8))))
                    .clickable(onClick = onOpenPicker),
                contentAlignment = Alignment.Center
            ) {
                Text("＋", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DockAction(label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (danger) Color(0x1FFF8E8E) else Color(0x14FFFFFF))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            color = when {
                !enabled -> Muted.copy(alpha = 0.5f)
                danger -> Danger
                else -> Color(0xFFD5DEEE)
            },
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun WindowChip(
    window: StageWindowView,
    focused: Boolean,
    onFocus: () -> Unit,
    onClose: () -> Unit,
    onReopen: () -> Unit
) {
    var confirmClose by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    !window.alive -> Color(0x0FFFFFFF)
                    focused -> Color(0x2E7C9CFF)
                    else -> Color(0x14FFFFFF)
                }
            )
            .border(
                1.dp,
                if (focused) Accent.copy(alpha = 0.7f) else Color(0x12FFFFFF),
                RoundedCornerShape(16.dp)
            )
            .clickable {
                if (!window.alive) onReopen() else onFocus()
            }
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                window.title.take(1).uppercase(),
                color = Color(0xFFEAF0FA),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Column(modifier = Modifier.width(64.dp)) {
            Text(
                window.title,
                color = if (window.alive) Color(0xFFEAF0FA) else Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when {
                    !window.alive -> "已关闭 · 点击恢复"
                    focused -> "主窗口 · task ${window.taskId}"
                    !window.visible -> "已停放 · 点击切到主窗口"
                    else -> "预览 · task ${window.taskId}"
                },
                color = Muted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (confirmClose) Color(0x33FF8E8E) else Color.Transparent)
                .clickable {
                    if (confirmClose) {
                        onClose()
                    } else {
                        confirmClose = true
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (confirmClose) "✓" else "✕",
                color = if (confirmClose) Danger else Muted,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun EmptyStage(onOpenPicker: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xCC101722))
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(28.dp))
                .padding(horizontal = 40.dp, vertical = 34.dp)
        ) {
            Text("准备就绪", color = Color(0xFFEAF0FA), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "打开一个应用，它会成为真实的 Android 任务窗口",
                color = Muted,
                fontSize = 12.sp
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(Accent, Color(0xFF5A78E8))))
                    .clickable(onClick = onOpenPicker)
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) {
                Text("选择应用", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ConnectPanel(
    state: StageStateView,
    onConnect: (BackendKind?) -> Unit,
    onShowHelp: () -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xF2101722))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(28.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Accent,
                    strokeWidth = 2.dp
                )
                Text(
                    "需要特权后端来托管真实任务窗口",
                    color = Color(0xFFEAF0FA),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "StageLab 通过 Shizuku（Shell UID 2000）或 KernelSU Root 调用系统窗口管理接口，" +
                    "创建、排列并实时显示其它应用的真实任务。普通应用权限无法完成这一步。",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 17.sp
            )
            state.message?.let {
                Text(it, color = Danger.copy(alpha = 0.9f), fontSize = 10.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("连接 Shizuku", onClick = { onConnect(BackendKind.SHIZUKU) })
                PrimaryButton("尝试 Root", onClick = { onConnect(BackendKind.ROOT) }, secondary = true)
                PrimaryButton("自动选择", onClick = { onConnect(null) }, secondary = true)
            }
            Text(
                "提示：KernelSU 会在后台拉起一个 app_process 服务；Shizuku 需要先安装并授权。",
                color = Muted.copy(alpha = 0.8f),
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, secondary: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (secondary) {
                    Modifier.background(Color(0x14FFFFFF))
                } else {
                    Modifier.background(Brush.linearGradient(listOf(Accent, Color(0xFF5A78E8))))
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        Text(
            label,
            color = if (secondary) Color(0xFFD5DEEE) else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun HelpOverlay(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.66f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF141B25))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("使用方式", color = Color(0xFFEAF0FA), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            HelpLine("1", "点击底部 ＋ 选择应用：会用 FREEFORM 窗口模式启动成真实任务。")
            HelpLine("2", "主窗口是当前聚焦的任务；右侧窄条是其它仍在运行的任务，内容是实时的。")
            HelpLine("3", "直接点击右侧窗口即可切换；StageLab 会自动把它换到主区域。")
            HelpLine("4", "Dock 里可以重整布局、关闭单个或全部窗口；关闭不会杀掉应用进程之外的状态。")
            HelpLine("5", "窗口位置与尺寸来自系统 WindowManager，没有截图、没有屏幕录制。")
            Spacer(Modifier.height(4.dp))
            Text("点击任意位置关闭", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun HelpLine(index: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(index, color = AccentSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(text, color = Color(0xFFC7D2E2), fontSize = 11.sp, lineHeight = 17.sp)
    }
}
