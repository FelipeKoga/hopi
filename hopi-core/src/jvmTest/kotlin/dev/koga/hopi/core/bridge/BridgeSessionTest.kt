package dev.koga.hopi.core.bridge

import dev.koga.hopi.core.complete
import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.json.parseFlatJsonObject
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.pending
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.store.HopiStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class BridgeSessionTest {
    private val storeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val storeScope = CoroutineScope(SupervisorJob() + storeExecutor.asCoroutineDispatcher())
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = mutableListOf<Thread>()

    @AfterTest
    fun tearDown() {
        sessionScope.cancel()
        storeScope.cancel()
        storeExecutor.shutdownNow()
    }

    private fun store(maxEntries: Int = 10_000, terminalCapacity: Int = HopiStore.DEFAULT_TERMINAL_CHANNEL_CAPACITY) =
        HopiStore(maxEntries, 1L shl 30, storeScope, terminalChannelCapacity = terminalCapacity)

    private fun serve(
        store: HopiStore,
        connection: MemoryConnection = MemoryConnection(),
        token: BridgeToken? = null,
        liveCapacity: Int = BridgeSession.liveCapacityFor(store),
        info: BridgeInfo = BridgeInfo("dev.koga.sample", "jvm"),
    ): MemoryConnection {
        sessions += thread(name = "test-session") {
            BridgeSession(connection, store, info, token, sessionScope, liveCapacity).run()
        }
        return connection
    }

    /** Waits until the store's consumer finished its current pass, listeners included. */
    private fun awaitStoreIdle() = pauseStore().countDown()

    /** Blocks the store's consumer until the returned gate opens, so writes pile up deterministically. */
    private fun pauseStore(): CountDownLatch {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        storeExecutor.execute {
            entered.countDown()
            gate.await()
        }
        entered.await()
        return gate
    }

    private fun HopiStore.awaitIds(vararg ids: Long) = runBlocking {
        withTimeout(10_000) { transactions.first { list -> list.map { it.id } == ids.toList() } }
    }

    private fun HopiStore.awaitDropped(count: Long) = runBlocking {
        withTimeout(10_000) { droppedTerminalWrites.first { it == count } }
    }

    private fun fields(line: String): Map<String, Any?> = assertNotNull(parseFlatJsonObject(line, Int.MAX_VALUE), line)

    private fun assertError(code: BridgeError, line: String) {
        val f = fields(line)
        assertEquals("error", f["type"], line)
        assertEquals(code.name, f["code"], line)
        assertTrue((f["message"] as String).isNotEmpty(), line)
    }

    private fun MemoryConnection.request(line: String): String {
        send(line)
        return nextLine()
    }

    @Test
    fun helloReportsAppPlatformCountAndDroppedTerminalWrites() {
        val store = store(terminalCapacity = 1)
        val gate = pauseStore()
        // The first write fills the lane (the consumer waits on its doorbell, not on the lane), three are dropped.
        for (id in 1L..4L) store.submit(complete(store.nextId()))
        gate.countDown()
        store.awaitDropped(3)
        store.awaitIds(1)
        val f = fields(serve(store).request("{\"cmd\":\"hello\"}"))
        assertEquals(
            mapOf("type" to "hello", "protocol" to 1L, "app" to "dev.koga.sample", "platform" to "jvm", "count" to 1L, "dropped" to 3L, "rules" to true),
            f,
        )
    }

    @Test
    fun listSendsRedactedSummariesFromSinceUpToLimitThenEnd() {
        val store = store()
        store.includeRedaction(Redactor.create(queryParameters = listOf("token")))
        for (id in 1L..3L) store.submit(complete(store.nextId()).copy(url = "https://api.example.com/a?token=secret&page=$id"))
        store.awaitIds(1, 2, 3)
        val client = serve(store)

        client.send("{\"cmd\":\"list\"}")
        val all = List(3) { fields(client.nextLine()) }
        assertEquals(listOf(1L, 2L, 3L), all.map { it["id"] })
        assertEquals("transaction", all[0]["type"])
        assertEquals("https://api.example.com/a?token=<redacted>&page=1", all[0]["url"])
        assertEquals(mapOf("type" to "end"), fields(client.nextLine()))

        client.send("{\"cmd\":\"list\",\"since\":1,\"limit\":1}")
        assertEquals(2L, fields(client.nextLine())["id"])
        assertEquals(mapOf("type" to "end"), fields(client.nextLine()))
    }

    @Test
    fun aListAndGetSentWithHelloSeeARequestThatEndedInTheStoresBatchWindow() {
        // The store's own thread, dozing after a pass: an end with no viewer only marks the work.
        val store = HopiStore(batchWindowMillis = 60_000)
        try {
            store.submit(complete(store.nextId()))
            val deadline = System.nanoTime() + 10_000_000_000
            while (store.get(1) == null || !store.consumerDozing) {
                check(System.nanoTime() < deadline) { "the first request was not applied" }
                Thread.onSpinWait()
            }
            val id = store.nextId()
            // An end whose record takes a while to build: a reply that did not wait for the pass beats it.
            store.submitDeferred(terminal = true) {
                Thread.sleep(100)
                complete(id)
            }
            assertNull(store.get(id))
            val client = serve(store)
            // As BridgeClient sends it: hello and the request in one write, right after connecting.
            client.sendBytes("{\"cmd\":\"hello\"}\n{\"cmd\":\"list\"}\n{\"cmd\":\"get\",\"id\":$id}\n".encodeToByteArray())
            assertEquals("hello", fields(client.nextLine())["type"])
            assertEquals(listOf(1L, id), List(2) { fields(client.nextLine())["id"] })
            assertEquals(mapOf("type" to "end"), fields(client.nextLine()))
            val detail = client.nextLine()
            assertTrue(detail.startsWith("{\"type\":\"transaction\",\"id\":$id,"), detail)
        } finally {
            store.close()
        }
    }

    @Test
    fun getSendsRedactedDetailOrNotFound() {
        val store = store()
        val id = store.nextId()
        store.submit(
            pending(id, method = "POST", headers = listOf("Authorization" to "Bearer secret", "X-Trace" to "t1"))
                .completed(201, listOf("Set-Cookie" to "s=1"), null, 12, "HTTP/1.1"),
        )
        store.awaitIds(id)
        val client = serve(store)

        val detail = client.request("{\"cmd\":\"get\",\"id\":$id}")
        assertTrue(detail.startsWith("{\"type\":\"transaction\",\"id\":$id,"), detail)
        assertTrue("[\"Authorization\",\"<redacted>\"]" in detail, detail)
        assertTrue("[\"Set-Cookie\",\"<redacted>\"]" in detail, detail)
        assertTrue("[\"X-Trace\",\"t1\"]" in detail, detail)
        assertFalse("secret" in detail, detail)

        assertError(BridgeError.NOT_FOUND, client.request("{\"cmd\":\"get\",\"id\":99}"))
    }

    @Test
    fun getOfALargeBodyArrivesWholeAsOneLineAmongOtherReplies() {
        val store = store()
        val id = store.nextId()
        // Larger than many writer slices, with escapes and multi-byte chars across slice edges.
        val text = buildString { while (length < 300_000) append("{\"k\":\"日本😀\\t\"},") }
        val body = CapturedBody(text.encodeToByteArray(), null, false, "application/json", null)
        val binary = CapturedBody(ByteArray(200_000) { (it % 13).toByte() }, null, false, "application/octet-stream", null)
        val transaction = pending(id, body = binary).completed(200, emptyList(), body, 12, "HTTP/1.1")
        store.submit(transaction)
        store.awaitIds(id)
        val client = serve(store)
        client.send("{\"cmd\":\"get\",\"id\":$id}")
        client.send("{\"cmd\":\"hello\"}")
        assertEquals(TransactionJson.bridgeDetail(transaction, store.redactor), client.nextLine())
        assertEquals("hello", fields(client.nextLine())["type"])
    }

    @Test
    fun clearAnswersOkAndLaterReadsNoLongerSeeTheTransactions() {
        val store = store()
        for (id in 1L..2L) store.submit(complete(store.nextId()))
        store.awaitIds(1, 2)
        val client = serve(store)
        // The consumer applies a clear later; the replies right after it must already reflect it.
        val gate = pauseStore()
        // `through`: the last id it covers, which a host viewer clears up to.
        assertEquals(mapOf("type" to "ok", "through" to 2L), fields(client.request("{\"cmd\":\"clear\"}")))
        assertEquals(mapOf("type" to "end"), fields(client.request("{\"cmd\":\"list\"}")))
        assertError(BridgeError.NOT_FOUND, client.request("{\"cmd\":\"get\",\"id\":1}"))
        assertEquals(0L, fields(client.request("{\"cmd\":\"hello\"}"))["count"])
        gate.countDown()
    }

    @Test
    fun malformedRequestsAreBadRequestAndTheSessionContinues() {
        val client = serve(store())
        for (line in listOf("hello", "{\"cmd\":\"nope\"}", "{\"cmd\":\"list\",\"limit\":0}", "{\"cmd\":\"get\"}", "{\"cmd\":\"get\",\"id\":0}", "[1]")) {
            assertError(BridgeError.BAD_REQUEST, client.request(line))
        }
        client.send("")
        assertEquals("hello", fields(client.request("{\"cmd\":\"hello\"}"))["type"])
    }

    @Test
    fun aRequestOverTheCapIsBadRequestAndTheNextLineIsServed() {
        val client = serve(store())
        val atCap = "{\"cmd\":\"hello\"}".padEnd(BridgeCommand.MAX_LINE_LENGTH, ' ')
        assertEquals("hello", fields(client.request(atCap))["type"])
        assertError(BridgeError.BAD_REQUEST, client.request("$atCap "))
        // A peer streaming megabytes without a newline: the reader discards them as they come.
        val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
        repeat(64) { client.sendBytes(chunk) }
        client.sendBytes("\n".encodeToByteArray())
        assertError(BridgeError.BAD_REQUEST, client.nextLine())
        assertEquals("hello", fields(client.request("{\"cmd\":\"hello\"}"))["type"])
    }

    @Test
    fun theRightTokenOpensTheSession() {
        val token = BridgeToken.generate()
        val line = StringBuilder().also(token::writeTo).toString()
        val client = serve(store(), token = token)
        client.send(line)
        assertEquals("hello", fields(client.request("{\"cmd\":\"hello\"}"))["type"])
    }

    @Test
    fun aWrongOrMissingTokenClosesTheConnectionBeforeAnyByte() {
        val token = BridgeToken.generate()
        val right = StringBuilder().also(token::writeTo).toString()
        val attempts: List<(MemoryConnection) -> Unit> = listOf(
            { it.send(right.dropLast(1) + (if (right.last() == '0') '1' else '0')) },
            { it.send(right + "0") },
            { it.send("{\"cmd\":\"hello\"}") },
            { it.send("") },
            { it.send("x".repeat(BridgeCommand.MAX_LINE_LENGTH + 1)) },
            { it.endInput() },
        )
        for (attempt in attempts) {
            val client = MemoryConnection()
            attempt(client)
            // A command right behind a bad token must not be answered either.
            client.send("{\"cmd\":\"hello\"}")
            serve(store(), client, token)
            client.awaitClosed()
            client.awaitShutdown()
            assertEquals(0L, client.bytesWritten)
            assertEquals(1, client.closeCount)
        }
    }

    @Test
    fun repliesQueuedBeforeTheClientHalfClosesAreStillSent() {
        val store = store()
        store.submit(complete(store.nextId()))
        store.awaitIds(1)
        val client = MemoryConnection()
        client.send("{\"cmd\":\"list\"}")
        client.endInput()
        serve(store, client)
        assertEquals(1L, fields(client.nextLine())["id"])
        assertEquals(mapOf("type" to "end"), fields(client.nextLine()))
        assertNull(client.nextLineOrEnd())
        assertEquals(1, client.closeCount)
    }

    /** A live summary is a body-free copy, yet still says how much a `get` would move for a cut body. */
    @Test
    fun liveSummariesOfTruncatedAndSkippedBodiesSayWhatTheAppHolds() {
        val store = store()
        store.submit(complete(store.nextId()))
        store.awaitIds(1)
        val client = serve(store)
        client.send("{\"cmd\":\"tail\"}")
        // The backlog line: the tail listens from now on, so what follows is live.
        assertEquals(1L, fields(client.nextLine())["id"])
        val cut = CapturedBody(ByteArray(256) { 'a'.code.toByte() }, 50_000_000, truncated = true, "text/plain", null)
        store.submit(complete(store.nextId(), responseBody = cut))
        val truncated = fields(client.nextLine())
        assertEquals(50_000_000L, truncated["resBytes"])
        assertEquals(256L, truncated["resHeld"])
        assertFalse("reqHeld" in truncated, "sent only where it differs from the size")

        store.submit(complete(store.nextId(), responseBody = CapturedBody.skipped("binary content type", "video/mp4", 100_000_000)))
        val skipped = fields(client.nextLine())
        assertEquals(100_000_000L, skipped["resBytes"])
        assertEquals(0L, skipped["resHeld"])

        store.submit(complete(store.nextId(), responseBody = CapturedBody("ok".encodeToByteArray(), 2, false, null, null)))
        assertFalse("resHeld" in fields(client.nextLine()))
    }

    @Test
    fun tailSendsTheBacklogSinceThenLiveEvents() {
        val store = store()
        for (id in 1L..3L) store.submit(complete(store.nextId()))
        store.awaitIds(1, 2, 3)
        val client = serve(store)
        client.send("{\"cmd\":\"tail\",\"since\":1}")
        assertEquals(listOf(2L, 3L), List(2) { fields(client.nextLine())["id"] })

        val id = store.nextId()
        store.submit(pending(id))
        assertEquals(mapOf("id" to id, "state" to "pending"), fields(client.nextLine()).filterKeys { it == "id" || it == "state" })
        store.submit(complete(id))
        assertEquals(mapOf("id" to id, "state" to "complete"), fields(client.nextLine()).filterKeys { it == "id" || it == "state" })

        // Later input is not answered; the stream keeps going.
        client.send("{\"cmd\":\"hello\"}")
        store.submit(complete(store.nextId()))
        assertEquals(5L, fields(client.nextLine())["id"])
    }

    @Test
    fun tailNeitherMissesNorDuplicatesAcrossTheBacklogSeam() {
        repeat(20) { round ->
            val store = store()
            val total = 400
            val startTail = CountDownLatch(1)
            val producer = thread(name = "test-producer") {
                for (n in 1..total) {
                    val id = store.nextId()
                    // Headers and bodies, so the dedup compares body-free copies, not instances.
                    val request = CapturedBody("{\"n\":$n}".encodeToByteArray(), null, false, "application/json", null)
                    assertTrue(store.submit(pending(id, method = "POST", headers = listOf("Accept" to "*/*"), body = request)))
                    assertTrue(store.submit(complete(id, responseBody = request, requestBody = request)))
                    if (n == total / 2) startTail.countDown()
                }
            }
            startTail.await()
            val client = serve(store, liveCapacity = 1 shl 16)
            client.send("{\"cmd\":\"tail\",\"since\":0}")
            producer.join()

            val states = HashMap<Long, MutableList<String>>()
            var terminal = 0
            while (terminal < total) {
                val f = fields(client.nextLine())
                val state = f["state"] as String
                states.getOrPut(f["id"] as Long) { mutableListOf() } += state
                if (state != "pending") terminal++
            }
            assertEquals((1L..total).toSet(), states.keys, "round $round")
            for ((id, seen) in states) {
                assertTrue(seen == listOf("complete") || seen == listOf("pending", "complete"), "round $round, id $id: $seen")
            }
            client.endInput()
            client.awaitClosed()
        }
    }

    @Test
    fun aQueuedLiveEventHoldsNoBodyBytesAndWritesTheSameSummary() {
        val big = CapturedBody(ByteArray(1 shl 20) { 'a'.code.toByte() }, null, false, "application/json", null)
        val cut = CapturedBody(ByteArray(1000), 5_000_000, true, "text/plain", null)
        val transaction = pending(7, headers = listOf("Authorization" to "Bearer x", "Accept" to "*/*"), body = big)
            .completed(200, listOf("Content-Type" to "application/json"), cut, 12, "HTTP/2")
        val summary = with(BridgeSession) { transaction.liveSummary() }
        assertEquals(0, summary.requestBody?.bytes?.size)
        assertEquals(0, summary.responseBody?.bytes?.size)
        assertTrue(summary.requestHeaders.isEmpty() && summary.responseHeaders.isEmpty())
        assertEquals(TransactionJson.bridgeSummary(transaction, Redactor.Default), TransactionJson.bridgeSummary(summary, Redactor.Default))
        assertEquals(summary, with(BridgeSession) { summary.liveSummary() })
        val bare = complete(8)
        assertTrue(with(BridgeSession) { bare.liveSummary() }.responseHeaders.isEmpty())
    }

    @Test
    fun tailSendsANoticeWheneverTheDropCountGrows() {
        val store = store(terminalCapacity = 1)
        val client = serve(store)
        client.send("{\"cmd\":\"tail\"}")
        store.submit(complete(store.nextId()))
        assertEquals(1L, fields(client.nextLine())["id"])

        val gate = pauseStore()
        for (n in 1..4) store.submit(complete(store.nextId()))
        gate.countDown()
        val lines = List(2) { fields(client.nextLine()) }
        assertTrue(mapOf("type" to "notice", "dropped" to 3L) in lines, lines.toString())
        assertEquals(listOf(2L), lines.mapNotNull { it["id"] }, lines.toString())
    }

    @Test
    fun aClientThatStopsReadingIsDisconnectedWithoutBlockingTheStoreOrOtherClients() {
        val store = store()
        val stuck = serve(store, MemoryConnection(blockWritesAfterLines = 1), liveCapacity = 8)
        val healthy = serve(store, liveCapacity = 1 shl 16)
        stuck.send("{\"cmd\":\"tail\"}")
        healthy.send("{\"cmd\":\"tail\"}")
        store.submit(complete(store.nextId()))
        assertEquals(1L, fields(stuck.nextLine())["id"])
        assertEquals(1L, fields(healthy.nextLine())["id"])

        val total = 1000
        for (n in 2..total) assertTrue(store.submit(complete(store.nextId())))
        stuck.awaitShutdown()
        stuck.awaitClosed()
        // The stuck writer was parked in a write: the error line did not fit, so it was not sent.
        assertNull(stuck.nextLineOrEnd())
        for (id in 2L..total) assertEquals(id, fields(healthy.nextLine())["id"])
        runBlocking { withTimeout(10_000) { store.transactions.first { it.size == total } } }
    }

    @Test
    fun aSlowClientGetsSlowClientAsItsLastLineWhenTheLineFits() {
        var delivered = 0
        repeat(20) {
            val store = store()
            val client = serve(store, liveCapacity = 4)
            client.send("{\"cmd\":\"tail\"}")
            store.submit(complete(store.nextId()))
            assertEquals(1L, fields(client.nextLine())["id"])
            // One consumer batch overflows the queue before the idle writer wakes up, but stays
            // within the further queue's worth of events after which the last write is cut.
            val gate = pauseStore()
            for (n in 1..8) store.submit(complete(store.nextId()))
            gate.countDown()
            val lines = generateSequence { client.nextLineOrEnd() }.toList()
            client.awaitShutdown()
            val errors = lines.map(::fields).withIndex().filter { it.value["type"] == "error" }
            assertTrue(errors.size <= 1, lines.toString())
            errors.singleOrNull()?.let { (index, error) ->
                assertEquals(lines.lastIndex, index, "nothing follows the error")
                assertEquals(BridgeError.SLOW_CLIENT.name, error["code"])
                delivered++
            }
            assertTrue(lines.size < 8, "the client was cut off: ${lines.size}")
        }
        assertTrue(delivered > 0, "SLOW_CLIENT never reached a client whose writer was idle")
    }

    @Test
    fun helloCarriesTheProofOnlyWhenTheTransportHasOne() {
        val proof = "ab".repeat(32)
        val android = fields(serve(store(), info = BridgeInfo("dev.koga.sample", "android", proof)).request("{\"cmd\":\"hello\"}"))
        assertEquals(proof, android["proof"])
        assertFalse("proof" in fields(serve(store()).request("{\"cmd\":\"hello\"}")))
    }

    @Test
    fun aBurstLargerThanTheOldQueueReachesAFastClientWhole() {
        val store = store()
        val client = serve(store)
        client.send("{\"cmd\":\"tail\"}")
        store.submit(complete(store.nextId()))
        assertEquals(1L, fields(client.nextLine())["id"])
        // Many requests finishing while the consumer is busy reach the listener in one batch,
        // far faster than any writer sends them; that alone is no reason to drop the client.
        val burst = 2000
        val gate = pauseStore()
        for (n in 1..burst) assertTrue(store.submit(complete(store.nextId())))
        gate.countDown()
        for (id in 2L..burst + 1) assertEquals(id, fields(client.nextLine())["id"])
        store.submit(complete(store.nextId()))
        assertEquals(burst + 2L, fields(client.nextLine())["id"], "the client is still connected")
    }

    @Test
    fun slowClientIsSentAfterAWriteInProgressAndNamesASafeResumePoint() {
        val store = store()
        val client = serve(store, MemoryConnection(blockWritesAfterLines = 2), liveCapacity = 4)
        client.send("{\"cmd\":\"tail\"}")
        store.submit(complete(store.nextId()))
        assertEquals(1L, fields(client.nextLine())["id"])
        val pendingId = store.nextId()
        store.submit(pending(pendingId))
        assertEquals(mapOf("id" to pendingId, "state" to "pending"), fields(client.nextLine()).filterKeys { it == "id" || it == "state" })
        // A third line is being written (parked, not failed) when the queue overflows.
        store.submit(complete(store.nextId()))
        client.awaitParkedWrite()
        val gate = pauseStore()
        for (n in 1..6) store.submit(complete(store.nextId()))
        gate.countDown()
        awaitStoreIdle()
        client.releaseWrites()

        assertEquals(3L, fields(client.nextLine())["id"])
        val error = fields(client.nextLine())
        assertEquals("error", error["type"])
        assertEquals(BridgeError.SLOW_CLIENT.name, error["code"])
        // id 2 was only ever seen pending, so resuming above it would lose its completion.
        assertEquals(pendingId - 1, error["since"])
        assertTrue("since ${pendingId - 1}" in error["message"] as String, error.toString())
        assertNull(client.nextLineOrEnd())
    }

    @Test
    fun tailStartsWithANoticeOfDropsMadeBeforeTheClientConnected() {
        val store = store(terminalCapacity = 1)
        val gate = pauseStore()
        for (n in 1..4) store.submit(complete(store.nextId()))
        gate.countDown()
        store.awaitDropped(3)
        store.awaitIds(1)
        val client = serve(store)
        client.send("{\"cmd\":\"tail\"}")
        assertEquals(mapOf("type" to "notice", "dropped" to 3L), fields(client.nextLine()))
        assertEquals(1L, fields(client.nextLine())["id"])
    }

    @Test
    fun aClientThatHalfClosesAfterTailStillGetsTheWholeBacklog() {
        val store = store()
        val total = 30
        for (n in 1..total) store.submit(complete(store.nextId()))
        store.awaitIds(*LongArray(total) { it + 1L })
        val client = MemoryConnection(blockWritesAfterLines = 1)
        client.send("{\"cmd\":\"tail\"}")
        client.endInput()
        serve(store, client)
        // The reader saw the half-close and finished while all but one line were still queued.
        sessions.last().join(10_000)
        assertFalse(sessions.last().isAlive)
        client.releaseWrites()
        assertEquals((1L..total).toList(), List(total) { fields(client.nextLine())["id"] })
        assertNull(client.nextLineOrEnd())
        assertEquals(1, client.closeCount)
    }
}
