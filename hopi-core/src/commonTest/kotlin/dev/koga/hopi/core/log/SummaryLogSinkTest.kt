package dev.koga.hopi.core.log

import dev.koga.hopi.core.complete
import dev.koga.hopi.core.json.parseFlatJsonObject
import dev.koga.hopi.core.pending
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.store.HopiStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SummaryLogSinkTest {
    @Test
    fun emitsOneRedactedLinePerTerminalTransaction() = runTest {
        val store = HopiStore(scope = backgroundScope)
        val lines = mutableListOf<String>()
        store.includeRedaction(Redactor.create(queryParameters = listOf("page")))
        launchSummaryLogSink(store, scope = backgroundScope) { lines += it }
        store.submit(pending(1))
        store.submit(pending(2))
        runCurrent()
        assertEquals(emptyList(), lines)
        store.submit(complete(1))
        store.submit(pending(2).failed("boom", 5))
        runCurrent()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("{\"hopi\":1,\"id\":1,") && "page=<redacted>" in lines[0], lines[0])
        assertTrue("\"state\":\"failed\"" in lines[1], lines[1])
    }

    @Test
    fun announcesTerminalWritesTheStoreHadToDrop() = runTest {
        val store = HopiStore(scope = backgroundScope, terminalChannelCapacity = 2)
        val lines = mutableListOf<String>()
        launchSummaryLogSink(store, scope = backgroundScope) { lines += it }
        for (id in 1L..3L) store.submit(complete(id))
        runCurrent()
        assertEquals(3, lines.size, lines.toString())
        assertEquals(2, lines.count { it.startsWith("{\"hopi\":1,\"id\":") })
        assertEquals(droppedNotice(1), lines.single { "notice" in it })
        assertEquals(mapOf("hopi" to 1L, "notice" to "transactions dropped under load; they stay pending and have no line", "dropped" to 1L), parseFlatJsonObject(droppedNotice(1)))
    }

    @Test
    fun linesFitTheGivenBudget() = runTest {
        val store = HopiStore(scope = backgroundScope)
        val lines = mutableListOf<String>()
        launchSummaryLogSink(store, scope = backgroundScope, maxLineBytes = 1000) { lines += it }
        store.submit(pending(1, url = "https://h/" + "a".repeat(1500)).failed("boom", 5))
        runCurrent()
        assertTrue(lines.single().encodeToByteArray().size <= 1000)
    }
}
