package dev.stagelab.android.core.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.stagelab.android.core.ipc.ChannelException
import dev.stagelab.android.core.ipc.PrivilegedChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shizuku transport. The UserService runs as UID 2000 (Shell) and therefore has
 * `MANAGE_ACTIVITY_TASKS` on this platform, which is exactly what the Stage engine needs.
 */
class ShizukuChannel(private val context: Context) : PrivilegedChannel {

    override val name: String = "Shizuku / Shell (UID 2000)"

    private val serviceRef = AtomicReference<IStageTaskService?>(null)
    private var bound = false
    private var connection: ServiceConnection? = null

    private val args: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context, StageTaskUserService::class.java))
            .daemon(false)
            .processNameSuffix("stage-engine")
            .tag("stagelab-stage-engine")
            .version(2)
    }

    override suspend fun connect(): JSONObject {
        if (!Shizuku.pingBinder()) {
            throw ChannelException("Shizuku 服务未运行。请先启动 Shizuku。")
        }
        if (Shizuku.isPreV11()) {
            throw ChannelException("需要 Shizuku v11 或更新版本。")
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            throw ChannelException("尚未授予 StageLab 的 Shizuku 权限。")
        }
        bindUserService()
        val hello = call(JSONObject().put("cmd", "hello"))
        if (!hello.optBoolean("ok")) throw ChannelException("Stage 引擎握手失败: " + hello.optString("error"))
        return hello.optJSONObject("data") ?: JSONObject()
    }

    private suspend fun bindUserService() = withContext(Dispatchers.Main) {
        if (bound && serviceRef.get() != null) return@withContext
        withTimeout(15_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val newConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        serviceRef.set(IStageTaskService.Stub.asInterface(binder))
                        bound = true
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        serviceRef.set(null)
                        bound = false
                    }
                }
                connection = newConnection
                try {
                    Shizuku.bindUserService(args, newConnection)
                } catch (t: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(t)
                }
                continuation.invokeOnCancellation {
                    runCatching { Shizuku.unbindUserService(args, newConnection, false) }
                }
            }
        }
    }

    override suspend fun call(command: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val service = serviceRef.get() ?: throw ChannelException("Shizuku 服务未连接")
        val raw = try {
            service.request(command.toString())
        } catch (t: Throwable) {
            throw ChannelException("Shizuku 调用失败: ${t.message}", t)
        }
        JSONObject(raw)
    }

    override fun close() {
        val existing = connection
        if (existing != null) {
            runCatching { Shizuku.unbindUserService(args, existing, true) }
        }
        connection = null
        bound = false
        serviceRef.set(null)
    }

    companion object {
        /** True when Shizuku's server is reachable and this app has been granted access. */
        fun isReady(): Boolean = runCatching {
            Shizuku.pingBinder() &&
                !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        fun isServerRunning(): Boolean = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }
            .getOrDefault(false)

        fun hasPermission(): Boolean = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        fun requestPermission(requestCode: Int) {
            runCatching { Shizuku.requestPermission(requestCode) }
        }
    }
}
