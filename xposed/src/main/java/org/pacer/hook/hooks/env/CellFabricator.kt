package org.pacer.hook.hooks.env

import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.lang.reflect.Constructor

object CellFabricator {

    /** A phone reports a handful of cells, never dozens. */
    private const val MAX_CELLS = 5

    /**
     * Set once construction has proven impossible on this build. Kept so a
     * failure costs one attempt instead of one attempt per call for the rest of
     * the session.
     */
    @Volatile private var unavailable = false

    @Volatile private var reported = false

    /**
     * Returns fabricated cells, or null when the caller must suppress instead.
     * Never throws: a failure here must not take the host app's telephony read
     * down with it.
     */
    fun fabricate(
        cells: List<FakeRadioEnvironment.Cell>,
        classLoader: ClassLoader?,
    ): List<Any>? {
        if (unavailable || cells.isEmpty()) return null
        return try {
            val built = ArrayList<Any>(MAX_CELLS)
            for (cell in cells) {
                if (built.size >= MAX_CELLS) break
                buildOne(cell, classLoader)?.let { built.add(it) }
            }
            if (built.isEmpty()) {
                unavailable = true
                PacerLog.i("cell fabrication unsupported on this build; suppressing cells instead")
                null
            } else {
                if (!reported) {
                    reported = true
                    PacerLog.i("cell fabrication active (${built.size} cell(s))")
                }
                built
            }
        } catch (t: Throwable) {
            unavailable = true
            PacerLog.w("cell-fabrication", t)
            null
        }
    }

    // ---- construction ----

    private fun buildOne(cell: FakeRadioEnvironment.Cell, classLoader: ClassLoader?): Any? {
        val family = familyFor(cell.type)
        val infoClass = HookUtils.findClassIfExists("android.telephony.CellInfo$family", classLoader) ?: return null
        val identityClass = HookUtils.findClassIfExists("android.telephony.CellIdentity$family", classLoader) ?: return null
        val strengthClass = HookUtils.findClassIfExists("android.telephony.CellSignalStrength$family", classLoader) ?: return null

        val identity = buildIdentity(identityClass, family, cell) ?: return null
        val strength = buildStrength(strengthClass, cell.level) ?: return null
        val info = buildInfo(infoClass, family, identity, strength) ?: return null
        if (!HookUtils.trySetField(info, "mRegistered", cell.registered) && cell.registered) return null
        return if (matches(info, cell)) info else null
    }

    private fun familyFor(type: String): String = when (type) {
        "GSM" -> "Gsm"
        "WCDMA" -> "Wcdma"
        else -> "Lte"
    }

    private fun buildIdentity(
        clazz: Class<*>,
        family: String,
        cell: FakeRadioEnvironment.Cell,
    ): Any? {
        // Longest constructor first: it leaves the fewest fields at a default.
        for (arity in identityArities(family)) {
            val ctor = intConstructor(clazz, arity) ?: continue
            val args = identityArgs(family, cell, arity)
            val boxed = Array<Any?>(args.size) { args[it] }
            val candidate = instantiate(ctor, boxed) ?: continue
            if (matchesIdentity(candidate, cell)) return candidate
        }

        // Fallback: the no-argument constructor plus the documented field names.
        val ctor = noArgConstructor(clazz) ?: return null
        val candidate = instantiate(ctor, emptyArray()) ?: return null
        val areaField = if (family == "Lte") "mTac" else "mLac"
        val cellField = if (family == "Lte") "mCi" else "mCid"
        val radioField = if (family == "Wcdma") "mPsc" else "mPci"
        val wrote = HookUtils.trySetField(candidate, "mMcc", cell.mcc) &&
            HookUtils.trySetField(candidate, "mMnc", cell.mnc) &&
            HookUtils.trySetField(candidate, areaField, cell.areaCode and 0xFFFF) &&
            HookUtils.trySetField(candidate, cellField, identityCellId(family, cell)) &&
            HookUtils.trySetField(candidate, radioField, radioCode(family, cell))
        if (!wrote) return null
        return if (matchesIdentity(candidate, cell)) candidate else null
    }

    /**
     * A no-argument strength plus field writes, rather than a constructor call:
     * the strength constructors changed shape repeatedly across releases, while
     * the accessor pair that matters (getLevel / getDbm) is stable.
     */
    private fun buildStrength(clazz: Class<*>, dbm: Int): Any? {
        val ctor = noArgConstructor(clazz) ?: return null
        val candidate = instantiate(ctor, emptyArray()) ?: return null
        val level = levelFor(dbm)
        HookUtils.trySetField(candidate, "mLevel", level)
        HookUtils.trySetField(candidate, "mRssi", dbm)
        HookUtils.trySetField(candidate, "mDbm", dbm)
        return if (matchesStrength(candidate, dbm, level)) candidate else null
    }

    private fun buildInfo(infoClass: Class<*>, family: String, identity: Any, strength: Any): Any? {
        for (ctor in infoClass.declaredConstructors) {
            if (ctor.parameterCount != 2 || ctor.parameterTypes[0] != identity.javaClass) continue
            val candidate = instantiate(ctor, arrayOf(identity, strength)) ?: continue
            if (readObject(candidate, "getCellIdentity") === identity) return candidate
        }

        val ctor = noArgConstructor(infoClass) ?: return null
        val candidate = instantiate(ctor, emptyArray()) ?: return null
        val identityOk = HookUtils.trySetField(candidate, "mCellIdentity", identity) ||
            HookUtils.trySetField(candidate, "mCellIdentity$family", identity)
        val strengthOk = HookUtils.trySetField(candidate, "mCellSignalStrength", strength) ||
            HookUtils.trySetField(candidate, "mCellSignalStrength$family", strength)
        return if (identityOk && strengthOk) candidate else null
    }

