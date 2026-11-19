package dev.stagelab.android.core.shizuku

import android.system.Os
import androidx.annotation.Keep
import java.io.File

/** Runs in the Shizuku/Sui privileged UserService process (Shell UID or Root UID). */
@Keep
class StageTaskUserService : IStageTaskService.Stub() {
    private var organizer: StageTaskOrganizer? = null

    override fun getRuntimeIdentity(): String {
        val selinux = runCatching {
            File("/proc/self/attr/current").readText().trim()
        }.getOrDefault("unavailable")
        return "uid=${Os.getuid()}\npid=${Os.getpid()}\nselinux=$selinux"
    }

    @Synchronized
    override fun probeTaskOrganizer(): String {
        organizer?.let {
            return "ALREADY_REGISTERED\ninitialTasks=${it.initialTaskCount}\n${it.snapshot()}"
        }

        val candidate = StageTaskOrganizer()
        return try {
            val initialTasks = candidate.registerOrganizer()
            candidate.setInitialTaskCount(initialTasks?.size ?: 0)
            organizer = candidate
            "REGISTERED\ninitialTasks=${candidate.initialTaskCount}\n" +
                "No task reparenting, bounds changes, or Surface transactions were performed."
        } catch (error: Throwable) {
            val root = generateSequence(error) { it.cause }.last()
            "REGISTRATION_FAILED\n${root.javaClass.name}: ${root.message ?: "no message"}"
        }
    }

    override fun getCallbackEvents(): String = organizer?.snapshot() ?: "TaskOrganizer is not registered."

    @Synchronized
    override fun unregisterTaskOrganizer(): String {
        val current = organizer ?: return "NOT_REGISTERED"
        return try {
            current.unregisterOrganizer()
            organizer = null
            "UNREGISTERED"
        } catch (error: Throwable) {
            val root = generateSequence(error) { it.cause }.last()
            "UNREGISTER_FAILED\n${root.javaClass.name}: ${root.message ?: "no message"}"
        }
    }

    override fun destroy() {
        runCatching { unregisterTaskOrganizer() }
        System.exit(0)
    }
}
