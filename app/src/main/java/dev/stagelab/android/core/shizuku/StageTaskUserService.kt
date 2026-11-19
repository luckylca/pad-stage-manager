package dev.stagelab.android.core.shizuku

import android.system.Os
import androidx.annotation.Keep
import dev.stagelab.android.core.privileged.StageEngine
import dev.stagelab.android.core.privileged.StageTaskOrganizerHolder
import java.io.File

/**
 * Shizuku UserService entry point. Shizuku starts this class with `app_process` in a dedicated
 * process running as UID 2000 (Shell) or UID 0 (Root). Every command is forwarded to
 * [StageEngine], which is the same engine the KernelSU/ADB service uses, so both transports share
 * one implementation of task control.
 */
@Keep
class StageTaskUserService : IStageTaskService.Stub() {

    override fun getRuntimeIdentity(): String = buildString {
        append("uid=").append(Os.getuid()).append('\n')
        append("pid=").append(Os.getpid()).append('\n')
        append("selinux=").append(
            runCatching { File("/proc/self/attr/current").readText().trim() }.getOrDefault("unavailable")
        ).append('\n')
        append("engine=").append(StageEngine.PROTOCOL_VERSION)
    }

    override fun probeTaskOrganizer(): String = StageEngine.handle("{\"cmd\":\"organizerProbe\"}")

    override fun getCallbackEvents(): String = StageEngine.handle("{\"cmd\":\"events\"}")

    override fun unregisterTaskOrganizer(): String =
        StageEngine.handle("{\"cmd\":\"organizerUnregister\"}")

    override fun request(commandJson: String): String = StageEngine.handle(commandJson)

    override fun destroy() {
        runCatching { StageTaskOrganizerHolder.unregister() }
        System.exit(0)
    }
}