    // ---- validation: the reason a wrong guess cannot reach the app ----

    private fun matches(info: Any, cell: FakeRadioEnvironment.Cell): Boolean {
        if ((readObject(info, "isRegistered") as? Boolean) != cell.registered) return false
        val identity = readObject(info, "getCellIdentity") ?: return false
        val strength = readObject(info, "getCellSignalStrength") ?: return false
        return matchesIdentity(identity, cell) &&
            matchesStrength(strength, cell.level, levelFor(cell.level))
    }

    private fun matchesIdentity(identity: Any, cell: FakeRadioEnvironment.Cell): Boolean {
        if (HookUtils.intAccessor(identity, "getMcc") != cell.mcc) return false
        if (HookUtils.intAccessor(identity, "getMnc") != cell.mnc) return false
        val area = HookUtils.intAccessor(identity, "getTac")
            ?: HookUtils.intAccessor(identity, "getLac")
            ?: return false
        if (area != (cell.areaCode and 0xFFFF)) return false
        val cellId = HookUtils.intAccessor(identity, "getCi")
            ?: HookUtils.intAccessor(identity, "getCid")
            ?: return false
        val family = identityFamilyOf(identity)
        if (cellId != identityCellId(family, cell)) return false
        // The radio code is exposed as PCI (LTE) or PSC (WCDMA) and absent on GSM.
        // Either spelling is accepted: some releases expose both, and only the one
        // we wrote is expected to match.
        val expected = radioCode(family, cell)
        val pci = HookUtils.intAccessor(identity, "getPci")
        val psc = HookUtils.intAccessor(identity, "getPsc")
        if (pci == null && psc == null) return true
        return pci == expected || psc == expected
    }

    private fun matchesStrength(strength: Any, dbm: Int, level: Int): Boolean {
        if (HookUtils.intAccessor(strength, "getLevel") != level) return false
        // getDbm is newer than getLevel; when it exists it must agree with the
        // level we derived, otherwise the strength object was written wrong.
        val reported = HookUtils.intAccessor(strength, "getDbm")
        return reported == null || reported == dbm
    }

    private fun identityFamilyOf(identity: Any): String =
        when (identity.javaClass.simpleName) {
            "CellIdentityGsm" -> "Gsm"
            "CellIdentityWcdma" -> "Wcdma"
            else -> "Lte"
        }

    // ---- value mapping ----

    private fun identityArities(family: String): IntArray = when (family) {
        "Lte" -> intArrayOf(6, 5, 4, 3)
        "Wcdma" -> intArrayOf(6, 5, 4)
        else -> intArrayOf(5, 4, 3)
    }

    /**
     * Argument order is a best guess; [matchesIdentity] is what makes guessing
     * safe. The order differs per type: LTE puts the cell identity before the
     * tracking area, WCDMA and GSM put the area first.
     */
    private fun identityArgs(family: String, cell: FakeRadioEnvironment.Cell, arity: Int): IntArray {
        val cid = identityCellId(family, cell)
        val area = cell.areaCode and 0xFFFF
        val radio = radioCode(family, cell)
        val head = when (family) {
            // CellIdentityLte(mcc, mnc, ci, pci, tac, earfcn)
            "Lte" -> intArrayOf(cell.mcc, cell.mnc, cid, radio, area, 0)
            // CellIdentityWcdma(mcc, mnc, lac, cid, psc, uarfcn)
            "Wcdma" -> intArrayOf(cell.mcc, cell.mnc, area, cid, radio, 0)
            // CellIdentityGsm(mcc, mnc, lac, cid, arfcn)
            else -> intArrayOf(cell.mcc, cell.mnc, area, cid, 0)
        }
        if (arity !in 1..head.size) return IntArray(arity)
        return head.copyOf(arity)
    }

    /** GSM cell identity is 16 bit; LTE and WCDMA use 28 bits. */
    private fun identityCellId(family: String, cell: FakeRadioEnvironment.Cell): Int =
        if (family == "Gsm") cell.cellId.toInt() and 0xFFFF else cell.cellId.toInt()

    /** LTE uses a 10-bit PCI, WCDMA a 9-bit PSC, GSM has none. */
    private fun radioCode(family: String, cell: FakeRadioEnvironment.Cell): Int = when (family) {
        "Wcdma" -> cell.pci and 0x1FF
        "Gsm" -> 0
        else -> cell.pci and 0x3FF
    }

    /** The platform's dBm to level mapping for LTE, used for every family. */
    private fun levelFor(dbm: Int): Int = when {
        dbm >= -85 -> 4
        dbm >= -95 -> 3
        dbm >= -105 -> 2
        dbm >= -115 -> 1
        else -> 0
    }

    // ---- reflection plumbing ----

    private fun intConstructor(clazz: Class<*>, arity: Int): Constructor<*>? =
        clazz.declaredConstructors.firstOrNull { ctor ->
            ctor.parameterCount == arity && ctor.parameterTypes.all { it == Integer.TYPE }
        }

    private fun noArgConstructor(clazz: Class<*>): Constructor<*>? =
        clazz.declaredConstructors.firstOrNull { it.parameterCount == 0 }

    private fun instantiate(ctor: Constructor<*>, args: Array<Any?>): Any? =
        try {
            ctor.isAccessible = true
            ctor.newInstance(*args)
        } catch (_: Throwable) {
            null
        }

    private fun readObject(obj: Any, getter: String): Any? =
        try {
            HookUtils.noArgAccessor(obj.javaClass, getter)?.invoke(obj)
        } catch (_: Throwable) {
            null
        }
}
