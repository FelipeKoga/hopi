package dev.koga.hopi.core.json

import dev.koga.hopi.api.HopiInternalApi

/**
 * Parses a JSON object into maps (`Map<String, Any?>`, insertion order kept), lists, strings,
 * integers (`Long`), booleans and null, at most [maxDepth] containers deep (the top object counts
 * as one). As strict as [parseFlatJsonObject]: fractions, exponents, duplicate keys, trailing data
 * and input over [maxLength] chars yield null. Never throws. The bridge's rule commands use it;
 * every other command stays flat.
 */
@HopiInternalApi
public fun parseJsonTree(text: String, maxLength: Int = 4096, maxDepth: Int = 6): Map<String, Any?>? {
    if (text.length > maxLength) return null
    return JsonTreeParser(text, maxDepth).parse()
}

private class JsonTreeParser(private val src: String, private val maxDepth: Int) {
    private var pos = 0

    fun parse(): Map<String, Any?>? {
        skipWhitespace()
        if (pos >= src.length || src[pos] != '{') return null
        val result = value(depth = 0) as? Map<*, *> ?: return null
        skipWhitespace()
        if (pos != src.length) return null
        @Suppress("UNCHECKED_CAST")
        return result as Map<String, Any?>
    }

    /** One value; [INVALID] when malformed. */
    private fun value(depth: Int): Any? {
        if (pos >= src.length) return INVALID
        return when (src[pos]) {
            '{' -> if (depth >= maxDepth) INVALID else obj(depth + 1)
            '[' -> if (depth >= maxDepth) INVALID else array(depth + 1)
            '"' -> string() ?: INVALID
            't' -> if (literal("true")) true else INVALID
            'f' -> if (literal("false")) false else INVALID
            'n' -> if (literal("null")) null else INVALID
            else -> integer() ?: INVALID
        }
    }

    private fun obj(depth: Int): Any? {
        pos++
        val result = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (consume('}')) return result
        while (true) {
            skipWhitespace()
            val key = string() ?: return INVALID
            if (result.containsKey(key)) return INVALID
            skipWhitespace()
            if (!consume(':')) return INVALID
            skipWhitespace()
            val v = value(depth)
            if (v === INVALID) return INVALID
            result[key] = v
            skipWhitespace()
            if (consume(',')) continue
            if (consume('}')) return result
            return INVALID
        }
    }

    private fun array(depth: Int): Any? {
        pos++
        val result = ArrayList<Any?>()
        skipWhitespace()
        if (consume(']')) return result
        while (true) {
            skipWhitespace()
            val v = value(depth)
            if (v === INVALID) return INVALID
            result += v
            skipWhitespace()
            if (consume(',')) continue
            if (consume(']')) return result
            return INVALID
        }
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

    private object INVALID
}
