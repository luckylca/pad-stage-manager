package dev.stagelab.android.stage

import android.content.Context
import android.util.Log
import org.json.JSONObject

/** Persists the stage session (groups, windows, geometry) so a restart restores the workspace. */
class StageSessionStore(context: Context) {

    private val prefs = context.getSharedPreferences("stage_session", Context.MODE_PRIVATE)

    fun load(): StageSession? = runCatching {
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        StageSession.fromJson(JSONObject(raw))
    }.onFailure { Log.w("StageSessionStore", "load failed: ${it.message}") }.getOrNull()

    fun save(session: StageSession) {
        runCatching {
            prefs.edit().putString(KEY_SESSION, session.toJson().toString()).apply()
        }.onFailure { Log.w("StageSessionStore", "save failed: ${it.message}") }
    }

    fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    private companion object {
        const val KEY_SESSION = "session_v2"
    }
}
