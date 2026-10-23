package dev.koga.hopi.core.body

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.model.CapturedBody
import kotlin.math.roundToLong

/**
 * Decodes [bytes] up to [end] as UTF-8, or returns null when they are not valid UTF-8 or contain a
 * NUL character (treated as binary). Never substitutes replacement characters.
 */
@HopiInternalApi
public fun decodeUtf8Strict(bytes: ByteArray, end: Int = bytes.size): String? {
    val text = try {
        bytes.decodeToString(0, end, throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        return null
    }
    return if (text.indexOf('\u0000') >= 0) null else text
}

/**
 * The body as text, or null when it is binary. A truncated body may end in the middle of a
 * multi-byte sequence; that incomplete tail is dropped instead of failing the whole body.
 */
@HopiInternalApi
public fun CapturedBody.textOrNull(): String? {
    val end = if (truncated) bytes.size - incompleteUtf8TailLength(bytes) else bytes.size
    return decodeUtf8Strict(bytes, end)
}

private fun incompleteUtf8TailLength(bytes: ByteArray): Int {
    val size = bytes.size
    var back = 1
    while (back <= 3 && back <= size) {
        val b = bytes[size - back].toInt() and 0xFF
        if (b and 0xC0 != 0x80) {
            val expected = when {
                b and 0x80 == 0 -> 1
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> return 0
            }
            return if (expected > back) back else 0
        }
        back++
    }
    return 0
}

/** True for `application/json`, `+json` types, or text that starts with `{` or `[`. */
@HopiInternalApi
public fun isJson(contentType: String?, text: String?): Boolean {
    if (contentType != null) {
        val mime = contentType.substringBefore(';').trim().lowercase()
        if (mime == "application/json" || mime.endsWith("+json") || mime.endsWith("/json")) return true
    }
    if (text == null) return false
    for (c in text) {
        if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue
        return c == '{' || c == '['
    }
    return false
}

/** Pretty-prints JSON with [indent] per level. Invalid JSON is returned unchanged. */
@HopiInternalApi
public fun prettyPrintJson(text: String, indent: String = "  "): String =
    JsonPrettyPrinter(text, indent).print() ?: text

/** Human byte size: `512 B`, `1.5 KB`, `2.0 MB`, binary units. */
@HopiInternalApi
public fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (unit < units.lastIndex && (value * 10).roundToLong() >= 10240) {
        value /= 1024.0
        unit++
    }
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10} ${units[unit]}"
}

private class JsonPrettyPrinter(private val src: String, private val indent: String) {
    private var pos = 0
    private val out = StringBuilder(src.length + (src.length shr 2))

    fun print(): String? {
        skipWhitespace()
        if (!value(0)) return null
        skipWhitespace()
        return if (pos == src.length) out.toString() else null
    }

    private fun value(depth: Int): Boolean {
        if (depth > MAX_DEPTH || pos >= src.length) return false
        return when (src[pos]) {
            '{' -> container(depth, '}', isObject = true)
            '[' -> container(depth, ']', isObject = false)
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> number()
        }
    }

    private fun container(depth: Int, close: Char, isObject: Boolean): Boolean {
        pos++
        skipWhitespace()
        if (pos < src.length && src[pos] == close) {
            pos++
            out.append(if (isObject) "{}" else "[]")
            return true
        }
        out.append(if (isObject) '{' else '[')
        while (true) {
            newline(depth + 1)
            if (isObject) {
                if (pos >= src.length || src[pos] != '"' || !string()) return false
                skipWhitespace()
                if (pos >= src.length || src[pos] != ':') return false
                pos++
                out.append(": ")
                skipWhitespace()
            }
            if (!value(depth + 1)) return false
            skipWhitespace()
            if (pos >= src.length) return false
            when (src[pos]) {
                ',' -> {
                    out.append(',')
                    pos++
                    skipWhitespace()
                }
                close -> {
                    pos++
                    newline(depth)
                    out.append(close)
                    return true
                }
                else -> return false
            }
        }
    }

    private fun string(): Boolean {
        val start = pos
        pos++
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == '"' -> {
                    pos++
                    out.append(src, start, pos)
                    return true
                }
                c == '\\' -> {
                    if (pos + 1 >= src.length) return false
                    when (src[pos + 1]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> pos += 2
                        'u' -> {
                            if (pos + 6 > src.length) return false
                            for (i in pos + 2 until pos + 6) if (!src[i].isHexDigit()) return false
                            pos += 6
                        }
                        else -> return false
                    }
                }
                c < ' ' -> return false
                else -> pos++
            }
        }
        return false
    }

    private fun number(): Boolean {
        val start = pos
        if (pos < src.length && src[pos] == '-') pos++
        if (pos >= src.length) return false
        if (src[pos] == '0') {
            pos++
        } else if (src[pos] in '1'..'9') {
            skipDigits()
        } else {
            return false
        }
        if (pos < src.length && src[pos] == '.') {
            pos++
            if (!skipDigits()) return false
        }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            if (!skipDigits()) return false
        }
        out.append(src, start, pos)
        return true
    }

    private fun skipDigits(): Boolean {
        val start = pos
        while (pos < src.length && src[pos] in '0'..'9') pos++
        return pos > start
    }

    private fun literal(word: String): Boolean {
        if (!src.startsWith(word, pos)) return false
        pos += word.length
        out.append(word)
        return true
    }

    private fun newline(depth: Int) {
        out.append('\n')
        repeat(depth) { out.append(indent) }
    }

    private fun skipWhitespace() {
        while (pos < src.length) {
            val c = src[pos]
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return
            pos++
        }
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private companion object {
        const val MAX_DEPTH = 256
    }
}
