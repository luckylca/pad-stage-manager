package dev.stagelab.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stagelab.android.core.backend.BackendReport
import dev.stagelab.android.core.backend.CapabilityAvailability
import dev.stagelab.android.core.backend.CapabilityState
import dev.stagelab.android.core.model.DeviceProfile
import dev.stagelab.android.demo.DemoMode

@Composable
fun StageLabScreen(
    deviceProfile: DeviceProfile,
    backendReport: BackendReport,
    onOpenDemo: (DemoMode) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0D1117)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { HeaderSection() }
            item { LiveSurfaceStatusCard() }
            item { DeviceProfileCard(deviceProfile) }
            item { BackendSection(backendReport) }
            item { DemoTargetsSection(onOpenDemo) }
            item { NextStepsCard() }
            item {
                Text(
                    text = "StageLab 0.1.0  •  Research build",
                    color = Color(0xFF758194),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun HeaderSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "STAGELAB",
                color = Color(0xFFEAF0FA),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Surface(
                color = Color(0xFF233047),
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = "LIVE TASK POC",
                    color = Color(0xFFB9C8FF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        Text(
            text = "A live workspace for Android tasks",
            color = Color(0xFFB5C0D0),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "真实窗口 · 实时画面 · 连续切换",
            color = Color(0xFF758194),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun LiveSurfaceStatusCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF182331)),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIVE SURFACE HOST",
                    color = Color(0xFFB9C8FF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                StatusPill(
                    text = "NOT CONNECTED",
                    color = Color(0xFFFFC58B),
                    background = Color(0xFF403020)
                )
            }
            Text(
                text = "The real task surface is the product.",
                color = Color(0xFFF0F4FA),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "This build prepares the app shell and dynamic test tasks. It does not yet capture, embed, or rearrange other apps' live surfaces.",
                color = Color(0xFFA8B3C3),
                style = MaterialTheme.typography.bodyMedium
            )
            HorizontalDivider(color = Color(0xFF2B3746))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Metric(label = "Task host", value = "Pending")
                Metric(label = "Transitions", value = "Pending")
                Metric(label = "Input route", value = "Pending")
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Color(0xFF758194), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color(0xFFE8EDF5), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DeviceProfileCard(profile: DeviceProfile) {
    SectionCard(title = "DEVICE PROFILE", subtitle = "Runtime information read from Android") {
        Text(
            text = profile.displayName,
            color = Color(0xFFE8EDF5),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        InfoRow("Android", profile.androidRelease.ifBlank { "Unknown" } + "  (API " + profile.apiLevel + ")")
        InfoRow("Security patch", profile.securityPatch.ifBlank { "Unknown" })
    }
}

@Composable
private fun BackendSection(report: BackendReport) {
    SectionCard(
        title = "PERMISSION BACKENDS",
        subtitle = "Use the lowest privilege that can satisfy a capability"
    ) {
        BackendTierCard(
            title = "01  Public Android APIs",
            subtitle = "Base application and workspace UI",
            status = "ACTIVE",
            statusColor = Color(0xFF9ED8C8),
            statusBackground = Color(0xFF1D3934)
        )
        BackendTierCard(
            title = "02  ADB / Shizuku",
            subtitle = "First candidate for privileged task inspection",
            status = "NOT CONNECTED",
            statusColor = Color(0xFFB9C8FF),
            statusBackground = Color(0xFF26334D)
        )
        BackendTierCard(
            title = "03  KernelSU Root",
            subtitle = "Optional fallback; Root does not imply signature permissions",
            status = "NOT CONNECTED",
            statusColor = Color(0xFFFFC58B),
            statusBackground = Color(0xFF403020)
        )
        HorizontalDivider(color = Color(0xFF2B3746))
        Text(
            text = "Public API capability baseline",
            color = Color(0xFFE8EDF5),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        report.states.forEach { state -> CapabilityRow(state) }
    }
}

@Composable
private fun BackendTierCard(
    title: String,
    subtitle: String,
    status: String,
    statusColor: Color,
    statusBackground: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color(0xFFE8EDF5), style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = Color(0xFF8995A6), style = MaterialTheme.typography.bodySmall)
        }
        StatusPill(status, statusColor, statusBackground)
    }
}

@Composable
private fun CapabilityRow(state: CapabilityState) {
    val statusColor = when (state.availability) {
        CapabilityAvailability.AVAILABLE -> Color(0xFF9ED8C8)
        CapabilityAvailability.NEEDS_USER_PERMISSION -> Color(0xFFFFC58B)
        CapabilityAvailability.BLOCKED_BY_PLATFORM -> Color(0xFFFF9A9A)
        CapabilityAvailability.NOT_IMPLEMENTED -> Color(0xFFB9C8FF)
        CapabilityAvailability.NOT_PROBED -> Color(0xFF9AA6B7)
    }
    val statusText = when (state.availability) {
        CapabilityAvailability.AVAILABLE -> "AVAILABLE"
        CapabilityAvailability.NEEDS_USER_PERMISSION -> "USER GRANT"
        CapabilityAvailability.BLOCKED_BY_PLATFORM -> "RESTRICTED"
        CapabilityAvailability.NOT_IMPLEMENTED -> "TODO"
        CapabilityAvailability.NOT_PROBED -> "NOT PROBED"
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(state.capability.displayName, color = Color(0xFFDDE5F0), style = MaterialTheme.typography.bodyMedium)
            Text(statusText, color = statusColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Text(state.note, color = Color(0xFF8995A6), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DemoTargetsSection(onOpenDemo: (DemoMode) -> Unit) {
    SectionCard(
        title = "DYNAMIC TASK TARGETS",
        subtitle = "Open independent test activities for the live-surface PoC"
    ) {
        DemoTarget(
            title = "Counter",
            description = "A number that keeps changing while the task is open.",
            action = "OPEN COUNTER",
            onClick = { onOpenDemo(DemoMode.COUNTER) }
        )
        DemoTarget(
            title = "Motion",
            description = "A continuously animated shape for frame-update checks.",
            action = "OPEN MOTION",
            onClick = { onOpenDemo(DemoMode.MOTION) }
        )
        DemoTarget(
            title = "Scrollable list",
            description = "A touch-driven list for later input and focus tests.",
            action = "OPEN SCROLL",
            onClick = { onOpenDemo(DemoMode.SCROLL) }
        )
        Text(
            text = "These are normal Android tasks for now. They are not embedded previews.",
            color = Color(0xFF8995A6),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun DemoTarget(
    title: String,
    description: String,
    action: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color(0xFFE8EDF5), style = MaterialTheme.typography.titleSmall)
            Text(description, color = Color(0xFF8995A6), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onClick) {
            Text(action, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NextStepsCard() {
    SectionCard(
        title = "NEXT TECHNICAL GATES",
        subtitle = "The first milestone is permission and API feasibility, not visual polish"
    ) {
        NumberedStep("01", "Connect the tablet and record the exact ColorOS build.")
        NumberedStep("02", "Check Shell access to MANAGE_ACTIVITY_TASKS.")
        NumberedStep("03", "Attempt a minimal TaskOrganizer registration.")
        NumberedStep("04", "Only then prototype Task leash and live Surface hosting.")
    }
}

@Composable
private fun NumberedStep(number: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(number, color = Color(0xFF9ED8C8), fontWeight = FontWeight.Bold)
        Text(text, color = Color(0xFFB5C0D0), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151B24)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                color = Color(0xFFB9C8FF),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp
            )
            Text(
                text = subtitle,
                color = Color(0xFF8995A6),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF8995A6), style = MaterialTheme.typography.bodySmall)
        Text(value, color = Color(0xFFE0E7F1), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusPill(text: String, color: Color, background: Color) {
    Surface(color = background, shape = RoundedCornerShape(50)) {
        Text(
            text = text,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
        )
    }
}
