package dev.koga.hopi.core.json

import dev.koga.hopi.core.HopiInternalApi
import kotlin.io.encoding.Base64

/**
 * Streaming JSON writer appending to [out]. Commas are inserted automatically; misuse (a value
 * where a name is expected, unbalanced containers) throws [IllegalStateException].
 */
@HopiInternalApi
public class JsonWriter(public val out: StringBuilder = StringBuilder()) {
    // Bit stack of open containers: 1 = object, 0 = array.
    private var kinds = LongArray(1)
    private var depth = 0
    private var needsComma = false
    private var expectingValueForName = false
    private var rootWritten = false

    public fun beginObject(): JsonWriter = open(isObject = true, '{')

    public fun endObject(): JsonWriter = close(isObject = true, '}')

    public fun beginArray(): JsonWriter = open(isObject = false, '[')

    public fun endArray(): JsonWriter = close(isObject = false, ']')

    public fun name(name: String): JsonWriter {
        check(depth > 0 && isObjectAt(depth - 1) && !expectingValueForName) { "name() outside an object" }
        if (needsComma) out.append(',')
        appendQuoted(out, name)
        out.append(':')
        expectingValueForName = true
        return this
    }

    public fun value(value: String?): JsonWriter {
        if (value == null) return nullValue()
        beforeValue()
        appendQuoted(out, value)
        return this
    }

    public fun value(value: Long): JsonWriter {
        beforeValue()
        out.append(value)
        return this
    }

    public fun value(value: Int): JsonWriter = value(value.toLong())

    public fun value(value: Long?): JsonWriter = if (value == null) nullValue() else value(value)

    public fun value(value: Int?): JsonWriter = if (value == null) nullValue() else value(value.toLong())

    /** Non-finite values have no JSON form and are written as `null`. */
    public fun value(value: Double): JsonWriter {
        if (!value.isFinite()) return nullValue()
        beforeValue()
        val asLong = value.toLong()
        if (asLong.toDouble() == value && asLong in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) {
            out.append(asLong)
        } else {
            out.append(value.toString())
        }
        return this
    }

    public fun value(value: Boolean): JsonWriter {
        beforeValue()
        out.append(if (value) "true" else "false")
        return this
    }

    public fun nullValue(): JsonWriter {
        beforeValue()
        out.append("null")
        return this
    }

    /** Writes [bytes] as a base64 string without building an intermediate copy of the input. */
    public fun base64Value(bytes: ByteArray): JsonWriter {
        beforeValue()
        out.append('"')
        Base64.encodeToAppendable(bytes, out)
        out.append('"')
        return this
    }

    public fun field(name: String, value: String?): JsonWriter = name(name).value(value)

    public fun field(name: String, value: Long?): JsonWriter = name(name).value(value)

    public fun field(name: String, value: Int?): JsonWriter = name(name).value(value)

    public fun field(name: String, value: Boolean): JsonWriter = name(name).value(value)

    override fun toString(): String = out.toString()

    private fun open(isObject: Boolean, bracket: Char): JsonWriter {
        beforeValue()
        out.append(bracket)
        if (depth == kinds.size * 64) kinds = kinds.copyOf(kinds.size * 2)
        val word = depth ushr 6
        val bit = 1L shl (depth and 63)
        kinds[word] = if (isObject) kinds[word] or bit else kinds[word] and bit.inv()
        depth++
        needsComma = false
        return this
    }

    private fun close(isObject: Boolean, bracket: Char): JsonWriter {
        check(depth > 0 && isObjectAt(depth - 1) == isObject && !expectingValueForName) { "unbalanced $bracket" }
        depth--
        out.append(bracket)
        needsComma = true
        return this
    }

    private fun beforeValue() {
        if (depth == 0) {
            check(!rootWritten) { "only one root value per writer" }
            rootWritten = true
            return
        }
        if (isObjectAt(depth - 1)) {
            check(expectingValueForName) { "value in an object without name()" }
            expectingValueForName = false
        } else if (needsComma) {
            out.append(',')
        }
        needsComma = true
    }

    private fun isObjectAt(level: Int): Boolean = (kinds[level ushr 6] ushr (level and 63)) and 1L == 1L

    public companion object {
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        private const val HEX = "0123456789abcdef"

        /**
         * Appends [value] as a JSON string literal, quotes included. An unpaired surrogate is
         * written as a `\u` escape, so the output is well-formed UTF-16 and encodes to valid UTF-8.
         */
        public fun appendQuoted(out: StringBuilder, value: String) {
            out.append('"')
            var runStart = 0
            for (i in value.indices) {
                val c = value[i]
                val escape: String? = when {
                    c == '"' -> "\\\""
                    c == '\\' -> "\\\\"
                    c == '\n' -> "\\n"
                    c == '\r' -> "\\r"
                    c == '\t' -> "\\t"
                    c == '\b' -> "\\b"
                    c == '\u000C' -> "\\f"
                    c < ' ' || c == '\u2028' || c == '\u2029' || c == '\u007F' -> ""
                    c.isHighSurrogate() -> if (i + 1 < value.length && value[i + 1].isLowSurrogate()) null else ""
                    c.isLowSurrogate() -> if (i > 0 && value[i - 1].isHighSurrogate()) null else ""
                    else -> null
                }
                if (escape == null) continue
                if (i > runStart) out.append(value, runStart, i)
                if (escape.isEmpty()) {
                    val code = c.code
                    out.append("\\u")
                        .append(HEX[(code shr 12) and 0xF])
                        .append(HEX[(code shr 8) and 0xF])
                        .append(HEX[(code shr 4) and 0xF])
                        .append(HEX[code and 0xF])
                } else {
                    out.append(escape)
                }
                runStart = i + 1
            }
            if (runStart < value.length) out.append(value, runStart, value.length)
            out.append('"')
        }

        /**
         * UTF-8 byte length of [c] once escaped by [appendQuoted]. A surrogate counts 3, although
         * a pair encodes to 4 and an unpaired one, escaped, to 6; [escapedUtf8Length] over a
         * string is exact.
         */
        public fun escapedUtf8Length(c: Char): Int = when {
            c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t' || c == '\b' || c == '\u000C' -> 2
            c < ' ' || c == '\u2028' || c == '\u2029' || c == '\u007F' -> 6
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            else -> 3
        }

        /** 2 when a surrogate pair starts at [index] of [text], else 1. */
        internal fun codePointChars(text: CharSequence, index: Int): Int =
            if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1

        /** Exact UTF-8 byte length, once escaped by [appendQuoted], of the code point at [index]. */
        internal fun escapedCodePointBytes(text: CharSequence, index: Int): Int = when {
            codePointChars(text, index) == 2 -> 4
            text[index].isSurrogate() -> 6
            else -> escapedUtf8Length(text[index])
        }

        /** Exact UTF-8 byte length of [text] once escaped by [appendQuoted], quotes excluded. */
        public fun escapedUtf8Length(text: CharSequence): Int {
            var bytes = 0
            var i = 0
            while (i < text.length) {
                bytes += escapedCodePointBytes(text, i)
                i += codePointChars(text, i)
            }
            return bytes
        }
    }
}
