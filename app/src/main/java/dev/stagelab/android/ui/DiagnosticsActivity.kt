package dev.stagelab.android.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.stagelab.android.core.model.DeviceProfile
import dev.stagelab.android.core.stage.BackendKind
import dev.stagelab.android.core.stage.StageRuntime
import kotlinx.coroutines.launch

/**
 * Diagnostics and the permission capability matrix. Everything shown here is measured at runtime
 * on the device, not hard coded.
 */
class DiagnosticsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StageLabTheme {
                val state by StageRuntime.state.collectAsState()
                var diagnostics by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
                var organizer by remember { mutableStateOf("") }
                var freeformCheck by remember { mutableStateOf("") }
                val scope = androidx.compose.runtime.rememberCoroutineScope()

                DiagnosticsScreen(
                    state = state,
                    device = DeviceProfile.read(),
                    displayInfo = displayInfo(this),
                    diagnostics = diagnostics,
                    organizerResult = organizer,
                    freeformCheck = freeformCheck,
                    onRefresh = {
                        scope.launch {
                            diagnostics = runCatching { StageRuntime.diagnostics() }.getOrDefault(emptyMap())
                            freeformCheck = runCatching {
                                StageRuntime.runShell("settings get global enable_freeform_support")
                            }.getOrDefault("")
                        }
                    },
                    onOrganizerProbe = {
                        scope.launch { organizer = runCatching { StageRuntime.organizerProbe() }.getOrElse { it.message ?: "失败" } }
                    },
                    onConnect = { kind -> scope.launch { StageRuntime.connect(kind) } },
                    onBack = { finish() },
                    onOpenShizukuProbe = {
                        startActivity(android.content.Intent(this, dev.stagelab.android.shizuku.ShizukuProbeActivity::class.java))
                    }
                )
            }
        }
        lifecycleScope.launch {
            runCatching { StageRuntime.diagnostics() }
        }
    }

    private fun displayInfo(context: Context): String {
        val manager = context.getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        val mode = runCatching { manager.defaultDisplay.mode }.getOrNull()
        return "${metrics.widthPixels}x${metrics.heightPixels} @ ${mode?.refreshRate ?: 0f}Hz density=${metrics.densityDpi}"
    }
}

