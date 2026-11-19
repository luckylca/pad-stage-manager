package dev.stagelab.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** App chooser. Returns the selected launchable activity to the stage host. */
class AppPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        dev.stagelab.android.core.stage.StageRuntime.attach(this)
        val apps = loadLaunchableApps(this)
        setContent {
            StageLabTheme {
                AppPickerScreen(
                    apps = apps,
                    onPick = { app ->
                        lifecycleScope.launch {
                            dev.stagelab.android.core.stage.StageRuntime.openApp(
                                packageName = app.packageName,
                                title = app.label,
                                activity = app.activityName
                            )
                            finish()
                        }
                    },
                    onCancel = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "dev.stagelab.android.extra.PACKAGE"
        const val EXTRA_ACTIVITY = "dev.stagelab.android.extra.ACTIVITY"
        const val EXTRA_LABEL = "dev.stagelab.android.extra.LABEL"
    }
}

@Composable
private fun AppPickerScreen(
    apps: List<LaunchableApp>,
    onPick: (LaunchableApp) -> Unit,
    onCancel: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, apps) {
        if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A0E14), Color(0xFF131B26))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("选择应用", color = Color(0xFFEDF2FB), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("将以真实任务窗口在 Stage 中打开", color = Color(0xFF8B97A8), fontSize = 11.sp)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x14FFFFFF))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text("取消", color = Color(0xFFD5DEEE), fontSize = 11.sp)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x14FFFFFF))
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                if (query.isEmpty()) {
                    Text("搜索应用…", color = Color(0xFF7A8798), fontSize = 13.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color(0xFFE8EEF8), fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0x0EFFFFFF))
                            .clickable { onPick(app) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val icon = remember(app.packageName) { AppIconCache.icon(context, app.packageName) }
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x1AFFFFFF)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (icon != null) {
                                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(42.dp))
                            } else {
                                Text(app.label.take(1), color = Color.White, fontSize = 16.sp)
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, color = Color(0xFFE8EEF8), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(app.packageName, color = Color(0xFF7A8798), fontSize = 10.sp)
                        }
                        Text("打开", color = Color(0xFF9DB4FF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
