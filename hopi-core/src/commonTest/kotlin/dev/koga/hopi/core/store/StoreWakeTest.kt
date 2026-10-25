package dev.koga.hopi.core.store

import dev.koga.hopi.core.complete
import dev.koga.hopi.core.model.TransactionEvent
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.pending
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * The store's own consumer thread (no scope): a request's start wakes it only while a viewer is
 * attached, and with nobody watching, an end wakes it only from its untimed sleep: ends that arrive
 * while it dozes after a pass are applied when the doze ends, with no wake. Runs on the JVM and on
 * the iOS simulator.
 */
class StoreWakeTest {
    private val store = HopiStore()

    @AfterTest
    fun tearDown() {
        store.close()
    }

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        val mark = TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (mark.elapsedNow() > 10_000.milliseconds) fail("timed out waiting for $what")
            delay(5)
        }
    }

    @Test
    fun withNoViewerARequestWakesTheConsumerOnceAtItsEnd() {
        waitFor("the consumer to sleep") { store.consumerSleeping }
        val wakes = store.consumerWakeups
        store.submit(pending(1))
        // A wake would be immediate; the consumer stays asleep instead.
        runBlocking { delay(300) }
        assertEquals(wakes, store.consumerWakeups, "a start woke the consumer with no viewer")
        assertTrue(store.consumerSleeping)
        assertEquals(emptyList(), store.transactions.value)

        store.submit(complete(1))
        waitFor("the request") { store.transactions.value.singleOrNull()?.state == TransactionState.Complete }
        waitFor("the consumer to sleep again") { store.consumerSleeping }
        assertEquals(wakes + 1, store.consumerWakeups, "one wake for the whole request")
    }

    @Test
    fun withAViewerEveryStartWakesTheConsumer() {
        store.submit(pending(1))
        waitFor("the consumer to sleep") { store.consumerSleeping }
        val wakes = store.consumerWakeups
        val detach = store.attachViewer()
        waitFor("the start that waited") { store.transactions.value.singleOrNull()?.id == 1L }
        waitFor("the consumer to sleep again") { store.consumerSleeping }
        assertEquals(wakes + 1, store.consumerWakeups, "attaching wakes the consumer once")

        store.submit(pending(2))
        waitFor("the second start") { store.transactions.value.size == 2 }
        waitFor("the consumer to sleep again") { store.consumerSleeping }
        assertEquals(wakes + 2, store.consumerWakeups)
        detach()
    }

    private fun batching(window: Long, terminalCapacity: Int = HopiStore.DEFAULT_TERMINAL_CHANNEL_CAPACITY) =
        HopiStore(terminalChannelCapacity = terminalCapacity, batchWindowMillis = window).also { batched = it }

    private var batched: HopiStore? = null

    @AfterTest
    fun closeBatched() {
        batched?.close()
    }

    /** Wakes the consumer once and waits until it applied [id]: it dozes from then on. */
    private fun HopiStore.firstOfBurst(id: Long) {
        submit(complete(id))
        waitFor("request $id") { get(id) != null }
        waitFor("the doze") { consumerDozing }
    }

    @Test
    fun withNoViewerEndsDuringTheDozeWakeNothingAndShowWhenItEnds() {
        val store = batching(window = 400)
        waitFor("the consumer to sleep") { store.consumerSleeping }
        val wakes = store.consumerWakeups
        val start = TimeSource.Monotonic.markNow()
        store.firstOfBurst(1)
        store.submit(pending(2))
        store.submit(complete(2))
        store.submit(complete(3))
        assertEquals(null, store.get(2), "an end during the doze was applied before it ended")
        waitFor("the burst") { store.get(2)?.state == TransactionState.Complete && store.get(3) != null }
        assertTrue(start.elapsedNow() < 5_000.milliseconds)
        waitFor("the consumer to sleep with no timer") { store.consumerSleeping }
        assertEquals(wakes + 1, store.consumerWakeups, "the burst paid more than one wake")
        assertTrue(store.consumerDozeTimeouts >= 1)
        // Back in the untimed sleep: nothing wakes it while idle.
        val dozes = store.consumerDozeTimeouts
        runBlocking { delay(1000) }
        assertEquals(wakes + 1, store.consumerWakeups)
        assertEquals(dozes, store.consumerDozeTimeouts, "the consumer kept a timer while idle")
        assertTrue(store.consumerSleeping)
    }

    @Test
    fun attachingAViewerAppliesWhatWaitedInTheDozeAtOnce() {
        val store = batching(window = 60_000)
        store.firstOfBurst(1)
        store.submit(complete(2))
        runBlocking { delay(100) }
        assertEquals(null, store.get(2))
        val start = TimeSource.Monotonic.markNow()
        val detach = store.attachViewer()
        waitFor("the end that waited") { store.get(2) != null }
        assertTrue(start.elapsedNow() < 30_000.milliseconds, "the attach did not end the doze")
        // With the viewer attached every end is applied at once.
        store.submit(complete(3))
        waitFor("an end with a viewer") { store.get(3) != null }
        detach()
    }

    @Test
    fun awaitAppliedAppliesAndLogsWhatWaitedInTheDozeBeforeItReturns() {
        val store = batching(window = 60_000)
        val completed = mutableListOf<Long>()
        // The summary log sink is such a listener: its line is written by the time awaitApplied returns.
        store.addEventListener { if (it is TransactionEvent.Completed) completed += it.transaction.id }
        store.firstOfBurst(1)
        store.submit(complete(2))
        store.submit(complete(3))
        assertTrue(store.consumerDozing)
        assertEquals(null, store.get(2), "an end during the doze was applied before it ended")
        val start = TimeSource.Monotonic.markNow()
        assertTrue(store.awaitApplied(timeoutMillis = 30_000))
        assertTrue(start.elapsedNow() < 30_000.milliseconds)
        assertEquals(TransactionState.Complete, store.get(2)?.state)
        assertEquals(TransactionState.Complete, store.get(3)?.state)
        assertEquals(listOf(1L, 2L, 3L), completed)
        // Nothing queued: it returns after an empty pass.
        assertTrue(store.awaitApplied(timeoutMillis = 30_000))
    }

    @Test
    fun awaitAppliedReturnsAtOnceAfterCloseAndInCoroutineMode() {
        val store = batching(window = 60_000)
        store.close()
        assertEquals(false, store.awaitApplied(timeoutMillis = 30_000))
        val scope = CoroutineScope(Job())
        val scoped = HopiStore(scope = scope)
        val start = TimeSource.Monotonic.markNow()
        assertEquals(false, scoped.awaitApplied(timeoutMillis = 30_000))
        assertTrue(start.elapsedNow() < 30_000.milliseconds)
        scoped.close()
        scope.cancel()
    }

    @Test
    fun aClearAndAViewerEndTheDozeButEndsAloneWaitForIt() {
        val store = batching(window = 60_000)
        store.firstOfBurst(1)
        store.submit(complete(2))
        store.clear(through = 1)
        waitFor("the clear and the end that waited") { store.transactions.value.map { it.id } == listOf(2L) }
    }

    @Test
    fun aFloodOfEndsWakesTheConsumerOutOfItsDoze() {
        // Half the terminal lane (4 of 8) queued without a wake forces one.
        val store = batching(window = 60_000, terminalCapacity = 8)
        store.firstOfBurst(1)
        for (id in 2L..4L) store.submit(complete(id))
        runBlocking { delay(100) }
        assertEquals(null, store.get(4), "fewer than the threshold woke the consumer")
        store.submit(complete(5))
        waitFor("the flood") { (1L..5L).all { store.get(it) != null } }
        assertEquals(0, store.droppedTerminalWrites.value)
    }

    @Test
    fun aWindowOfZeroWakesForEveryEnd() {
        val store = batching(window = 0)
        waitFor("the consumer to sleep") { store.consumerSleeping }
        val wakes = store.consumerWakeups
        store.submit(complete(1))
        waitFor("request 1") { store.get(1) != null }
        waitFor("the consumer to sleep again") { store.consumerSleeping }
        store.submit(complete(2))
        waitFor("request 2") { store.get(2) != null }
        waitFor("the consumer to sleep again") { store.consumerSleeping }
        assertEquals(wakes + 2, store.consumerWakeups)
        assertEquals(0, store.consumerDozeTimeouts)
    }
}
