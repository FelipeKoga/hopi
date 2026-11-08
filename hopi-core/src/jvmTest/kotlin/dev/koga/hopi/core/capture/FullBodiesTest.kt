package dev.koga.hopi.core.capture

import dev.koga.hopi.core.store.HopiStore
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FullBodiesTest {
    private val a = HopiStore(maxTotalBytes = 1_000)
    private val b = HopiStore(maxTotalBytes = 2_000)

    @AfterTest
    fun tearDown() {
        FullBodies.resetForTests(null)
        a.close()
        b.close()
    }

    @Test
    fun eachStoreIsRaisedByItsOwnHoldersAndLoweredByItsLastRelease() {
        val releaseA = FullBodies.acquire(a)
        val releaseB = FullBodies.acquire(b)
        assertTrue(FullBodies.cap > 0)
        assertTrue(a.maxTotalBytes > a.baseMaxTotalBytes, "a raised")
        assertTrue(b.maxTotalBytes > b.baseMaxTotalBytes, "b raised, though a held the mode first")

        releaseA()
        releaseA()
        assertEquals(a.baseMaxTotalBytes, a.maxTotalBytes, "a back once its holder left")
        assertTrue(b.maxTotalBytes > b.baseMaxTotalBytes, "b still held; a second release of a's is a no-op")
        assertTrue(FullBodies.cap > 0)

        releaseB()
        assertEquals(0, FullBodies.cap)
        assertEquals(b.baseMaxTotalBytes, b.maxTotalBytes)
    }

    @Test
    fun aReleaseRacingAnAcquireNeverLeavesAHolderWithoutTheCap() {
        repeat(2_000) {
            val first = FullBodies.acquire(a)
            val barrier = CyclicBarrier(2)
            var second: (() -> Unit)? = null
            val releaser = thread { barrier.await(); first() }
            val acquirer = thread { barrier.await(); second = FullBodies.acquire(a) }
            releaser.join()
            acquirer.join()
            assertTrue(FullBodies.cap > 0, "one holder left, so full bodies stay on")
            assertTrue(a.maxTotalBytes > a.baseMaxTotalBytes)
            second!!()
            assertEquals(0, FullBodies.cap)
        }
    }
}
