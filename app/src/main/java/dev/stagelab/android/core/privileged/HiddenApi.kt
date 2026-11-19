package dev.stagelab.android.core.privileged

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Reflection helper for platform (hidden) APIs.
 *
 * StageLab intentionally resolves every non-public platform symbol at runtime instead of
 * linking against version specific compiler stubs. Android 17 (SDK 37) is still moving, and
 * the device ships an OEM modified framework, so a hard link failure (NoSuchMethodError) would
 * be fatal for the privileged service. Resolution failures surface as [HiddenApiException]
 * with the exact symbol that is missing, which is what the diagnostics screen reports.
 */
object HiddenApi {

    class HiddenApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    private val methodCache = ConcurrentHashMap<String, Method>()
    private val fieldCache = ConcurrentHashMap<String, Field>()

    fun classOrNull(name: String): Class<*>? = try {
        Class.forName(name)
    } catch (_: Throwable) {
        null
    }

    fun classOrThrow(name: String): Class<*> =
        classOrNull(name) ?: throw HiddenApiException("Class not found: $name")

    fun methodOrNull(
        owner: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? {
        val key = owner.name + "#" + name + parameterTypes.joinToString(",") { it.name }
        methodCache[key]?.let { return it }
        var current: Class<*>? = owner
        while (current != null) {
            try {
                val method = current.getDeclaredMethod(name, *parameterTypes)
                method.isAccessible = true
                methodCache[key] = method
                return method
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            } catch (t: Throwable) {
                break
            }
        }
        return null
    }

    fun methodOrThrow(owner: Class<*>, name: String, vararg parameterTypes: Class<*>): Method =
        methodOrNull(owner, name, *parameterTypes)
            ?: throw HiddenApiException(
                "Method not found: ${owner.name}#$name(${parameterTypes.joinToString(",") { it.simpleName }})"
            )

    fun fieldOrNull(owner: Class<*>, name: String): Field? {
        val key = owner.name + "#" + name
        fieldCache[key]?.let { return it }
        var current: Class<*>? = owner
        while (current != null) {
            try {
                val field = current.getDeclaredField(name)
                field.isAccessible = true
                fieldCache[key] = field
                return field
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            } catch (t: Throwable) {
                break
            }
        }
        return null
    }

    fun fieldOrThrow(owner: Class<*>, name: String): Field =
        fieldOrNull(owner, name) ?: throw HiddenApiException("Field not found: ${owner.name}#$name")

    fun readField(instance: Any, name: String): Any? =
        fieldOrThrow(instance.javaClass, name).get(instance)

    fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val types = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        val direct = methodOrNull(target.javaClass, name, *types)
        if (direct != null) {
            return direct.invoke(target, *args)
        }
        // Fall back to a name-only scan; primitive widening and interface typed parameters make
        // exact signature matching unreliable for framework code.
        val candidates = target.javaClass.methods.filter { it.name == name && it.parameterCount == args.size }
        val method = candidates.firstOrNull { candidate ->
            candidate.parameterTypes.indices.all { index ->
                val value = args[index] ?: return@all !candidate.parameterTypes[index].isPrimitive
                boxed(candidate.parameterTypes[index]).isAssignableFrom(value.javaClass)
            }
        } ?: throw HiddenApiException(
            "No compatible method ${target.javaClass.name}#$name(${args.joinToString(",") { it?.javaClass?.simpleName ?: "null" }})"
        )
        method.isAccessible = true
        return method.invoke(target, *args)
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> type
    }

    /** Service manager lookup for system services (`window`, `activity_task`, ...). */
    fun systemService(name: String): Any {
        val serviceManager = classOrThrow("android.os.ServiceManager")
        val binder = methodOrThrow(serviceManager, "getService", String::class.java).invoke(null, name)
            ?: throw HiddenApiException("System service unavailable: $name")
        return binder
    }

    fun asInterface(stubClass: Class<*>, binder: Any): Any {
        val method = methodOrThrow(stubClass, "asInterface", classOrThrow("android.os.IBinder"))
        return method.invoke(null, binder) ?: throw HiddenApiException("asInterface returned null")
    }

    fun interfaceClass(name: String): Class<*> = classOrThrow(name)
}
