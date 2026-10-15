// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Reflection and hook helpers for the LSPosed entry point. Independent
// implementation; the structure is informed by the public GPL-3.0
// LocationSpoofer sources (see THIRD_PARTY_NOTICES.md).
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

    inline fun <T> runCatchingLogged(tag: String, block: () -> T): T? =
        try {
            block()
        } catch (t: Throwable) {
            PacerLog.w(tag, t)
            null
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
