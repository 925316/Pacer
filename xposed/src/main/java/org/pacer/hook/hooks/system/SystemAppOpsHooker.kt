// system_server side AppOps anti-detection. A detector that asks the framework
// "is mock location allowed for this app?" must be answered MODE_IGNORED,
// while Pacer's own process keeps seeing MODE_ALLOWED so its own operations
// are not broken: hide from others, allow myself.
package org.pacer.hook.hooks.system

import io.github.libxposed.api.XposedInterface
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.system.vendor.SystemClassLocator
import org.pacer.hook.hooks.system.vendor.SystemComponent
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

object SystemAppOpsHooker {

    private const val MODE_ALLOWED = 0
    private const val MODE_IGNORED = 1
    private const val FALLBACK_OP_MOCK_LOCATION = 58
    private const val OP_NAME = "android:mock_location"

    private val serviceMethods = arrayOf(
        "checkOperation",
        "checkOperationImpl",
        "noteOperation",
        "noteOperationUnchecked",
        "noteOperationWithResult",
    )

    private val managerMethods = arrayOf(
        "checkOp",
        "checkOpNoThrow",
        "unsafeCheckOp",
        "unsafeCheckOpRaw",
        "noteOp",
        "noteOpNoThrow",
        "noteProxyOp",
        "noteProxyOpNoThrow",
    )

    fun install(classLoader: ClassLoader) {
        val mockOp = resolveMockLocationOp(classLoader)
        var hooked = 0
        val service = SystemClassLocator.locate(SystemComponent.APPOPS_SERVICE, classLoader)
        if (service != null) {
            for (method in serviceMethods) {
                hooked += HookUtils.hookAllMethods(service, method) { chain -> decide(chain, mockOp) }
            }
        }
        val manager = HookUtils.findClassIfExists("android.app.AppOpsManager", classLoader)
        if (manager != null) {
            for (method in managerMethods) {
                hooked += HookUtils.hookAllMethods(manager, method) { chain -> decide(chain, mockOp) }
            }
        }
        PacerLog.i("system appops hooks installed (op=$mockOp, targets=$hooked)")
    }

    /**
     * Resolves the runtime op code for "android:mock_location" through the
     * platform's own strOpToOp() so a shifted op table on a newer release is
     * handled automatically. Falls back to the long-standing AOSP value 58.
     */
    private fun resolveMockLocationOp(classLoader: ClassLoader): Int {
        val candidates = listOfNotNull(
            HookUtils.findClassIfExists("android.app.AppOpsManager", classLoader),
            SystemClassLocator.locate(SystemComponent.APPOPS_SERVICE, classLoader),
        )
        for (clazz in candidates) {
            val one = try {
                HookUtils.findMethodExact(clazz, "strOpToOp", String::class.java)
            } catch (_: Throwable) {
                null
            }
            if (one != null) {
                val value = try {
                    one.invoke(null, OP_NAME) as? Int
                } catch (_: Throwable) {
                    null
                }
                if (value != null) return value
            }
            val two = try {
                HookUtils.findMethodExact(clazz, "strOpToOp", String::class.java, Int::class.javaPrimitiveType!!)
            } catch (_: Throwable) {
                null
            }
            if (two != null) {
                val value = try {
                    two.invoke(null, OP_NAME, 0) as? Int
                } catch (_: Throwable) {
                    null
                }
                if (value != null) return value
            }
        }
        return FALLBACK_OP_MOCK_LOCATION
    }

    private fun decide(chain: XposedInterface.Chain, mockOp: Int): Any? {
        if (!PacerRuntimeConfig.snapshot().active) return chain.proceed(chain.args.toTypedArray())
        if (!isMockLocationOp(chain.args, mockOp)) return chain.proceed(chain.args.toTypedArray())
        val uid = resolveUid(chain.args, mockOp)
        if (uid >= 0 && uid == ownUid()) return MODE_ALLOWED
        return MODE_IGNORED
    }

    private fun isMockLocationOp(args: List<Any?>, mockOp: Int): Boolean {
        for (arg in args) {
            if (arg is Int && arg == mockOp) return true
            if (arg is String && (arg == OP_NAME || arg == "mock_location")) return true
        }
        return false
    }

    /**
     * The affected uid sits immediately after the op code in every AppOps
     * signature. Returns -1 when the overload carries no uid, which leaves the
     * call hidden from the caller.
     */
    private fun resolveUid(args: List<Any?>, mockOp: Int): Int {
        var seenOp = false
        for (arg in args) {
            if (!seenOp) {
                if (arg is Int && arg == mockOp) {
                    seenOp = true
                } else if (arg is String && (arg == OP_NAME || arg == "mock_location")) {
                    seenOp = true
                }
                continue
            }
            if (arg is Int) return arg
        }
        return -1
    }

    private fun ownUid(): Int = try {
        HookUtils.module.moduleApplicationInfo.uid
    } catch (_: Throwable) {
        -1
    }
}
