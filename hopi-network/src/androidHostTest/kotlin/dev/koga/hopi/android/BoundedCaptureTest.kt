package dev.koga.hopi.android

import dev.koga.hopi.android.internal.BoundedCapture
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import okio.Buffer

class BoundedCaptureTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Test
    fun nothingIsAllocatedBeforeTheFirstCopy() {
        // A body known to reach the cap, closed unread: no cap-sized array.
        val capture = BoundedCapture(256 * 1024, sizeHint = 20L * 1024 * 1024)
        assertEquals(emptyList(), blocks(capture))
        assertEquals(0, capture.bytes().size)
        capture.take(ByteArray(10) { it.toByte() }, 0, 10)
        // Then one block at the cap, which never grows; a body shorter than its length is trimmed.
        assertEquals(listOf(256 * 1024), blocks(capture))
        assertEquals(10, capture.kept)
        assertContentEquals(ByteArray(10) { it.toByte() }, capture.bytes())
    }

    @Test
    fun heldIsWhatTheBlocksTakeAsTheyGrow() {
        // What a record queued to the store counts: the blocks, not only the bytes kept in them.
        val capture = BoundedCapture(64 * 1024)
        assertEquals(0, capture.held)
        capture.take(ByteArray(10), 0, 10)
        assertEquals(8 * 1024, capture.held)
        capture.take(ByteArray(9 * 1024), 0, 9 * 1024)
        assertEquals(16 * 1024, capture.held)
        assertEquals(listOf(8 * 1024, 8 * 1024), blocks(capture))
        assertEquals(blocks(capture).sum(), capture.held)
        assertEquals(10 + 9 * 1024, capture.kept)
    }

    @Test
    fun aBodyOfItsContentLengthIsOneExactBlockThatNeedsNoJoin() {
        val body = ByteArray(100_000) { (it % 251).toByte() }
        val capture = BoundedCapture(256 * 1024, sizeHint = body.size.toLong())
        capture.take(body, 0, 60_000)
        capture.take(body, 60_000, 40_000)
        assertEquals(listOf(body.size), blocks(capture))
        assertEquals(body.size, capture.held)
        assertContentEquals(body, capture.bytes())
        assertSame(capture.bytes(), capture.bytes(), "the block itself, not a copy")
    }

    @Test
    fun aBodyKnownToPassTheCapIsOneCapSizedBlockThatNeedsNoJoin() {
        // The usual giant body: a Content-Length past the cap. One block at the cap, never a second
        // one at it (whose join would copy the cap on the store's thread), and the count goes on.
        val cap = 256 * 1024
        val body = ByteArray(300_001) { (it % 251).toByte() }
        val capture = BoundedCapture(cap, sizeHint = 20L * 1024 * 1024)
        capture.take(body, 0, 200_000)
        capture.take(body, 200_000, 100_000) // across the cap
        capture.takeByte(body[300_000].toInt() and 0xff)
        assertEquals(listOf(cap), blocks(capture))
        assertEquals(cap, capture.held)
        assertEquals(cap, capture.kept)
        assertEquals(300_001L, capture.total)
        assertTrue(capture.truncated)
        assertContentEquals(body.copyOf(cap), capture.bytes())
        assertSame(capture.bytes(), capture.bytes(), "the block itself, not a copy")
    }

    @Test
    fun aChunkedBodyUnderTheCapAllocatesItsBlocksThenOneJoin() {
        // No size to start from: blocks of 8 KB growing to 64 KB hold the body plus at most one
        // unfilled block, and the store's join copies it once: about 2x the body in all. One array
        // grown by doubling allocated 2 MB on the reader for this body, and its trim 1 MB more.
        val body = ByteArray(1_000_000) { (it % 251).toByte() }
        fun captureOnce(): Triple<BoundedCapture, Long, Long> {
            val capture = BoundedCapture(8 * 1024 * 1024)
            val start = allocated()
            var offset = 0
            while (offset < body.size) {
                val size = minOf(3_001, body.size - offset)
                capture.take(body, offset, size)
                offset += size
            }
            val taken = allocated()
            capture.bytes()
            return Triple(capture, taken - start, allocated() - taken)
        }
        repeat(3) { captureOnce() } // warm-up
        val (capture, taking, joining) = captureOnce()
        assertTrue(taking < body.size + 128 * 1024, "the takes allocated $taking bytes for a ${body.size}-byte body")
        assertTrue(joining in body.size.toLong()..body.size + 16L * 1024, "the join allocated $joining bytes for a ${body.size}-byte body")
        assertEquals(listOf(8, 8, 16, 32).map { it * 1024 } + List(15) { 64 * 1024 }, blocks(capture))
        assertEquals(blocks(capture).sum(), capture.held)
        assertContentEquals(body, capture.bytes())
        assertEquals(body.size.toLong(), capture.total)
        assertFalse(capture.truncated)
    }

    @Test
    fun aChunkedBodyThatEndsOnABlockBoundaryOpensNoBlockMore() {
        val body = ByteArray(64 * 1024 + 1) { (it % 251).toByte() }
        val capture = BoundedCapture(1024 * 1024)
        // Single bytes fill the first block exactly: the only block, so it is the body as it is.
        for (i in 0 until 8 * 1024) capture.takeByte(body[i].toInt() and 0xff)
        assertEquals(listOf(8 * 1024), blocks(capture))
        assertSame(capture.bytes(), capture.bytes(), "one full block needs no join")
        // Up to the end of the fourth block: nothing allocated past it.
        capture.take(body, 8 * 1024, 56 * 1024)
        assertEquals(listOf(8, 8, 16, 32).map { it * 1024 }, blocks(capture))
        assertEquals(capture.kept, capture.held)
        assertContentEquals(body.copyOf(64 * 1024), capture.bytes())
        // One byte more opens the next block, as large as what is kept (64 KB at most).
        capture.takeByte(body[64 * 1024].toInt() and 0xff)
        assertEquals(listOf(8, 8, 16, 32, 64).map { it * 1024 }, blocks(capture))
        assertContentEquals(body, capture.bytes())
        assertEquals(body.size.toLong(), capture.total)
        assertFalse(capture.truncated)
    }

    @Test
    fun aChunkedBodyPastTheCapFillsItsBlocksToTheCapExactly() {
        // As OkHttp's tee takes them: from a buffer it does not consume, at offsets inside a segment
        // or on its boundary. The last block stops at the cap, so the blocks take the cap and no more.
        val body = ByteArray(300_000) { (it % 251).toByte() }
        val cap = 200_000
        val source = Buffer()
        val capture = BoundedCapture(cap)
        var offset = 0
        for (size in listOf(1, 8191, 8192, 3, 20_000, 65_536, 77, 100_000, 97_999, 1)) {
            source.write(body, offset, size)
            capture.take(source, offset.toLong(), size.toLong())
            offset += size
        }
        assertEquals(body.size, offset)
        assertContentEquals(body, source.readByteArray(), "the source is not consumed")
        assertEquals(listOf(8, 8, 16, 32, 64, 64).map { it * 1024 } + (cap - 192 * 1024), blocks(capture))
        assertEquals(cap, capture.held)
        assertEquals(cap, capture.kept)
        assertContentEquals(body.copyOf(cap), capture.bytes())
        assertEquals(body.size.toLong(), capture.total)
        assertTrue(capture.truncated)
    }

    @Test
    fun aChunkedBodyOfExactlyTheCapIsWholeUntilOneByteMore() {
        val cap = 64 * 1024
        val body = ByteArray(cap + 1) { (it % 251).toByte() }
        val capture = BoundedCapture(cap)
        var offset = 0
        for (size in listOf(5_000, 3_192, 9_000, 40_000, 8_344)) {
            capture.take(body, offset, size)
            offset += size
        }
        assertEquals(cap, offset)
        assertEquals(cap, capture.kept)
        assertEquals(cap.toLong(), capture.total)
        assertFalse(capture.truncated, "every byte seen was kept")
        assertEquals(listOf(8, 8, 16, 32).map { it * 1024 }, blocks(capture))
        assertContentEquals(body.copyOf(cap), capture.bytes())
        // One byte past the cap is only counted: no block opens for it.
        capture.takeByte(body[cap].toInt() and 0xff)
        assertEquals(cap + 1L, capture.total)
        assertTrue(capture.truncated)
        assertEquals(listOf(8, 8, 16, 32).map { it * 1024 }, blocks(capture))
        assertEquals(cap, capture.held)
        assertContentEquals(body.copyOf(cap), capture.bytes())
    }

    @Test
    fun aBodyLongerThanItsContentLengthGoesOnInBlocksUpToTheCap() {
        val body = ByteArray(100_000) { it.toByte() }
        val capture = BoundedCapture(64 * 1024, sizeHint = 10_000)
        capture.take(body, 0, body.size)
        assertEquals(listOf(10_000, 10_000, 20_000, 64 * 1024 - 40_000), blocks(capture))
        assertEquals(64 * 1024, capture.held)
        assertContentEquals(body.copyOf(64 * 1024), capture.bytes())
        assertEquals(body.size.toLong(), capture.total)
        assertTrue(capture.truncated)
    }

    @Test
    fun anEmptyBodyAllocatesNothing() {
        // Chunked, or a Content-Length of 0: no block, and an empty array for the store.
        for (sizeHint in listOf(-1L, 0L)) {
            val capture = BoundedCapture(64 * 1024, sizeHint)
            capture.take(ByteArray(0), 0, 0)
            capture.take(Buffer(), 0, 0)
            assertTrue(capture.freeze())
            assertEquals(emptyList(), blocks(capture))
            assertEquals(0, capture.held)
            assertEquals(0, capture.bytes().size)
            assertEquals(0L, capture.total)
            assertFalse(capture.truncated)
        }
        // A body said to be empty that has bytes after all grows as one of unknown size.
        val capture = BoundedCapture(64 * 1024, sizeHint = 0)
        capture.take("abc".encodeToByteArray(), 0, 3)
        assertEquals(listOf(8 * 1024), blocks(capture))
        assertContentEquals("abc".encodeToByteArray(), capture.bytes())
    }

    @Test
    fun singleBytesAreCopiedBelowTheCapAndOnlyCountedPastIt() {
        val capture = BoundedCapture(2)
        for (b in "abc".encodeToByteArray()) capture.takeByte(b.toInt())
        assertContentEquals("ab".encodeToByteArray(), capture.bytes())
        assertEquals(3L, capture.total)
        assertTrue(capture.truncated)
    }

    @Test
    fun afterTheFreezeNothingIsTakenAndOnlyTheFirstFreezeEnds() {
        val capture = BoundedCapture(8)
        capture.take("abc".encodeToByteArray(), 0, 3)
        assertTrue(capture.freeze())
        assertFalse(capture.freeze(), "a second end does not end the record again")
        capture.take("defghijkl".encodeToByteArray(), 0, 9)
        capture.takeByte('x'.code)
        capture.skipped(100)
        capture.take(Buffer().writeUtf8("more"), 0, 4)
        assertContentEquals("abc".encodeToByteArray(), capture.bytes())
        assertEquals(3L, capture.total)
        assertFalse(capture.truncated)
    }

    @Test
    fun aReplayKeepsWhatWasTakenStopsCountingAndIsTruncated() {
        val capture = BoundedCapture(8)
        capture.take("abc".encodeToByteArray(), 0, 3)
        capture.replay()
        capture.take("abc".encodeToByteArray(), 0, 3)
        assertContentEquals("abc".encodeToByteArray(), capture.bytes())
        assertEquals(3L, capture.total)
        assertTrue(capture.truncated)
    }

    @Test
    fun aFreezeOnAnotherThreadSeesEveryTakeBeforeItAndNoneAfter() {
        // A reader keeps taking while a second thread ends the record (a disconnect mid-read).
        repeat(50) {
            val capture = BoundedCapture(64 * 1024)
            val chunk = ByteArray(100) { 'x'.code.toByte() }
            val taken = CountDownLatch(20)
            val stop = AtomicBoolean(false)
            val reader = thread {
                while (!stop.get()) {
                    capture.take(chunk, 0, chunk.size)
                    taken.countDown()
                }
            }
            taken.await()
            assertTrue(capture.freeze())
            val kept = capture.kept
            val total = capture.total
            val bytes = capture.bytes()
            stop.set(true)
            reader.join()
            assertEquals(kept, bytes.size)
            assertTrue(bytes.all { it == 'x'.code.toByte() }, "no zero-filled tail")
            assertEquals(minOf(total, 64L * 1024).toInt(), kept, "count and bytes agree")
            assertEquals(total, capture.total, "nothing taken after the freeze")
            assertContentEquals(bytes, capture.bytes())
        }
    }

    private fun allocated(): Long = threads.currentThreadAllocatedBytes

    /** The sizes of the capture's blocks, in order: what its copies allocated. */
    private fun blocks(capture: BoundedCapture): List<Int> {
        fun field(name: String): Any? = BoundedCapture::class.java.getDeclaredField(name).apply { isAccessible = true }.get(capture)

        @Suppress("UNCHECKED_CAST")
        val full = field("full") as List<ByteArray>?
        val block = field("block") as ByteArray
        return (full.orEmpty() + block).map { it.size }.filter { it > 0 }
    }
}
