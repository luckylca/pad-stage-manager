package dev.stagelab.nativehook.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material3.OutlinedButton
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
import dev.stagelab.nativehook.bridge.StageNativeRuntime

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
    private val moduleEnabledState = mutableStateOf(false)

    private val uiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == StageModeNotificationHelper.ACTION_UI_STATE_CHANGED) {
                moduleEnabledState.value = intent.getBooleanExtra(StageModeNotificationHelper.KEY_ENABLED, false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        val prefs = getSharedPreferences(StageModeNotificationHelper.PREFS_NAME, MODE_PRIVATE)
        moduleEnabledState.value = prefs.getBoolean(StageModeNotificationHelper.KEY_ENABLED, false)

        registerReceiver(
            uiStateReceiver,
            IntentFilter(StageModeNotificationHelper.ACTION_UI_STATE_CHANGED),
            RECEIVER_NOT_EXPORTED,
        )

        setContent {
            MaterialTheme(colorScheme = StageLabColorScheme) {
                val moduleEnabled by moduleEnabledState
                var safeMode by remember { mutableStateOf(prefs.getBoolean("safe_mode", false)) }
                var hideRecentStrip by remember { mutableStateOf(prefs.getBoolean("hide_strip", false)) }
                var hideDock by remember { mutableStateOf(prefs.getBoolean("hide_dock", false)) }
                val active = remember { ModuleStatus.isXposedModuleActive() }

                SettingsScreen(
                    isModuleActive = active,
                    moduleEnabled = moduleEnabled,
                    safeMode = safeMode,
                    hideRecentStrip = hideRecentStrip,
                    hideDock = hideDock,
                    onToggleEnabled = { next ->
                        moduleEnabledState.value = next
                        prefs.edit().putBoolean(StageModeNotificationHelper.KEY_ENABLED, next).apply()
                        if (!next) {
                            StageModeNotificationHelper.cancelExitNotification(this@MainActivity)
                            sendStageCmd("disable_module")
                        } else {
                            StageModeNotificationHelper.showExitNotification(this@MainActivity)
                            sendStageCmd("enable_module")
                            // Immediately dismiss settings Activity so the user lands cleanly on the
                            // blurred-wallpaper Stage canvas instead of being stuck on a fullscreen settings page.
                            moveTaskToBack(true)
                            finishAndRemoveTask()
                        }
                    },
                    onToggleSafeMode = { next ->
                        safeMode = next
                        prefs.edit().putBoolean("safe_mode", next).apply()
                        if (next) {
                            moduleEnabledState.value = false
                            prefs.edit().putBoolean(StageModeNotificationHelper.KEY_ENABLED, false).apply()
                            StageModeNotificationHelper.cancelExitNotification(this@MainActivity)
                            sendStageCmd("disable_module")
                        }
                    },
                    onToggleHideStrip = { next ->
                        hideRecentStrip = next
                        prefs.edit().putBoolean("hide_strip", next).apply()
                        sendBroadcast(
                            Intent(StageNativeRuntime.ACTION_STAGE_CMD).apply {
                                setPackage("com.android.systemui")
                                putExtra("cmd", "strip_hide")
                                putExtra("hidden", next)
                            },
                        )
                    },
                    onToggleHideDock = { next ->
                        hideDock = next
                        prefs.edit().putBoolean("hide_dock", next).apply()
                        sendBroadcast(
                            Intent(StageNativeRuntime.ACTION_STAGE_CMD).apply {
                                setPackage("com.android.systemui")
                                putExtra("cmd", "dock_hide")
                                putExtra("hidden", next)
                            },
                        )
                    },
                    onSyncLiveTasks = { sendStageCmd("sync_live") },
                    onCleanReset = {
                        moduleEnabledState.value = false
                        prefs.edit().putBoolean(StageModeNotificationHelper.KEY_ENABLED, false).apply()
                        StageModeNotificationHelper.cancelExitNotification(this@MainActivity)
                        sendStageCmd("disable_module")
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(StageModeNotificationHelper.PREFS_NAME, MODE_PRIVATE)
        moduleEnabledState.value = prefs.getBoolean(StageModeNotificationHelper.KEY_ENABLED, false)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(uiStateReceiver) }
        super.onDestroy()
    }

    private fun sendStageCmd(cmd: String) {
        sendBroadcast(
            Intent(StageNativeRuntime.ACTION_STAGE_CMD).apply {
                setPackage("com.android.systemui")
                putExtra("cmd", cmd)
            },
        )
    }
}

@Composable
private fun SettingsScreen(
    isModuleActive: Boolean,
    moduleEnabled: Boolean,
    safeMode: Boolean,
    hideRecentStrip: Boolean,
    hideDock: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleSafeMode: (Boolean) -> Unit,
    onToggleHideStrip: (Boolean) -> Unit,
    onToggleHideDock: (Boolean) -> Unit,
    onSyncLiveTasks: () -> Unit,
    onCleanReset: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MdSurface),
    ) {
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
                            text = if (isModuleActive) "Vector / LSPosed 已激活 · ColorOS 17 原生小窗模式" else "请在 Vector / LSPosed 中启用本模块",
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
                        text = "基于 ColorOS 17 原生 Flexible Window、ShellTaskOrganizer 与原生 Dock 的 iPad 台前调度模块。",
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = MdOnSurfaceVariant,
                    )
                }
            }

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
                        title = "启用台前调度（Stage Manager）",
                        subtitle = "关闭后立即移除左侧悬浮缩略图并保留全部用户任务，恢复 ColorOS 原生体验",
                        checked = moduleEnabled,
                        onCheckedChange = onToggleEnabled,
                    )
                    SettingSwitchRow(
                        title = "隐藏左侧最近应用栏（Recent Strip）",
                        subtitle = "隐藏后从屏幕最左侧边缘向右轻扫可临时呼出",
                        checked = hideRecentStrip,
                        onCheckedChange = onToggleHideStrip,
                    )
                    SettingSwitchRow(
                        title = "隐藏底部 Dock 栏",
                        subtitle = "隐藏后从屏幕底部向上轻扫可临时呼出，向上拖动图标可合并进当前 Stage",
                        checked = hideDock,
                        onCheckedChange = onToggleHideDock,
                    )
                    SettingSwitchRow(
                        title = "安全模式（Safe Mode）",
                        subtitle = "跳过全部 Hook 回调，保留 ColorOS 100% 原生行为",
                        checked = safeMode,
                        onCheckedChange = onToggleSafeMode,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MdSurfaceContainer,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "快捷操作与安全恢复",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MdOnSurface,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onSyncLiveTasks,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MdPrimary),
                        ) {
                            Text(text = "同步当前运行窗口", fontWeight = FontWeight.Medium)
                        }
                        OutlinedButton(
                            onClick = onCleanReset,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text(text = "清除逻辑状态", color = MdPrimary, fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "紧急禁用：\nadb shell su -c \"/data/adb/lspd/cli modules disable dev.stagelab.nativehook\"\n\n实时日志：\nadb logcat -s StageLabNativeHook:V",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp,
                        color = MdOnSurfaceVariant,
                    )
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
