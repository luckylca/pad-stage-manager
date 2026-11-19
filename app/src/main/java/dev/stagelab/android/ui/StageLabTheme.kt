package dev.stagelab.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StageLabColors = darkColorScheme(
    primary = Color(0xFFB9C8FF),
    onPrimary = Color(0xFF18264A),
    secondary = Color(0xFF9ED8C8),
    tertiary = Color(0xFFFFC58B),
    background = Color(0xFF0D1117),
    surface = Color(0xFF151B24),
    surfaceVariant = Color(0xFF202936),
    onSurface = Color(0xFFE8EDF5),
    onSurfaceVariant = Color(0xFFA8B3C3)
)

@Composable
fun StageLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StageLabColors,
        content = content
    )
}
