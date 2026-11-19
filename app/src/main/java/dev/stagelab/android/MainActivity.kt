package dev.stagelab.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.stagelab.android.demo.DemoActivity
import dev.stagelab.android.demo.DemoMode
import dev.stagelab.android.shizuku.ShizukuProbeActivity
import dev.stagelab.android.ui.StageLabScreen
import dev.stagelab.android.ui.StageLabTheme
import dev.stagelab.android.core.backend.PublicApiTaskBackend
import dev.stagelab.android.core.model.DeviceProfile

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deviceProfile = DeviceProfile.read()
        val backendReport = PublicApiTaskBackend().inspect()

        setContent {
            StageLabTheme {
                StageLabScreen(
                    deviceProfile = deviceProfile,
                    backendReport = backendReport,
                    onOpenDemo = ::openDemoTask,
                    onOpenShizukuProbe = ::openShizukuProbe
                )
            }
        }
    }

    private fun openDemoTask(mode: DemoMode) {
        val intent = Intent(this, DemoActivity::class.java).apply {
            putExtra(DemoActivity.EXTRA_MODE, mode.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        startActivity(intent)
    }

    private fun openShizukuProbe() {
        startActivity(Intent(this, ShizukuProbeActivity::class.java))
    }
}
