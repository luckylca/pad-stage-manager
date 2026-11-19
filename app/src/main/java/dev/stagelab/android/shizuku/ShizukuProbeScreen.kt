package dev.stagelab.android.shizuku

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class ShizukuProbeState(
    val shizukuStatus: String = "Not checked",
    val appPermission: String = "Not checked",
    val remoteUid: String = "Unknown",
    val selinuxContext: String = "Unknown",
    val manageActivityTasks: String = "Not checked",
    val serviceStatus: String = "Disconnected",
    val runtimeIdentity: String = "Not connected",
    val organizerResult: String = "Not tested",
    val callbackEvents: String = "No callbacks queried yet."
)

@Composable
internal fun ShizukuProbeScreen(
    state: ShizukuProbeState,
    onRefresh: () -> Unit,
    onConnect: () -> Unit,
    onRegister: () -> Unit,
    onOpenDemoTasks: () -> Unit,
    onRefreshCallbacks: () -> Unit,
    onUnregister: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D1117)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("SHIZUKU TASK ORGANIZER PROBE", color = Color(0xFFEAF0FA), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("只验证权限、注册和回调；不会移动任务、改变窗口边界或修改 Surface。", color = Color(0xFF9AA8B7), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                ProbeCard("SHIZUKU SERVER") {
                    ProbeRow("Binder", state.shizukuStatus)
                    ProbeRow("App permission", state.appPermission)
                    ProbeRow("Remote UID", state.remoteUid)
                    ProbeRow("SELinux", state.selinuxContext)
                    ProbeRow("MANAGE_ACTIVITY_TASKS", state.manageActivityTasks)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onRefresh) { Text("Refresh") }
                        Button(onClick = onConnect) { Text("Request access / connect") }
                    }
                }
            }
            item {
                ProbeCard("USER SERVICE") {
                    ProbeRow("Connection", state.serviceStatus)
                    Text(state.runtimeIdentity, color = Color(0xFFDDE5F0), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onRegister, enabled = state.serviceStatus == "Connected", modifier = Modifier.fillMaxWidth()) {
                        Text("Register TaskOrganizer (read-only)")
                    }
                }
            }
            item {
                ProbeCard("TASKORGANIZER RESULT") {
                    Text(state.organizerResult, color = Color(0xFFDDE5F0), style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(color = Color(0xFF2B3746))
                    Text("Callback events", color = Color(0xFFB9C8FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(state.callbackEvents, color = Color(0xFFDDE5F0), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefreshCallbacks, enabled = state.serviceStatus == "Connected") { Text("Refresh events") }
                        OutlinedButton(onClick = onUnregister, enabled = state.serviceStatus == "Connected") { Text("Unregister") }
                    }
                }
            }
            item {
                ProbeCard("CALLBACK TEST") {
                    Text("先注册 TaskOrganizer，再打开三个测试任务；返回本页后刷新回调日志。此步骤不执行 reparent。", color = Color(0xFF9AA8B7), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onOpenDemoTasks, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Counter / Motion / Scroll tasks")
                    }
                }
            }
            item {
                Text("Important: Shizuku authorization and MANAGE_ACTIVITY_TASKS are separate checks. The actual registerOrganizer() result is the decisive platform test.", color = Color(0xFF758194), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ProbeCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151B24)), shape = RoundedCornerShape(18.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Color(0xFFB9C8FF), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            content()
        }
    }
}

@Composable
private fun ProbeRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF8995A6), style = MaterialTheme.typography.bodySmall)
        Text(value, color = Color(0xFFE0E7F1), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