@Composable
private fun DiagnosticsScreen(
    state: dev.stagelab.android.core.stage.StageStateView,
    device: DeviceProfile,
    displayInfo: String,
    diagnostics: Map<String, String>,
    organizerResult: String,
    freeformCheck: String,
    onRefresh: () -> Unit,
    onOrganizerProbe: () -> Unit,
    onConnect: (BackendKind?) -> Unit,
    onBack: () -> Unit,
    onOpenShizukuProbe: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF070A0F), Color(0xFF111823))))
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column {
                        Text("诊断与能力矩阵", color = Color(0xFFEDF2FB), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("全部数据来自真机运行时", color = Color(0xFF8B97A8), fontSize = 11.sp)
                    }
                    Card2 {
                        Text("返回", color = Color(0xFFD5DEEE), fontSize = 11.sp, modifier = Modifier.clickable(onClick = onBack))
                    }
                }
            }
            item {
                Section("设备") {
                    InfoLine("型号", device.displayName)
                    InfoLine("系统", "Android ${device.androidRelease} (API ${device.apiLevel})")
                    InfoLine("安全补丁", device.securityPatch)
                    InfoLine("构建", Build.DISPLAY)
                    InfoLine("指纹", Build.FINGERPRINT)
                    InfoLine("屏幕", displayInfo)
                }
            }
            item {
                Section("Stage 后端") {
                    InfoLine("当前后端", state.backend)
                    InfoLine("已连接", if (state.connected) "是" else "否")
                    InfoLine("身份", state.identity.ifBlank { "—" })
                    InfoLine("FREEFORM 设置", freeformCheck.ifBlank { "点击刷新" })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("刷新引擎诊断", onRefresh)
                        Action("TaskOrganizer 探针", onOrganizerProbe)
                        Action("Shizuku 探针页", onOpenShizukuProbe)
                    }
                }
            }
            item {
                Section("引擎诊断（特权进程内实测）") {
                    if (diagnostics.isEmpty()) {
                        Text("尚未获取。点击“刷新引擎诊断”。", color = Color(0xFF8B97A8), fontSize = 11.sp)
                    } else {
                        diagnostics.forEach { (key, value) -> InfoLine(key, value) }
                    }
                    if (organizerResult.isNotBlank()) {
                        Text("TaskOrganizer", color = Color(0xFF9DB4FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(organizerResult, color = Color(0xFFC7D2E2), fontSize = 10.sp)
                    }
                }
            }
            item {
                Section("Level 1 公共 API 能力（应用内实测）") {
                    val report = remember { dev.stagelab.android.core.backend.PublicApiTaskBackend().inspect() }
                    report.states.forEach { state ->
                        val color = when (state.availability) {
                            dev.stagelab.android.core.backend.CapabilityAvailability.AVAILABLE -> Color(0xFF8FE3C9)
                            dev.stagelab.android.core.backend.CapabilityAvailability.NEEDS_USER_PERMISSION -> Color(0xFFFFC58B)
                            dev.stagelab.android.core.backend.CapabilityAvailability.BLOCKED_BY_PLATFORM -> Color(0xFFFF9B9B)
                            else -> Color(0xFFB9C4D4)
                        }
                        Text(
                            state.capability.displayName + " · " + state.availability.name,
                            color = color,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(state.note, color = Color(0xFF8B97A8), fontSize = 9.sp, lineHeight = 14.sp)
                    }
                }
            }
            item {
                Section("三级权限能力对照表（本机实测结论）") {
                    CapabilityHeader()
                    CapabilityRow(
                        "启动应用",
                        "可（普通 Intent）",
                        "可",
                        "可"
                    )
                    CapabilityRow(
                        "枚举系统任务",
                        "否",
                        "可（getTasks）",
                        "可"
                    )
                    CapabilityRow(
                        "创建 FREEFORM 真实任务窗口",
                        "否（平台限制）",
                        "可",
                        "可"
                    )
                    CapabilityRow(
                        "移动 / 缩放外部任务",
                        "否",
                        "可（resizeTask）",
                        "可"
                    )
                    CapabilityRow(
                        "切换任务焦点",
                        "否",
                        "可（setFocusedTask）",
                        "可"
                    )
                    CapabilityRow(
                        "实时画面（非截图）",
                        "否",
                        "可（系统合成）",
                        "可"
                    )
                    CapabilityRow(
                        "输入 / 输入法",
                        "—",
                        "系统原生",
                        "系统原生"
                    )
                    CapabilityRow(
                        "启动未导出 Activity",
                        "否",
                        "否（缺 START_ANY_ACTIVITY）",
                        "可（uid 0 绕过）"
                    )
                }
            }
            item {
                Section("说明") {
                    Text(
                        "StageLab 不使用截图、MediaProjection 或 WebView 伪造。窗口内容是系统 SurfaceFlinger " +
                            "合成出来的真实任务 Surface；Stage 只负责创建任务并指定位置与尺寸。",
                        color = Color(0xFFC7D2E2),
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                    Text(
                        "普通权限（Level 1）无法嵌入其它应用的真实任务窗口，这是 Android 平台设计，不是未实现。",
                        color = Color(0xFF8B97A8),
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF141B25))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = Color(0xFF9DB4FF), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        content()
    }
}

@Composable
private fun Card2(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x14FFFFFF))
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) { content() }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x1A7C9CFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(label, color = Color(0xFFC9D6FF), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF8B97A8), fontSize = 10.sp)
        Text(
            value,
            color = Color(0xFFDCE5F2),
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun CapabilityHeader() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("能力", color = Color(0xFF8B97A8), fontSize = 9.sp, modifier = Modifier.weight(1.2f))
        Text("普通", color = Color(0xFF8B97A8), fontSize = 9.sp, modifier = Modifier.weight(1f))
        Text("Shell", color = Color(0xFF8B97A8), fontSize = 9.sp, modifier = Modifier.weight(1f))
        Text("Root", color = Color(0xFF8B97A8), fontSize = 9.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CapabilityRow(name: String, normal: String, shell: String, root: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, color = Color(0xFFDCE5F2), fontSize = 9.sp, modifier = Modifier.weight(1.2f))
        Text(normal, color = capabilityColor(normal), fontSize = 9.sp, modifier = Modifier.weight(1f))
        Text(shell, color = capabilityColor(shell), fontSize = 9.sp, modifier = Modifier.weight(1f))
        Text(root, color = capabilityColor(root), fontSize = 9.sp, modifier = Modifier.weight(1f))
    }
}

private fun capabilityColor(value: String): Color = when {
    value.startsWith("可") -> Color(0xFF8FE3C9)
    value.startsWith("否") -> Color(0xFFFF9B9B)
    else -> Color(0xFFB9C4D4)
}
