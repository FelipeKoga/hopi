package dev.koga.hopi.ui.format

/** Host (with port) and the rest of a URL, for a row's two lines. */
internal class UrlParts(val host: String, val path: String)

/** Splits `scheme://userinfo@host:port/path?query#fragment`; userinfo is never shown. */
internal fun splitUrl(url: String): UrlParts {
    val schemeEnd = url.indexOf("://")
    val authorityStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
    var authorityEnd = url.length
    for (i in authorityStart until url.length) {
        val c = url[i]
        if (c == '/' || c == '?' || c == '#') {
            authorityEnd = i
            break
        }
    }
    val authority = url.substring(authorityStart, authorityEnd)
    val host = authority.substring(authority.lastIndexOf('@') + 1)
    val rest = url.substring(authorityEnd)
    return UrlParts(host, if (rest.isEmpty() || rest[0] != '/') "/$rest" else rest)
}

/** `183 ms`, `1.24 s`, `12.3 s`, `2m 05s`. */
internal fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "$ms ms"
    ms < 10_000 -> {
        val hundredths = (ms + 5) / 10
        "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} s"
    }
    ms < 60_000 -> {
        val tenths = (ms + 50) / 100
        "${tenths / 10}.${tenths % 10} s"
    }
    else -> {
        val seconds = (ms + 500) / 1_000
        "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
    }
}

/** The standard reason phrase for common status codes, else null. */
internal fun reasonPhrase(code: Int): String? = when (code) {
    100 -> "Continue"
    101 -> "Switching Protocols"
    200 -> "OK"
    201 -> "Created"
    202 -> "Accepted"
    204 -> "No Content"
    206 -> "Partial Content"
    301 -> "Moved Permanently"
    302 -> "Found"
    303 -> "See Other"
    304 -> "Not Modified"
    307 -> "Temporary Redirect"
    308 -> "Permanent Redirect"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    408 -> "Request Timeout"
    409 -> "Conflict"
    410 -> "Gone"
    413 -> "Content Too Large"
    415 -> "Unsupported Media Type"
    422 -> "Unprocessable Content"
    429 -> "Too Many Requests"
    500 -> "Internal Server Error"
    501 -> "Not Implemented"
    502 -> "Bad Gateway"
    503 -> "Service Unavailable"
    504 -> "Gateway Timeout"
    else -> null
}

/**
 * Splits [text] into pieces of at most [maxLines] lines and about [maxChars] characters, so a large
 * body becomes many small lazy items instead of one text layout that freezes the frame. A line
 * longer than [maxChars] is cut, never inside a surrogate pair. Pieces carry no trailing newline.
 */
internal fun chunkText(text: String, maxLines: Int = 64, maxChars: Int = 4_096): List<String> =
    splitText(text, maxLines, maxChars).pieces

/**
 * [text] cut into [pieces] by [splitText]; [newlineAfter] says, per piece, whether the newline
 * that ended it was dropped (a piece cut inside a long line has none). Together they are the
 * whole text, so nothing needs to keep the text itself.
 */
internal class TextPieces(val pieces: List<String>, private val newlineAfter: BooleanArray) {
    /** Characters held by the pieces. */
    val length: Int = pieces.sumOf { it.length }

    /** The text the pieces were cut from, or its first [maxChars] characters. */
    fun join(maxChars: Int = Int.MAX_VALUE): String {
        val out = StringBuilder(minOf(maxChars.toLong(), length.toLong() + pieces.size).toInt())
        for (i in pieces.indices) {
            if (out.length >= maxChars) break
            out.append(pieces[i])
            if (newlineAfter[i]) out.append('\n')
        }
        if (out.length > maxChars) out.setLength(maxChars)
        return out.toString()
    }
}

/** [chunkText], keeping what [TextPieces.join] needs to rebuild the text exactly. */
internal fun splitText(text: String, maxLines: Int = 64, maxChars: Int = 4_096): TextPieces {
    if (text.isEmpty()) return TextPieces(listOf(""), BooleanArray(1))
    val chunks = ArrayList<String>(text.length / maxChars + 1)
    val newlines = ArrayList<Boolean>(text.length / maxChars + 1)
    var start = 0
    while (start < text.length) {
        var end = start
        var lines = 0
        while (end < text.length && lines < maxLines && end - start < maxChars) {
            val newline = text.indexOf('\n', end)
            val lineEnd = if (newline < 0) text.length else newline
            if (lineEnd - start > maxChars) {
                // The line does not fit what is left of this piece: it starts the next piece, or
                // is cut when it alone is longer than a piece.
                if (end > start) break
                end = start + maxChars
                if (end < text.length && text[end - 1].isHighSurrogate()) end--
                break
            }
            end = lineEnd
            lines++
            if (newline < 0) break
            end++ // past the newline
        }
        if (end == start) end = minOf(text.length, start + maxChars)
        val newline = end > start && text[end - 1] == '\n'
        chunks += text.substring(start, if (newline) end - 1 else end)
        newlines += newline
        start = end
    }
    return TextPieces(chunks, newlines.toBooleanArray())
}

private const val HEX = "0123456789abcdef"

/**
 * A classic hex dump of the first [limit] bytes, 16 per line: offset, two groups of eight bytes,
 * then the printable ASCII.
 */
internal fun hexDump(bytes: ByteArray, limit: Int = 512): String {
    val end = minOf(bytes.size, limit)
    val out = StringBuilder((end / 16 + 1) * 76)
    var offset = 0
    while (offset < end) {
        if (offset > 0) out.append('\n')
        val hexOffset = offset.toString(16).padStart(8, '0')
        out.append(hexOffset).append("  ")
        for (i in 0 until 16) {
            if (i == 8) out.append(' ')
            val index = offset + i
            if (index < end) {
                val b = bytes[index].toInt() and 0xFF
                out.append(HEX[b ushr 4]).append(HEX[b and 0xF]).append(' ')
            } else {
                out.append("   ")
            }
        }
        out.append(' ')
        for (i in offset until minOf(offset + 16, end)) {
            val b = bytes[i].toInt() and 0xFF
            out.append(if (b in 0x20..0x7E) b.toChar() else '.')
        }
        offset += 16
    }
    return out.toString()
}
