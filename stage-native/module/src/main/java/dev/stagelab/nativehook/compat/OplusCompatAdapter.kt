package dev.stagelab.nativehook.compat

import android.os.Build
import android.os.Bundle
import android.graphics.Rect
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import dev.stagelab.nativehook.xposed.HookGuard
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-version ColorOS (14 / 15 / 16 / 17, API 34–37) compatibility adapter & live Hook probe registry.
 *
 * Centralizes private class/method/Bundle-key discovery with multi-candidate fallbacks and records
 * exact hit/fallback/missing status for the built-in Environment Diagnostics card in MainActivity.
 */
object OplusCompatAdapter {

    const val SYS_DIAG_PATH = "/data/system/stagelab_diag_sys.properties"
    const val UI_DIAG_PATH = "/data/local/tmp/stagelab_diag_ui.properties"

    enum class Status { HIT, FALLBACK, MISSING, ERROR }

    data class ProbeRecord(
        val key: String,
        val status: Status,
        val detail: String,
    )

    private val probes = ConcurrentHashMap<String, ProbeRecord>()

    fun record(key: String, status: Status, detail: String) {
        val existing = probes[key]
        if (existing != null && existing.status == Status.HIT && status != Status.HIT) return
        probes[key] = ProbeRecord(key, status, detail)
    }

    fun getAllProbes(): Map<String, ProbeRecord> = probes.toMap()

    /**
     * Resolves ColorOS ROM version string across ColorOS 14 / 15 / 16 / 17 (OPPO / OnePlus / realme).
     */
    fun getColorOsVersion(): String {
        val keys = listOf(
            "ro.build.version.oplusrom",
            "ro.oplus.version.my_rom",
            "ro.build.display.oplus",
            "ro.rom.version",
        )
        for (k in keys) {
            val v = getSystemProperty(k)
            if (v.isNotBlank()) return v
        }
        return "Unknown (Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT})"
    }

    fun getSystemProperty(key: String): String {
        return runCatching {
            val sp = Class.forName("android.os.SystemProperties")
            (XposedHelpers.callStaticMethod(sp, "get", key, "") as? String).orEmpty().trim()
        }.getOrDefault("")
    }

    /**
     * Tries multiple candidate class names in order (primary -> fallback) and records probe status.
     */
    fun findClassAny(
        probeKey: String,
        classLoader: ClassLoader,
        vararg candidates: String,
    ): Class<*>? {
        candidates.forEachIndexed { idx, name ->
            try {
                val cls = XposedHelpers.findClass(name, classLoader)
                val status = if (idx == 0) Status.HIT else Status.FALLBACK
                record(probeKey, status, name.substringAfterLast('.'))
                return cls
            } catch (_: Throwable) {
                // try next candidate
            }
        }
        record(probeKey, Status.MISSING, candidates.firstOrNull()?.substringAfterLast('.') ?: "none")
        HookGuard.warn("OplusCompatAdapter: missing class for $probeKey (${candidates.joinToString()})")
        return null
    }

