package dev.stagelab.android.core.privileged

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Root / ADB service host.
 *
 * Started by the app as:
 * ```
 * su -c 'CLASSPATH=<base.apk> app_process /system/bin --nice-name=stagelab-root \
 *        dev.stagelab.android.core.privileged.RootServiceMain connect <port>'
 * ```
 * or from a developer shell with `listen <port>`.
 *
 * The wire protocol is newline delimited JSON, authenticated with a one-shot token that the app
 * generates for each launch. The privileged process never opens a port by itself when started in
 * `connect` mode, which keeps it off the network surface.
 */
object RootServiceMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.getOrNull(0) ?: "listen"
        val port = args.getOrNull(1)?.toIntOrNull() ?: 39721
        val token = args.getOrNull(2).orEmpty()
        log("start mode=$mode port=$port uid=${RuntimeIdentity.uid()} selinux=${RuntimeIdentity.selinuxContext()}")

        try {
            when (mode) {
                "connect" -> {
                    val socket = Socket(InetAddress.getByName("127.0.0.1"), port)
                    log("connected back to app on $port")
                    serve(socket, token)
                }
                else -> {
                    val server = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))
                    log("listening on 127.0.0.1:$port")
                    while (true) {
                        val socket = server.accept()
                        log("accepted ${socket.remoteSocketAddress}")
                        Thread { runCatching { serve(socket, token) } }.start()
                    }
                }
            }
        } catch (t: Throwable) {
            log("fatal " + t.javaClass.simpleName + ": " + t.message)
        }
    }

    private fun serve(socket: Socket, expectedToken: String) {
        socket.use { client ->
            client.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))
            var authenticated = false
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val reply = if (!authenticated) {
                    authenticated = authenticate(line, expectedToken)
                    if (authenticated) {
                        JSONObject()
                            .put("ok", true)
                            .put("data", JSONObject()
                                .put("identity", RuntimeIdentity.describe())
                                .put("uid", RuntimeIdentity.uid())
                                .put("protocol", StageEngine.PROTOCOL_VERSION))
                            .toString()
                    } else {
                        JSONObject().put("ok", false).put("error", "unauthorized").toString()
                    }
                } else {
                    StageEngine.handle(line)
                }
                writer.write(reply.replace('\n', ' '))
                writer.newLine()
                writer.flush()
                if (!authenticated) break
            }
        }
    }

    private fun authenticate(line: String, expectedToken: String): Boolean = runCatching {
        val obj = JSONObject(line)
        obj.optString("cmd") == "auth" &&
            (expectedToken.isEmpty() || obj.optString("token") == expectedToken)
    }.getOrDefault(false)

    private fun log(message: String) {
        println("[StageLabRoot] $message")
        System.out.flush()
    }
}
