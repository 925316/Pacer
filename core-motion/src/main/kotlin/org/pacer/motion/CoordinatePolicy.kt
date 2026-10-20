package org.pacer.motion

/**
 * What coordinate system a target app expects a fix to be in.
 *
 * Why this is a per-app setting at all: Android's own location API carries no datum. A
 * fix is a pair of numbers, and an app that renders it on an AMap base map assumes
 * GCJ-02 while one that renders on a Google base map assumes WGS-84. The two differ by
 * roughly half a kilometre in China, so guessing wrong puts the subject on the wrong
 * side of a river - and the hook cannot tell from the API which one the app assumes.
 *
 * [AUTO] is the default and means "use the rule for the API being hooked": the AMap SDK
 * wants GCJ-02, Baidu wants BD-09 in its own projection, and a plain `LocationManager`
 * fix is WGS-84. An explicit choice overrides that rule for one package, which is the
 * only thing this setting is for.
 */
enum class CoordinateSystem {
    /** Follow the default rule for whichever API is being hooked. */
    AUTO,

    /** The international standard; what the engine and the stored route use. */
    WGS84,

    /** The Chinese mandated offset used by AMap, Tencent and most domestic apps. */
    GCJ02,

    /** Baidu's own offset, on top of GCJ-02. */
    BD09,
    ;

    /** Parses a stored name, falling back to [AUTO] for anything unrecognised. */
    companion object {
        fun byName(name: String?): CoordinateSystem {
            val clean = name?.trim()?.uppercase() ?: return AUTO
            return entries.firstOrNull { it.name == clean } ?: AUTO
        }
    }
}

/**
 * One package's coordinate override.
 *
 * A package name, not a process name: `com.example.app:remote` is the same app as
 * `com.example.app`, and a user choosing a datum for "that app" does not know or care
 * which process it happens to run in.
 */
data class CoordinateOverride(val packageName: String, val system: CoordinateSystem) {

    /** True when this override applies to the process named [processName]. */
    fun appliesTo(processName: String?): Boolean {
        val process = processName?.trim().orEmpty()
        if (process.isEmpty()) return false
        return process == packageName || process.startsWith("$packageName:")
    }
}

/**
 * The override table, and the decision "which datum does this fix use here".
 *
 * Kept pure so the interesting part - the precedence between an explicit override, the
 * API default and the out-of-China rule - can be tested without a device.
 */
object CoordinatePolicy {

    /**
     * The datum a fix delivered through [apiDefault] should carry in [processName].
     *
     * An explicit override wins, including an explicit [CoordinateSystem.WGS84] where the
     * API default would have been GCJ-02: that is the whole point of the setting.
     */
    fun resolve(
        processName: String?,
        overrides: List<CoordinateOverride>,
        apiDefault: CoordinateSystem,
    ): CoordinateSystem {
        val match = overrides.firstOrNull { it.appliesTo(processName) } ?: return apiDefault
        return if (match.system == CoordinateSystem.AUTO) apiDefault else match.system
    }

    /**
     * A fix converted from the engine's WGS-84 into [system].
     *
     * Outside China the offset transforms are identities by definition (the GCJ-02
     * algorithm only applies inside the boundary polygon), so a Chinese datum applied to
     * a foreign point returns the point unchanged. That is deliberate rather than a
     * shortcut: it is the same rule the providers themselves use, and pretending a
     * foreign point needs an offset would introduce one that no client applies.
     */
    fun convert(point: GeoPoint, system: CoordinateSystem): GeoPoint = when (system) {
        CoordinateSystem.AUTO, CoordinateSystem.WGS84 -> point
        CoordinateSystem.GCJ02 -> CoordinateTransform.wgs84ToGcj02(point)
        CoordinateSystem.BD09 -> CoordinateTransform.wgs84ToBd09(point)
    }

    /**
     * Parses the override table from its stored form: `pkg=SYSTEM;pkg2=SYSTEM2`.
     *
     * A compact text form rather than JSON: the table is edited as one line in a text
     * field, and a malformed entry has to be skippable without taking the rest with it.
     * Entries with an unknown package or an unreadable datum are dropped.
     */
    fun parse(text: String): List<CoordinateOverride> {
        val out = ArrayList<CoordinateOverride>()
        for (piece in text.split(';')) {
            val item = piece.trim()
            if (item.isEmpty()) continue
            val separator = item.indexOf('=')
            if (separator <= 0 || separator == item.lastIndex) continue
            val packageName = item.substring(0, separator).trim()
            val system = item.substring(separator + 1).trim().uppercase()
            if (!isPackageName(packageName)) continue
            val parsed = CoordinateSystem.entries.firstOrNull { it.name == system } ?: continue
            if (out.none { it.packageName == packageName }) {
                out.add(CoordinateOverride(packageName, parsed))
            }
        }
        return out
    }

    /** The inverse of [parse]; used to show the stored table back in the field. */
    fun format(overrides: List<CoordinateOverride>): String =
        overrides.joinToString(";") { "${it.packageName}=${it.system.name}" }

    /**
     * A conservative package check.
     *
     * Only used to reject obvious junk in a hand-typed field: a package name is dot
     * separated segments of letters, digits and underscores. Anything else cannot be a
     * process name this module will ever see.
     */
    fun isPackageName(name: String): Boolean {
        if (name.isEmpty() || name.length > 255) return false
        if (name.startsWith('.') || name.endsWith('.') || name.contains("..")) return false
        return name.all { it.isLetterOrDigit() || it == '.' || it == '_' }
    }
}
