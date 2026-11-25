package dev.stagelab.nativehook.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import dev.stagelab.nativehook.compat.OplusCompatAdapter
import dev.stagelab.nativehook.ui.StageLayoutMetrics
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ColorOS 17 `system_server` (`system/0` -> `lpparam.packageName == "android"`) hook.
 *
 * Delivers two core native Stage Manager capabilities cleanly inside ColorOS FlexibleTaskController:
 * 1. Wide Primary-Stage Default Mode:
 *    Hooks `FlexibleTaskController#collectFlexibleTaskInfo` so newly opened single-window Flexible Tasks
 *    start directly at full primary-stage widescreen bounds (excluding left slanted strip & bottom Dock)
 *    with `mScale = 1.0f` instead of narrow `0.618` portrait phone ratio.
 * 2. Persistent Window Size & Position Memory:
 *    - Persists real user-adjusted `Task.getBounds()` + `scale` into `/data/local/tmp/stagelab_window_memory.properties`
 *      whenever ColorOS finishes moving or resizing a Flexible Task (`finishMovingFlexibleTask` / `updateResizedTaskParams`).
 *    - Restores memorized `mLaunchBounds` & `mScale` automatically on relaunch or Stage switch, and allows
 *      `stagelab.force_adjust=true` calls through `OplusPanoramaWorkBranchController.adjustFlexibleTask`.
 */
object SystemServerFlexibleHook {

    const val KEY_FORCE_ADJUST = "stagelab.force_adjust"
    const val KEY_SKIP_PERSIST = "stagelab.skip_persist"
    const val ACTION_SIDEBAR_MINI_HINT = "io.github.luckylca.stagelab.ACTION_SIDEBAR_MINI_HINT"
    const val ACTION_ACTIVE_STAGE_CHANGED = "io.github.luckylca.stagelab.ACTION_ACTIVE_STAGE_CHANGED"
    const val ACTION_TUCKED_TASKS_SYNC = "io.github.luckylca.stagelab.ACTION_TUCKED_TASKS_SYNC"
    const val ACTION_STAGE_MODE_SYNC = "io.github.luckylca.stagelab.ACTION_STAGE_MODE_SYNC"
    const val ACTION_FORGET_APP_MEMORY = "io.github.luckylca.stagelab.ACTION_FORGET_APP_MEMORY"
    const val ACTION_PRUNE_APP_MEMORY = "io.github.luckylca.stagelab.ACTION_PRUNE_APP_MEMORY"
    private const val MEMORY_FILE_PATH = "/data/system/stagelab_window_memory.properties"
    private const val MINI_MEMORY_PREFIX = "mini:"

    private const val PANORAMA_CONTROLLER = "com.android.server.wm.OplusPanoramaWorkBranchController"
    private const val FLEXIBLE_TASK_CONTROLLER = "com.android.server.wm.FlexibleTaskController"
    private const val FLEXIBLE_WINDOW_UTILS = "com.android.server.wm.FlexibleWindowUtils"
    private const val FLOAT_HANDLE_CONTROLLER = "com.android.server.wm.FloatHandleController"
    private const val CONNECTED_DISPLAY_MANAGER = "com.android.server.wm.OplusConnectedDisplayManager"
    private const val ACTIVITY_TASK_MANAGER_SERVICE = "com.android.server.wm.ActivityTaskManagerService"
    private const val UNLIMITED_WINDOW_CAP = 99

    private val allowWideStageRect = ThreadLocal<Boolean>()
    private val skipPersistOnAdjust = ThreadLocal<Boolean>()
    private val memoryMap = ConcurrentHashMap<String, MemorizedFrame>()
    private val recentlyClosedPkgUntilMs = ConcurrentHashMap<String, Long>()
    private val skipPersistUntilMs = ConcurrentHashMap<Int, Long>()
    private val lastAutoSnapMs = ConcurrentHashMap<Int, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stageReceiverRegistered = AtomicBoolean(false)
    private val stageFloatHandles = ConcurrentHashMap<String, MutableList<Any>>()
    @Volatile
    private var currentActiveStageId: String = "stage-default"
    @Volatile
    private var cachedClassLoader: ClassLoader? = null
    @Volatile
    private var stageManagerEnabled: Boolean = true

    private fun isEnabled(): Boolean = HookGuard.isActive() && stageManagerEnabled

    data class MemorizedFrame(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val scale: Float,
    ) {
        fun toRect(): Rect = Rect(left, top, right, bottom)
        fun serialize(): String = "$left,$top,$right,$bottom,$scale"

        companion object {
            fun parse(raw: String): MemorizedFrame? {
                val parts = raw.split(",")
                if (parts.size < 5) return null
                val l = parts[0].toIntOrNull() ?: return null
                val t = parts[1].toIntOrNull() ?: return null
                val r = parts[2].toIntOrNull() ?: return null
                val b = parts[3].toIntOrNull() ?: return null
                val s = parts[4].toFloatOrNull() ?: 1.0f
                if (r <= l || b <= t) return null
                return MemorizedFrame(l, t, r, b, s.coerceIn(0.25f, 1.0f))
            }
        }
    }

    fun install(classLoader: ClassLoader) {
        cachedClassLoader = classLoader
        loadMemoryFromDisk()
        hookPanoramaBypass(classLoader)
        hookUnlimitedWindowLimits(classLoader)
        hookFlexibleTaskController(classLoader)
        hookTaskRemovalCleanup(classLoader)
        hookAllowStageLabBroadcastWakeup(classLoader)
        OplusCompatAdapter.persistProbesToFile(OplusCompatAdapter.SYS_DIAG_PATH)
        mainHandler.postDelayed({ ensureStageReceiverRegistered(null) }, 4_000L)
    }

