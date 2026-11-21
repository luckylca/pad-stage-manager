package dev.stagelab.nativehook.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object ModuleStatus {
    /** Hooked by StageLabHookEntry to return true when LSPosed/Vector is active. */
    @JvmStatic
    fun isXposedModuleActive(): Boolean = false
}

private val MdSurface = Color(0xFFFFFBFE)
private val MdOnSurface = Color(0xFF1C1B1F)
private val MdOnSurfaceVariant = Color(0xFF49454F)
private val MdPrimary = Color(0xFF6750A4)
private val MdSecondaryContainer = Color(0xFFE8DEF8)
private val MdOnSecondaryContainer = Color(0xFF1D192B)
private val MdTertiary = Color(0xFF7D5260)
private val MdSurfaceContainer = Color(0xFFF3EDF7)

private val StageLabColorScheme = lightColorScheme(
    primary = MdPrimary,
    onPrimary = Color.White,
    secondaryContainer = MdSecondaryContainer,
    onSecondaryContainer = MdOnSecondaryContainer,
    tertiary = MdTertiary,
    background = MdSurface,
    surface = MdSurface,
    onBackground = MdOnSurface,
    onSurface = MdOnSurface,
    onSurfaceVariant = MdOnSurfaceVariant,
    surfaceContainer = MdSurfaceContainer,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("stagelab_native_prefs", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = StageLabColorScheme) {
                var moduleEnabled by remember { mutableStateOf(prefs.getBoolean("enabled", true)) }
                var safeMode by remember { mutableStateOf(prefs.getBoolean("safe_mode", false)) }
                val active = remember { ModuleStatus.isXposedModuleActive() }

                SettingsScreen(
                    isModuleActive = active,
                    moduleEnabled = moduleEnabled,
                    safeMode = safeMode,
                    onToggleEnabled = { next ->
                        moduleEnabled = next
                        prefs.edit().putBoolean("enabled", next).apply()
                    },
                    onToggleSafeMode = { next ->
                        safeMode = next
                        prefs.edit().putBoolean("safe_mode", next).apply()
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    isModuleActive: Boolean,
    moduleEnabled: Boolean,
    safeMode: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleSafeMode: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MdSurface),
    ) {
        // Material You organic blurred atmospheric shapes
        Box(
            modifier = Modifier
                .size(320.dp)
                .offset(x = (-80).dp, y = (-60).dp)
                .clip(CircleShape)
                .background(MdSecondaryContainer.copy(alpha = 0.55f))
                .blur(72.dp),
        )
        Box(
            modifier = Modifier
                .size(280.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 70.dp, y = 70.dp)
                .clip(CircleShape)
                .background(MdTertiary.copy(alpha = 0.20f))
                .blur(72.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Hero Header Card
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = MdSurfaceContainer,
                tonalElevation = 1.dp,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (isModuleActive) MdSecondaryContainer else MdTertiary.copy(alpha = 0.16f),
                    ) {
                        Text(
                            text = if (isModuleActive) "Vector / LSPosed 已激活 · N2 只读观察" else "等待在 Vector / LSPosed 中启用",
                            color = if (isModuleActive) MdOnSecondaryContainer else MdTertiary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                    Text(
                        text = "StageLab Native",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Medium,
                        color = MdOnSurface,
                    )
                    Text(
                        text = "基于 ColorOS 17 原生 Flexible Window 与 ShellTaskOrganizer 的 iPad 台前调度模块。",
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = MdOnSurfaceVariant,
                    )
                }
            }

            // Control Card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MdSurfaceContainer,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    SettingSwitchRow(
                        title = "启用模块观察器",
                        subtitle = "仅 Hook com.android.systemui（N2 只读模式，不修改任何系统窗口行为）",
                        checked = moduleEnabled,
                        onCheckedChange = onToggleEnabled,
                    )
                    SettingSwitchRow(
                        title = "安全模式（Safe Mode）",
                        subtitle = "开启后跳过全部 Hook 回调，保留 ColorOS 100% 原生行为",
                        checked = safeMode,
                        onCheckedChange = onToggleSafeMode,
                    )
                }
            }

            // Target Baseline & Recovery Card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MdSurfaceContainer,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "ADB 快速恢复与日志",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MdOnSurface,
                    )
                    Text(
                        text = "紧急禁用：\nadb shell su -c \"/data/adb/lspd/cli modules disable dev.stagelab.nativehook\"\n\n实时日志：\nadb logcat -s StageLabNativeHook:V",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp,
                        color = MdOnSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { onToggleSafeMode(true) },
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MdPrimary),
                    ) {
                        Text(text = "一键切入安全模式", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MdOnSurface,
            )
            Text(
                text = subtitle,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MdOnSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MdPrimary,
            ),
        )
    }
}
