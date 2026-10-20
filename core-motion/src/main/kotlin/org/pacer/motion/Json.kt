package org.pacer.motion

/**
 * A minimal JSON reader, just enough to accept a route file written by another app.
 *
 * WHY NOT THE HAND-ROLLED SCANNERS USED ELSEWHERE: those read formats this project owns,
 * where the shape is known in advance. An imported file's shape is not known - it may be
 * Pacer's saved-route array, Pacer's bare draft array, or another app's export object whose
 * points sit inside an escaped string - and format sniffing with a prefix check breaks on
 * the first file with a leading comment or different key order. Parsing once into a value
 * tree makes the three shapes three small readers instead of three fragile scanners.
 *
 * Deliberately incomplete: no streaming, no number precision beyond Double, no duplicate-key
 * policy beyond last-wins. It is for files of a few hundred kilobytes at most.
 */
sealed interface JsonValue {
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Num(val value: Double) : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

/** Parses [text] into a [JsonValue], or throws [IllegalArgumentException] with a position. */
object Json {

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.value()
        parser.skipWhitespace()
        parser.expectEnd()
        return value
    }

    /** [parse] that returns null instead of throwing. */
    fun parseOrNull(text: String): JsonValue? =
        try {
            parse(text)
        } catch (_: Throwable) {
            null
        }

    private class Parser(private val s: String) {
        private var pos = 0

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun expectEnd() {
            if (pos != s.length) throw IllegalArgumentException("trailing content at $pos")
        }

        fun value(): JsonValue {
            skipWhitespace()
            return when (val c = peek()) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> {
                    if (c == '-' || c.isDigit()) number()
                    else throw IllegalArgumentException("unexpected '$c' at $pos")
                }
            }
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'

        private fun next(): Char {
            if (pos >= s.length) throw IllegalArgumentException("unexpected end of input")
            return s[pos++]
        }

        private fun expect(c: Char) {
            val got = next()
            if (got != c) throw IllegalArgumentException("expected '$c' but found '$got' at ${pos - 1}")
        }

        private fun literal(word: String, result: JsonValue): JsonValue {
            if (!s.startsWith(word, pos)) throw IllegalArgumentException("bad literal at $pos")
            pos += word.length
            return result
        }

        private fun obj(): JsonValue.Obj {
            expect('{')
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                next()
                return JsonValue.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                entries[key] = value()
                skipWhitespace()
                when (peek()) {
                    ',' -> next()
                    '}' -> {
                        next()
                        return JsonValue.Obj(entries)
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun arr(): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                next()
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(value())
                skipWhitespace()
                when (peek()) {
                    ',' -> next()
                    ']' -> {
                        next()
                        return JsonValue.Arr(items)
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return sb.toString()
                    '\\' -> when (val escaped = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > s.length) throw IllegalArgumentException("bad \\u at $pos")
                            val hex = s.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> sb.append(escaped)
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): JsonValue.Num {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length) {
                val c = s[pos]
                if (c.isDigit() || c == '.' || c == 'e' || c == 'E') {
                    pos++
                } else if ((c == '+' || c == '-') &&
                    pos > start &&
                    (s[pos - 1] == 'e' || s[pos - 1] == 'E')
                ) {
                    pos++
                } else {
                    break
                }
            }
            val token = s.substring(start, pos)
            return JsonValue.Num(
                token.toDoubleOrNull() ?: throw IllegalArgumentException("bad number '$token' at $start"),
            )
        }
    }
}