    /**
     * Prevents ColorOS `OplusAppStartupManager` from blocking `com.android.systemui`'s
     * `ACTION_SYNC_STATE` broadcast to `io.github.luckylca.stagelab/dev.stagelab.nativehook.ui.StageModeStateReceiver`,
     * guaranteeing the standalone exit notification ALWAYS appears after boot & unlock!
     */
    private fun hookAllowStageLabBroadcastWakeup(classLoader: ClassLoader) {
        val cls = OplusCompatAdapter.findClassAny(
            "sys.class.OplusAppStartupManager",
            classLoader,
            "com.android.server.am.OplusAppStartupManager",
            "com.android.server.am.ColorAppStartupManager",
        ) ?: return
        OplusCompatAdapter.hookMethodAny(
            probeKey = "sys.startup.allowNotifSync",
            cls = cls,
            nameCandidates = listOf("shouldPreventSendReceiver", "shouldPreventSendBroadcast"),
            fuzzyKeyword = "PreventSend",
            hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!HookGuard.isActive()) return
                    val args = param.args ?: return
                    for (arg in args) {
                        if (arg != null && arg.toString().contains("io.github.luckylca.stagelab")) {
                            param.result = false
                            return
                        }
                    }
                }
            },
        )
    }

    /**
     * Lifts ColorOS 15–17's hardcoded caps on both Flexible Stage windows and Mini / Float-Handle
     * windows using [OplusCompatAdapter] multi-candidate matching so every workspace can host
     * unlimited regular windows AND unlimited mini windows.
     */
    private fun hookUnlimitedWindowLimits(classLoader: ClassLoader) {
        val utilsCls = OplusCompatAdapter.findClassAny(
            "sys.class.FlexibleWindowUtils",
            classLoader,
            FLEXIBLE_WINDOW_UTILS,
            "com.android.server.wm.OplusFlexibleWindowUtils",
        )
        if (utilsCls != null) {
            OplusCompatAdapter.hookMethodAny(
                probeKey = "sys.limit.maxFlexibleTaskNum",
                cls = utilsCls,
                nameCandidates = listOf("getMaxFlexibleTaskNum", "getMaxFlexibleNum"),
                fuzzyKeyword = "MaxFlexible",
                hook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isEnabled()) {
                            param.result = UNLIMITED_WINDOW_CAP
                        }
                    }
                },
            )
        }

        val floatCls = OplusCompatAdapter.findClassAny(
            "sys.class.FloatHandleController",
            classLoader,
            FLOAT_HANDLE_CONTROLLER,
            "com.android.server.wm.OplusFloatHandleController",
        )
        if (floatCls != null) {
            OplusCompatAdapter.hookMethodAny(
                probeKey = "sys.limit.maxFloatHandleNum",
                cls = floatCls,
                nameCandidates = listOf("getLimitMaxFloatNum", "getMaxFloatNum"),
                fuzzyKeyword = "LimitMaxFloat",
                hook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isEnabled()) {
                            param.result = UNLIMITED_WINDOW_CAP
                        }
                    }
                },
            )
            // Keep SystemUI 100% in sync with which taskIds are currently tucked as edge FloatHandles
            val syncTuckedHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (isEnabled()) {
                        broadcastTuckedTaskIds(param.thisObject)
                    }
                }
            }
            val removeTuckedHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (isEnabled()) {
                        purgeExitedFloatHandlesFromAllStages(param.thisObject)
                        broadcastTuckedTaskIds(param.thisObject)
                    }
                }
            }
            OplusCompatAdapter.hookMethodAny(
                probeKey = "sys.float.addSync",
                cls = floatCls,
                nameCandidates = listOf("addFloatHandle"),
                hook = syncTuckedHook,
            )
            OplusCompatAdapter.hookMethodAny(
                probeKey = "sys.float.removeSync",
                cls = floatCls,
                nameCandidates = listOf("removeFloatHandle", "removeFloatHandleInfo", "removeFloatHandleByTaskId"),
                fuzzyKeyword = "removeFloatHandle",
                hook = removeTuckedHook,
            )
        }

        val cdmCls = OplusCompatAdapter.findClassAny(
            "sys.class.ConnectedDisplayManager",
            classLoader,
            CONNECTED_DISPLAY_MANAGER,
        )
        if (cdmCls != null) {
            OplusCompatAdapter.hookMethodAny(
                probeKey = "sys.limit.pickRemoveTask",
                cls = cdmCls,
                nameCandidates = listOf("pickFlexibleTaskToRemoveWhenExceedLimit"),
                hook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isEnabled()) {
                            param.result = null
                        }
                    }
                },
            )
        }
    }

    private fun hookTaskRemovalCleanup(classLoader: ClassLoader) {
        val atmsCls = OplusCompatAdapter.findClassAny(
            "sys.class.ActivityTaskManagerService",
            classLoader,
            ACTIVITY_TASK_MANAGER_SERVICE,
        ) ?: return
        OplusCompatAdapter.hookMethodAny(
            probeKey = "sys.atm.removeTask",
            cls = atmsCls,
            nameCandidates = listOf("removeTask"),
            hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    HookGuard.safeRun("ATMS.removeTask.forgetMemory") {
                        val taskId = param.args.firstOrNull() as? Int ?: return@safeRun
                        if (taskId <= 0) return@safeRun
                        val taskObj = resolveTaskById(param.thisObject, taskId)
                        val pkg = taskObj?.let {
                            runCatching { XposedHelpers.callMethod(it, "getBasePackageName") as? String }.getOrNull()
                        }.orEmpty()
                        if (pkg.isNotBlank() && !isIgnoredSystemPkg(pkg)) {
                            forgetPackageMemory(pkg, taskId = taskId, reason = "atm_removeTask")
                        } else {
                            purgeTaskIdFromFloatHandles(taskId)
                        }
                    }
                }
            },
        )
    }

    private fun resolveTaskById(atmsObj: Any, taskId: Int): Any? = runCatching {
        val root = XposedHelpers.getObjectField(atmsObj, "mRootWindowContainer") ?: return@runCatching null
        XposedHelpers.callMethod(root, "anyTaskForId", taskId)
    }.getOrNull()

    /**
     * Removes every saved geometry / mini-window configuration entry associated with [pkg]
     * so reopening the app after closing a window always falls back to default Stage behavior.
     */
    private fun forgetPackageMemory(pkg: String, taskId: Int = -1, reason: String) {
        if (pkg.isBlank() || isIgnoredSystemPkg(pkg)) return
        recentlyClosedPkgUntilMs[pkg] = System.currentTimeMillis() + 2_500L
        var removedCount = 0
        val keys = memoryMap.keys.toList()
        for (key in keys) {
            if (doesMemoryKeyBelongToPkg(key, pkg)) {
                if (memoryMap.remove(key) != null) {
                    removedCount++
                }
            }
        }
        if (removedCount > 0) {
            saveMemoryToDisk()
        }
        if (taskId > 0) {
            purgeTaskIdFromFloatHandles(taskId)
        }
        HookGuard.info("SYS.MEMORY_FORGOTTEN pkg=$pkg task=$taskId removed=$removedCount reason=$reason")
    }

    private fun pruneMemoryToActivePackages(activePkgs: Set<String>) {
        val knownPkgs = HashSet<String>()
        for (k in memoryMap.keys) {
            extractPkgFromMemoryKey(k)?.let { knownPkgs.add(it) }
        }
        var removedCount = 0
        for (pkg in knownPkgs) {
            if (!activePkgs.contains(pkg)) {
                val keys = memoryMap.keys.toList()
                for (k in keys) {
                    if (doesMemoryKeyBelongToPkg(k, pkg) && memoryMap.remove(k) != null) {
                        removedCount++
                    }
                }
            }
        }
        if (removedCount > 0) {
            saveMemoryToDisk()
            HookGuard.info("SYS.MEMORY_PRUNED staleEntries=$removedCount activePkgs=${activePkgs.size}")
        }
    }

    private fun doesMemoryKeyBelongToPkg(key: String, pkg: String): Boolean {
        if (key == pkg || key == "land:$pkg" || key == "port:$pkg") return true
        if (key == "$MINI_MEMORY_PREFIX$pkg" ||
            key == "${MINI_MEMORY_PREFIX}land:$pkg" ||
            key == "${MINI_MEMORY_PREFIX}port:$pkg"
        ) {
            return true
        }
        return key.startsWith(MINI_MEMORY_PREFIX) && key.endsWith(":$pkg")
    }

    private fun extractPkgFromMemoryKey(key: String): String? {
        if (key.isBlank()) return null
        val idx = key.lastIndexOf(':')
        return if (idx >= 0 && idx + 1 < key.length) key.substring(idx + 1) else key
    }

    private fun ensureStageReceiverRegistered(controllerObj: Any?) {
        if (stageReceiverRegistered.get()) return
        val ctx = runCatching {
            controllerObj?.let { XposedHelpers.getObjectField(it, "mContext") as? Context }
        }.getOrNull() ?: runCatching {
            val atCls = Class.forName("android.app.ActivityThread")
            val at = XposedHelpers.callStaticMethod(atCls, "currentActivityThread")
            XposedHelpers.callMethod(at, "getSystemContext") as? Context
        }.getOrNull() ?: return

        if (!stageReceiverRegistered.compareAndSet(false, true)) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(ACTION_ACTIVE_STAGE_CHANGED)
                addAction(ACTION_STAGE_MODE_SYNC)
                addAction(ACTION_FORGET_APP_MEMORY)
                addAction(ACTION_PRUNE_APP_MEMORY)
                addAction("io.github.luckylca.stagelab.ACTION_QUERY_SYS_DIAG")
            }
            ctx.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        when (intent?.action) {
                            ACTION_STAGE_MODE_SYNC -> {
                                val enabled = intent.getBooleanExtra("enabled", false)
                                stageManagerEnabled = enabled
                                if (!enabled) {
                                    allowWideStageRect.remove()
                                    skipPersistOnAdjust.remove()
                                    stageFloatHandles.clear()
                                }
                                HookGuard.info("SYS.STAGE_MODE_SYNC enabled=$enabled")
                            }
                            ACTION_ACTIVE_STAGE_CHANGED -> {
                                if (!stageManagerEnabled) return
                                val nextStageId = intent.getStringExtra("stageId").orEmpty().ifBlank { "stage-default" }
                                postOnAndroidUiThread { swapFloatHandlesForStage(nextStageId) }
                            }
                            ACTION_FORGET_APP_MEMORY -> {
                                val pkg = intent.getStringExtra("pkg").orEmpty()
                                val taskId = intent.getIntExtra("taskId", -1)
                                val reason = intent.getStringExtra("reason").orEmpty().ifBlank { "broadcast" }
                                if (pkg.isNotBlank()) {
                                    forgetPackageMemory(pkg, taskId = taskId, reason = reason)
                                } else if (taskId > 0) {
                                    purgeTaskIdFromFloatHandles(taskId)
                                }
                            }
                            ACTION_PRUNE_APP_MEMORY -> {
                                val activePkgs = intent.getStringArrayListExtra("activePkgs").orEmpty().toSet()
                                pruneMemoryToActivePackages(activePkgs)
                            }
                            "io.github.luckylca.stagelab.ACTION_QUERY_SYS_DIAG" -> {
                                mainHandler.post {
                                    val lines = ArrayList(
                                        OplusCompatAdapter.getAllProbes().values.map {
                                            "${it.key}|${it.status.name}|${it.detail}"
                                        },
                                    )
                                    runCatching {
                                        ctx.sendBroadcast(
                                            Intent("io.github.luckylca.stagelab.ACTION_SYS_DIAG_RESPONSE").apply {
                                                setPackage("io.github.luckylca.stagelab")
                                                putExtra("coloros", OplusCompatAdapter.getColorOsVersion())
                                                putStringArrayListExtra("probes", lines)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                filter,
                Context.RECEIVER_EXPORTED,
            )
            HookGuard.info("SystemServerFlexibleHook: registered per-stage FloatHandle switcher & diag responder")
        }.onFailure {
            stageReceiverRegistered.set(false)
            HookGuard.warn("SystemServerFlexibleHook: failed to register stage receiver", it)
        }
    }

    /**
     * Posts work onto `system_server`'s `android.ui` (`com.android.server.UiThread`), which is the
     * ONLY thread allowed to touch `FloatHandleView` / `FloatHandleViewSpringAnimation` without
     * triggering `ViewRootImpl$CalledFromWrongThreadException`.
     */
    private fun postOnAndroidUiThread(block: () -> Unit) {
        val uiHandler = runCatching {
            val cl = cachedClassLoader ?: ClassLoader.getSystemClassLoader()
            val uiThreadCls = XposedHelpers.findClass("com.android.server.UiThread", cl)
            XposedHelpers.callStaticMethod(uiThreadCls, "getHandler") as? Handler
        }.getOrNull()
        if (uiHandler != null) {
            uiHandler.post { runCatching { block() }.onFailure { HookGuard.warn("SYS.UI_THREAD_TASK failed", it) } }
        } else {
            HookGuard.warn("SystemServerFlexibleHook: com.android.server.UiThread unavailable; skipping FloatHandle UI swap")
        }
    }

    /**
     * Per-workspace edge-tucked Mini Windows (`FloatHandleController` / `FloatHandleViewManager`):
     * Saves outgoing workspace's tucked edge pills and swaps in the incoming workspace's own list
     * so every workspace has its own independent edge mini-windows instead of one global list!
     * MUST run on `android.ui` (`UiThread`).
     */
    @Suppress("UNCHECKED_CAST")
    private fun swapFloatHandlesForStage(nextStageId: String) {
        val prevStageId = currentActiveStageId
        if (prevStageId == nextStageId) return
        currentActiveStageId = nextStageId

        val cl = cachedClassLoader ?: return
        runCatching {
            val floatCls = XposedHelpers.findClass(FLOAT_HANDLE_CONTROLLER, cl)
            val controller = XposedHelpers.callStaticMethod(floatCls, "getInstance") ?: return@runCatching
            val liveList = XposedHelpers.getObjectField(controller, "mFloatHandleInfoList") as? MutableList<Any>
                ?: return@runCatching
            val manager = runCatching { XposedHelpers.callMethod(controller, "getFloatManager") }.getOrNull()
                ?: runCatching { XposedHelpers.getObjectField(controller, "mFloatManager") }.getOrNull()

            // 1. Snapshot outgoing workspace's edge mini-windows
            stageFloatHandles[prevStageId] = ArrayList(liveList)

            // 2. Swap in incoming workspace's own edge mini-windows
            val incoming = stageFloatHandles[nextStageId]?.let { ArrayList(it) } ?: ArrayList()
            liveList.clear()
            liveList.addAll(incoming)

            // 3. Refresh or hide the native FloatHandle edge pill UI cleanly on android.ui thread
            if (manager != null) {
                if (incoming.isEmpty()) {
                    runCatching { XposedHelpers.callMethod(manager, "hideFloatHandleView") }
                    runCatching { XposedHelpers.callMethod(manager, "setViewVisible", View.GONE) }
                } else {
                    runCatching { XposedHelpers.callMethod(manager, "setViewVisible", View.VISIBLE) }
                    runCatching { XposedHelpers.callMethod(manager, "showFloatHandleView") }
                    runCatching { XposedHelpers.callMethod(manager, "refreshFloatHandleView") }
                }
            }
            HookGuard.info(
                "SYS.FLOAT_HANDLE_STAGE_SWAP thread=${Thread.currentThread().name} $prevStageId(${stageFloatHandles[prevStageId]?.size ?: 0}) -> $nextStageId(${incoming.size})",
            )
            broadcastTuckedTaskIds(controller)
        }.onFailure {
            HookGuard.warn("SYS.FLOAT_HANDLE_STAGE_SWAP failed", it)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun purgeExitedFloatHandlesFromAllStages(controllerObj: Any?) {
        runCatching {
            val liveList = controllerObj?.let {
                XposedHelpers.getObjectField(it, "mFloatHandleInfoList") as? List<Any>
            }.orEmpty()
            stageFloatHandles[currentActiveStageId] = ArrayList(liveList)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun purgeTaskIdFromFloatHandles(taskId: Int) {
        if (taskId <= 0) return
        postOnAndroidUiThread {
            val cl = cachedClassLoader ?: return@postOnAndroidUiThread
            runCatching {
                for ((_, list) in stageFloatHandles) {
                    list.removeAll { info ->
                        runCatching { XposedHelpers.getIntField(info, "taskId") }.getOrDefault(-1) == taskId
                    }
                }
                val floatCls = XposedHelpers.findClass(FLOAT_HANDLE_CONTROLLER, cl)
                val controller = XposedHelpers.callStaticMethod(floatCls, "getInstance") ?: return@runCatching
                val liveList = XposedHelpers.getObjectField(controller, "mFloatHandleInfoList") as? MutableList<Any>
                val removedLive = liveList?.removeAll { info ->
                    runCatching { XposedHelpers.getIntField(info, "taskId") }.getOrDefault(-1) == taskId
                } == true
                if (removedLive) {
                    val manager = runCatching { XposedHelpers.callMethod(controller, "getFloatManager") }.getOrNull()
                        ?: runCatching { XposedHelpers.getObjectField(controller, "mFloatManager") }.getOrNull()
                    if (manager != null) {
                        if (liveList.isNullOrEmpty()) {
                            runCatching { XposedHelpers.callMethod(manager, "hideFloatHandleView") }
                            runCatching { XposedHelpers.callMethod(manager, "setViewVisible", View.GONE) }
                        } else {
                            runCatching { XposedHelpers.callMethod(manager, "refreshFloatHandleView") }
                        }
                    }
                }
                broadcastTuckedTaskIds(controller)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun broadcastTuckedTaskIds(controllerObj: Any?) {
        val ids = LinkedHashSet<Int>()
        runCatching {
            val liveList = controllerObj?.let {
                XposedHelpers.getObjectField(it, "mFloatHandleInfoList") as? List<Any>
            }.orEmpty()
            for (info in liveList) {
                val tid = runCatching { XposedHelpers.getIntField(info, "taskId") }.getOrDefault(-1)
                if (tid > 0) ids.add(tid)
            }
            for (list in stageFloatHandles.values) {
                for (info in list) {
                    val tid = runCatching { XposedHelpers.getIntField(info, "taskId") }.getOrDefault(-1)
                    if (tid > 0) ids.add(tid)
                }
            }
        }
        val idArray = ids.toIntArray()
        mainHandler.post {
            runCatching {
                val ctx = runCatching {
                    controllerObj?.let { XposedHelpers.getObjectField(it, "mContext") as? Context }
                }.getOrNull() ?: runCatching {
                    val atCls = Class.forName("android.app.ActivityThread")
                    val at = XposedHelpers.callStaticMethod(atCls, "currentActivityThread")
                    XposedHelpers.callMethod(at, "getSystemContext") as? Context
                }.getOrNull() ?: return@runCatching
                ctx.sendBroadcast(
                    Intent(ACTION_TUCKED_TASKS_SYNC).apply {
                        setPackage("com.android.systemui")
                        putExtra("tuckedTaskIds", idArray)
                    },
                )
            }
        }
    }

    private fun hookPanoramaBypass(classLoader: ClassLoader) {
        val cls = OplusCompatAdapter.findClassAny(
            "sys.class.PanoramaController",
            classLoader,
            PANORAMA_CONTROLLER,
            "com.android.server.wm.OplusPanoramaController",
            "com.android.server.wm.OplusFlexibleWorkController",
        ) ?: return

        OplusCompatAdapter.hookMethodAny(
            probeKey = "sys.panorama.adjustFlexible",
            cls = cls,
            nameCandidates = listOf("adjustFlexibleTask"),
            hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    try {
                        val bundle = param.args.firstOrNull() as? Bundle ?: return
                        if (bundle.getBoolean(KEY_FORCE_ADJUST, false)) {
                            allowWideStageRect.set(true)
                            val skipPersist = bundle.getBoolean(KEY_SKIP_PERSIST, false)
                            skipPersistOnAdjust.set(skipPersist)
                            if (skipPersist) {
                                val taskId = bundle.getInt("androidx.activity.LaunchFlexibleTaskId", -1)
                                if (taskId > 0) {
                                    skipPersistUntilMs[taskId] = System.currentTimeMillis() + 800L
                                }
                            }
                            param.result = false
                        }
                    } catch (t: Throwable) {
                        allowWideStageRect.remove()
                        skipPersistOnAdjust.remove()
                        HookGuard.warn("SystemServerFlexibleHook.adjustFlexibleTask isolated error", t)
                    }
                }
            },
        )
    }

    private fun hookFlexibleTaskController(classLoader: ClassLoader) {
        val cls = OplusCompatAdapter.findClassAny(
            "sys.class.FlexibleTaskController",
            classLoader,
            FLEXIBLE_TASK_CONTROLLER,
            "com.android.server.wm.OplusFlexibleTaskController",
        ) ?: return

        // 0. Never auto-evict or force-minimize existing Flexible/Mini tasks when opening more windows
        OplusCompatAdapter.hookMethodAny(
            probeKey = "sys.limit.exceedFlexible",
            cls = cls,
            nameCandidates = listOf("handleExceedLimitFlexibleTask"),
            fuzzyKeyword = "ExceedLimitFlexible",
            hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isEnabled()) {
                        param.result = false
                    }
                }
            },
        )

        // 1. Wide Primary-Stage Default + Memorized Launch Bounds + Smart Sidebar Mini Detection in collectFlexibleTaskInfo
        OplusCompatAdapter.hookMethodAny(
            probeKey = "sys.flexible.collectInfo",
            cls = cls,
            nameCandidates = listOf("collectFlexibleTaskInfo"),
            hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    HookGuard.safeRun("FlexibleTaskController.collectFlexibleTaskInfo") {
                        ensureStageReceiverRegistered(param.thisObject)
                        val info = param.result ?: return@safeRun
                        val pkg = extractPackageFromArgs(param.args) ?: return@safeRun
                        if (isIgnoredSystemPkg(pkg)) return@safeRun
                        recentlyClosedPkgUntilMs.remove(pkg)

                        val (screenW, screenH) = extractDisplaySizeFromArgs(param.args)
                        val orientPrefix = orientKeyPrefix(screenW, screenH)
                        val isMiniOrSidebar = isSidebarOrMiniLaunch(param.args, info)

                        if (isMiniOrSidebar) {
                            notifySystemUiSidebarMiniHint(param.thisObject, pkg)
                            val stageMiniKey = "$MINI_MEMORY_PREFIX$orientPrefix$currentActiveStageId:$pkg"
                            val fallbackMiniKey = "$MINI_MEMORY_PREFIX$orientPrefix$pkg"
                            val miniMemorized = (memoryMap[stageMiniKey] ?: memoryMap[fallbackMiniKey])?.takeIf { frame ->
                                isValidMiniGeometry(frame.toRect(), frame.scale, screenW, screenH)
                            }
                            if (miniMemorized != null) {
                                runCatching { XposedHelpers.setObjectField(info, "mLaunchBounds", Rect(miniMemorized.toRect())) }
                                runCatching { XposedHelpers.setFloatField(info, "mScale", miniMemorized.scale) }
                                runCatching { XposedHelpers.setBooleanField(info, "mSkipPosInMemoryAdjust", true) }
                                runCatching { XposedHelpers.setBooleanField(info, "mSkipMemoryRectToDefault", true) }
                                HookGuard.info("SYS.LAUNCH_MINI_MEMORY stage=$currentActiveStageId pkg=$pkg rect=${miniMemorized.toRect()} scale=${miniMemorized.scale}")
                            } else {
                                HookGuard.info("SYS.LAUNCH_MINI_DEFAULT stage=$currentActiveStageId pkg=$pkg (preserving compact floating bounds)")
                            }
                            return@safeRun
                        }

                        val memKey = "$orientPrefix$pkg"
                        val memorized = memoryMap[memKey]?.takeIf { frame ->
                            if (isValidVisualGeometry(frame.toRect(), frame.scale, screenW, screenH)) {
                                true
                            } else {
                                memoryMap.remove(memKey)
                                saveMemoryToDisk()
                                HookGuard.info("SYS.MEMORY_PURGED_INVALID key=$memKey rect=${frame.toRect()} scale=${frame.scale}")
                                false
                            }
                        }
                        val targetRect: Rect
                        val targetScale: Float

                        if (memorized != null) {
                            targetRect = memorized.toRect()
                            targetScale = memorized.scale
                            HookGuard.info("SYS.LAUNCH_MEMORY pkg=$pkg rect=$targetRect scale=$targetScale")
                        } else {
                            targetRect = computeWidePrimaryStageRect(screenW, screenH)
                            targetScale = 1.0f
                            HookGuard.info("SYS.LAUNCH_WIDE_STAGE pkg=$pkg rect=$targetRect scale=$targetScale (${screenW}x${screenH})")
                        }

                        OplusCompatAdapter.setObjectFieldAny(info, Rect(targetRect), "mLaunchBounds", "mLaunchRect", "mBounds")
                        OplusCompatAdapter.setFloatFieldAny(info, targetScale, "mScale", "mScaleRatio")
                        if (targetRect.height() > 0) {
                            val ratio = targetRect.width().toFloat() / targetRect.height().toFloat()
                            OplusCompatAdapter.setFloatFieldAny(info, ratio, "mRatio")
                        }
                        OplusCompatAdapter.setBooleanFieldAny(info, true, "mSkipPosInMemoryAdjust")
                        OplusCompatAdapter.setBooleanFieldAny(info, true, "mSkipMemoryRectToDefault")
                        if (memorized == null) {
                            OplusCompatAdapter.setBooleanFieldAny(info, false, "mIsMiniState")
                            OplusCompatAdapter.setIntFieldAny(info, 1, "mFlexibleState")
                        }
                    }
                }
            },
        )

        // 2. Allow wide stage bounds during stagelab.force_adjust, recover leaked changing flags once transitions settle,
        //    and persist manual geometry unless KEY_SKIP_PERSIST is set
        OplusCompatAdapter.hookMethodAny(
            probeKey = "sys.flexible.adjustTask",
            cls = cls,
            nameCandidates = listOf("adjustFlexibleTask"),
            hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled() || allowWideStageRect.get() != true) return
                    val bundle = param.args.firstOrNull() as? Bundle ?: return
                    if (!bundle.getBoolean(KEY_FORCE_ADJUST, false)) return
                    val taskId = bundle.getInt("androidx.activity.LaunchFlexibleTaskId", -1)
                    if (taskId > 0) {
                        val taskObj = findTaskById(param.thisObject, taskId)
                        if (taskObj != null) {
                            clearStaleFlexibleTaskChangingIfIdle(param.thisObject, taskObj, "force_adjust")
                        }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val wasForced = allowWideStageRect.get() == true
                    val skipPersist = skipPersistOnAdjust.get() == true
                    allowWideStageRect.remove()
                    skipPersistOnAdjust.remove()
                    if (wasForced && !skipPersist) {
                        HookGuard.safeRun("FlexibleTaskController.adjustFlexibleTask.persist") {
                            val taskObj = param.args.firstOrNull { it != null && it.javaClass.name.endsWith(".Task") }
                            if (taskObj != null) {
                                recordTaskGeometry(taskObj)
                            }
                        }
                    }
                }
            },
        )
        OplusCompatAdapter.hookMethodAny(
            probeKey = "sys.flexible.startChangeAnim",
            cls = cls,
            nameCandidates = listOf("startChangeAnim"),
            hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val taskObj = param.args.firstOrNull() ?: return
                    if (!taskObj.javaClass.name.endsWith(".Task")) return
                    scheduleStaleChangingCleanup(param.thisObject, taskObj, attempt = 1)
                }
            },
        )
        OplusCompatAdapter.hookMethodAny(
            probeKey = "sys.flexible.validAdjustRect",
            cls = cls,
            nameCandidates = listOf("isValidAdjustEndRect"),
            hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (allowWideStageRect.get() == true && isEnabled()) {
                        param.result = true
                    }
                }
            },
        )

        // 3. Block true fullscreen exit on drag/scale-to-fullscreen (prevents frozen transition snapshot)
        //    and instead snap the window cleanly to the widescreen Main Stage rect.
        OplusCompatAdapter.hookMethodAny(
            probeKey = "sys.flexible.canFullscreen",
            cls = cls,
            nameCandidates = listOf("canFullscreenTask"),
            hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isEnabled()) {
                        param.result = false
                    }
                }
            },
        )
        OplusCompatAdapter.hookMethodAny(
            probeKey = "sys.flexible.stopScaleFullscreen",
            cls = cls,
            nameCandidates = listOf("stopScaleAndToFullScreen"),
            hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    HookGuard.safeRun("FlexibleTaskController.stopScaleAndToFullScreen") {
                        param.result = false
                        val taskObj = param.args.firstOrNull() ?: return@safeRun
                        snapTaskObjToMainStage(taskObj, "stopScaleAndToFullScreen")
                    }
                }
            },
        )
        OplusCompatAdapter.hookMethodAny(
            probeKey = "sys.flexible.exitStrategy",
            cls = cls,
            nameCandidates = listOf("exitFlexibleTaskWindowInnerLocked", "exitFlexibleTaskWindowLocked"),
            hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    HookGuard.safeRun("FlexibleTaskController.exitFlexibleTaskWindowInnerLocked") {
                        val strategy = param.args.firstOrNull() ?: return@safeRun
                        val strategyName = strategy.javaClass.simpleName
                        val exitTo = runCatching {
                            XposedHelpers.callMethod(strategy, "getExitTo") as? Int
                        }.getOrNull() ?: runCatching {
                            XposedHelpers.getIntField(strategy, "mExitTo")
                        }.getOrDefault(-1)

                        val taskObj = runCatching {
                            XposedHelpers.callMethod(strategy, "getTask")
                        }.getOrNull() ?: runCatching {
                            XposedHelpers.getObjectField(strategy, "mTask")
                        }.getOrNull()

                        // 1. Fullscreen exit (exitTo == 4) -> snap cleanly to Main Stage full
                        if (exitTo == 4 || strategyName.contains("Full", ignoreCase = true)) {
                            param.result = null
                            if (taskObj != null) {
                                snapTaskObjToMainStage(taskObj, "exitToFullscreen")
                            }
                            return@safeRun
                        }

                        // 2. Native Split Screen menu action -> keep inside Stage Manager, snap to left half of Main Stage!
                        if (strategyName.contains("Split", ignoreCase = true) || exitTo == 2 || exitTo == 3) {
                            param.result = null
                            if (taskObj != null) {
                                snapTaskObjToSplitLeft(taskObj)
                            }
                            return@safeRun
                        }

                        // 3. Explicit close / dismiss strategy -> forget saved window config for this app
                        if (strategyName.contains("Close", ignoreCase = true) ||
                            strategyName.contains("Dismiss", ignoreCase = true) ||
                            strategyName.contains("Remove", ignoreCase = true)
                        ) {
                            val taskId = taskObj?.let {
                                runCatching { XposedHelpers.getIntField(it, "mTaskId") }.getOrDefault(-1)
                            } ?: -1
                            val pkg = taskObj?.let {
                                runCatching { XposedHelpers.callMethod(it, "getBasePackageName") as? String }.getOrNull()
                            }.orEmpty()
                            if (pkg.isNotBlank()) {
                                forgetPackageMemory(pkg, taskId = taskId, reason = "exitStrategy:$strategyName")
                            }
                        }
                    }
                }
            },
        )

        // 3b. Prevent FlexibleTaskScaleManager from EVER entering fullscreen-maximize preview/animation
        //     when dragging a window corner bigger — keeps resizing smooth & never leaves scale state stuck!
        val scaleMgrCls = OplusCompatAdapter.findClassAny(
            "sys.class.ScaleManager",
            classLoader,
            "com.android.server.wm.FlexibleTaskScaleManager",
            "com.android.server.wm.OplusFlexibleTaskScaleManager",
        )
        if (scaleMgrCls != null) {
            val returnFalseHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isEnabled()) {
                        param.result = false
                    }
                }
            }
            OplusCompatAdapter.hookMethodAny(
                probeKey = "sys.scale.maxingRegion",
                cls = scaleMgrCls,
                nameCandidates = listOf("isInMaxingRegion", "isInWaitMaxingState"),
                hook = returnFalseHook,
            )
            OplusCompatAdapter.hookMethodAny(
                probeKey = "sys.scale.maxAnim",
                cls = scaleMgrCls,
                nameCandidates = listOf("startFlexibleTaskMaximizedAnimation"),
                hook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isEnabled()) {
                            param.result = null
                        }
                    }
                },
            )
            val blockFullScreenScaleHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    HookGuard.safeRun("FlexibleTaskScaleManager.blockFullScreenScale") {
                        param.result = null
                        val mgr = param.thisObject
                        val taskId = (param.args.getOrNull(2) as? Int) ?: -1
                        val taskObj = runCatching {
                            XposedHelpers.getObjectField(mgr, "mScaleTask")
                        }.getOrNull() ?: runCatching {
                            XposedHelpers.getObjectField(mgr, "mTask")
                        }.getOrNull()
                        // Cleanly reset scale leash & state so the window remains 100% resizable afterward!
                        runCatching { XposedHelpers.callMethod(mgr, "removeFlexibleMaximizedOverlay") }
                        runCatching { XposedHelpers.callMethod(mgr, "destroyFlexibleScaleLeash") }
                        runCatching { XposedHelpers.callMethod(mgr, "resetFlexibleTaskScaleState") }
                        if (taskObj != null) {
                            snapTaskObjToMainStage(taskObj, "fullScreenByScale")
                        } else if (taskId > 0) {
                            val (sw, sh) = extractDisplaySizeFromArgs(arrayOf(mgr))
                            snapTaskIdToMainStage(taskId, sw, sh, "fullScreenByScale")
                        }
                    }
                }
            }
            OplusCompatAdapter.hookMethodAny(
                probeKey = "sys.scale.blockFullscreen",
                cls = scaleMgrCls,
                nameCandidates = listOf("fullScreenByScaleRatios", "fullScreenByScale"),
                hook = blockFullScreenScaleHook,
            )
        }

        // 4. Persist real user drag/resize bounds & scale directly inside system_server
        //    AND auto-snap to Main Stage default max bounds when the user stretches a window near max size!
        val persistHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isEnabled()) return
                HookGuard.safeRun("FlexibleTaskController.persistGeometry") {
                    val taskObj = param.args.firstOrNull { it != null && it.javaClass.name.endsWith(".Task") } ?: return@safeRun
                    val isResizeMethod = param.method.name != "finishMovingFlexibleTask"
                    if (isResizeMethod && maybeAutoSnapNearMaxToMainStage(taskObj)) {
                        return@safeRun
                    }
                    recordTaskGeometry(taskObj)
                }
            }
        }
        for (methodName in listOf(
            "finishMovingFlexibleTask",
            "updateResizedTaskParams",
            "resizeTaskByVisibleBounds",
            "adjustMiniFinishRectIfOffScreen",
        )) {
            OplusCompatAdapter.hookMethodAny(
                probeKey = "sys.persist.$methodName",
                cls = cls,
                nameCandidates = listOf(methodName),
                hook = persistHook,
            )
        }
    }

    private fun snapTaskObjToSplitLeft(taskObj: Any) {
        val taskId = runCatching { XposedHelpers.getIntField(taskObj, "mTaskId") }.getOrDefault(-1)
        if (taskId <= 0) return
        val (screenW, screenH) = extractDisplaySizeFromArgs(arrayOf(taskObj))
        val metrics = StageLayoutMetrics.compute(screenW = screenW, screenH = screenH)
        val stage = metrics.mainStageRect
        val halfW = ((stage.width() - metrics.multiColGap2Px) / 2).coerceAtLeast(360)
        val leftRect = Rect(stage.left, stage.top, stage.left + halfW, stage.bottom)

        // Dispatch outside WindowManagerGlobalLock so clicking Split Screen never stutters!
        mainHandler.post {
            val bundle = Bundle().apply {
                putBoolean(KEY_FORCE_ADJUST, true)
                putInt("androidx.activity.LaunchFlexibleTaskId", taskId)
                putParcelable("androidx.flexible.LaunchBounds", leftRect)
                putFloat("androidx.activity.LaunchScale", 1.0f)
            }
            allowWideStageRect.set(true)
            runCatching {
                val fwmCls = Class.forName("com.oplus.flexiblewindow.FlexibleWindowManager")
                val fwm = XposedHelpers.callStaticMethod(fwmCls, "getInstance")
                XposedHelpers.callMethod(fwm, "adjustFlexibleTask", bundle)
            }
            HookGuard.info("SYS.SNAP_TO_SPLIT_LEFT task=$taskId rect=$leftRect")
        }
    }

    private fun snapTaskObjToMainStage(taskObj: Any, reason: String) {
        val taskId = runCatching { XposedHelpers.getIntField(taskObj, "mTaskId") }.getOrDefault(-1)
        if (taskId <= 0) return
        val (screenW, screenH) = extractDisplaySizeFromArgs(arrayOf(taskObj))
        snapTaskIdToMainStage(taskId, screenW, screenH, reason)
    }

    private fun snapTaskIdToMainStage(taskId: Int, screenW: Int, screenH: Int, reason: String) {
        val mainStage = computeWidePrimaryStageRect(screenW, screenH)
        mainHandler.post {
            val bundle = Bundle().apply {
                putBoolean(KEY_FORCE_ADJUST, true)
                putInt("androidx.activity.LaunchFlexibleTaskId", taskId)
                putParcelable("androidx.flexible.LaunchBounds", mainStage)
                putFloat("androidx.activity.LaunchScale", 1.0f)
            }
            allowWideStageRect.set(true)
            runCatching {
                val fwmCls = Class.forName("com.oplus.flexiblewindow.FlexibleWindowManager")
                val fwm = XposedHelpers.callStaticMethod(fwmCls, "getInstance")
                XposedHelpers.callMethod(fwm, "adjustFlexibleTask", bundle)
            }
            HookGuard.info("SYS.SNAP_TO_MAIN_STAGE($reason) task=$taskId rect=$mainStage")
        }
    }

    /**
     * Auto-snap feature: when the user resizes a window close to or beyond the Main Stage maximum
     * (width >= 86% or height >= 86% of Main Stage), cleanly snap it to the exact Main Stage
     * default full rect `Rect(466, 97 - 2440, 1353)` (`scale = 1.0f`).
     */
    private fun maybeAutoSnapNearMaxToMainStage(taskObj: Any): Boolean {
        val taskId = runCatching { XposedHelpers.getIntField(taskObj, "mTaskId") }.getOrDefault(-1)
        if (taskId <= 0) return false
        val now = System.currentTimeMillis()
        val prevSnap = lastAutoSnapMs[taskId] ?: 0L
        if (now - prevSnap < 800L) return false

        val bounds = runCatching { XposedHelpers.callMethod(taskObj, "getBounds") as? Rect }.getOrNull() ?: return false
        val (scale, zoomState) = runCatching {
            val wrapper = XposedHelpers.callMethod(taskObj, "getWrapper")
            val ext = XposedHelpers.callMethod(wrapper, "getExtImpl")
            val s = (XposedHelpers.callMethod(ext, "getScale") as? Float) ?: 1.0f
            val st = (XposedHelpers.callMethod(ext, "getFlexibleZoomState") as? Int) ?: 1
            s to st
        }.getOrDefault(1.0f to 1)
        if (zoomState == 2 || scale < 0.45f) return false

        val (screenW, screenH) = extractDisplaySizeFromArgs(arrayOf(taskObj))
        val mainStage = computeWidePrimaryStageRect(screenW, screenH)
        val visW = (bounds.width() * scale).toInt()
        val visH = (bounds.height() * scale).toInt()

        val nearMax = visW >= (mainStage.width() * 0.86f).toInt() && visH >= (mainStage.height() * 0.82f).toInt()
        val alreadySnapped = kotlin.math.abs(bounds.left - mainStage.left) <= 16 &&
            kotlin.math.abs(bounds.top - mainStage.top) <= 16 &&
            kotlin.math.abs(bounds.right - mainStage.right) <= 16 &&
            kotlin.math.abs(bounds.bottom - mainStage.bottom) <= 16 &&
            scale >= 0.98f

        if (nearMax && !alreadySnapped) {
            lastAutoSnapMs[taskId] = now
            snapTaskIdToMainStage(taskId, screenW, screenH, "autoSnapMax")
            return true
        }
        return false
    }

    private fun recordTaskGeometry(taskObj: Any) {
        val pkg = runCatching {
            XposedHelpers.callMethod(taskObj, "getBasePackageName") as? String
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        if (isIgnoredSystemPkg(pkg)) return
        val closedUntil = recentlyClosedPkgUntilMs[pkg] ?: 0L
        if (System.currentTimeMillis() < closedUntil) {
            HookGuard.info("SYS.MEMORY_SKIP_RECENT_CLOSE pkg=$pkg")
            return
        }
        val taskId = runCatching { XposedHelpers.getIntField(taskObj, "mTaskId") }.getOrDefault(-1)
        if (taskId > 0 && System.currentTimeMillis() < (skipPersistUntilMs[taskId] ?: 0L)) {
            return
        }
        val visible = runCatching {
            XposedHelpers.callMethod(taskObj, "isVisibleRequested") as? Boolean
                ?: (XposedHelpers.callMethod(taskObj, "isVisible") as? Boolean)
        }.getOrNull()
        if (visible == false) return

        val bounds = runCatching {
            XposedHelpers.callMethod(taskObj, "getBounds") as? Rect
        }.getOrNull() ?: return
        if (bounds.isEmpty || bounds.right <= 0 || bounds.left < -100) return

        val (scale, zoomState) = runCatching {
            val wrapper = XposedHelpers.callMethod(taskObj, "getWrapper")
            val ext = XposedHelpers.callMethod(wrapper, "getExtImpl")
            val s = (XposedHelpers.callMethod(ext, "getScale") as? Float) ?: 1.0f
            val st = (XposedHelpers.callMethod(ext, "getFlexibleZoomState") as? Int) ?: 1
            s to st
        }.getOrDefault(1.0f to 1)

        val (screenW, screenH) = extractDisplaySizeFromArgs(arrayOf(taskObj))
        val orientPrefix = orientKeyPrefix(screenW, screenH)

        // Persist mini/sidebar floating windows per-orientation & per-Stage ("mini:land:<stageId>:<pkg>")
        // so every orientation and workspace remembers its own distinct mini-window positions & scales independently!
        if (zoomState == 2 || scale < 0.72f) {
            val clampedMiniScale = scale.coerceIn(0.25f, 1.0f)
            if (!isValidMiniGeometry(bounds, clampedMiniScale, screenW, screenH)) return
            val miniFrame = MemorizedFrame(bounds.left, bounds.top, bounds.right, bounds.bottom, clampedMiniScale)
            val stageMiniKey = "$MINI_MEMORY_PREFIX$orientPrefix$currentActiveStageId:$pkg"
            val fallbackMiniKey = "$MINI_MEMORY_PREFIX$orientPrefix$pkg"
            val prevMini = memoryMap[stageMiniKey]
            if (prevMini != miniFrame) {
                memoryMap[stageMiniKey] = miniFrame
                memoryMap[fallbackMiniKey] = miniFrame
                saveMemoryToDisk()
                HookGuard.info("SYS.MINI_MEMORY_SAVED key=$stageMiniKey rect=${miniFrame.toRect()} scale=$clampedMiniScale state=$zoomState")
            }
            return
        }

        val clampedScale = scale.coerceIn(0.45f, 1.0f)
        if (!isValidVisualGeometry(bounds, clampedScale, screenW, screenH)) return

        val memKey = "$orientPrefix$pkg"
        val frame = MemorizedFrame(bounds.left, bounds.top, bounds.right, bounds.bottom, clampedScale)
        val prev = memoryMap[memKey]
        if (prev != frame) {
            memoryMap[memKey] = frame
            saveMemoryToDisk()
            HookGuard.info("SYS.MEMORY_SAVED key=$memKey rect=${frame.toRect()} scale=$clampedScale")
        }
    }

    private fun isValidMiniGeometry(rect: Rect, scale: Float, screenW: Int, screenH: Int): Boolean {
        if (rect.isEmpty || scale < 0.25f || scale > 1.05f) return false
        val visW = (rect.width() * scale).toInt()
        val visH = (rect.height() * scale).toInt()
        if (visW < 180 || visH < 180) return false
        val visRight = rect.left + visW
        val visBottom = rect.top + visH
        return rect.left >= -120 && rect.top >= 0 && visRight <= screenW + 120 && visBottom <= screenH + 120
    }

    private fun isSidebarOrMiniLaunch(args: Array<Any?>?, info: Any): Boolean {
        val isMiniFlag = runCatching { XposedHelpers.getBooleanField(info, "mIsMiniState") }.getOrDefault(false)
        if (isMiniFlag) return true
        val state = runCatching { XposedHelpers.getIntField(info, "mFlexibleState") }.getOrDefault(1)
        if (state == 2) return true
        if (args != null) {
            for (arg in args) {
                if (arg == null) continue
                if (arg is String && arg.contains("smartsidebar", ignoreCase = true)) return true
                if (arg is Bundle) {
                    if (arg.getBoolean("is_mini_state", false)) return true
                    val str = runCatching { arg.toString() }.getOrDefault("")
                    if (str.contains("smartsidebar", ignoreCase = true) || str.contains("sidebar", ignoreCase = true)) {
                        return true
                    }
                }
                if (arg.javaClass.name.endsWith("ActivityOptions")) {
                    val str = runCatching { arg.toString() }.getOrDefault("")
                    if (str.contains("smartsidebar", ignoreCase = true) || str.contains("mini", ignoreCase = true)) {
                        return true
                    }
                }
                if (arg.javaClass.name.endsWith("ActivityRecord")) {
                    val launchedFrom = runCatching {
                        XposedHelpers.getObjectField(arg, "launchedFromPackage") as? String
                    }.getOrNull().orEmpty()
                    if (launchedFrom.contains("smartsidebar", ignoreCase = true)) return true
                }
            }
        }
        return false
    }

    private fun notifySystemUiSidebarMiniHint(controllerObj: Any?, pkg: String) {
        mainHandler.post {
            runCatching {
                val ctx = runCatching {
                    XposedHelpers.getObjectField(controllerObj, "mContext") as? android.content.Context
                }.getOrNull() ?: runCatching {
                    val atCls = Class.forName("android.app.ActivityThread")
                    val at = XposedHelpers.callStaticMethod(atCls, "currentActivityThread")
                    XposedHelpers.callMethod(at, "getSystemContext") as? android.content.Context
                }.getOrNull() ?: return@runCatching
                val intent = Intent(ACTION_SIDEBAR_MINI_HINT).apply {
                    setPackage("com.android.systemui")
                    putExtra("pkg", pkg)
                }
                ctx.sendBroadcast(intent)
            }
        }
    }

    private fun isValidVisualGeometry(rect: Rect, scale: Float, screenW: Int, screenH: Int): Boolean {
        if (rect.isEmpty || scale < 0.45f || scale > 1.05f) return false
        val visW = (rect.width() * scale).toInt()
        val visH = (rect.height() * scale).toInt()
        if (visW < 360 || visH < 360) return false
        val visRight = rect.left + visW
        val visBottom = rect.top + visH
        // Reject off-screen / corrupted virtual rects (e.g. from minimized state=2 windows)
        return rect.left >= -160 && rect.top >= 0 && visRight <= screenW + 160 && visBottom <= screenH + 160
    }

    private fun computeWidePrimaryStageRect(screenW: Int, screenH: Int): Rect {
        return StageLayoutMetrics.compute(screenW = screenW, screenH = screenH).mainStageRect
    }

    private fun extractPackageFromArgs(args: Array<Any?>?): String? {
        if (args == null) return null
        for (arg in args) {
            when (arg) {
                is ActivityInfo -> if (!arg.packageName.isNullOrBlank()) return arg.packageName
                is Intent -> {
                    val p = arg.component?.packageName ?: arg.`package`
                    if (!p.isNullOrBlank()) return p
                }
                else -> {
                    if (arg != null && arg.javaClass.name.endsWith("ActivityRecord")) {
                        val p = runCatching { XposedHelpers.getObjectField(arg, "packageName") as? String }.getOrNull()
                        if (!p.isNullOrBlank()) return p
                    }
                    if (arg != null && arg.javaClass.name.endsWith(".Task")) {
                        val p = runCatching { XposedHelpers.callMethod(arg, "getBasePackageName") as? String }.getOrNull()
                        if (!p.isNullOrBlank()) return p
                    }
                }
            }
        }
        return null
    }

    private fun orientKeyPrefix(screenW: Int, screenH: Int): String =
        if (screenW >= screenH) "land:" else "port:"

    private fun extractDisplaySizeFromArgs(args: Array<Any?>?): Pair<Int, Int> {
        if (args != null) {
            for (arg in args) {
                if (arg == null) continue
                if (arg.javaClass.name.endsWith("DisplayContent")) {
                    val rect = runCatching {
                        XposedHelpers.callMethod(arg, "getBounds") as? Rect
                    }.getOrNull()
                    if (rect != null && rect.width() > 0 && rect.height() > 0) {
                        return rect.width() to rect.height()
                    }
                }
                if (arg.javaClass.name.endsWith(".Task") || arg.javaClass.name.endsWith("WindowContainer")) {
                    val dc = runCatching { XposedHelpers.callMethod(arg, "getDisplayContent") }.getOrNull()
                    val rect = dc?.let {
                        runCatching { XposedHelpers.callMethod(it, "getBounds") as? Rect }.getOrNull()
                    }
                    if (rect != null && rect.width() > 0 && rect.height() > 0) {
                        return rect.width() to rect.height()
                    }
                }
            }
        }
        val dm = runCatching {
            val atCls = Class.forName("android.app.ActivityThread")
            val at = XposedHelpers.callStaticMethod(atCls, "currentActivityThread")
            val ctx = XposedHelpers.callMethod(at, "getSystemContext") as? Context
            ctx?.resources?.displayMetrics
        }.getOrNull()
        if (dm != null && dm.widthPixels > 0 && dm.heightPixels > 0) {
            return dm.widthPixels to dm.heightPixels
        }
        return 2520 to 1680
    }

    private fun findTaskById(controller: Any, taskId: Int): Any? {
        return runCatching {
            val atms = XposedHelpers.getObjectField(controller, "mAtms") ?: return null
            val rwc = XposedHelpers.getObjectField(atms, "mRootWindowContainer") ?: return null
            val globalLock = XposedHelpers.getObjectField(atms, "mGlobalLock")
            if (globalLock != null) {
                synchronized(globalLock) {
                    XposedHelpers.callMethod(rwc, "anyTaskForId", taskId, 0)
                }
            } else {
                XposedHelpers.callMethod(rwc, "anyTaskForId", taskId, 0)
            }
        }.getOrNull()
    }

    private fun scheduleStaleChangingCleanup(controller: Any, taskObj: Any, attempt: Int) {
        mainHandler.postDelayed({
            if (!isEnabled()) return@postDelayed
            val stillBusy = clearStaleFlexibleTaskChangingIfIdle(
                controller = controller,
                taskObj = taskObj,
                reason = "startChangeAnim_$attempt",
            )
            if (stillBusy && attempt < 3) {
                scheduleStaleChangingCleanup(controller, taskObj, attempt + 1)
            }
        }, 650L)
    }

    /**
     * ColorOS `FlexibleTaskController.startChangeAnim` sets `mIsFlexibleTaskChanging=true` (often with
     * `customTransitionType=256`) before checking if a transition change actually collects the task.
     * For portrait/trampoline apps like Reddit, bounds don't change so no transition ever clears that flag,
     * leaving the task permanently locked against `adjustFlexibleTask` and finger drags.
     *
     * @return `true` if a real transition/animation/drag is still active on [taskObj] (caller may retry).
     */
    private fun clearStaleFlexibleTaskChangingIfIdle(
        controller: Any,
        taskObj: Any,
        reason: String,
    ): Boolean {
        return runCatching {
            val atms = XposedHelpers.getObjectField(controller, "mAtms") ?: return false
            val globalLock = XposedHelpers.getObjectField(atms, "mGlobalLock") ?: return false
            synchronized(globalLock) {
                val attached = runCatching {
                    XposedHelpers.callMethod(taskObj, "isAttached") as? Boolean
                }.getOrNull() ?: true
                if (!attached) return false
                val wrapper = runCatching { XposedHelpers.callMethod(taskObj, "getWrapper") }.getOrNull() ?: return false
                val extImpl = runCatching { XposedHelpers.callMethod(wrapper, "getExtImpl") }.getOrNull() ?: return false
                if (!callBool(extImpl, "isFlexibleTaskChanging")) return false

                val tc = runCatching { XposedHelpers.getObjectField(taskObj, "mTransitionController") }.getOrNull()
                val animator = runCatching { XposedHelpers.getObjectField(controller, "mAnimator") }.getOrNull()
                if (
                    callBool(tc, "inTransition", taskObj) ||
                    callBool(animator, "isAnimRunning") ||
                    callBool(extImpl, "isFlexibleTaskIsScaleHanding") ||
                    callBool(extImpl, "isReboundsAnimaing") ||
                    callBool(controller, "isFlexibleAndStartDraging", taskObj)
                ) {
                    return true
                }

                XposedHelpers.callMethod(extImpl, "setFlexibleTaskChanging", false)
                val customType = runCatching {
                    XposedHelpers.callMethod(extImpl, "getFlexibleCustomTransitionType") as? Int
                }.getOrNull() ?: 0
                if (customType != 0) {
                    runCatching { XposedHelpers.callMethod(extImpl, "removeFlexibleCustomTransitionType", customType) }
                }
                val tid = runCatching { XposedHelpers.getIntField(taskObj, "mTaskId") }.getOrDefault(-1)
                HookGuard.info("SYS.CLEAR_STALE_CHANGING task=$tid customType=$customType reason=$reason")
                false
            }
        }.getOrDefault(false)
    }

    private fun callBool(target: Any?, method: String, vararg args: Any?): Boolean {
        if (target == null) return false
        return runCatching { XposedHelpers.callMethod(target, method, *args) as? Boolean }.getOrNull() == true
    }

    private fun isIgnoredSystemPkg(pkg: String): Boolean {
        return pkg.isBlank() ||
            pkg == "com.android.launcher" ||
            pkg == "com.android.systemui" ||
            pkg == "com.android.permissioncontroller" ||
            pkg == "com.android.packageinstaller" ||
            pkg == "com.oplus.appdetail"
    }

    private fun loadMemoryFromDisk() {
        runCatching {
            val file = File(MEMORY_FILE_PATH)
            if (!file.exists()) return
            val props = Properties()
            file.inputStream().use { props.load(it) }
            for (name in props.stringPropertyNames()) {
                val parsed = MemorizedFrame.parse(props.getProperty(name).orEmpty())
                if (parsed != null) {
                    memoryMap[name] = parsed
                }
            }
            HookGuard.info("SYS.MEMORY_LOADED count=${memoryMap.size}")
        }.onFailure {
            HookGuard.warn("SYS.MEMORY_LOADED failed", it)
        }
    }

    private fun saveMemoryToDisk() {
        runCatching {
            val props = Properties()
            for ((k, v) in memoryMap) {
                props.setProperty(k, v.serialize())
            }
            val file = File(MEMORY_FILE_PATH)
            file.outputStream().use { props.store(it, "StageLab Native Window Geometry Memory") }
            file.setReadable(true, false)
        }.onFailure {
            HookGuard.warn("SYS.MEMORY_SAVE failed", it)
        }
    }
}
