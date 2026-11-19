package dev.stagelab.android.control

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stagelab.android.stage.StageMode
import dev.stagelab.android.stage.StageModeState

private val Ink = Color(0xFFF2F5FA)
private val Muted = Color(0xFF8E9AAC)
private val Accent = Color(0xFF8AA0FF)

/**
 * The OFF page: one switch, one sentence about the current state.
 *
 * The only extra content is the one-time Shizuku prompt, and it only shows when the privileged
 * backend is actually missing.
 */
@Composable
fun ControlScreen(
    state: StageModeState,
    busy: Boolean,
    onToggle: () -> Unit,
    onRequestShizuku: () -> Unit
) {
    val on = state.mode == StageMode.ON || state.mode == StageMode.STARTING
    val knob by animateFloatAsState(if (on) 1f else 0f, tween(220), label = "switch")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B0F16), Color(0xFF101724))))
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x1A3D5BB5), Color(0x00000000)),
                        center = Offset(0.5f, 0.32f),
                        radius = 1400f
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(180.dp))

            Text(
                "Stage Manager",
                color = Ink,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when (state.mode) {
                    StageMode.ON -> "已开启 · 点击关闭"
                    StageMode.STARTING -> "正在启动…"
                    StageMode.STOPPING -> "正在关闭…"
                    StageMode.OFF -> "已关闭 · 点击开启"
                },
                color = Muted,
                fontSize = 13.sp
            )

            Spacer(Modifier.height(46.dp))

            // The switch itself.
            Box(
                modifier = Modifier
                    .width(112.dp)
                    .height(60.dp)
                    .shadow(if (on) 26.dp else 8.dp, CircleShape, spotColor = Accent.copy(alpha = 0.5f))
                    .clip(CircleShape)
                    .background(
                        if (on) {
                            Brush.horizontalGradient(listOf(Accent, Color(0xFF6B84F0)))
                        } else {
                            Brush.horizontalGradient(listOf(Color(0xFF242C38), Color(0xFF1B222C)))
                        }
                    )
                    .border(1.dp, Color(0x22FFFFFF), CircleShape)
                    .clickable(enabled = !busy) { onToggle() }
            ) {
                Box(
                    modifier = Modifier
                        .offset(x = with(androidx.compose.ui.platform.LocalDensity.current) { (10 + 52 * knob).dp })
                        .padding(top = 10.dp)
                        .size(40.dp)
                        .shadow(6.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color(0xFFF7F9FF))
                )
            }

            Spacer(Modifier.height(26.dp))
            Text(
                if (on) "工作区已准备好，可在系统窗口之间自由切换" else "开启后进入沉浸式多窗口工作区",
                color = Muted,
                fontSize = 11.sp
            )

            if (state.needsShizuku || state.error != null) {
                Spacer(Modifier.height(30.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0x14FFFFFF))
                        .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(20.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("需要 Shizuku 授权", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        state.error ?: "Stage Manager 需要 Shell 权限来创建和排列真实窗口。",
                        color = Muted,
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Accent)
                                .clickable { onRequestShizuku() }
                                .padding(horizontal = 16.dp, vertical = 9.dp)
                        ) {
                            Text("授权 Shizuku", color = Color(0xFF0B1018), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x14FFFFFF))
                                .clickable { onToggle() }
                                .padding(horizontal = 16.dp, vertical = 9.dp)
                        ) {
                            Text("重试", color = Ink, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