    /**
     * Hooks all overloads of the first matching method name from [nameCandidates].
     * If none match literally, optionally scans declared methods containing [fuzzyKeyword].
     */
    fun hookMethodAny(
        probeKey: String,
        cls: Class<*>,
        nameCandidates: List<String>,
        hook: XC_MethodHook,
        fuzzyKeyword: String? = null,
    ): Boolean {
        nameCandidates.forEachIndexed { idx, methodName ->
            try {
                val unhooks = XposedBridge.hookAllMethods(cls, methodName, hook)
                if (unhooks.isNotEmpty()) {
                    val status = if (idx == 0) Status.HIT else Status.FALLBACK
                    record(probeKey, status, "${cls.simpleName}#$methodName(${unhooks.size})")
                    HookGuard.info("OplusCompatAdapter: [$status] $probeKey -> ${cls.simpleName}#$methodName (${unhooks.size} overloads)")
                    return true
                }
            } catch (t: Throwable) {
                HookGuard.warn("OplusCompatAdapter: hook error on ${cls.simpleName}#$methodName", t)
            }
        }
        if (!fuzzyKeyword.isNullOrBlank()) {
            val matched = cls.declaredMethods.filter {
                it.name.contains(fuzzyKeyword, ignoreCase = true) && !it.name.contains("lambda") && !it.name.startsWith("-")
            }
            if (matched.isNotEmpty()) {
                var hookedCount = 0
                for (m in matched) {
                    runCatching {
                        XposedBridge.hookMethod(m, hook)
                        hookedCount++
                    }
                }
                if (hookedCount > 0) {
                    record(probeKey, Status.FALLBACK, "${cls.simpleName}#*${fuzzyKeyword}*($hookedCount)")
                    HookGuard.info("OplusCompatAdapter: [FALLBACK] $probeKey -> fuzzy *$fuzzyKeyword* ($hookedCount methods)")
                    return true
                }
            }
        }
        record(probeKey, Status.MISSING, "${cls.simpleName}#${nameCandidates.firstOrNull().orEmpty()}")
        HookGuard.warn("OplusCompatAdapter: [MISSING] $probeKey on ${cls.simpleName} (tried ${nameCandidates.joinToString()})")
        return false
    }

    /**
     * Multi-version Bundle visual bounds extractor (ColorOS 14 / 15 / 16 / 17).
     */
    @Suppress("DEPRECATION")
    fun extractBundleRect(extra: Bundle?, vararg keys: String): Rect? {
        if (extra == null) return null
        for (k in keys) {
            val r = runCatching { extra.getParcelable(k) as? Rect }.getOrNull()
            if (r != null && !r.isEmpty) return Rect(r)
        }
        return null
    }

    /**
     * Multi-version Bundle float extractor (e.g. LaunchScale / oplus_flexible_scale).
     */
    fun extractBundleFloat(extra: Bundle?, minVal: Float, maxVal: Float, vararg keys: String): Float? {
        if (extra == null) return null
        for (k in keys) {
            if (!extra.containsKey(k)) continue
            val v = runCatching { extra.getFloat(k, -1f) }.getOrDefault(-1f)
            if (v in minVal..maxVal) return v
        }
        return null
    }

    fun persistProbesToFile(filePath: String) {
        runCatching {
            val props = Properties()
            props.setProperty("_sdk", Build.VERSION.SDK_INT.toString())
            props.setProperty("_coloros", getColorOsVersion())
            props.setProperty("_updatedAt", System.currentTimeMillis().toString())
            for ((k, rec) in probes) {
                props.setProperty(k, "${rec.status.name}|${rec.detail}")
            }
            val file = File(filePath)
            file.outputStream().use { props.store(it, "StageLab Native Compat Diagnostics") }
            file.setReadable(true, false)
        }.onFailure {
            HookGuard.warn("OplusCompatAdapter: failed to persist diagnostics to $filePath", it)
        }
    }

    fun readProbesFromFile(filePath: String): Map<String, ProbeRecord> {
        val out = LinkedHashMap<String, ProbeRecord>()
        runCatching {
            val file = File(filePath)
            if (!file.exists() || !file.canRead()) return out
            val props = Properties()
            file.inputStream().use { props.load(it) }
            for (name in props.stringPropertyNames().sorted()) {
                if (name.startsWith("_")) continue
                val raw = props.getProperty(name).orEmpty()
                val sep = raw.indexOf('|')
                val status = if (sep > 0) {
                    runCatching { Status.valueOf(raw.substring(0, sep)) }.getOrDefault(Status.MISSING)
                } else {
                    Status.MISSING
                }
                val detail = if (sep > 0 && sep + 1 < raw.length) raw.substring(sep + 1) else raw
                out[name] = ProbeRecord(name, status, detail)
            }
        }
        return out
    }
}
