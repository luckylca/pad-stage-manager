package dev.stagelab.android.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import org.json.JSONArray

/** One application entry in the stage dock. */
data class DockApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: ImageBitmap?,
    val pinned: Boolean = true,
    val supportsMultiWindow: Boolean = false
)

/** Structured iPad-style Dock model: pinned apps on the left, recent/suggested on the right. */
data class DockModel(
    val pinned: List<DockApp> = emptyList(),
    val recent: List<DockApp> = emptyList()
)

/** Packages that make a sensible default pinned dock on a tablet, in iPad-ish order. */
private val DEFAULT_PINNED = listOf(
    "mark.via",
    "com.android.chrome",
    "com.coloros.note",
    "com.android.mms",
    "com.coloros.calculator",
    "tv.danmaku.bili",
    "com.netease.cloudmusic"
)

private val SUGGESTED_FALLBACK = listOf(
    "org.telegram.messenger",
    "com.coloros.filemanager",
    "com.coloros.calendar",
    "com.coloros.alarmclock",
    "com.coloros.weather2",
    "com.android.settings"
)

/**
 * Persists user-pinned Dock apps and recently used apps (P5).
 */
object DockConfigStore {
    private const val PREFS = "stagelab_dock_v1"
    private const val KEY_PINNED = "pinned_packages"
    private const val KEY_RECENT = "recent_packages"
    private const val MAX_RECENT = 8

    fun pinnedPackages(context: Context): List<String>? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PINNED, null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }.getOrNull()
    }

    fun savePinned(context: Context, packages: List<String>) {
        val arr = JSONArray()
        packages.distinct().forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PINNED, arr.toString()).apply()
    }

    fun pin(context: Context, packageName: String, currentPinned: List<String>) {
        if (packageName in currentPinned) return
        savePinned(context, currentPinned + packageName)
    }

    fun unpin(context: Context, packageName: String, currentPinned: List<String>) {
        savePinned(context, currentPinned.filterNot { it == packageName })
    }

    fun recentPackages(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECENT, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun recordRecent(context: Context, packageName: String) {
        if (packageName.isBlank() || packageName == context.packageName) return
        val updated = (listOf(packageName) + recentPackages(context).filterNot { it == packageName })
            .take(MAX_RECENT)
        val arr = JSONArray()
        updated.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECENT, arr.toString()).apply()
    }
}

/**
 * Inspects the Activity manifest metadata to determine whether Android genuinely allows multiple
 * independent task windows for this activity (P5/P6).
 *
 * Apps declaring `singleTask` or `singleInstance` (and not `documentLaunchMode = always/intoExisting`)
 * will reuse their existing task rather than creating a second window; we never fake multi-window
 * support for them.
 */
fun supportsMultiInstance(context: Context, packageName: String, activityName: String): Boolean {
    if (packageName.isBlank() || activityName.isBlank()) return false
    return runCatching {
        val info = context.packageManager.getActivityInfo(ComponentName(packageName, activityName), 0)
        val docAlways = info.documentLaunchMode == ActivityInfo.DOCUMENT_LAUNCH_ALWAYS ||
            info.documentLaunchMode == ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING
        val standardLaunch = info.launchMode == ActivityInfo.LAUNCH_MULTIPLE ||
            info.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP
        docAlways || standardLaunch
    }.getOrDefault(false)
}

fun loadDockModel(
    context: Context,
    pinnedLimit: Int = 7,
    recentLimit: Int = 3
): DockModel {
    val launchable = loadLaunchableApps(context)
    val byPackage = launchable.associateBy { it.packageName }

    val savedPinned = DockConfigStore.pinnedPackages(context)
    val pinnedPkgs = if (savedPinned != null) {
        savedPinned
    } else {
        val seeded = DEFAULT_PINNED.filter { byPackage.containsKey(it) }.toMutableList()
        if (seeded.size < 4) {
            launchable.forEach { app ->
                if (app.packageName !in seeded && app.packageName != context.packageName) {
                    seeded += app.packageName
                }
            }
        }
        val initial = seeded.take(pinnedLimit)
        DockConfigStore.savePinned(context, initial)
        initial
    }

    val pinned = pinnedPkgs.mapNotNull { pkg ->
        val app = byPackage[pkg] ?: return@mapNotNull null
        DockApp(
            packageName = app.packageName,
            activityName = app.activityName,
            label = app.label,
            icon = AppIconCache.icon(context, pkg),
            pinned = true,
            supportsMultiWindow = supportsMultiInstance(context, app.packageName, app.activityName)
        )
    }.take(pinnedLimit)

    val pinnedSet = pinned.map { it.packageName }.toSet()
    val recentCandidates = (DockConfigStore.recentPackages(context) + SUGGESTED_FALLBACK)
        .distinct()
        .filter { it !in pinnedSet && it != context.packageName }

    val recent = recentCandidates.mapNotNull { pkg ->
        val app = byPackage[pkg] ?: return@mapNotNull null
        DockApp(
            packageName = app.packageName,
            activityName = app.activityName,
            label = app.label,
            icon = AppIconCache.icon(context, pkg),
            pinned = false,
            supportsMultiWindow = supportsMultiInstance(context, app.packageName, app.activityName)
        )
    }.take(recentLimit)

    return DockModel(pinned = pinned, recent = recent)
}

fun loadDockApps(context: Context, limit: Int = 7): List<DockApp> =
    loadDockModel(context, pinnedLimit = limit, recentLimit = 0).pinned

@Composable
fun rememberDockApps(context: Context): List<DockApp> =
    androidx.compose.runtime.remember { loadDockApps(context) }
