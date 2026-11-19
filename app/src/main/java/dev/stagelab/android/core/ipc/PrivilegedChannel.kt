package dev.stagelab.android.core.ipc

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/** A transport to the privileged task engine (Shizuku, KernelSU root, or an ADB shell service). */
interface PrivilegedChannel {
    val name: String

    /** Connects and performs the authenticated handshake. */
    suspend fun connect(): JSONObject

    suspend fun call(command: JSONObject): JSONObject

    fun close()
}

class ChannelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * KernelSU / Magisk root channel.
 *
 * The app opens an ephemeral loopback listener, then asks `su` to start
 * [dev.stagelab.android.core.privileged.RootServiceMain] which connects back and authenticates
 * with a one-shot token. No port is exposed to the LAN and no long lived socket is opened by the
 * privileged side.
 */
class RootChannel(private val context: Context) : PrivilegedChannel {

    override val name: String = "KernelSU Root"

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var process: Process? = null
    private var token: String = ""

    override suspend fun connect(): JSONObject = withContext(Dispatchers.IO) {
        close()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = CONNECT_TIMEOUT_MS
        token = UUID.randomUUID().toString()
        val port = server.localPort
        val apk = context.applicationInfo.sourceDir
        val command = "CLASSPATH=$apk app_process /system/bin --nice-name=stagelab-root " +
            "dev.stagelab.android.core.privileged.RootServiceMain connect $port $token"

        val result = runCatching {
            // App processes do not always inherit /system/bin in PATH and KernelSU may expose su
            // only through a specific path, so probe by execution and report every failure.
            val candidates = listOf(
                listOf("/system/bin/su", "-c", command),
                listOf("/debug_ramdisk/su", "-c", command),
                listOf("/sbin/su", "-c", command),
                listOf("su", "-c", command),
                listOf("/system/bin/sh", "-c", "su -c " + shellQuote(command))
            )
            val failures = StringBuilder()
            var started: Process? = null
            for (argv in candidates) {
                try {
                    val builder = ProcessBuilder(argv)
                    builder.redirectErrorStream(true)
                    started = builder.start()
                    break
                } catch (t: Throwable) {
                    failures.append(argv.first()).append(": ")
                        .append(t.message).append(" | ")
                }
            }
            val process = started
                ?: throw ChannelException("无法启动 root 服务（su 不可用）: $failures")
            this@RootChannel.process = process
            // Drain output so the child never blocks on a full pipe.
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        android.util.Log.d("StageLabRoot", line)
                    }
                }
            }.apply { isDaemon = true }.start()
            process
        }.getOrElse { error ->
            server.close()
            throw ChannelException("无法启动 su 服务: ${error.message}", error)
        }

        val accepted = runCatching { server.accept() }.getOrElse { error ->
            server.close()
            result.destroy()
            process = null
            throw ChannelException("Root 服务未连接回来（su 被拒绝或进程启动失败）: ${error.message}", error)
        }
        server.close()
        socket = accepted
        accepted.tcpNoDelay = true
        reader = BufferedReader(InputStreamReader(accepted.getInputStream(), Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(accepted.getOutputStream(), Charsets.UTF_8))

        val hello = exchange(JSONObject().put("cmd", "auth").put("token", token))
        if (!hello.optBoolean("ok")) {
            throw ChannelException("Root 服务认证失败: " + hello.optString("error"))
        }
        hello.optJSONObject("data") ?: JSONObject()
    }

    override suspend fun call(command: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        exchange(command)
    }

    private fun exchange(command: JSONObject): JSONObject {
        val out = writer ?: throw ChannelException("Root 服务未连接")
        val input = reader ?: throw ChannelException("Root 服务未连接")
        out.write(command.toString().replace('\n', ' '))
        out.newLine()
        out.flush()
        val line = input.readLine() ?: throw ChannelException("Root 服务已断开")
        return JSONObject(line)
    }

    override fun close() {
        runCatching { socket?.close() }
        runCatching { process?.destroy() }
        socket = null
        reader = null
        writer = null
        process = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 12_000

        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}

/**
 * ADB channel: the developer starts the service from a PC with `scripts/adb-service.ps1`, and the
 * app connects to the loopback listener. This is the zero-install path used for bring-up.
 */
class AdbChannel(private val port: Int = 39721) : PrivilegedChannel {

    override val name: String = "ADB shell service"

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    override suspend fun connect(): JSONObject = withContext(Dispatchers.IO) {
        close()
        val client = Socket(InetAddress.getByName("127.0.0.1"), port)
        client.soTimeout = 4000
        client.tcpNoDelay = true
        socket = client
        reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))
        val hello = exchange(JSONObject().put("cmd", "auth").put("token", ""))
        if (!hello.optBoolean("ok")) throw ChannelException("ADB 服务认证失败")
        client.soTimeout = 0
        hello.optJSONObject("data") ?: JSONObject()
    }

    override suspend fun call(command: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        exchange(command)
    }

    private fun exchange(command: JSONObject): JSONObject {
        val out = writer ?: throw ChannelException("ADB 服务未连接")
        val input = reader ?: throw ChannelException("ADB 服务未连接")
        out.write(command.toString().replace('\n', ' '))
        out.newLine()
        out.flush()
        val line = input.readLine() ?: throw ChannelException("ADB 服务已断开")
        return JSONObject(line)
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }
}
