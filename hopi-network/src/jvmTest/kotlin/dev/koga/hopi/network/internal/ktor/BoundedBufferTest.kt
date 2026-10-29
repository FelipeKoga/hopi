package dev.koga.hopi.network.internal.ktor

import io.ktor.utils.io.ByteReadChannel
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

class BoundedBufferTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Test
    fun aBodyOfExactlyItsContentLengthIsCopiedOnce() = runBlocking {
        val body = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val source = ByteReadChannel(body)
        val buffer = BoundedBuffer(limit = 8 * 1024 * 1024, sizeHint = body.size.toLong())
        val before = threads.getThreadAllocatedBytes(Thread.currentThread().id)
        buffer.drain(source)
        val captured = buffer.body("application/json")
        val allocated = threads.getThreadAllocatedBytes(Thread.currentThread().id) - before
        assertContentEquals(body, captured.bytes)
        assertEquals(body.size.toLong(), captured.totalBytes)
        assertFalse(captured.truncated)
        // One array of the body's size, plus small change. Growing at the end to look for more
        // bytes, then trimming, allocated 8 MB + 4 MB more.
        assertTrue(allocated < body.size + 512 * 1024, "drain allocated $allocated bytes for a ${body.size}-byte body")
    }

    @Test
    fun aBodyWhoseContentLengthExceedsTheCapAllocatesTheCapOnce() = runBlocking {
        val limit = 8 * 1024 * 1024
        val body = ByteArray(20 * 1024 * 1024) { (it % 251).toByte() }
        val source = ByteReadChannel(body)
        val before = threads.getThreadAllocatedBytes(Thread.currentThread().id)
        val buffer = BoundedBuffer(limit = limit, sizeHint = body.size.toLong())
        buffer.drain(source)
        val captured = buffer.body("application/json")
        val allocated = threads.getThreadAllocatedBytes(Thread.currentThread().id) - before
        assertContentEquals(body.copyOf(limit), captured.bytes)
        assertEquals(body.size.toLong(), captured.totalBytes)
        assertTrue(captured.truncated)
        // One array of the cap. Growing from 8 KB by doubling allocated about twice the cap.
        assertTrue(allocated < limit + 512 * 1024, "drain allocated $allocated bytes for a $limit-byte cap")
    }

    @Test
    fun aContentLengthAboveTheCapThatLiesKeepsTheRealBody() = runBlocking {
        val body = ByteArray(10_000) { it.toByte() }
        val buffer = BoundedBuffer(limit = 64 * 1024, sizeHint = 1_000_000)
        buffer.drain(ByteReadChannel(body))
        val captured = buffer.body(null)
        assertContentEquals(body, captured.bytes)
        assertEquals(10_000L, captured.totalBytes)
        assertFalse(captured.truncated)
    }

    @Test
    fun aBodyLongerThanItsHintStillGrowsAndIsCountedToTheEnd() = runBlocking {
        val body = ByteArray(100_000) { it.toByte() }
        val buffer = BoundedBuffer(limit = 64 * 1024, sizeHint = 10_000)
        buffer.drain(ByteReadChannel(body))
        val captured = buffer.body(null)
        assertContentEquals(body.copyOf(64 * 1024), captured.bytes)
        assertEquals(100_000L, captured.totalBytes)
        assertTrue(captured.truncated)
    }

    @Test
    fun captureCopiesFromAnyOffsetAcrossSegmentsWithoutConsuming() {
        // Uneven appends leave segments of many sizes; each capture starts where the last ended,
        // inside a segment or on its boundary, as TeedBody's moves do, and spans several segments.
        val body = ByteArray(300_000) { (it % 251).toByte() }
        val limit = 200_000
        val source = Buffer()
        val buffer = BoundedBuffer(limit = limit, sizeHint = null)
        var offset = 0
        for (size in listOf(1, 8191, 8192, 3, 20_000, 65_536, 77, 100_000, 98_000 - 1, 1)) {
            source.write(body, offset, offset + size)
            buffer.capture(source, offset.toLong(), size.toLong())
            offset += size
        }
        assertEquals(body.size, offset)
        assertEquals(body.size.toLong(), source.size)
        assertContentEquals(body, source.readByteArray())
        val captured = buffer.body(null)
        assertContentEquals(body.copyOf(limit), captured.bytes)
        assertEquals(body.size.toLong(), captured.totalBytes)
        assertTrue(captured.truncated)
    }

    @Test
    fun anUnsizedCaptureGrowsInBlocksWithoutCopyingWhatItKept() {
        val body = ByteArray(1_000_000) { (it % 251).toByte() }
        fun capture(): Pair<BoundedBuffer, Long> {
            val source = Buffer().apply { write(body) }
            val buffer = BoundedBuffer(limit = 8 * 1024 * 1024, sizeHint = null)
            val before = threads.getThreadAllocatedBytes(Thread.currentThread().id)
            var offset = 0L
            while (offset < body.size) {
                val size = minOf(3_001L, body.size - offset)
                buffer.capture(source, offset, size)
                offset += size
            }
            return buffer to threads.getThreadAllocatedBytes(Thread.currentThread().id) - before
        }
        repeat(3) { capture() } // warm-up
        val (buffer, allocated) = capture()
        // The blocks hold the body plus at most one unfilled 64 KB block, and each call's few
        // bytes of bookkeeping; growing by doubling allocated about twice the body.
        assertTrue(allocated < body.size + 192 * 1024, "capturing allocated $allocated bytes for a ${body.size}-byte body")
        assertEquals(body.size, buffer.joinSize)
        val captured = buffer.body(null)
        assertContentEquals(body, captured.bytes)
        assertEquals(body.size.toLong(), captured.totalBytes)
        assertFalse(captured.truncated)
    }
}
