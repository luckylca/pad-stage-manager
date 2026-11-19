package dev.stagelab.android.shizuku

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.stagelab.android.core.shizuku.IStageTaskService
import dev.stagelab.android.core.shizuku.StageTaskUserService
import dev.stagelab.android.demo.DemoActivity
import dev.stagelab.android.demo.DemoMode
import dev.stagelab.android.ui.StageLabTheme
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class ShizukuProbeActivity : ComponentActivity() {
    private val uiState = androidx.compose.runtime.mutableStateOf(ShizukuProbeState())
    private val worker = Executors.newSingleThreadExecutor()
    private var userService: IStageTaskService? = null
    private var isBound = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, StageTaskUserService::class.java))
            .daemon(false)
            .processNameSuffix("stagelab-task")
            .tag("stagelab-task-organizer-probe")
            .version(1)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            userService = IStageTaskService.Stub.asInterface(binder)
            isBound = true
            uiState.value = uiState.value.copy(serviceStatus = "Connected")
            refreshRuntimeIdentity()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            userService = null
            isBound = false
            uiState.value = uiState.value.copy(serviceStatus = "Disconnected", runtimeIdentity = "UserService binder disconnected")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshShizukuState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        userService = null
        isBound = false
        uiState.value = uiState.value.copy(
            shizukuStatus = "Binder died",
            serviceStatus = "Disconnected",
            appPermission = "Unavailable",
            manageActivityTasks = "Unavailable"
        )
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE_SHIZUKU) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                refreshShizukuState()
                bindUserService()
            } else {
                uiState.value = uiState.value.copy(appPermission = "Denied by user")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StageLabTheme {
                ShizukuProbeScreen(
                    state = uiState.value,
                    onRefresh = ::refreshShizukuState,
                    onConnect = ::requestAccessAndConnect,
                    onRegister = ::registerTaskOrganizer,
                    onOpenDemoTasks = ::openDemoTasks,
                    onRefreshCallbacks = ::refreshCallbackEvents,
                    onUnregister = ::unregisterTaskOrganizer
                )
            }
        }
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshShizukuState()
    }

    private fun refreshShizukuState() {
        if (!Shizuku.pingBinder()) {
            uiState.value = uiState.value.copy(
                shizukuStatus = "Not running / binder unavailable",
                appPermission = "Unavailable",
                remoteUid = "Unknown",
                selinuxContext = "Unknown",
                manageActivityTasks = "Unknown"
            )
            return
        }

        try {
            if (Shizuku.isPreV11()) {
                uiState.value = uiState.value.copy(
                    shizukuStatus = "Binder connected",
                    appPermission = "Shizuku server v11+ is required",
                    remoteUid = "Unavailable",
                    selinuxContext = "Unavailable",
                    manageActivityTasks = "Not checked"
                )
                return
            }

            val appGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            if (!appGranted) {
                uiState.value = uiState.value.copy(
                    shizukuStatus = "Binder connected",
                    appPermission = "Not granted",
                    remoteUid = "Grant app access first",
                    selinuxContext = "Grant app access first",
                    manageActivityTasks = "Not checked"
                )
                return
            }

            val uid = Shizuku.getUid()
            val selinux = Shizuku.getSELinuxContext().orEmpty().ifBlank { "Unavailable" }
            val taskPermission = Shizuku.checkRemotePermission("android.permission.MANAGE_ACTIVITY_TASKS") == PackageManager.PERMISSION_GRANTED
            val identity = when (uid) {
                0 -> "Root (UID 0)"
                2000 -> "ADB Shell (UID 2000)"
                else -> "UID $uid"
            }
            uiState.value = uiState.value.copy(
                shizukuStatus = "Binder connected",
                appPermission = "Granted",
                remoteUid = identity,
                selinuxContext = selinux,
                manageActivityTasks = if (taskPermission) "Granted" else "Not granted"
            )
        } catch (error: Throwable) {
            uiState.value = uiState.value.copy(
                shizukuStatus = "Binder connected; query failed",
                appPermission = error.javaClass.simpleName + ": " + (error.message ?: "no message")
            )
        }
    }

    private fun requestAccessAndConnect() {
        if (!Shizuku.pingBinder()) {
            refreshShizukuState()
            return
        }
        if (Shizuku.isPreV11()) {
            uiState.value = uiState.value.copy(appPermission = "Shizuku server v11+ is required")
            return
        }
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindUserService()
            } else {
                Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
            }
        } catch (error: Throwable) {
            uiState.value = uiState.value.copy(appPermission = error.javaClass.simpleName + ": " + (error.message ?: "no message"))
        }
    }

    private fun bindUserService() {
        if (isBound) return
        uiState.value = uiState.value.copy(serviceStatus = "Connecting")
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            isBound = true
        } catch (error: Throwable) {
            isBound = false
            uiState.value = uiState.value.copy(serviceStatus = "Bind failed: " + error.javaClass.simpleName + ": " + (error.message ?: "no message"))
        }
    }

    private fun refreshRuntimeIdentity() {
        val remote = userService ?: return
        worker.execute {
            val result = runCatching { remote.runtimeIdentity }.getOrElse {
                "Identity query failed: ${it.javaClass.simpleName}: ${it.message}"
            }
            runOnUiThread {
                uiState.value = uiState.value.copy(runtimeIdentity = result)
            }
        }
    }

    private fun registerTaskOrganizer() {
        val remote = userService ?: return
        uiState.value = uiState.value.copy(organizerResult = "Registering…")
        worker.execute {
            val result = runCatching { remote.probeTaskOrganizer() }.getOrElse {
                "Binder call failed: ${it.javaClass.simpleName}: ${it.message}"
            }
            val callbacks = runCatching { remote.callbackEvents }.getOrDefault("Callback query failed")
            runOnUiThread {
                uiState.value = uiState.value.copy(organizerResult = result, callbackEvents = callbacks)
            }
        }
    }

    private fun refreshCallbackEvents() {
        val remote = userService ?: return
        worker.execute {
            val callbacks = runCatching { remote.callbackEvents }.getOrElse {
                "Callback query failed: ${it.javaClass.simpleName}: ${it.message}"
            }
            runOnUiThread {
                uiState.value = uiState.value.copy(callbackEvents = callbacks)
            }
        }
    }

    private fun unregisterTaskOrganizer() {
        val remote = userService ?: return
        worker.execute {
            val result = runCatching { remote.unregisterTaskOrganizer() }.getOrElse {
                "Unregister call failed: ${it.javaClass.simpleName}: ${it.message}"
            }
            runOnUiThread {
                uiState.value = uiState.value.copy(organizerResult = result, callbackEvents = "TaskOrganizer unregistered")
            }
        }
    }

    private fun openDemoTasks() {
        DemoMode.values().forEach { mode ->
            val intent = Intent(this, DemoActivity::class.java).apply {
                putExtra(DemoActivity.EXTRA_MODE, mode.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        if (isBound) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
            isBound = false
        }
        userService = null
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_CODE_SHIZUKU = 7101
    }
}
