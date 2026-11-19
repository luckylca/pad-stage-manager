package dev.stagelab.android.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.stagelab.android.ui.StageLabTheme

enum class DemoMode {
    COUNTER,
    MOTION,
    SCROLL,
    VIDEO
}

class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mode = intent.getStringExtra(EXTRA_MODE)
            ?.let { value -> DemoMode.values().firstOrNull { it.name == value } }
            ?: DemoMode.COUNTER

        setContent {
            StageLabTheme {
                DemoScreen(mode = mode)
            }
        }
    }

    companion object {
        const val EXTRA_MODE = "dev.stagelab.android.demo.MODE"
    }
}
