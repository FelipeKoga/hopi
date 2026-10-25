package dev.koga.hopi.core.json

/**
 * Encodes [text] into [out] (at least 3 bytes per char) and returns the byte count. An
 * unpaired surrogate becomes U+FFFD, as [String.encodeToByteArray] does.
 */
internal fun encodeUtf8(text: CharSequence, out: ByteArray): Int {
    var n = 0
    var i = 0
    val length = text.length
    while (i < length) {
        val c = text[i].code
        when {
            c < 0x80 -> out[n++] = c.toByte()
            c < 0x800 -> {
                out[n++] = (0xC0 or (c shr 6)).toByte()
                out[n++] = (0x80 or (c and 0x3F)).toByte()
            }
            c in 0xD800..0xDBFF && i + 1 < length && text[i + 1].code in 0xDC00..0xDFFF -> {
                val point = 0x10000 + ((c - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                out[n++] = (0xF0 or (point shr 18)).toByte()
                out[n++] = (0x80 or ((point shr 12) and 0x3F)).toByte()
                out[n++] = (0x80 or ((point shr 6) and 0x3F)).toByte()
                out[n++] = (0x80 or (point and 0x3F)).toByte()
                i++
            }
            else -> {
                val unit = if (c in 0xD800..0xDFFF) 0xFFFD else c
                out[n++] = (0xE0 or (unit shr 12)).toByte()
                out[n++] = (0x80 or ((unit shr 6) and 0x3F)).toByte()
                out[n++] = (0x80 or (unit and 0x3F)).toByte()
            }
        }
        i++
    }
    return n
}
