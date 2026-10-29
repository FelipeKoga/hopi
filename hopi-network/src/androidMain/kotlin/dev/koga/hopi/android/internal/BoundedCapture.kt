package dev.koga.hopi.android.internal

import dev.koga.hopi.api.HopiInternalApi
import java.io.OutputStream
import okio.Buffer

/**
 * The first [cap] bytes of a body, copied as the app's reader moves them, and the exact count of
 * all of them. Past the cap nothing is copied. The array is allocated by the first copy, so a body
 * closed unread costs none; it starts at the body's size when it is known (at most the cap), else
 * 8 KB, and doubles up to the cap; [bytes] trims it once, on the store's thread.
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
        sizeHint in 0..cap.toLong() -> sizeHint.toInt()
        // A body known to reach the cap fills it: one array, no growth copies on the reader.
        sizeHint > cap -> cap
        else -> minOf(cap, INITIAL)
    }

    private var array: ByteArray = EMPTY

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
     * Bytes the array takes: [kept], or more while a body of unknown size grew by doubling, never
     * more than the cap. What a record queued to the store keeps alive until the store builds it.
     */
    public val held: Int get() = array.size

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
        ensure(kept + 1)
        array[kept++] = b.toByte()
        total++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        ensure(kept + len)
        System.arraycopy(b, off, array, kept, len)
        kept += len
        total += len
    }

    /** The kept bytes, as one array of exactly [kept] bytes. */
    public fun bytes(): ByteArray = if (kept == array.size) array else array.copyOf(kept)

    private fun ensure(size: Int) {
        if (size <= array.size) return
        array = if (array.isEmpty()) ByteArray(maxOf(size, initialSize)) else array.copyOf(maxOf(size, minOf(cap.toLong(), array.size * 2L).toInt()))
    }

    private companion object {
        const val INITIAL = 8 * 1024
        val EMPTY = ByteArray(0)
    }
}
