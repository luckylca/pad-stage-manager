package dev.stagelab.android.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** One application entry in the stage dock. */
data class DockApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: ImageBitmap?
)

/** Packages that make a sensible default dock on a phone/tablet, in iPad-ish order. */
private val PREFERRED = listOf(
    "mark.via",
    "com.android.chrome",
    "com.coloros.note",
    "com.android.mms",
    "com.coloros.calculator",
    "tv.danmaku.bili",
    "com.netease.cloudmusic",
    "org.telegram.messenger",
    "com.coloros.alarmclock",
    "com.coloros.filemanager",
    "com.coloros.calendar",
    "com.coloros.weather2"
)

fun loadDockApps(context: Context, limit: Int = 7): List<DockApp> {
    val launchable = loadLaunchableApps(context)
    val byPackage = launchable.associateBy { it.packageName }
    val result = ArrayList<DockApp>()
    PREFERRED.forEach { pkg ->
        val app = byPackage[pkg] ?: return@forEach
        result += DockApp(app.packageName, app.activityName, app.label, AppIconCache.icon(context, pkg))
    }
    if (result.size < 4) {
        launchable.forEach { app ->
            if (result.none { it.packageName == app.packageName }) {
                result += DockApp(app.packageName, app.activityName, app.label, AppIconCache.icon(context, app.packageName))
            }
        }
    }
    return result.take(limit)
}

@Composable
fun rememberDockApps(context: Context): List<DockApp> =
    androidx.compose.runtime.remember { loadDockApps(context) }
