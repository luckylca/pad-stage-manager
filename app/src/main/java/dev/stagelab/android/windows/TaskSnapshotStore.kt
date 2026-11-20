package dev.stagelab.android.windows

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.stagelab.android.core.stage.StageRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory LRU cache of real Android TaskSnapshot thumbnails fetched from the privileged engine.
 *
 * Used by both the iPad-style 3D left rail (allowing every recent group to show real app previews
 * without shrinking live tasks into 220dp boxes) and [StageTransitionOverlay] (60/120fps spring
 * transitions during group switches and minimize).
 */
object TaskSnapshotStore {

    private data class Entry(
        val bitmap: Bitmap,
        val imageBitmap: ImageBitmap,
        val capturedAt: Long
    )

    private val cache = object : LruCache<Int, Entry>(24) {}
    private val inflight = ConcurrentHashMap.newKeySet<Int>()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun bitmapFor(taskId: Int): Bitmap? = if (taskId > 0) cache.get(taskId)?.bitmap else null

    fun imageBitmapFor(taskId: Int): ImageBitmap? = if (taskId > 0) cache.get(taskId)?.imageBitmap else null

    fun evict(taskId: Int) {
        if (taskId > 0) {
            cache.remove(taskId)
            _version.value++
        }
    }

    /**
     * Captures a fresh snapshot of [taskId] if [force] is true or no recent snapshot exists.
     * Safe to call from any coroutine; concurrent requests for the same taskId are deduplicated.
     */
    suspend fun capture(taskId: Int, force: Boolean = false, maxDim: Int = 420): Bitmap? {
        if (taskId <= 0) return null
        val existing = cache.get(taskId)
        val now = System.currentTimeMillis()
        if (!force && existing != null && now - existing.capturedAt < 2500L) {
            return existing.bitmap
        }
        if (!inflight.add(taskId)) return existing?.bitmap
        return try {
            withContext(Dispatchers.IO) {
                val data = runCatching {
                    StageRuntime.command(
                        JSONObject()
                            .put("cmd", "snapshot")
                            .put("taskId", taskId)
                            .put("maxDim", maxDim)
                    )
                }.getOrNull() ?: return@withContext existing?.bitmap

                if (!data.optBoolean("available", false)) {
                    android.util.Log.d("TaskSnapshotStore", "snapshot unavailable task=$taskId reason=${data.optString("reason")}")
                    return@withContext existing?.bitmap
                }
                val b64 = data.optString("jpeg")
                if (b64.isEmpty()) return@withContext existing?.bitmap
                val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                    ?: return@withContext existing?.bitmap
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@withContext existing?.bitmap
                android.util.Log.d("TaskSnapshotStore", "snapshot ok task=$taskId ${decoded.width}x${decoded.height}")
                val entry = Entry(decoded, decoded.asImageBitmap(), System.currentTimeMillis())
                cache.put(taskId, entry)
                _version.value++
                decoded
            }
        } finally {
            inflight.remove(taskId)
        }
    }
}
