package org.pacer.motion

/**
 * A route kept under a name, so drawing a second one does not destroy the first.
 *
 * Until now the only route that existed was the draft - the list of waypoints being
 * edited - and starting a new one overwrote it. This is the small amount of state a
 * named list needs: a name and the same geometry the draft and the module already use.
 *
 * [id] rather than the name is the identity: renaming a route must not create a second
 * copy, and two routes may reasonably be called the same thing by accident. It is
 * assigned by [SavedRoutes] from a counter, so it is stable across a save/load cycle
 * without needing a UUID or a timestamp to be unique.
 */
data class NamedRoute(
    val id: Long,
    val name: String,
    val points: List<GeoPoint>,
) {
    /** Total length, or 0 while there are fewer than two points. */
    val lengthMeters: Double get() = lengthOf(points)

    /** Same rule as [RouteDraft.isValid]: two points, not the same place. */
    val isValid: Boolean get() = points.size >= 2 && lengthMeters > 0.0

    /** The same JSON the draft and the module use; there is no second route format. */
    fun toJson(): String = RouteJson.encode(points)

    private fun lengthOf(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) total += GeoMath.haversine(points[i - 1], points[i])
        return total
    }
}

/**
 * The named-route list: validation, renaming, removal, and the wire format.
 *
 * Kept out of the Android layer so the rules can be tested without a device, and so
 * there is exactly one answer to "what is a valid name" - the UI asks instead of
 * re-implementing a trim and a length check.
 *
 * The stored form is a flat JSON array of `{"id":..,"name":..,"points":[[lat,lon],..]}`.
 * Flat because it is written and read by hand-rolled code in the same module that
 * already has a hand-rolled number scanner, and because a route list is a few dozen
 * entries: a dependency-free encoder that cannot drift from the decoder is worth more
 * than a schema.
 */
class SavedRoutes(private val entries: MutableList<NamedRoute> = ArrayList()) {

    /**
     * The next id to hand out.
     *
     * Never derived from the current maximum. Reusing an id makes a stale reference -
     * a list row the user has not refreshed, the "replace this route" target of a save -
     * name a different route after a delete and a new save. It only has to be monotonic
     * within one process: ids are stored, so a reloaded list keeps its own ids and the
     * counter continues above them.
     */
    private var nextId: Long = (entries.maxOfOrNull { it.id } ?: 0L) + 1L

    /** Read-only view, in the order the user sees it. */
    val routes: List<NamedRoute> get() = entries

    val size: Int get() = entries.size

    /**
     * Stores [draft] under [name].
     *
     * Replaces an existing entry when [replaceId] matches, which is how "save over the
     * route I loaded" works without a second button. A new entry gets the next id.
     * Returns null when the name or the geometry is unusable; the caller shows why
     * rather than silently discarding what the user typed.
     */
    fun save(draft: RouteDraft, name: String, replaceId: Long? = null): SaveResult {
        val clean = name.trim()
        if (clean.isEmpty()) return SaveResult.InvalidName("a name is required")
        if (clean.length > MAX_NAME_LENGTH) {
            return SaveResult.InvalidName("keep the name under $MAX_NAME_LENGTH characters")
        }
        if (!draft.isValid) {
            return SaveResult.InvalidRoute("a route needs two distinct waypoints")
        }
        val points = draft.points.toList()
        val existing = replaceId?.let { id -> entries.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
        return if (existing != null) {
            val updated = NamedRoute(entries[existing].id, clean, points)
            entries[existing] = updated
            SaveResult.Saved(updated, replaced = true)
        } else {
            val created = NamedRoute(nextId++, clean, points)
            entries.add(created)
            SaveResult.Saved(created, replaced = false)
        }
    }

    /** Renames in place; false when the id is unknown or the name is unusable. */
    fun rename(id: Long, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > MAX_NAME_LENGTH) return false
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return false
        entries[index] = entries[index].copy(name = clean)
        return true
    }

    /** Removes one entry; false when the id is unknown. */
    fun remove(id: Long): Boolean = entries.removeAll { it.id == id }

    fun find(id: Long): NamedRoute? = entries.firstOrNull { it.id == id }

    /** The draft form, so a loaded route is editable exactly like a tapped one. */
    fun draftOf(id: Long): RouteDraft? = find(id)?.let { RouteDraft(it.points) }

    /** Sorts by name for display, without changing the stored order. */
    fun sortedByName(): List<NamedRoute> = entries.sortedBy { it.name.lowercase() }

    fun toJson(): String = encode(entries)

    sealed interface SaveResult {
        data class Saved(val route: NamedRoute, val replaced: Boolean) : SaveResult

        data class InvalidName(val reason: String) : SaveResult

        data class InvalidRoute(val reason: String) : SaveResult
    }

