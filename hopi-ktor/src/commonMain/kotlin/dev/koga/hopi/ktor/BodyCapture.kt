package dev.koga.hopi.ktor

import dev.koga.hopi.core.model.CapturedBody
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.discard
import io.ktor.utils.io.readAvailable
import kotlinx.io.Source
import kotlinx.io.readTo

/**
 * Keeps the first [limit] bytes of a body and counts all of them. Grows on demand, so a small body
 * never allocates the full cap.
 */
internal class BoundedBuffer(private val limit: Int, sizeHint: Long?) {
    private var bytes = ByteArray(initialSize(limit, sizeHint))
    private var count = 0
    private var total = 0L

    fun append(source: ByteArray, start: Int, length: Int) {
        val kept = minOf(length, limit - count)
        if (kept > 0) {
            ensureCapacity(count + kept)
            source.copyInto(bytes, count, start, start + kept)
            count += kept
        }
        total += length
    }

    /** Counts the next [size] bytes of [source], keeping a copy of those that fit, without consuming them. */
    fun capture(source: Source, size: Long) {
        val kept = minOf(size, (limit - count).toLong()).toInt()
        if (kept > 0) {
            ensureCapacity(count + kept)
            source.peek().readTo(bytes, count, count + kept)
            count += kept
        }
        total += size
    }

    /** Reads [source] to its end, keeping at most [limit] bytes; rethrows the channel's failure. */
    suspend fun drain(source: ByteReadChannel) {
        while (count < limit) {
            if (count == bytes.size) {
                // A body of exactly the size hint (its Content-Length) ends here: growing the
                // buffer only to find that out would copy it twice more (grow, then trim).
                if (!source.awaitContent()) break
                ensureCapacity(count + 1)
            }
            val read = source.readAvailable(bytes, count, bytes.size - count)
            if (read < 0) break
            count += read
            total += read
        }
        if (count >= limit) total += source.discard()
        source.closedCause?.let { throw it }
    }

    fun body(contentType: String?): CapturedBody {
        val kept = if (count == bytes.size) bytes else bytes.copyOf(count)
        return CapturedBody(kept, total, truncated = total > count, contentType, skippedReason = null)
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= bytes.size) return
        var size = maxOf(bytes.size * 2, GROW_STEP)
        while (size < needed) size *= 2
        bytes = bytes.copyOf(minOf(size, limit))
    }

    private companion object {
        const val GROW_STEP = 8 * 1024

        /**
         * The hint when it fits; the cap itself when the hint exceeds it, since such a body fills
         * the cap (growing there by doubling allocated about twice the cap and held 1.5x it during
         * the last copy); else a small start. A hint that lies costs at most the cap: the buffer
         * never exceeds [limit], and [body] trims what was not filled.
         */
        fun initialSize(limit: Int, sizeHint: Long?): Int = when {
            sizeHint == null || sizeHint < 0 -> minOf(limit, GROW_STEP)
            sizeHint > limit -> limit
            else -> sizeHint.toInt()
        }
    }
}

internal object SkipReason {
    const val BINARY = "binary content-type"
    const val STREAMING = "streaming"
    const val MULTIPART = "multipart"
    const val PROTOCOL_UPGRADE = "protocol upgrade"
    const val INCOMPLETE = "incomplete"
}

private val binaryApplicationTypes = setOf(
    "application/octet-stream",
    "application/zip",
    "application/gzip",
    "application/x-gzip",
    "application/x-bzip2",
    "application/x-tar",
    "application/x-7z-compressed",
    "application/x-rar-compressed",
    "application/vnd.rar",
    "application/zstd",
    "application/pdf",
    "application/wasm",
    "application/protobuf",
    "application/x-protobuf",
    "application/vnd.google.protobuf",
    "application/grpc",
    "application/msgpack",
    "application/x-msgpack",
    "application/cbor",
    "application/vnd.android.package-archive",
)

/** Why a response body with [contentType] is not captured, or null when it is. */
internal fun responseSkipReason(contentType: String?): String? {
    if (contentType == null) return null
    val mime = contentType.substringBefore(';').trim().lowercase()
    return when {
        mime == "text/event-stream" -> SkipReason.STREAMING
        mime == "image/svg+xml" -> null
        mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") ||
            mime.startsWith("font/") || mime in binaryApplicationTypes ||
            mime.startsWith("application/grpc+") -> SkipReason.BINARY
        else -> null
    }
}
