// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

/**
 * The single wire format for routes: a compact JSON array of [lat, lon] pairs,
 * for example `[[39.9042,116.4074],[39.9052,116.4090]]`.
 *
 * Every producer and consumer must use this one form. Before this existed the
 * core module serialized `GeoPoint` with kotlinx.serialization (objects with
 * named fields) while the hook parsed a hand-written array form, so any route
 * pushed from a screen that used the core serializer would have been silently
 * dropped by the hook and the simulation would have fallen back to a straight
 * line. Keeping one encoder here removes that class of bug: the tests, the app
 * and the hook all call this object.
 *
 * The encoding is dependency free so it works in the pure-Kotlin core and in
 * tests; [Double.toString] is used for numbers because it is locale independent
 * and round trips a double exactly.
 */
object RouteJson {

    /** Encodes [points] as `[[lat,lon],...]`. Rejects non-finite coordinates. */
    fun encode(points: List<GeoPoint>): String {
        val sb = StringBuilder(points.size * 24 + 2)
        sb.append('[')
        for (i in points.indices) {
            if (i > 0) sb.append(',')
            val p = points[i]
            require(p.lat.isFinite() && p.lon.isFinite()) { "route point must be finite" }
            sb.append('[').append(p.lat).append(',').append(p.lon).append(']')
        }
        sb.append(']')
        return sb.toString()
    }

    /**
     * Decodes `[[lat,lon],...]`, tolerating surrounding and internal whitespace.
     *
     * @throws IllegalArgumentException when the text is not exactly one array of
     * two-number arrays (trailing content, wrong nesting and bad numbers all fail).
     */
    fun decode(text: String): List<GeoPoint> {
        val scanner = Scanner(text)
        scanner.skipWhitespace()
        scanner.expect('[')
        val out = ArrayList<GeoPoint>()
        scanner.skipWhitespace()
        if (scanner.peek() == ']') {
            scanner.next()
            scanner.skipWhitespace()
            scanner.expectEnd()
            return out
        }
        while (true) {
            scanner.skipWhitespace()
            scanner.expect('[')
            val lat = scanner.number()
            scanner.skipWhitespace()
            scanner.expect(',')
            scanner.skipWhitespace()
            val lon = scanner.number()
            scanner.skipWhitespace()
            scanner.expect(']')
            out.add(GeoPoint(lat, lon))
            scanner.skipWhitespace()
            when (scanner.peek()) {
                ',' -> scanner.next()
                ']' -> {
                    scanner.next()
                    break
                }
                else -> throw IllegalArgumentException("expected ',' or ']' at ${scanner.pos}")
            }
        }
        scanner.skipWhitespace()
        scanner.expectEnd()
        return out
    }

    /** [decode] that returns null instead of throwing; useful while typing. */
    fun decodeOrNull(text: String): List<GeoPoint>? =
        try {
            decode(text)
        } catch (_: Throwable) {
            null
        }

    /** Minimal scanner for the one grammar above; no general JSON support. */
    private class Scanner(private val s: String) {
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

        fun number(): Double {
            val start = pos
            if (peek() == '+' || peek() == '-') pos++
            while (pos < s.length) {
                val c = s[pos]
                if (c.isDigit() || c == '.') {
                    pos++
                } else if (c == 'e' || c == 'E') {
                    pos++
                } else if (c == '+' || c == '-') {
                    // A sign is only part of a number directly after an exponent.
                    val prev = if (pos > start) s[pos - 1] else '\u0000'
                    if (prev != 'e' && prev != 'E') break
                    pos++
                } else {
                    break
                }
            }
            if (pos == start) throw IllegalArgumentException("expected a number at $start")
            val token = s.substring(start, pos)
            return token.toDoubleOrNull()
                ?: throw IllegalArgumentException("bad number '$token' at $start")
        }
    }
}
