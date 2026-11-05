package dev.koga.hopi.android.internal

import dev.koga.hopi.api.HopiInternalApi
import java.io.OutputStream
import okio.Buffer

/**
 * The first [cap] bytes of a body, copied as the app's reader moves them, and the exact count of
 * all of them. Past the cap nothing is copied. The copies go into blocks, so growing never copies a
 * kept byte. The first is allocated by the first copy, so a body closed unread costs none, at the
 * body's size when that is known (at most the cap), else at 8 KB; each next one is as large as what
 * is kept, from 8 KB up to 64 KB, as in the Ktor plugin's capture. [bytes] joins the blocks once, on
 * the store's thread; a body of its known size is one block, which needs no join.
 *
 * Written by the app's reader or writer, ended by [freeze], which may run on another thread (a
 * `disconnect()` or `close()` from a second thread while the first is blocked in a read): every
 * take and the freeze hold this object's monitor, so the thread that freezes sees every byte taken
 * before it and no take changes the capture after it. Bytes that the reader gets after the freeze
 * reach the app as always and are neither copied nor counted. The fields are read after the freeze,
 * by the thread that froze or, after its post, by the store's thread.
 */
@HopiInternalApi
public class BoundedCapture(private val cap: Int, sizeHint: Long = -1) : OutputStream() {
    private val initialSize: Int = when {
        // A body known to reach the cap fills it: one block, no growth on the reader, no join.
        sizeHint > cap -> cap
        sizeHint > 0 -> sizeHint.toInt()
        // Unknown, or 0 and bytes come anyway: the first block must hold at least one.
        else -> minOf(cap, GROW_STEP)
    }

    /** The block being filled: [used] bytes of it are kept. */
    private var block: ByteArray = EMPTY
    private var used = 0

    /** Full blocks before [block], in order; null while there is at most one. */
    private var full: ArrayList<ByteArray>? = null

    // Guarded by this: set once by [freeze]; [replayed] by [replay].
    private var frozen = false
    private var replayed = false

    /** Bytes kept, at most the cap. */
    public var kept: Int = 0
        private set

    /** Bytes seen, kept or not. */
    public var total: Long = 0
        private set

    /** True when some bytes were seen and not kept, or the stream was reset and replayed ([replay]). */
    public val truncated: Boolean get() = total > kept || replayed

    /**
     * Bytes the blocks take: [kept], or more while the last block is not full (less than 64 KB more
     * for a body of unknown size), never more than the cap. What a record queued to the store keeps
     * alive until the store builds it.
     */
    public var held: Int = 0
        private set

    /**
     * Ends the capture: later takes change nothing. Returns true for the first call only, so the
     * thread that gets true is the one that ends the record.
     */
    @Synchronized
    public fun freeze(): Boolean {
        if (frozen) return false
        frozen = true
        return true
    }

    /** After a stream's `reset()` the same bytes come again: the capture keeps what it has and stops counting. */
    @Synchronized
    public fun replay() {
        replayed = true
    }

    /** Counts [byteCount] bytes of [buffer] starting at [offset], and copies those below the cap. */
    @Synchronized
    public fun take(buffer: Buffer, offset: Long, byteCount: Long) {
        if (byteCount <= 0 || frozen || replayed) return
        val copy = minOf((cap - kept).coerceAtLeast(0).toLong(), byteCount)
        // copyTo hands the kept part to write(), which counts it.
        if (copy > 0) buffer.copyTo(this, offset, copy)
        total += byteCount - copy
    }

    /** Counts [length] bytes of [bytes] from [offset], and copies those below the cap. */
    @Synchronized
    public fun take(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || frozen || replayed) return
        val room = cap - kept
        val copy = minOf(room.coerceAtLeast(0), length)
        if (copy > 0) write(bytes, offset, copy)
        total += (length - copy).toLong()
    }

    /** Counts one byte and copies it below the cap, with no array of its own (a single-byte read or write). */
    @Synchronized
    public fun takeByte(b: Int) {
        if (frozen || replayed) return
        if (kept < cap) write(b) else total++
    }

    /** Counts bytes that were skipped, never seen: the capture is then truncated. */
    @Synchronized
    public fun skipped(count: Long) {
        if (count > 0 && !frozen && !replayed) total += count
    }

    override fun write(b: Int) {
        room()
        block[used++] = b.toByte()
        kept++
        total++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        var left = minOf(len, cap - kept)
        var at = off
        while (left > 0) {
            val n = minOf(left, room())
            System.arraycopy(b, at, block, used, n)
            used += n
            kept += n
            at += n
            left -= n
        }
        total += len
    }

    /** The kept bytes, as one array of exactly [kept] bytes: the only block when it is full, else one join. */
    public fun bytes(): ByteArray {
        val blocks = full ?: return if (used == block.size) block else block.copyOf(used)
        val out = ByteArray(kept)
        var at = 0
        for (filled in blocks) {
            System.arraycopy(filled, 0, out, at, filled.size)
            at += filled.size
        }
        System.arraycopy(block, 0, out, at, used)
        return out
    }

    /** Free bytes in [block], opening the next block when it is full (the first at the first copy); called below the cap. */
    private fun room(): Int {
        if (used == block.size) {
            if (kept > 0) (full ?: ArrayList<ByteArray>().also { full = it }).add(block)
            block = ByteArray(if (kept == 0) initialSize else minOf(cap - kept, kept.coerceIn(GROW_STEP, MAX_BLOCK)))
            used = 0
            held += block.size
        }
        return block.size - used
    }

    private companion object {
        const val GROW_STEP = 8 * 1024

        /** The largest block opened past the first: what a body that ends early leaves unused. */
        const val MAX_BLOCK = 64 * 1024

        val EMPTY = ByteArray(0)
    }
}
