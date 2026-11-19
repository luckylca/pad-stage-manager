package dev.stagelab.android.core.privileged

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect

/**
 * ADB harness. Runs the exact same privileged engine that Shizuku / KernelSU will run, but is
 * started directly from a shell:
 *
 * ```
 * CLASSPATH=/data/app/.../base.apk app_process /system/bin \
 *   dev.stagelab.android.core.privileged.ShellProbeMain launch
 * ```
 *
 * Keeping this entry point in the APK means every privileged code path can be validated over ADB
 * before Shizuku or root is wired up, and it doubles as the KernelSU root service bootstrap.
 */
object ShellProbeMain {

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== StageLab privileged harness ===")
        println(RuntimeIdentity.describe())
        val engine = TaskMutations()

        val command = args.firstOrNull() ?: "probe"
        try {
            when (command) {
                "probe" -> probe(engine)
                "launch" -> {
                    val pkg = args.getOrNull(1) ?: "dev.stagelab.android"
                    val activity = args.getOrNull(2) ?: "dev.stagelab.android.demo.DemoActivity"
                    val rect = parseRect(args.getOrNull(3)) ?: Rect(80, 240, 980, 1700)
                    val outcome = engine.startFreeformTask(
                        Intent().setComponent(ComponentName(pkg, activity))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                        rect
                    )
                    println("launch success=${outcome.success} taskId=${outcome.taskId} ${outcome.detail}")
                }
                "list" -> dumpTasks(engine)
                "resize" -> {
                    val taskId = args.getOrNull(1)?.toIntOrNull() ?: error("taskId required")
                    val rect = parseRect(args.getOrNull(2)) ?: error("rect required")
                    println(engine.resizeTask(taskId, rect))
                }
                "focus" -> {
                    val taskId = args.getOrNull(1)?.toIntOrNull() ?: error("taskId required")
                    println(engine.focusTask(taskId))
                }
                "remove" -> {
                    val taskId = args.getOrNull(1)?.toIntOrNull() ?: error("taskId required")
                    println(engine.removeTask(taskId))
                }
                "freeform" -> println(engine.enableFreeformGlobally())
                "shell" -> println(engine.shell(args.drop(1).joinToString(" ")))
                "reflect" -> reflect(args.drop(1))
                "raw" -> raw(engine)
                "cmd" -> println(StageEngine.handle(args.drop(1).joinToString(" ")))
                else -> probe(engine)
            }
        } catch (t: Throwable) {
            println("HARNESS_ERROR ${t.javaClass.name}: ${t.message}")
            t.stackTrace.take(8).forEach { println("  at $it") }
        }
        println("=== done ===")
        System.out.flush()
    }

    private fun probe(engine: TaskMutations) {
        println("--- diagnostics ---")
        engine.diagnostics().forEach { (key, value) -> println("$key = $value") }
        println("--- freeform settings ---")
        println(engine.enableFreeformGlobally())
        println("--- tasks ---")
        dumpTasks(engine)
        engine.lastError?.let { println("listTasks error: $it") }
    }

    private fun raw(engine: TaskMutations) {
        val atmClass = HiddenApi.classOrThrow("android.app.ActivityTaskManager")
        val atm = HiddenApi.methodOrThrow(atmClass, "getService").invoke(null)!!
        println("atm class: " + atm.javaClass.name)
        println("methods with getTasks: " + atm.javaClass.methods.filter { it.name == "getTasks" }
            .joinToString { it.parameterTypes.joinToString(",") { p -> p.simpleName } })
        try {
            val result = HiddenApi.invoke(atm, "getTasks", Int.MAX_VALUE, false, true, 0)
            println("getTasks -> " + result?.javaClass?.name + " size=" + (result as? List<*>)?.size)
            (result as? List<*>)?.take(5)?.forEach { item ->
                println("  item " + item?.javaClass?.name + " -> " + item)
            }
        } catch (t: Throwable) {
            println("getTasks FAILED " + engine.describeError(t))
            t.printStackTrace()
        }
        try {
            val roots = HiddenApi.invoke(atm, "getAllRootTaskInfos")
            println("getAllRootTaskInfos -> " + (roots as? List<*>)?.size)
            (roots as? List<*>)?.take(6)?.forEach { println("  root " + it) }
        } catch (t: Throwable) {
            println("getAllRootTaskInfos FAILED " + engine.describeError(t))
        }
        val existing = 386
        try {
            println("resizeTask(386): " + engine.resizeTask(existing, android.graphics.Rect(120, 260, 960, 1500)))
        } catch (t: Throwable) {
            println("resizeTask FAILED " + engine.describeError(t))
        }
        try {
            HiddenApi.invoke(atm, "setFocusedTask", existing)
            println("setFocusedTask(386) ok")
        } catch (t: Throwable) {
            println("setFocusedTask FAILED " + engine.describeError(t))
        }
    }

    private fun reflect(classes: List<String>) {
        val targets = if (classes.isEmpty()) {
            listOf(
                "android.app.IActivityTaskManager",
                "android.window.IWindowOrganizerController",
                "android.window.TaskOrganizer",
                "android.app.TaskInfo"
            )
        } else {
            classes
        }
        for (name in targets) {
            val cls = HiddenApi.classOrNull(name)
            if (cls == null) {
                println("$name -> MISSING")
                continue
            }
            println("### $name")
            if (name == "android.app.TaskInfo") {
                cls.declaredFields.forEach { println("  field ${it.type.simpleName} ${it.name}") }
            } else {
                cls.declaredMethods
                    .filter { it.name.startsWith("get") || it.name.contains("start") || it.name.contains("Task") || it.name.contains("apply") || it.name.contains("register") || it.name.contains("resize") || it.name.contains("move") }
                    .sortedBy { it.name }
                    .forEach { m ->
                        println("  ${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }}) -> ${m.returnType.simpleName}")
                    }
            }
        }
    }

    private fun dumpTasks(engine: TaskMutations) {
        val tasks = engine.listTasks()
        println("taskCount=${tasks.size}")
        tasks.take(40).forEach { task ->
            println(
                "task=${task.taskId} root=${task.rootTaskId} display=${task.displayId} " +
                    "mode=${task.windowingMode} visible=${task.isVisible} focused=${task.isFocused} " +
                    "bounds=${task.bounds} top=${task.topActivity}"
            )
        }
    }

    private fun parseRect(value: String?): Rect? {
        val parts = value?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        if (parts.size != 4) return null
        return Rect(parts[0], parts[1], parts[2], parts[3])
    }
}
