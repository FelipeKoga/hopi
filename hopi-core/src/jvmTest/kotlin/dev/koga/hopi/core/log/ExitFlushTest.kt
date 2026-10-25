package dev.koga.hopi.core.log

import dev.koga.hopi.core.complete
import dev.koga.hopi.core.model.TransactionEvent
import dev.koga.hopi.core.store.HopiStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A request that ends in the store's batch window, right before the app crashes: its record and
 * summary line are out before the crash reaches the app's own handler.
 */
class ExitFlushTest {
    private val store = HopiStore(batchWindowMillis = 60_000)

    @AfterTest
    fun tearDown() {
        store.close()
    }

    @Test
    fun anUncaughtExceptionWaitsForTheStoreThenReachesTheHandlerItReplaced() {
        val lines = mutableListOf<Long>()
        store.addEventListener { if (it is TransactionEvent.Completed) synchronized(lines) { lines += it.transaction.id } }
        store.submit(complete(1))
        val deadline = System.nanoTime() + 10_000_000_000
        while (store.get(1) == null) {
            check(System.nanoTime() < deadline) { "the first request was not applied" }
            Thread.onSpinWait()
        }
        // The consumer dozes now: this end only marks the work, for up to a minute.
        store.submit(complete(2))
        assertNull(store.get(2))

        var seenByPrevious: List<Long>? = null
        var previousGot: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, error ->
            seenByPrevious = synchronized(lines) { lines.toList() }
            previousGot = error
        }
        val crash = IllegalStateException("malformed response")
        ApplyingExceptionHandler(store, previous).uncaughtException(Thread.currentThread(), crash)

        assertEquals(listOf(1L, 2L), seenByPrevious, "the line of the request that ended last was not out before the crash went on")
        assertSame(crash, previousGot)
    }
}
