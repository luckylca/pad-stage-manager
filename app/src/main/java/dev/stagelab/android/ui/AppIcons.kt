package dev.stagelab.android.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.ConcurrentHashMap

object AppIconCache {

    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun icon(context: Context, packageName: String): ImageBitmap? {
        cache[packageName]?.let { return it }
        return runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val size = 96
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bitmap.asImageBitmap().also { cache[packageName] = it }
        }.getOrNull()
    }

    fun label(context: Context, packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}

data class LaunchableApp(
    val packageName: String,
    val activityName: String,
    val label: String
)

fun loadLaunchableApps(context: Context): List<LaunchableApp> {
    val manager = context.packageManager
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    val resolved = manager.queryIntentActivities(
        intent,
        PackageManager.MATCH_ALL or PackageManager.MATCH_DISABLED_COMPONENTS
    )
    return resolved.asSequence()
        .map { info ->
            LaunchableApp(
                packageName = info.activityInfo.packageName,
                activityName = info.activityInfo.name,
                label = info.loadLabel(manager).toString()
            )
        }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
        .toList()
}
