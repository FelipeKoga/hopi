package dev.koga.hopi.ktor

import dev.koga.hopi.core.body.ResponseSkip
import dev.koga.hopi.core.model.CapturedBody
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.discard
import io.ktor.utils.io.readAvailable
import kotlinx.io.Buffer
import kotlinx.io.Segment
import kotlinx.io.UnsafeIoApi
import kotlinx.io.unsafe.UnsafeBufferOperations
import kotlinx.io.unsafe.withData

/**
 * Keeps the first [limit] bytes of a body and counts all of them, in blocks: a full block is kept
 * as it is and the next bytes go to a new one, so growing never copies what was kept, and a small
 * body never allocates the full cap. [body] joins the blocks once, at the end.
 */
internal class BoundedBuffer(private val limit: Int, sizeHint: Long?) {
    /** The block being filled: [kept] bytes of it are used. */
    private var bytes = ByteArray(initialSize(limit, sizeHint))
    private var kept = 0

    /** Full blocks before [bytes], in order; null while there is only one. */
    private var full: ArrayList<ByteArray>? = null
    private var count = 0
    private var total = 0L

    fun append(source: ByteArray, start: Int, length: Int) {
        var left = minOf(length, limit - count)
        var at = start
        while (left > 0) {
            val n = minOf(left, room())
            source.copyInto(bytes, kept, at, at + n)
            kept += n
            count += n
            at += n
            left -= n
        }
        total += length
    }

    /**
     * Counts the [size] bytes of [source] that start at [from], keeping a copy of those that fit,
     * without consuming them. Once the cap is reached this is only the count.
     *
     * The copy reads [source]'s segments in place, one array copy per segment and block, from the
     * segment that holds [from]: no peek source, no skip over the bytes before it, no segment created.
     */
    @OptIn(UnsafeIoApi::class)
    fun capture(source: Buffer, from: Long, size: Long) {
        var left = minOf(size, (limit - count).toLong()).toInt()
        if (left > 0) {
            copies++
            UnsafeBufferOperations.iterate(source, from) { context, first, firstStart ->
                var segment: Segment? = first
                var skip = (from - firstStart).toInt()
                while (left > 0 && segment != null) {
                    context.withData(segment) { data, start, end ->
                        var at = start + skip
                        var inSegment = minOf(left, end - at)
                        left -= inSegment
                        while (inSegment > 0) {
                            val n = minOf(inSegment, room())
                            data.copyInto(bytes, kept, at, at + n)
                            kept += n
                            count += n
                            at += n
                            inSegment -= n
                        }
                    }
                    skip = 0
                    segment = context.next(segment)
                }
            }
        }
        total += size
    }

    /** How many times [capture] copied bytes: a test hook, never more than the calls before the cap. */
    internal var copies: Int = 0
        private set

    /** Reads [source] to its end, keeping at most [limit] bytes; rethrows the channel's failure. */
    suspend fun drain(source: ByteReadChannel) {
        while (count < limit) {
            if (kept == bytes.size) {
                // A body of exactly the size hint (its Content-Length) ends here: opening a block
                // only to find that out would allocate one, and joining the two would copy it all.
                if (!source.awaitContent()) break
            }
            val free = room() // may open a block, so it comes before reading bytes and kept
            val read = source.readAvailable(bytes, kept, free)
            if (read < 0) break
            kept += read
            count += read
            total += read
        }
        if (count >= limit) total += source.discard()
        source.closedCause?.let { throw it }
    }

    /** How many bytes [body] would copy: none for a single full block, else every kept byte. */
    val joinSize: Int
        get() = if (full == null && kept == bytes.size) 0 else count

    /**
     * The kept bytes in one array: the block itself when it is the only one and full, else one join.
     *
     * A [TeedBody.cancel] from another thread may call this while the reader is still capturing,
     * which then records the capture as of now: the fields are read once each and every copy is
     * clamped, so a torn view keeps fewer bytes and never throws.
     */
    fun body(contentType: String?): CapturedBody {
        val blocks = full
        val last = bytes
        val inLast = minOf(kept, last.size)
        val size = count
        val total = total
        val joined = when {
            blocks == null && inLast == last.size -> last
            blocks == null -> last.copyOf(inLast)
            else -> {
                val out = ByteArray(size)
                var at = 0
                for (i in 0 until blocks.size) {
                    val block = blocks.getOrNull(i) ?: break
                    if (block === last) break
                    val n = minOf(block.size, out.size - at)
                    block.copyInto(out, at, 0, n)
                    at += n
                }
                last.copyInto(out, at, 0, minOf(inLast, out.size - at))
                out
            }
        }
        return CapturedBody(joined, total, truncated = total > joined.size, contentType, skippedReason = null)
    }

    /** Free bytes in the current block, opening the next one when it is full; called below the cap. */
    private fun room(): Int {
        if (kept == bytes.size) {
            (full ?: ArrayList<ByteArray>().also { full = it }).add(bytes)
            bytes = ByteArray(minOf(limit - count, count.coerceIn(GROW_STEP, MAX_BLOCK)))
            kept = 0
        }
        return bytes.size - kept
    }

    private companion object {
        const val GROW_STEP = 8 * 1024

        /** The largest block opened past the first: what a body that ends early leaves unused. */
        const val MAX_BLOCK = 64 * 1024

        /**
         * The hint when it fits; the cap itself when the hint exceeds it, since such a body fills
         * the cap; else a small start. A hint that lies costs at most the cap: the blocks never hold
         * more than [limit], and [body] keeps only what was filled.
         */
        fun initialSize(limit: Int, sizeHint: Long?): Int = when {
            sizeHint == null || sizeHint < 0 -> minOf(limit, GROW_STEP)
            sizeHint > limit -> limit
            else -> sizeHint.toInt()
        }
    }
}

internal object SkipReason {
    const val BINARY = ResponseSkip.BINARY
    const val STREAMING = ResponseSkip.STREAMING
    const val MULTIPART = "multipart"
    const val PROTOCOL_UPGRADE = "protocol upgrade"
    const val INCOMPLETE = "incomplete"
}

/** Why a response body with [contentType] is not captured, or null when it is. */
internal fun responseSkipReason(contentType: String?): String? = ResponseSkip.reason(contentType)
