package dev.koga.hopi.core.store

import dev.koga.hopi.core.bytesBody
import dev.koga.hopi.core.complete
import dev.koga.hopi.core.model.TransactionEvent
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.pending
import dev.koga.hopi.core.redact.Redactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class HopiStoreTest {
    private fun TestScope.store(
        maxEntries: Int = 500,
        maxTotalBytes: Long = 1L shl 30,
        capacity: Int = 256,
        terminalCapacity: Int = HopiStore.DEFAULT_TERMINAL_CHANNEL_CAPACITY,
    ) = HopiStore(maxEntries, maxTotalBytes, backgroundScope, capacity, terminalChannelCapacity = terminalCapacity)

    private fun HopiStore.ids() = transactions.value.map { it.id }

    @Test
    fun idsAreMonotonicFromOne() = runTest {
        val store = store()
        assertEquals(listOf(1L, 2L, 3L), List(3) { store.nextId() })
    }

    @Test
    fun keepsIdOrderNewestLastAndReplacesById() = runTest {
        val store = store()
        store.submit(pending(1))
        store.submit(pending(3))
        store.submit(pending(2))
        store.submit(complete(1))
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), store.ids())
        assertEquals(TransactionState.Complete, store.get(1)?.state)
        assertEquals(TransactionState.Pending, store.get(2)?.state)
        assertNull(store.get(99))
    }

    @Test
    fun evictsOldestByCount() = runTest {
        val store = store(maxEntries = 3)
        for (id in 1L..5L) store.submit(pending(id))
        runCurrent()
        assertEquals(listOf(3L, 4L, 5L), store.ids())
    }

    @Test
    fun evictsOldestByCapturedBytesIncludingGrowthOnUpdate() = runTest {
        val store = store(maxTotalBytes = 250)
        store.submit(complete(1, responseBody = bytesBody(100)))
        store.submit(complete(2, responseBody = bytesBody(100)))
        store.submit(pending(3))
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), store.ids())
        store.submit(complete(3, responseBody = bytesBody(60), requestBody = bytesBody(40)))
        runCurrent()
        assertEquals(listOf(2L, 3L), store.ids())
        store.submit(complete(4, responseBody = bytesBody(300)))
        runCurrent()
        assertEquals(listOf(2L, 3L, 4L), store.ids(), "an entry over the byte cap evicts nothing that fits")
        val oversized = assertNotNull(store.get(4)?.responseBody)
        assertEquals(HopiStore.SKIPPED_OVER_STORE_CAP, oversized.skippedReason)
        assertEquals(0, oversized.capturedBytes)
        assertEquals(300L, oversized.totalBytes)
        assertEquals(TransactionState.Complete, store.get(4)?.state)
    }

    @Test
    fun anEntryOverTheByteCapKeepsTheBodyThatFitsAlone() = runTest {
        val store = store(maxTotalBytes = 250)
        store.submit(complete(1, responseBody = bytesBody(50)))
        store.submit(complete(2, responseBody = bytesBody(200), requestBody = bytesBody(150)))
        store.submit(complete(3, responseBody = bytesBody(260), requestBody = bytesBody(300)))
        runCurrent()
        val two = assertNotNull(store.get(2))
        assertEquals(HopiStore.SKIPPED_OVER_STORE_CAP, two.responseBody?.skippedReason)
        assertEquals(150, two.requestBody?.capturedBytes, "the smaller body still fits and is kept")
        val three = assertNotNull(store.get(3))
        assertEquals(HopiStore.SKIPPED_OVER_STORE_CAP, three.requestBody?.skippedReason)
        assertEquals(HopiStore.SKIPPED_OVER_STORE_CAP, three.responseBody?.skippedReason)
        assertEquals(listOf(1L, 2L, 3L), store.ids())
        assertTrue(store.transactions.value.sumOf { it.capturedBytes } <= 250)
    }

    @Test
    fun oneEntryKeepsAtMostThreeQuartersOfTheStoreSoTheHistorySurvivesTheLargestCap() = runTest {
        // The plugin's largest per-body cap is half the store: a POST with both bodies at that cap
        // used to fill the store exactly and evict every other entry. One body at that cap with a
        // small other body is kept whole.
        val store = store(maxTotalBytes = 1600)
        for (id in 1L..5L) store.submit(complete(id, responseBody = bytesBody(100)))
        store.submit(complete(6, responseBody = bytesBody(800), requestBody = bytesBody(800)))
        runCurrent()
        assertEquals((1L..6L).toList(), store.ids())
        val post = assertNotNull(store.get(6))
        assertEquals(800, post.responseBody?.capturedBytes, "on a tie the response is kept")
        assertEquals(HopiStore.SKIPPED_OVER_STORE_CAP, post.requestBody?.skippedReason)
        assertEquals(800L, post.requestBody?.totalBytes)
        store.submit(complete(7, responseBody = bytesBody(800)))
        store.submit(complete(8, responseBody = bytesBody(800)))
        runCurrent()
        assertEquals(listOf(7L, 8L), store.ids(), "two entries at the cap fill the store, as the byte cap says")

        val upload = store(maxTotalBytes = 1600)
        upload.submit(complete(1, responseBody = bytesBody(10), requestBody = bytesBody(800)))
        runCurrent()
        assertEquals(800, upload.get(1)?.requestBody?.capturedBytes, "a body at the cap with a small other one is kept whole")
        assertEquals(10, upload.get(1)?.responseBody?.capturedBytes)
    }

    @Test
    fun anEntryThatCompletesLargeIsEvictedAfterTheOthersEvenWithTheLowestId() = runTest {
        val store = store(maxTotalBytes = 1600)
        store.submit(pending(1))
        for (id in 2L..10L) store.submit(complete(id, responseBody = bytesBody(100)))
        runCurrent()
        store.submit(complete(1, responseBody = bytesBody(800)))
        runCurrent()
        assertEquals(800, store.get(1)?.responseBody?.capturedBytes, "the download just completed stays")
        assertEquals(listOf(1L) + (3L..10L).toList(), store.ids())
        assertTrue(store.transactions.value.sumOf { it.capturedBytes } <= 1600)
        // Its pending snapshot arriving late is still stale, and the next write evicts by id again.
        store.submit(pending(1))
        runCurrent()
        assertEquals(TransactionState.Complete, store.get(1)?.state)
        store.submit(complete(11, responseBody = bytesBody(200)))
        runCurrent()
        assertEquals((3L..11L).toList(), store.ids())
    }

    @Test
    fun terminalWriteWithoutStartedIsInsertedInIdOrder() = runTest {
        val store = store()
        store.submit(pending(1))
        store.submit(pending(3))
        store.submit(complete(2))
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), store.ids())
    }

    @Test
    fun sinceReturnsLaterIdsWithLimit() = runTest {
        val store = store()
        for (id in listOf(1L, 2L, 4L, 7L)) store.submit(pending(id))
        runCurrent()
        assertEquals(listOf(1L, 2L, 4L, 7L), store.since(0).map { it.id })
        assertEquals(listOf(4L, 7L), store.since(2).map { it.id })
        assertEquals(listOf(4L, 7L), store.since(3).map { it.id })
        assertEquals(listOf(4L), store.since(3, limit = 1).map { it.id })
        assertEquals(emptyList(), store.since(7))
    }

    @Test
    fun clearRemovesEverythingAllocatedBeforeIt() = runTest {
        val store = store()
        val a = store.nextId()
        val b = store.nextId()
        store.submit(complete(a))
        store.submit(pending(b))
        runCurrent()
        store.clear()
        runCurrent()
        assertEquals(emptyList(), store.ids())
        store.submit(complete(b))
        val c = store.nextId()
        store.submit(pending(c))
        runCurrent()
        assertEquals(listOf(c), store.ids(), "a completion of a cleared transaction is not stored again")
    }

    @Test
    fun clearSurvivesAFloodOfWrites() = runTest {
        val store = store(capacity = 4)
        repeat(3) { store.submit(pending(store.nextId())) }
        store.clear()
        repeat(10) { store.submit(pending(1)) }
        runCurrent()
        assertEquals(emptyList(), store.ids())
    }

    @Test
    fun producersNeverSuspendUnderFloodAndStoreStaysBounded() = runTest {
        val store = store(maxEntries = 500, capacity = 256, terminalCapacity = 1024)
        for (i in 0 until 100_000) {
            assertTrue(store.submit(pending(store.nextId())), "pending writes are always accepted")
        }
        runCurrent()
        assertEquals(256, store.ids().size, "only the newest channel-capacity pending writes survive the flood")
        var accepted = 0
        for (i in 0 until 100_000) {
            if (store.submit(complete(store.nextId(), responseBody = bytesBody(16)))) accepted++
        }
        // The idle consumer takes the first write directly; the channel buffers the next 1024.
        assertEquals(1025, accepted, "terminal writes beyond their channel's capacity are rejected, not silently dropped")
        runCurrent()
        assertEquals(100_000L - 1025, store.droppedTerminalWrites.value)
        val ids = store.ids()
        assertEquals(500, ids.size)
        assertEquals(100_000L + 1025, ids.last(), "every accepted terminal write was applied")
        assertTrue(ids.zipWithNext().all { (x, y) -> x < y })
    }

    @Test
    fun terminalWritesSurviveAFloodOfPendingWrites() = runTest {
        val store = store(capacity = 4)
        for (id in 1L..50L) {
            store.submit(pending(id))
            store.submit(complete(id))
        }
        runCurrent()
        assertEquals((1L..50L).toList(), store.ids())
        assertTrue(store.transactions.value.all { it.state == TransactionState.Complete })
        assertEquals(0L, store.droppedTerminalWrites.value)
    }

    @Test
    fun aPendingSnapshotArrivingAfterItsTerminalOneIsIgnored() = runTest {
        val store = store(maxEntries = 2)
        val seen = mutableListOf<TransactionEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { store.events.collect { seen += it } }
        store.submit(complete(1))
        runCurrent()
        store.submit(pending(1))
        runCurrent()
        assertEquals(TransactionState.Complete, store.get(1)?.state)
        store.submit(complete(2))
        store.submit(complete(3))
        runCurrent()
        store.submit(pending(1))
        runCurrent()
        assertEquals(listOf(2L, 3L), store.ids(), "a stale pending write does not resurrect an evicted entry")
        assertEquals(3, seen.size)
        assertTrue(seen.all { it is TransactionEvent.Completed })
    }

    @Test
    fun emitsStartedUpdatedCompletedAfterPublishing() = runTest {
        val store = store()
        val seen = mutableListOf<TransactionEvent>()
        val visibleOnEvent = mutableListOf<Boolean>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.events.collect {
                seen += it
                visibleOnEvent += store.get(it.transaction.id) != null
            }
        }
        store.submit(pending(1))
        runCurrent()
        store.submit(pending(1, url = "https://changed"))
        runCurrent()
        store.submit(complete(1))
        runCurrent()
        assertIs<TransactionEvent.Started>(seen[0])
        assertIs<TransactionEvent.Updated>(seen[1])
        assertIs<TransactionEvent.Completed>(seen[2])
        assertEquals(3, seen.size)
        assertEquals(listOf(true, true, true), visibleOnEvent)
        assertSame(seen[2].transaction, store.get(1))
    }

    @Test
    fun failedTransactionsEmitCompleted() = runTest {
        val store = store()
        val seen = mutableListOf<TransactionEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { store.events.collect { seen += it } }
        store.submit(pending(1).failed("java.io.IOException: boom", 12))
        runCurrent()
        val event = assertIs<TransactionEvent.Completed>(seen.single())
        assertEquals(TransactionState.Failed, event.transaction.state)
    }

    @Test
    fun closeRejectsLaterWrites() = runTest {
        val store = store()
        store.close()
        assertEquals(false, store.submit(pending(1)))
    }

    @Test
    fun defaultStoreIsCreatedOnceWithFirstCaps() {
        val first = HopiStore.configureDefault(maxEntries = 42)
        val second = HopiStore.configureDefault(maxEntries = 7)
        assertSame(first, second)
        assertSame(first, HopiStore.default)
        assertEquals(42, HopiStore.default.maxEntries)
    }

    @Test
    fun outwardRedactionIsTheUnionOfEveryRegistration() = runTest {
        val store = store()
        assertSame(Redactor.Default, store.redactor)
        store.includeRedaction(Redactor.Disabled)
        assertSame(Redactor.Disabled, store.redactor)
        store.includeRedaction(Redactor.create(extraHeaders = listOf("X-Api-Key")))
        store.includeRedaction(Redactor.Disabled)
        assertTrue(store.redactor.isRedactedHeader("x-api-key"))
        assertTrue(store.redactor.isRedactedHeader("Authorization"))
    }

    @Test
    fun readsHonorAClearBeforeTheConsumerAppliesIt() = runTest {
        val store = store()
        for (id in 1L..3L) store.submit(complete(store.nextId()))
        runCurrent()
        store.clear()
        // No runCurrent(): the consumer has not pruned the buffer yet.
        assertEquals(3, store.transactions.value.size)
        assertNull(store.get(2))
        assertTrue(store.since(0).isEmpty())
        val next = store.nextId()
        store.submit(complete(next))
        runCurrent()
        assertEquals(listOf(next), store.since(0).map { it.id })
        assertNotNull(store.get(next))
    }

    @Test
    fun eventListenersSeeEveryEventInOrderEvenWhenEventsCollectorsWouldDrop() = runTest {
        val store = HopiStore(scope = backgroundScope, eventBufferCapacity = 1)
        val seen = mutableListOf<Long>()
        val remove = store.addEventListener { if (it is TransactionEvent.Completed) seen += it.transaction.id }
        for (n in 1..50) store.submit(complete(store.nextId()))
        runCurrent()
        assertEquals((1L..50L).toList(), seen)
        remove()
        store.submit(complete(store.nextId()))
        runCurrent()
        assertEquals(50, seen.size)
    }
}
