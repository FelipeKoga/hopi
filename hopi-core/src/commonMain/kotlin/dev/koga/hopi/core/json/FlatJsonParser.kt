package dev.koga.hopi.core.json

import dev.koga.hopi.api.HopiInternalApi

/**
 * Parses a JSON object whose values are strings, integers, booleans or null. Anything else
 * (nested containers, fractions, duplicate keys, trailing data, input over [maxLength] chars)
 * yields null. Never throws.
 */
@HopiInternalApi
public fun parseFlatJsonObject(text: String, maxLength: Int = 4096): Map<String, Any?>? {
    if (text.length > maxLength) return null
    return FlatJsonParser(text).parse()
}

private class FlatJsonParser(private val src: String) {
    private var pos = 0

    fun parse(): Map<String, Any?>? {
        skipWhitespace()
        if (!consume('{')) return null
        val result = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (consume('}')) return result.takeIf { atEnd() }
        while (true) {
            skipWhitespace()
            val key = string() ?: return null
            if (result.containsKey(key)) return null
            skipWhitespace()
            if (!consume(':')) return null
            skipWhitespace()
            if (pos >= src.length) return null
            val value: Any? = when (src[pos]) {
                '"' -> string() ?: return null
                't' -> if (literal("true")) true else return null
                'f' -> if (literal("false")) false else return null
                'n' -> if (literal("null")) null else return null
                else -> integer() ?: return null
            }
            result[key] = value
            skipWhitespace()
            if (consume(',')) continue
            if (consume('}')) return result.takeIf { atEnd() }
            return null
        }
    }

    private fun atEnd(): Boolean {
        skipWhitespace()
        return pos == src.length
    }

    private fun string(): String? {
        if (!consume('"')) return null
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (pos >= src.length) return null
                    when (src[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > src.length) return null
                            var code = 0
                            for (i in 0 until 4) {
                                val digit = src[pos + i].digitToIntOrNull(16) ?: return null
                                code = (code shl 4) or digit
                            }
                            pos += 4
                            sb.append(code.toChar())
                        }
                        else -> return null
                    }
                }
                c < ' ' -> return null
                else -> sb.append(c)
            }
        }
        return null
    }

    private fun integer(): Long? {
        val start = pos
        if (pos < src.length && src[pos] == '-') pos++
        val digitsStart = pos
        while (pos < src.length && src[pos] in '0'..'9') pos++
        val digits = pos - digitsStart
        if (digits == 0 || digits > 19) return null
        if (digits > 1 && src[digitsStart] == '0') return null
        return src.substring(start, pos).toLongOrNull()
    }

    private fun literal(word: String): Boolean {
        if (!src.startsWith(word, pos)) return false
        pos += word.length
        return true
    }

    private fun consume(c: Char): Boolean {
        if (pos < src.length && src[pos] == c) {
            pos++
            return true
        }
        return false
    }

    private fun skipWhitespace() {
        while (pos < src.length) {
            val c = src[pos]
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return
            pos++
        }
    }
}
