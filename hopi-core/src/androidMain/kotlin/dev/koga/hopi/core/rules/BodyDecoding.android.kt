package dev.koga.hopi.core.rules

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

internal actual fun decodeBody(coding: String, bytes: ByteArray, maxBytes: Int): ByteArray? = try {
    when (coding) {
        "gzip", "x-gzip" -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readAtMost(maxBytes + 1) }
        "deflate" -> inflate(bytes, maxBytes)
        else -> null
    }
} catch (_: IOException) {
    // Not gzip, a corrupt or cut-off stream, a trailer that does not match.
    null
}

/** HTTP's `deflate` is a zlib stream, but some servers send the bare deflate data: as browsers do, the first two bytes tell which. */
private fun inflate(bytes: ByteArray, maxBytes: Int): ByteArray? {
    val inflater = Inflater(!hasZlibHeader(bytes))
    return try {
        val decoded = InflaterInputStream(ByteArrayInputStream(bytes), inflater).readAtMost(maxBytes + 1)
        // A stream that stops before its end (one wanting a preset dictionary, which HTTP never uses) is not the body.
        if (decoded.size > maxBytes || inflater.finished()) decoded else null
    } finally {
        inflater.end()
    }
}

private fun hasZlibHeader(bytes: ByteArray): Boolean {
    if (bytes.size < 2) return false
    val cmf = bytes[0].toInt() and 0xff
    val flg = bytes[1].toInt() and 0xff
    return (cmf and 0x0f) == 8 && (cmf * 256 + flg) % 31 == 0
}

/**
 * This stream's bytes, at most [max] of them, read into one array that doubles from 8 KB and never
 * grows past [max]. The last doubling goes straight to [max]: the callers pass a limit + 1, and
 * doubling to the limit would copy everything once more for the one byte that shows a longer stream.
 */
private fun InputStream.readAtMost(max: Int): ByteArray {
    var bytes = ByteArray(minOf(max, 8 * 1024))
    var size = 0
    while (size < max) {
        if (size == bytes.size) bytes = bytes.copyOf(if (bytes.size >= max / 2) max else bytes.size * 2)
        val count = read(bytes, size, bytes.size - size)
        if (count < 0) break
        size += count
    }
    return if (size == bytes.size) bytes else bytes.copyOf(size)
}