    companion object {
        /** Long enough for a descriptive name, short enough to fit one row. */
        const val MAX_NAME_LENGTH = 60

        fun parse(json: String): SavedRoutes =
            SavedRoutes(decodeOrEmpty(json).toMutableList())

        /** Tolerant by design: a corrupt store yields an empty list, not a crash. */
        fun decodeOrEmpty(json: String): List<NamedRoute> =
            try {
                decode(json)
            } catch (_: Throwable) {
                emptyList()
            }

        private fun encode(routes: List<NamedRoute>): String {
            val sb = StringBuilder(routes.size * 96 + 2)
            sb.append('[')
            for (i in routes.indices) {
                if (i > 0) sb.append(',')
                val route = routes[i]
                sb.append("{\"id\":").append(route.id)
                sb.append(",\"name\":\"").append(escape(route.name)).append('"')
                sb.append(",\"points\":").append(RouteJson.encode(route.points))
                sb.append('}')
            }
            sb.append(']')
            return sb.toString()
        }

        /**
         * Reads the format above.
         *
         * Written against the same small grammar [RouteJson] uses rather than a general
         * JSON parser: the points are handed straight to [RouteJson.decode], so the
         * only syntax handled here is the three fields and their punctuation.
         */
        private fun decode(text: String): List<NamedRoute> {
            val s = Cursor(text)
            s.skipWhitespace()
            s.expect('[')
            val out = ArrayList<NamedRoute>()
            s.skipWhitespace()
            if (s.peek() == ']') {
                s.next()
                s.skipWhitespace()
                s.expectEnd()
                return out
            }
            while (true) {
                s.skipWhitespace()
                s.expect('{')
                var id: Long? = null
                var name: String? = null
                var points: List<GeoPoint>? = null
                while (true) {
                    s.skipWhitespace()
                    if (s.peek() == '}') {
                        s.next()
                        break
                    }
                    val key = s.string()
                    s.skipWhitespace()
                    s.expect(':')
                    s.skipWhitespace()
                    when (key) {
                        "id" -> id = s.long()
                        "name" -> name = s.string()
                        "points" -> points = RouteJson.decode(s.arrayText())
                        else -> s.skipValue()
                    }
                    s.skipWhitespace()
                    if (s.peek() == ',') s.next()
                }
                // An entry missing any field is dropped rather than half-loaded: a route
                // without points cannot be run, and one without a name cannot be shown.
                if (id != null && name != null && points != null) {
                    out.add(NamedRoute(id, name, points))
                }
                s.skipWhitespace()
                when (s.peek()) {
                    ',' -> s.next()
                    ']' -> {
                        s.next()
                        break
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' at ${s.pos}")
                }
            }
            s.skipWhitespace()
            s.expectEnd()
            return out
        }

        private fun escape(value: String): String {
            val sb = StringBuilder(value.length + 4)
            for (c in value) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        /** Enough of a scanner for the format above; no general JSON support. */
        private class Cursor(private val s: String) {
            var pos = 0
                private set

            fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'

            fun next(): Char {
                if (pos >= s.length) throw IllegalArgumentException("unexpected end of input")
                return s[pos++]
            }

            fun skipWhitespace() {
                while (pos < s.length && s[pos].isWhitespace()) pos++
            }

            fun expect(c: Char) {
                val got = next()
                if (got != c) throw IllegalArgumentException("expected '$c' but found '$got' at ${pos - 1}")
            }

            fun expectEnd() {
                if (pos != s.length) throw IllegalArgumentException("trailing content at $pos")
            }

            fun string(): String {
                expect('"')
                val sb = StringBuilder()
                while (true) {
                    val c = next()
                    when (c) {
                        '"' -> return sb.toString()
                        '\\' -> when (val escaped = next()) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            else -> sb.append(escaped)
                        }
                        else -> sb.append(c)
                    }
                }
            }

            fun long(): Long {
                val start = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '-')) pos++
                if (pos == start) throw IllegalArgumentException("expected an integer at $start")
                return s.substring(start, pos).toLong()
            }

            /** The raw text of one JSON array, for [RouteJson] to parse. */
            fun arrayText(): String {
                val start = pos
                var depth = 0
                while (pos < s.length) {
                    when (s[pos]) {
                        '[' -> depth++
                        ']' -> {
                            depth--
                            if (depth == 0) {
                                pos++
                                return s.substring(start, pos)
                            }
                        }
                    }
                    pos++
                }
                throw IllegalArgumentException("unterminated array at $start")
            }

            /** Skips one value of unknown shape, so an unknown field cannot break a load. */
            fun skipValue() {
                when (peek()) {
                    '"' -> string()
                    '[', '{' -> {
                        var depth = 0
                        while (pos < s.length) {
                            when (s[pos]) {
                                '[', '{' -> depth++
                                ']', '}' -> {
                                    depth--
                                    if (depth == 0) {
                                        pos++
                                        return
                                    }
                                }
                            }
                            pos++
                        }
                        throw IllegalArgumentException("unterminated value at $pos")
                    }
                    else -> {
                        while (pos < s.length && s[pos] !in ",}]") pos++
                    }
                }
            }
        }
    }
}
