// Hands a fabricated cell identity to a caller in the shape that caller's declared
// type can hold.
//
// WHY THIS IS SHARED RATHER THAN PRIVATE TO EACH HOOKER: three separate
// adaptations of the same value - a Bundle, a CellIdentity and a
// GsmCellLocation, each with its own formula for the area and cell codes - would
// answer with different numbers on one phone, and an app comparing
// getCellLocation() against getAllCellInfo() could see them disagree. One
// function, one formula.
//
// The type check at the end is not caution about our own building. A cell value
// crosses a Binder boundary, and handing a caller an object that its declared
// return type or parameter type cannot hold throws at the far end - inside the
// phone process or system_server, where a throw is a crash rather than a failed
// hook. Null is always the safe answer: no cell location, which is what a device
// with no service reports, and never the real value.
package org.pacer.hook.hooks.env

import android.os.Bundle
import org.pacer.hook.utils.HookUtils

object CellLocationShapes {

    private const val BUNDLE_CLASS = "android.os.Bundle"
    private const val CELL_LOCATION_CLASS = "android.telephony.CellLocation"

    /**
     * The identity of the fabricated serving cell: the first entry that reports
     * itself registered, falling back to the first entry. Null when there is no
     * payload to take one from.
     */
    fun servingIdentity(cells: List<Any>?): Any? {
        val list = cells ?: return null
        val serving = list.firstOrNull { read(it, "isRegistered") == true }
            ?: list.firstOrNull()
            ?: return null
        return read(serving, "getCellIdentity")
    }

    /**
     * [identity] in the shape [declaredType] can hold, or null when that shape
     * cannot be built. A null [declaredType] means the caller does not care and the
     * identity itself is handed over.
     */
    fun fit(identity: Any, declaredType: Class<*>?): Any? {
        val built = when {
            declaredType == null -> identity
            isBundle(declaredType) -> bundleFor(identity)
            isCellLocation(declaredType) -> asCellLocation(identity)
            else -> identity
        } ?: return null
        if (declaredType != null && !declaredType.isInstance(built)) return null
        return built
    }

    private fun isBundle(type: Class<*>): Boolean =
        type.name == BUNDLE_CLASS || HookUtils.hasTypeByName(type, BUNDLE_CLASS)

    private fun isCellLocation(type: Class<*>): Boolean =
        type.name == CELL_LOCATION_CLASS || HookUtils.hasTypeByName(type, CELL_LOCATION_CLASS)

    /**
     * The legacy notification Bundle.
     *
     * The platform's own writer is tried first, because it owns the mapping between
     * a cell type and the keys the framework's client side reads back. It is hidden,
     * and whether it lives on the identity or only on the identity's CellLocation
     * view varies, so both are tried. When neither wrote anything the legacy
     * lac/cid/psc keys are written directly; -1 is the value the telephony classes
     * themselves use for an unavailable field.
     */
    private fun bundleFor(identity: Any): Bundle {
        val bundle = Bundle()
        if (writeWithPlatform(identity, bundle)) return bundle
        bundle.putInt("lac", intOf(identity, "getTac", "getLac"))
        bundle.putInt("cid", intOf(identity, "getCi", "getCid"))
        bundle.putInt("psc", intOf(identity, "getPsc"))
        return bundle
    }

    private fun writeWithPlatform(identity: Any, bundle: Bundle): Boolean {
        for (target in listOfNotNull(identity, asCellLocation(identity))) {
            try {
                HookUtils.callMethod(target, "fillInNotifierBundle", bundle)
                // Checked by key rather than Bundle.isEmpty, whose meaning is not
                // the same across releases.
                if (bundle.containsKey("lac") || bundle.containsKey("cid") || bundle.containsKey("psc")) {
                    return true
                }
            } catch (_: Throwable) {
                // Absent or hidden on this release: try the other target, then the
                // legacy keys.
            }
        }
        return false
    }

    /** First readable getter wins; -1 when none exists, matching the platform. */
    private fun intOf(identity: Any, vararg getters: String): Int {
        for (getter in getters) {
            (read(identity, getter) as? Int)?.let { return it }
        }
        return -1
    }

    /** The identity's CellLocation view. Hidden, hence reflective and nullable. */
    fun asCellLocation(identity: Any): Any? =
        try {
            HookUtils.callMethod(identity, "asCellLocation")
        } catch (_: Throwable) {
            null
        }

    private fun read(target: Any, getter: String): Any? =
        try {
            HookUtils.noArgAccessor(target.javaClass, getter)?.invoke(target)
        } catch (_: Throwable) {
            null
        }
}
