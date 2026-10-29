package dev.koga.hopi.android

import dev.koga.hopi.android.internal.BoundedCapture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Buffer

class BoundedCaptureTest {
    @Test
    fun nothingIsAllocatedBeforeTheFirstCopy() {
        // A body known to reach the cap, closed unread: no cap-sized array.
        val capture = BoundedCapture(256 * 1024, sizeHint = 20L * 1024 * 1024)
        assertEquals(0, arraySize(capture))
        assertEquals(0, capture.bytes().size)
        capture.take(ByteArray(10) { it.toByte() }, 0, 10)
        // Then one array at the cap, with no growth copies.
        assertEquals(256 * 1024, arraySize(capture))
        assertEquals(10, capture.kept)
    }

    @Test
    fun heldIsWhatTheArrayTakesAsItGrows() {
        // What a record queued to the store counts: the array, not only the bytes kept in it.
        val capture = BoundedCapture(64 * 1024)
        assertEquals(0, capture.held)
        capture.take(ByteArray(10), 0, 10)
        assertEquals(8 * 1024, capture.held)
        capture.take(ByteArray(9 * 1024), 0, 9 * 1024)
        assertEquals(16 * 1024, capture.held)
        assertEquals(arraySize(capture), capture.held)
        assertEquals(10 + 9 * 1024, capture.kept)
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

    private fun arraySize(capture: BoundedCapture): Int =
        (BoundedCapture::class.java.getDeclaredField("array").apply { isAccessible = true }.get(capture) as ByteArray).size
}
