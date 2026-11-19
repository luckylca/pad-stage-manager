package dev.stagelab.android.core.privileged

import android.os.Build
import java.io.File

/** Identity of the process that hosts the privileged service. */
object RuntimeIdentity {

    fun uid(): Int = runCatching { android.system.Os.getuid() }.getOrDefault(-1)

    fun pid(): Int = runCatching { android.system.Os.getpid() }.getOrDefault(-1)

    fun selinuxContext(): String = runCatching {
        File("/proc/self/attr/current").readText().trim()
    }.getOrDefault("unavailable")

    fun describe(): String = buildString {
        val uid = uid()
        append("uid=").append(uid)
        append(" (").append(
            when (uid) {
                0 -> "root"
                1000 -> "system"
                2000 -> "shell"
                else -> "app/oem"
            }
        ).append(")")
        append(" pid=").append(pid())
        append(" selinux=").append(selinuxContext())
        append(" sdk=").append(Build.VERSION.SDK_INT)
        append(" device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append(" build=").append(Build.DISPLAY)
    }

    fun permissionGranted(permission: String): Boolean = runCatching {
        val pm = HiddenApi.systemService("package")
        val stub = HiddenApi.interfaceClass("android.content.pm.IPackageManager")
        val proxy = HiddenApi.methodOrThrow(stub, "asInterface", HiddenApi.classOrThrow("android.os.IBinder"))
            .invoke(null, pm) ?: return false
        val check = HiddenApi.methodOrThrow(
            proxy.javaClass,
            "checkPermission",
            String::class.java,
            String::class.java,
            Integer.TYPE
        )
        val uid = uid()
        val pkg = when (uid) {
            2000 -> "com.android.shell"
            1000 -> "android"
            0 -> "root"
            else -> "android"
        }
        (check.invoke(proxy, permission, pkg, uid) as? Int) == 0
    }.getOrDefault(false)
}
