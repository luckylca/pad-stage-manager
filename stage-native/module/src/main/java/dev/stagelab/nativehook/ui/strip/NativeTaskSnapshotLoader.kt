package dev.stagelab.nativehook.ui.strip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.util.LruCache
import de.robv.android.xposed.XposedHelpers
import dev.stagelab.nativehook.xposed.HookGuard

/**
 * Honest classification of preview fidelity for parked/recent tasks.
 * Never disguises a static snapshot as live surface mirroring.
 */
enum class PreviewMode {
    LIVE_SURFACE_MIRROR,
    NATIVE_TASK_SNAPSHOT,
    UNAVAILABLE_OR_SECURE,
}

data class TaskPreviewResult(
    val taskId: Int,
    val mode: PreviewMode,
    val bitmap: Bitmap?,
    val appIcon: Drawable?,
)

/**
 * Loads native system TaskSnapshots inside `com.android.systemui` via `IActivityTaskManager.getTaskSnapshot`.
 *
 * Technical finding on ColorOS 17 / Android 17 (OPD2515):
 * Once a Flexible Window is parked via `ExitFlexibleTaskToBackStrategy` (`visible=false`), WMS stops
 * producing live frames for that task's SurfaceControl. Therefore parked Stage thumbnails use
 * `PreviewMode.NATIVE_TASK_SNAPSHOT` (the same mechanism as Android Recents), and respect `FLAG_SECURE`
 * by returning `PreviewMode.UNAVAILABLE_OR_SECURE`.
 */
class NativeTaskSnapshotLoader {

    private val bitmapCache = LruCache<Int, Bitmap>(16)

    fun loadPreview(context: Context, taskId: Int, packageName: String, forceRefresh: Boolean = false): TaskPreviewResult {
        val icon = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()

        if (!forceRefresh) {
            bitmapCache.get(taskId)?.let { cached ->
                if (!cached.isRecycled) {
                    return TaskPreviewResult(taskId, PreviewMode.NATIVE_TASK_SNAPSHOT, cached, icon)
                }
            }
        }

        val snapshotResult = fetchNativeTaskSnapshot(taskId)
        if (snapshotResult != null) {
            bitmapCache.put(taskId, snapshotResult)
            return TaskPreviewResult(taskId, PreviewMode.NATIVE_TASK_SNAPSHOT, snapshotResult, icon)
        }

        return TaskPreviewResult(taskId, PreviewMode.UNAVAILABLE_OR_SECURE, null, icon)
    }

    fun invalidate(taskId: Int) {
        bitmapCache.remove(taskId)
    }

    fun clear() {
        bitmapCache.evictAll()
    }

    private fun fetchNativeTaskSnapshot(taskId: Int): Bitmap? {
        return runCatching {
            // Android 17 (API 37) verified API: android.window.TaskSnapshotManager
            val snapshot = fetchViaTaskSnapshotManager(taskId)
                ?: fetchViaActivityTaskManagerFallback(taskId)
                ?: return null

            // Respect FLAG_SECURE (TaskSnapshot.isRealSnapshot == false or buffer == null)
            val isReal = runCatching {
                XposedHelpers.callMethod(snapshot, "isRealSnapshot") as? Boolean
            }.getOrNull() ?: true
            if (!isReal) {
                HookGuard.info("SNAPSHOT task=$taskId skipped (FLAG_SECURE / non-real snapshot)")
                return null
            }

            val hwBuffer = XposedHelpers.callMethod(snapshot, "getHardwareBuffer") as? HardwareBuffer ?: return null
            val colorSpace = runCatching {
                XposedHelpers.callMethod(snapshot, "getColorSpace") as? ColorSpace
            }.getOrNull()

            val wrapped = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace) ?: return null
            val swCopy = wrapped.copy(Bitmap.Config.ARGB_8888, false)
            hwBuffer.close()
            HookGuard.info("SNAPSHOT task=$taskId loaded (${swCopy.width}x${swCopy.height}, NATIVE_TASK_SNAPSHOT)")
            swCopy
        }.onFailure {
            HookGuard.warn("SNAPSHOT fetch failed for task=$taskId", it)
        }.getOrNull()
    }

    private fun fetchViaTaskSnapshotManager(taskId: Int): Any? {
        return runCatching {
            val mgrCls = Class.forName("android.window.TaskSnapshotManager")
            val mgr = XposedHelpers.callStaticMethod(mgrCls, "getInstance")
            // resolution: 1 = RESOLUTION_LOW (fast thumbnail), 0 = RESOLUTION_HIGH
            XposedHelpers.callMethod(mgr, "getTaskSnapshot", taskId, 1)
                ?: XposedHelpers.callMethod(mgr, "getTaskSnapshot", taskId, 0)
                ?: XposedHelpers.callMethod(mgr, "takeTaskSnapshot", taskId, true)
        }.onFailure {
            HookGuard.warn("TaskSnapshotManager lookup failed for task=$taskId", it)
        }.getOrNull()
    }

    private fun fetchViaActivityTaskManagerFallback(taskId: Int): Any? {
        return runCatching {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val service = XposedHelpers.callStaticMethod(atmClass, "getService")
            XposedHelpers.callMethod(service, "getTaskSnapshot", taskId, true)
        }.getOrNull()
    }
}
