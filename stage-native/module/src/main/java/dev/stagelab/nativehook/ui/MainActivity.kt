package dev.stagelab.nativehook.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.viewinterop.AndroidView
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
import dev.stagelab.nativehook.compat.OplusCompatAdapter

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
    private val uiDiagProbesState = mutableStateOf<List<OplusCompatAdapter.ProbeRecord>>(emptyList())
    private val sysDiagProbesState = mutableStateOf<List<OplusCompatAdapter.ProbeRecord>>(emptyList())
    private val sysColorOsVersionState = mutableStateOf("")

    private val uiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                StageModeNotificationHelper.ACTION_UI_STATE_CHANGED -> {
                    moduleEnabledState.value = intent.getBooleanExtra(StageModeNotificationHelper.KEY_ENABLED, false)
                }
                "dev.stagelab.nativehook.ACTION_UI_DIAG_RESPONSE" -> {
                    uiDiagProbesState.value = parseProbeLines(intent.getStringArrayListExtra("probes").orEmpty())
                }
                "dev.stagelab.nativehook.ACTION_SYS_DIAG_RESPONSE" -> {
                    sysColorOsVersionState.value = intent.getStringExtra("coloros").orEmpty()
                    sysDiagProbesState.value = parseProbeLines(intent.getStringArrayListExtra("probes").orEmpty())
                }
            }
        }
    }

    private fun parseProbeLines(raw: List<String>): List<OplusCompatAdapter.ProbeRecord> {
        return raw.mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) null else {
                val st = runCatching { OplusCompatAdapter.Status.valueOf(parts[1]) }
                    .getOrDefault(OplusCompatAdapter.Status.MISSING)
                OplusCompatAdapter.ProbeRecord(parts[0], st, parts[2])
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
            IntentFilter().apply {
                addAction(StageModeNotificationHelper.ACTION_UI_STATE_CHANGED)
                addAction("dev.stagelab.nativehook.ACTION_UI_DIAG_RESPONSE")
                addAction("dev.stagelab.nativehook.ACTION_SYS_DIAG_RESPONSE")
            },
            RECEIVER_EXPORTED,
        )
        sendStageCmd("diag")
        sendBroadcast(Intent("dev.stagelab.nativehook.ACTION_QUERY_SYS_DIAG"))

        setContent {
            MaterialTheme(colorScheme = StageLabColorScheme) {
                val moduleEnabled by moduleEnabledState
                var safeMode by remember { mutableStateOf(prefs.getBoolean("safe_mode", false)) }
                var hideRecentStrip by remember { mutableStateOf(prefs.getBoolean("hide_strip", false)) }
                var hideDock by remember { mutableStateOf(prefs.getBoolean("hide_dock", false)) }
                var livePreview by remember { mutableStateOf(prefs.getBoolean("live_preview", true)) }
                val active = remember { ModuleStatus.isXposedModuleActive() }
                val uiProbes by uiDiagProbesState
                val sysProbes by sysDiagProbesState
                val sysColorOs by sysColorOsVersionState
                var sysDiagSnapshot by remember { mutableStateOf(loadDiagnosticsReport()) }
                val diagHeaderText = remember(sysDiagSnapshot, sysColorOs) {
                    if (sysColorOs.isNotBlank()) {
                        val metrics = StageLayoutMetrics.fromContext(this@MainActivity)
                        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                            "ROM: $sysColorOs · 形态: ${metrics.deviceTier.label}\n" +
                            "屏幕: ${metrics.screenW}×${metrics.screenH} @ ${resources.displayMetrics.densityDpi}dpi " +
                            "(SafeTop=${metrics.safeTopPx}px, ExtraNav=${metrics.extraNavBottomPx}px)"
                    } else {
                        sysDiagSnapshot.first
                    }
                }
                val mergedDiagProbes = remember(sysDiagSnapshot, sysProbes, uiProbes) {
                    val map = LinkedHashMap<String, OplusCompatAdapter.ProbeRecord>()
                    sysDiagSnapshot.second.forEach { map[it.key] = it }
                    sysProbes.forEach { map[it.key] = it }
                    uiProbes.forEach { map[it.key] = it }
                    map.values.sortedBy { it.key }
                }

                SettingsScreen(
                    isModuleActive = active,
                    moduleEnabled = moduleEnabled,
                    safeMode = safeMode,
                    hideRecentStrip = hideRecentStrip,
                    hideDock = hideDock,
                    livePreview = livePreview,
                    diagHeader = diagHeaderText,
                    diagProbes = mergedDiagProbes,
                    onRefreshDiag = {
                        sysDiagSnapshot = loadDiagnosticsReport()
                        sendStageCmd("diag")
                        sendBroadcast(Intent("dev.stagelab.nativehook.ACTION_QUERY_SYS_DIAG"))
                        Toast.makeText(this@MainActivity, "已刷新本机 ColorOS 兼容性诊断", Toast.LENGTH_SHORT).show()
                    },
                    onCopyDiag = {
                        val fullReport = buildPlainTextDiagReport(diagHeaderText, mergedDiagProbes)
                        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("StageLab Diagnostics", fullReport))
                        Toast.makeText(this@MainActivity, "诊断报告已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    },
                    onToggleLivePreview = { next ->
                        livePreview = next
                        prefs.edit().putBoolean("live_preview", next).apply()
                        sendBroadcast(
                            Intent(StageNativeRuntime.ACTION_STAGE_CMD).apply {
                                setPackage("com.android.systemui")
                                putExtra("cmd", "live_preview")
                                putExtra("enabled", next)
                            },
                        )
                    },
                    onToggleEnabled = { next ->
                        moduleEnabledState.value = next
                        prefs.edit().putBoolean(StageModeNotificationHelper.KEY_ENABLED, next).apply()
                        sendBroadcast(
                            Intent(dev.stagelab.nativehook.xposed.SystemServerFlexibleHook.ACTION_STAGE_MODE_SYNC).apply {
                                putExtra("enabled", next)
                            },
                        )
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

    private fun loadDiagnosticsReport(): Pair<String, List<OplusCompatAdapter.ProbeRecord>> {
        val metrics = StageLayoutMetrics.fromContext(this)
        val dm = resources.displayMetrics
        val colorOs = OplusCompatAdapter.getColorOsVersion()
        val header = buildString {
            append("${Build.MANUFACTURER} ${Build.MODEL}")
            append(" · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("ROM: $colorOs · 形态: ${metrics.deviceTier.label}\n")
            append("屏幕: ${metrics.screenW}×${metrics.screenH} @ ${dm.densityDpi}dpi (SafeTop=${metrics.safeTopPx}px, ExtraNav=${metrics.extraNavBottomPx}px)")
        }
        val merged = LinkedHashMap<String, OplusCompatAdapter.ProbeRecord>()
        merged.putAll(OplusCompatAdapter.readProbesFromFile(OplusCompatAdapter.SYS_DIAG_PATH))
        merged.putAll(OplusCompatAdapter.readProbesFromFile(OplusCompatAdapter.UI_DIAG_PATH))
        return header to merged.values.toList()
    }

    private fun buildPlainTextDiagReport(
        header: String,
        probes: List<OplusCompatAdapter.ProbeRecord>,
    ): String = buildString {
        appendLine("=== StageLab Native ColorOS Diagnostics ===")
        appendLine(header)
        appendLine("ModuleActive: ${ModuleStatus.isXposedModuleActive()}")
        if (probes.isEmpty()) {
            appendLine("(尚未读取到 Hook 探针记录，请确认已在 LSPosed/Vector 勾选系统框架与系统界面并软重启)")
        } else {
            for (p in probes) {
                appendLine("[${p.status.name}] ${p.key} -> ${p.detail}")
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    isModuleActive: Boolean,
    moduleEnabled: Boolean,
    safeMode: Boolean,
    hideRecentStrip: Boolean,
    hideDock: Boolean,
    livePreview: Boolean,
    diagHeader: String,
    diagProbes: List<OplusCompatAdapter.ProbeRecord>,
    onRefreshDiag: () -> Unit,
    onCopyDiag: () -> Unit,
    onToggleLivePreview: (Boolean) -> Unit,
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
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                ImageView(ctx).apply {
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                    setImageResource(dev.stagelab.nativehook.R.mipmap.ic_launcher_round)
                                }
                            },
                            modifier = Modifier
                                .size(68.dp)
                                .clip(RoundedCornerShape(20.dp)),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (isModuleActive) MdSecondaryContainer else MdTertiary.copy(alpha = 0.16f),
                            ) {
                                Text(
                                    text = if (isModuleActive) "Vector / LSPosed 已激活" else "请在 Vector / LSPosed 中启用本模块",
                                    color = if (isModuleActive) MdOnSecondaryContainer else MdTertiary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                )
                            }
                            Text(
                                text = "StageLab Native",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Medium,
                                color = MdOnSurface,
                            )
                        }
                    }
                    Text(
                        text = "基于原生窗口管理与 ShellTaskOrganizer 的 iPad 台前调度模块，适配 Android 13–17 / ColorOS 13–17 / OxygenOS / realme UI 多尺寸平板与折叠屏。",
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
                        title = "侧边栏实时动态内容预览（实验性）",
                        subtitle = "保持后台工作区画面实时镜像到左侧 3D 侧边栏（默认开启，关闭后切回省电高清快照）",
                        checked = livePreview,
                        onCheckedChange = onToggleLivePreview,
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

            // Built-in ColorOS Multi-Version Environment Diagnostics Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MdSurfaceContainer,
                tonalElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "多设备与多版本兼容性诊断",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = MdOnSurface,
                    )
                    Text(
                        text = diagHeader,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MdOnSurfaceVariant,
                    )
                    if (diagProbes.isEmpty()) {
                        Text(
                            text = "暂无 Hook 探针数据（请确认已在 LSPosed/Vector 启用模块并勾选系统框架、系统界面后软重启）。",
                            fontSize = 13.sp,
                            color = MdTertiary,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (probe in diagProbes) {
                                val (badgeText, badgeColor) = when (probe.status) {
                                    OplusCompatAdapter.Status.HIT -> "✓ 命中" to Color(0xFF1B6E3A)
                                    OplusCompatAdapter.Status.FALLBACK -> "△ 回退" to Color(0xFF9A5B00)
                                    OplusCompatAdapter.Status.MISSING -> "✕ 缺失" to Color(0xFFB3261E)
                                    OplusCompatAdapter.Status.ERROR -> "! 异常" to Color(0xFFB3261E)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${probe.key} (${probe.detail})",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MdOnSurface,
                                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                                    )
                                    Text(
                                        text = badgeText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = badgeColor,
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onCopyDiag,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MdPrimary),
                        ) {
                            Text(text = "一键复制诊断报告", fontWeight = FontWeight.Medium)
                        }
                        OutlinedButton(
                            onClick = onRefreshDiag,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text(text = "刷新诊断", color = MdPrimary, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(110.dp))
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
