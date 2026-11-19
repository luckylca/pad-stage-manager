package dev.stagelab.android.stage

import android.content.Context
import android.util.Log
import dev.stagelab.android.core.stage.StageRuntime
import org.json.JSONObject

/**
 * Owns the two global settings Stage Manager needs to turn on, and their safe restoration.
 *
 * Rules:
 *  * the original value is captured before the first write;
 *  * on stop, a setting is restored only when its current value is still the value we wrote
 *    (i.e. the user did not change it in the meantime);
 *  * `null` / unset originals are restored by deleting the key.
 */
object StageSettingsGuard {

    private const val TAG = "StageSettings"
    private const val PREFS = "stage_mode"
    private const val KEY = "global_settings_snapshot"

    private val settings = listOf(
        "enable_freeform_support" to "1",
        "force_resizable_activities" to "1"
    )

    suspend fun apply(context: Context) {
        if (!StageRuntime.isConnected) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val snapshot = JSONObject(prefs.getString(KEY, null) ?: "{}")
        var changed = false
        settings.forEach { (name, value) ->
            if (!snapshot.has(name)) {
                val original = readGlobal(name)
                snapshot.put(name, original ?: JSONObject.NULL)
                changed = true
                Log.i(TAG, "captured $name=$original")
            }
            if (readGlobal(name) != value) {
                writeGlobal(name, value)
            }
        }
        if (changed) {
            prefs.edit().putString(KEY, snapshot.toString()).apply()
        }
    }

    suspend fun restore(context: Context) {
        if (!StageRuntime.isConnected) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return
        val snapshot = runCatching { JSONObject(raw) }.getOrNull() ?: return
        settings.forEach { (name, value) ->
            if (!snapshot.has(name)) return@forEach
            val current = readGlobal(name)
            if (current != value) {
                Log.i(TAG, "keep $name=$current (user changed it, not restoring)")
                return@forEach
            }
            val original = snapshot.opt(name)
            if (original == null || original == JSONObject.NULL) {
                runCatching { StageRuntime.runShell("settings delete global $name") }
            } else {
                writeGlobal(name, original.toString())
            }
            Log.i(TAG, "restored $name=$original")
        }
        prefs.edit().remove(KEY).apply()
    }

    private suspend fun readGlobal(name: String): String? {
        val output = runCatching { StageRuntime.runShell("settings get global $name") }
            .getOrDefault("")
            .trim()
        if (output.isBlank() || output == "null" || output.startsWith("失败")) return null
        return output.lineSequence().lastOrNull { it.isNotBlank() }?.trim()
    }

    private suspend fun writeGlobal(name: String, value: String) {
        runCatching { StageRuntime.runShell("settings put global $name $value") }
    }
}
