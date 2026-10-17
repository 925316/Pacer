package org.pacer.hook.utils

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

object HookUtils {

    lateinit var module: XposedModule
        private set

    fun bind(module: XposedModule) {
        this.module = module
    }

    fun isBound(): Boolean = ::module.isInitialized

    private val fieldCache = ConcurrentHashMap<String, Field?>()
    private val methodCache = ConcurrentHashMap<String, Method?>()

    fun findClass(className: String, classLoader: ClassLoader?): Class<*> =
        Class.forName(className, false, classLoader ?: ClassLoader.getSystemClassLoader())

    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? =
        try {
            findClass(className, classLoader)
        } catch (_: Throwable) {
            null
        }

    /**
     * Resolves [fqn] among [clazz]'s interfaces and superclasses, or returns null.
     *
     * Several hookers have to answer "is this argument the callback object I care
     * about?" from an argument list whose order they do not control. Matching the
     * TYPE NAME rather than a parameter position is what makes that portable: the
     * registration methods keep their callback types even when a request object is
     * inserted in front of them, which is exactly what changed between the
     * registerGnss*Callback and addGnss*Listener families.
     */
    fun typeByName(clazz: Class<*>, fqn: String): Class<*>? {
        var c: Class<*>? = clazz
        while (c != null) {
            for (i in c.interfaces) {
                if (i.name == fqn) return i
                typeByName(i, fqn)?.let { return it }
            }
            c = c.superclass
        }
        return null
    }

    fun hasTypeByName(clazz: Class<*>, fqn: String): Boolean = typeByName(clazz, fqn) != null

    private fun field(clazz: Class<*>, name: String): Field? {
        val key = clazz.name + "#" + name
        if (fieldCache.containsKey(key)) return fieldCache[key]
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                fieldCache[key] = f
                return f
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        fieldCache[key] = null
        return null
    }

    fun getObjectField(obj: Any, name: String): Any? =
        field(obj.javaClass, name)?.get(obj)

    fun getIntField(obj: Any, name: String): Int =
        field(obj.javaClass, name)?.getInt(obj) ?: 0

    fun getBooleanField(obj: Any, name: String): Boolean =
        field(obj.javaClass, name)?.getBoolean(obj) ?: false

    fun getLongField(obj: Any, name: String): Long =
        field(obj.javaClass, name)?.getLong(obj) ?: 0L

    fun setObjectField(obj: Any, name: String, value: Any?) {
        field(obj.javaClass, name)?.set(obj, value)
    }

    fun setIntField(obj: Any, name: String, value: Int) {
        field(obj.javaClass, name)?.setInt(obj, value)
    }

    fun setBooleanField(obj: Any, name: String, value: Boolean) {
        field(obj.javaClass, name)?.setBoolean(obj, value)
    }

    fun setLongField(obj: Any, name: String, value: Long) {
        field(obj.javaClass, name)?.setLong(obj, value)
    }

    fun setField(obj: Any, name: String, value: Any?) {
        field(obj.javaClass, name)?.set(obj, value)
    }

    /** Non-throwing setter: missing fields are common across SDK versions. */
    fun setFieldQuietly(obj: Any, name: String, value: Any?) {
        try {
            setField(obj, name, value)
        } catch (_: Throwable) {
        }
    }

    fun callMethod(obj: Any, methodName: String, vararg args: Any?): Any? {
        val key = obj.javaClass.name + "#" + methodName + "#" + args.size
        val cached = methodCache[key]
        if (cached != null) return cached.invoke(obj, *args)
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == methodName && m.parameterCount == args.size) {
                    m.isAccessible = true
                    methodCache[key] = m
                    return m.invoke(obj, *args)
                }
            }
            c = c.superclass
        }
        throw NoSuchMethodException(methodName)
    }

    fun findMethodExact(clazz: Class<*>, name: String, vararg types: Class<*>): Method {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val m = c.getDeclaredMethod(name, *types)
                m.isAccessible = true
                return m
            } catch (_: NoSuchMethodException) {
                c = c.superclass
            }
        }
        throw NoSuchMethodException(name)
    }

    /**
     * Hooks every declared, non-abstract method with [methodName] on [clazz].
     * Returns how many methods were successfully hooked.
     */
    inline fun hookAllMethods(
        clazz: Class<*>,
        methodName: String,
        crossinline interceptor: (XposedInterface.Chain) -> Any?,
    ): Int {
        var hooked = 0
        for (method in clazz.declaredMethods) {
            if (method.name == methodName && !Modifier.isAbstract(method.modifiers)) {
                try {
                    module.hook(method).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            return interceptor(chain)
                        }
                    })
                    hooked++
                } catch (_: Throwable) {
                    // Hook failure on one overload does not abort the rest.
                }
            }
        }
        return hooked
    }

    inline fun hookExact(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>,
        crossinline interceptor: (XposedInterface.Chain) -> Any?,
    ): Boolean {
        return try {
            val method = findMethodExact(clazz, methodName, *parameterTypes)
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? = interceptor(chain)
            })
            true
        } catch (_: Throwable) {
            false
        }
    }

    inline fun hookAllConstructors(
        clazz: Class<*>,
        crossinline interceptor: (XposedInterface.Chain) -> Any?,
    ) {
        for (ctor: Constructor<*> in clazz.declaredConstructors) {
            try {
                module.hook(ctor).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? = interceptor(chain)
                })
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Runs an installer, records the outcome in [HookStatus], and never lets a
     * failure escape: a broken hooker must not take the target process with it.
     */
    inline fun <T> runCatchingLogged(tag: String, block: () -> T): T? {
        val before = HookStatus.countHooked()
        return try {
            val result = block()
            HookStatus.recordOk(tag, HookStatus.countHooked() - before)
            result
        } catch (t: Throwable) {
            PacerLog.w(tag, t)
            HookStatus.recordFailure(tag, t)
            null
        }
    }
}

/** Small logging facade so hooks never crash the target process. */
object PacerLog {
    private const val TAG = "Pacer"

    fun i(msg: String) {
        try {
            android.util.Log.i(TAG, msg)
        } catch (_: Throwable) {
        }
        try {
            if (HookUtils.isBound()) HookUtils.module.log(android.util.Log.INFO, TAG, msg)
        } catch (_: Throwable) {
        }
    }

    fun w(scope: String, t: Throwable) {
        i("$scope: ${t.message}")
    }
}

/** Convenience: same signatures as the reference helpers. */
typealias ExecutableRef = Executable
