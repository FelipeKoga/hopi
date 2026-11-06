package dev.koga.hopi.connect

import dev.koga.hopi.core.capture.FullBodies
import dev.koga.hopi.core.bridge.BridgeSession
import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.store.HopiStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private const val APP = "dev.koga.sample"

class RemoteSessionTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val bridges = mutableListOf<JvmAgentBridge>()
    private val sessions = mutableListOf<RemoteSession>()
    private val root: Path = Files.createTempDirectory("hopi-remote-test")
    private val directory: Path = root.resolve("app")

    @AfterTest
    fun tearDown() {
        sessions.forEach { it.close() }
        bridges.forEach { it.close() }
        scopes.forEach { it.cancel() }
        root.toFile().deleteRecursively()
    }

    /** The app's side: a store of its own (large enough for the giant body) and its JVM bridge. */
    private fun startApp(): HopiStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val store = HopiStore(maxEntries = 100, maxTotalBytes = 128L * 1024 * 1024, scope = scope)
        bridges += JvmAgentBridge.start(store, directory, app = APP)
        return store
    }

    private fun endpoint(): BridgeEndpoint {
        val (port, token) = readBridgeFile(directory.resolve(JvmAgentBridge.BRIDGE_FILE_NAME), missing = "no bridge file")
        return BridgeEndpoint(port, token, Platform.Jvm, APP, unreachableMessage = "nothing serves $APP")
    }

    private fun session(open: () -> BridgeEndpoint = ::endpoint) = RemoteSession(open).also { sessions += it }

    private fun HopiStore.add(tx: (Long) -> HttpTransaction): HttpTransaction {
        val transaction = tx(nextId())
        assertTrue(submit(transaction))
        return transaction
    }

    private fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = runBlocking { withTimeout(15_000) { first(predicate) } }

    private fun RemoteSession.awaitLocal(predicate: (List<HttpTransaction>) -> Boolean): List<HttpTransaction> =
        runBlocking { withTimeout(15_000) { store.value.transactions.first(predicate) } }

    private fun RemoteSession.connected(): RemoteSession {
        connect()
        assertEquals(RemoteState.Connected(APP, "jvm"), state.value)
        return this
    }

    @Test
    fun summariesStreamAndOnlyTheSelectedBodyIsFetched() {
        val app = startApp()
        val sent = (1..3).map { n -> app.add { completeTx(it, url = "https://api.example.com/$n", responseBody = textBody("""{"n":$n}""")) } }
        val session = session().connected()
        session.awaitLocal { it.size == 3 }
        assertTrue(session.store.value.transactions.value.all { it.responseBody?.isNotFetched == true && it.requestHeaders.isEmpty() })

        session.select(2)
        val local = session.awaitLocal { list -> list.any { it.id == 2L && it.responseBody?.isNotFetched == false } }
        assertEquals(sent[1], local.single { it.id == 2L })
        for (id in listOf(1L, 3L)) assertTrue(local.single { it.id == id }.responseBody!!.isNotFetched, "id $id was fetched")

        // A transaction made while connected arrives live, as a summary.
        app.add { completeTx(it, url = "https://api.example.com/live") }
        val live = session.awaitLocal { it.size == 4 }.last()
        assertEquals("https://api.example.com/live", live.url)
        assertTrue(live.responseBody!!.isNotFetched)
    }

    @Test
    fun aPendingSelectionIsFetchedAgainWhenItCompletes() {
        val app = startApp()
        val started = app.add { pendingTx(it, method = "POST", body = textBody("""{"q":1}""")) }
        val session = session().connected()
        session.select(started.id)
        session.awaitLocal { list -> list.any { it.requestBody?.isNotFetched == false } }

        assertTrue(app.submit(started.completed(201, listOf("X" to "y"), textBody("done", "text/plain"), 50, "HTTP/2")))
        val done = session.awaitLocal { list -> list.any { it.responseBody?.isNotFetched == false } }.single()
        assertEquals(201, done.responseCode)
        assertEquals("done", done.responseBody!!.bytes.decodeToString())
        assertEquals(listOf("X" to "y"), done.responseHeaders)
    }

    @Test
    fun aBodyOver16MbCrossesOnlyWhenOpened() {
        val app = startApp()
        val size = 17 * 1024 * 1024
        val giant = buildString(size + 64) {
            append('[')
            while (length < size) append("""{"id":$length,"name":"user"},""")
            append("{}]")
        }
        val sent = app.add { completeTx(it, url = "https://api.example.com/giant", responseBody = textBody(giant)) }
        val session = session().connected()
        val summary = session.awaitLocal { it.size == 1 }.single()
        assertEquals(giant.length.toLong(), summary.responseBody!!.totalBytes)
        assertEquals(0, summary.capturedBytes)

        session.select(sent.id)
        val fetched = session.awaitLocal { list -> list.single().responseBody?.isNotFetched == false }.single()
        assertContentEquals(sent.responseBody!!.bytes, fetched.responseBody!!.bytes)
        assertEquals(giant.length.toLong(), fetched.capturedBytes)
    }

    @Test
    fun reconnectingToARestartedAppStartsAFreshStore() {
        startApp().add { completeTx(it, url = "https://api.example.com/before") }
        val session = session().connected()
        session.awaitLocal { it.size == 1 }
        val before = session.store.value

        // The app process ends: its bridge closes both connections and deletes bridge.json.
        bridges.removeAt(0).close()
        session.state.await { it is RemoteState.Disconnected }

        // A new process with a store of its own: its ids start at 1 again.
        startApp().add { completeTx(it, url = "https://api.example.com/after") }
        session.connected()
        assertNotSame(before, session.store.value)
        val after = session.awaitLocal { it.size == 1 }.single()
        assertEquals(1, after.id)
        assertEquals("https://api.example.com/after", after.url)
    }

    @Test
    fun reconnectingToTheSameAppKeepsTheStoreAndFetchedBodies() {
        val app = startApp()
        app.add { completeTx(it, url = "https://api.example.com/a") }
        val session = session().connected()
        session.select(1)
        // The tail may not have brought the summary yet: the first list can be empty.
        session.awaitLocal { list -> list.singleOrNull()?.responseBody?.isNotFetched == false }
        val before = session.store.value

        // adb reset, say: the caller connects again to the same process.
        session.connected()
        assertSame(before, session.store.value)
        app.add { completeTx(it, url = "https://api.example.com/b") }
        val list = session.awaitLocal { it.size == 2 }
        assertTrue(list.first().responseBody?.isNotFetched == false, "the fetched body survived the reconnect")
    }

    @Test
    fun clearEmptiesBothSidesAndLaterTransactionsStillArrive() {
        val app = startApp()
        app.add { completeTx(it) }
        app.add { completeTx(it) }
        val session = session().connected()
        session.awaitLocal { it.size == 2 }
        session.clear().get(15, TimeUnit.SECONDS)
        assertEquals(emptyList(), app.since(0))
        session.awaitLocal { it.isEmpty() }
        app.add { completeTx(it, url = "https://api.example.com/next") }
        assertEquals(3, session.awaitLocal { it.size == 1 }.single().id)
    }

    @Test
    fun anUnreachableAppIsReportedNotThrown() {
        val session = session()
        session.connect()
        val state = session.state.value
        assertTrue(state is RemoteState.Disconnected && "no bridge file" in state.message, state.toString())
    }

    @Test
    fun closeEndsBothConnectionsAndTheEndpoint() {
        startApp().add { completeTx(it) }
        var endpointsClosed = 0
        val session = session { endpoint().let { BridgeEndpoint(it.port, it.token, it.platform, it.app, "x") { endpointsClosed++ } } }
        session.connected()
        session.awaitLocal { it.size == 1 }
        session.connected()
        assertEquals(1, endpointsClosed, "a reconnect closes the old endpoint first")
        session.close()
        assertEquals(2, endpointsClosed)
        assertEquals(RemoteState.Disconnected("closed"), session.state.value)
        assertTrue(waitForNoThread("hopi-remote-tail"), "the tail thread ended")
    }

    /** The bridge's live queue overflowed: `SLOW_CLIENT` with `since`, scripted, then a re-tail from exactly there. */
    @Test
    fun slowClientResumesFromTheGivenSinceAndKeepsFetchedBodies() {
        val script = ScriptedBridge()
        val first = completeTx(1, url = "https://api.example.com/one")
        val session = session { script.endpoint() }
        session.connected()
        val tail1 = script.tails.take()
        assertEquals(0, tail1.since)
        tail1.send(TransactionJson.bridgeSummary(first, Redactor.Disabled))
        session.awaitLocal { it.size == 1 }

        session.select(1)
        session.awaitLocal { list -> list.single().responseBody?.isNotFetched == false }
        assertEquals(listOf(1L), script.gets)

        tail1.send(BridgeSession.slowClientError(since = 0))
        val tail2 = script.tails.poll(15, TimeUnit.SECONDS)
        assertNotNull(tail2, "the session tailed again")
        assertEquals(0, tail2.since)
        // The backlog repeats the summary: it must not replace the fetched body.
        tail2.send(TransactionJson.bridgeSummary(first, Redactor.Disabled))
        tail2.send(TransactionJson.bridgeSummary(completeTx(2, url = "https://api.example.com/two"), Redactor.Disabled))
        val list = session.awaitLocal { it.size == 2 }
        assertTrue(list.first().responseBody?.isNotFetched == false)
        assertTrue(session.state.value is RemoteState.Connected)
        assertEquals(listOf(1L), script.gets, "nothing else was fetched")
        script.close()
    }

    @Test
    fun aRowTheAppNoLongerHasStopsLoadingAndIsNotAskedForAgain() {
        val app = startApp()
        val session = session().connected()
        app.add { completeTx(it, url = "https://api.example.com/old") }
        session.awaitLocal { it.size == 1 }
        // The app's store is cleared from elsewhere (another viewer's `hopi clear`, say): no event reaches the tail.
        app.clear()
        session.select(1)
        val gone = session.awaitLocal { list -> list.single().responseBody?.isGone == true }.single()
        assertTrue(gone.requestBody!!.isGone)
        assertEquals("https://api.example.com/old", gone.url, "the summary stays, marked")
        assertEquals(null, session.fetch(1), "fetch still reports it missing")
    }

    @Test
    fun noNotFoundIsResentOnReselectOnceMarkedGone() {
        val script = ScriptedBridge()
        script.onGet = { id -> """{"type":"error","code":"NOT_FOUND","message":"no transaction $id"}""" }
        val session = session { script.endpoint() }.connected()
        script.tails.take().send(TransactionJson.bridgeSummary(completeTx(1), Redactor.Disabled))
        session.awaitLocal { it.size == 1 }
        session.select(1)
        session.awaitLocal { list -> list.single().responseBody?.isGone == true }
        session.select(null)
        session.select(1)
        // A barrier on the request thread: every queued fetch ran before it.
        session.fetch(2)
        assertEquals(listOf(1L, 2L), script.gets)
        assertTrue(session.state.value is RemoteState.Connected, "NOT_FOUND keeps the link")
        script.close()
    }

    private fun notFound(id: Long) = """{"type":"error","code":"NOT_FOUND","message":"no transaction $id"}"""

    /**
     * A row held pending here that the app no longer has (its recorder withdrew it while this viewer
     * was away; evicted or cleared alike): the reconnect asks the app about it with a one-line
     * `list`, no `get`, and ends it as gone, and the resume point moves past it. Its request body was
     * fetched while it was pending, so only its state still says it is open. Selecting it again
     * asks once more and changes nothing, and an end the app records after all still replaces it.
     */
    @Test
    fun aReconnectEndsAPendingRowTheAppNoLongerHas() {
        val script = ScriptedBridge()
        val started = pendingTx(5, method = "POST", body = textBody("{}"))
        script.onGet = { TransactionJson.bridgeDetail(started, Redactor.Disabled) }
        val session = session { script.endpoint() }.connected()
        val tail1 = script.tails.take()
        tail1.send(TransactionJson.bridgeSummary(completeTx(4), Redactor.Disabled))
        tail1.send(TransactionJson.bridgeSummary(started, Redactor.Disabled))
        session.awaitLocal { it.size == 2 }
        session.select(5)
        session.awaitLocal { list -> list.any { it.id == 5L && it.requestBody?.isNotFetched == false } }
        session.select(null)

        // From here the app has nothing above 4: `list` answers no line, `get` NOT_FOUND.
        script.onGet = ::notFound
        session.connected()
        assertEquals(4, script.tails.take().since)
        val gone = session.awaitLocal { list -> list.single { it.id == 5L }.state.isTerminal }.single { it.id == 5L }
        assertEquals(TransactionState.Failed, gone.state)
        assertEquals(CapturedBody.GONE, gone.error)
        assertEquals("{}", gone.requestBody!!.bytes.decodeToString(), "what was fetched stays")
        assertEquals(listOf(4L), script.listed, "the reconnect's one-line list from just below 5")
        assertEquals(listOf(5L), script.gets, "only the selection's get: the reconnect moves no bodies")

        // A new selection asks once more; its NOT_FOUND must not turn the row into the app's end.
        session.select(5)
        assertEquals(null, session.fetch(6), "a barrier on the request thread")
        assertEquals(listOf(5L, 5L, 6L), script.gets)
        session.select(null)

        session.connected()
        val tail3 = script.tails.take()
        assertEquals(5, tail3.since, "nothing holds the resume point below 5 any more")
        // An end the app records after all (a request it evicted or cleared in flight) replaces the row.
        tail3.send(TransactionJson.bridgeSummary(completeTx(5), Redactor.Disabled))
        session.awaitLocal { list -> list.single { it.id == 5L }.state == TransactionState.Complete }
        script.close()
    }

    /** The same after a `SLOW_CLIENT` resume: a withdrawn row's end can be among the events the bridge's queue lost. */
    @Test
    fun aSlowClientResumeEndsAPendingRowTheAppNoLongerHas() {
        val script = ScriptedBridge()
        script.onGet = ::notFound
        val session = session { script.endpoint() }.connected()
        val tail1 = script.tails.take()
        tail1.send(TransactionJson.bridgeSummary(pendingTx(1), Redactor.Disabled))
        session.awaitLocal { it.size == 1 }
        tail1.send(BridgeSession.slowClientError(since = 0))
        assertEquals(0, assertNotNull(script.tails.poll(15, TimeUnit.SECONDS), "the session tailed again").since)
        val gone = session.awaitLocal { list -> list.single().state.isTerminal }.single()
        assertEquals(CapturedBody.GONE, gone.error)
        assertEquals(listOf(0L), script.listed)
        assertEquals(emptyList<Long>(), script.gets)
        script.close()
    }

    /**
     * A resume asks about at most 64 pending rows, the oldest first, and keeps a row the app still
     * has; the next resume asks about the rest.
     */
    @Test
    fun aReconnectAsksAboutTheOldestPendingRowsFirstAndAtMost64() {
        val script = ScriptedBridge()
        script.onGet = ::notFound
        // The app still has 3, still in flight: its list from 2 answers with it.
        script.onList = { since -> if (since == 2L) listOf(TransactionJson.bridgeSummary(pendingTx(3), Redactor.Disabled)) else emptyList() }
        val session = session { script.endpoint() }.connected()
        val tail = script.tails.take()
        for (id in 1L..70L) tail.send(TransactionJson.bridgeSummary(pendingTx(id), Redactor.Disabled))
        session.awaitLocal { it.size == 70 }

        session.connected()
        assertEquals(0, script.tails.take().since)
        // A barrier on the request thread (the app has no 71): the reconnect's lists ran before it.
        assertEquals(null, session.fetch(71))
        assertEquals((0L..63L).toList(), script.listed)
        assertEquals(listOf(71L), script.gets)
        val rows = session.store.value.transactions.value.associateBy { it.id }
        assertTrue(!rows.getValue(3).state.isTerminal, "a row the app still has stays pending")
        assertTrue((1L..64L).filter { it != 3L }.all { rows.getValue(it).error == CapturedBody.GONE })
        assertTrue((65L..70L).none { rows.getValue(it).state.isTerminal }, "past the bound: asked about next time")

        session.connected()
        assertEquals(2, script.tails.take().since, "3 still holds the resume point")
        assertEquals(null, session.fetch(72))
        assertEquals((0L..63L) + 2L + (64L..69L), script.listed)
        script.close()
    }

    /** The tail brings the terminal summary while a `get` answered pending is on its way back. */
    @Test
    fun aPendingReplyReadAfterTheTerminalSummaryIsFetchedAgain() {
        val script = ScriptedBridge()
        val started = pendingTx(1, method = "POST", body = textBody("{}"))
        val done = started.completed(201, listOf("X" to "y"), textBody("done", "text/plain"), 50, "HTTP/2")
        val session = session { script.endpoint() }.connected()
        val tail = script.tails.take()
        tail.send(TransactionJson.bridgeSummary(started, Redactor.Disabled))
        session.awaitLocal { it.size == 1 }
        val replied = CountDownLatch(1)
        script.onGet = { id ->
            if (script.gets.size == 1) {
                // The app snapshots it pending; the terminal summary overtakes that reply.
                tail.send(TransactionJson.bridgeSummary(done, Redactor.Disabled))
                session.awaitLocal { list -> list.single().state.isTerminal }
                replied.countDown()
                TransactionJson.bridgeDetail(started, Redactor.Disabled)
            } else {
                TransactionJson.bridgeDetail(done, Redactor.Disabled)
            }.also { assertEquals(1L, id) }
        }
        session.select(1)
        val fetched = session.awaitLocal { list -> list.single().responseBody?.isNotFetched == false }.single()
        assertTrue(replied.await(15, TimeUnit.SECONDS))
        assertEquals(201, fetched.responseCode)
        assertEquals(listOf("X" to "y"), fetched.responseHeaders)
        assertEquals(listOf(1L, 1L), script.gets)
        script.close()
    }

    @Test
    fun aRestartedAppsSameIdIsNotFetchedForTheOldSelection() {
        startApp().add { completeTx(it, url = "https://api.example.com/before") }
        val session = session().connected()
        session.select(1)
        session.awaitLocal { list -> list.singleOrNull()?.responseBody?.isNotFetched == false }

        bridges.removeAt(0).close()
        session.state.await { it is RemoteState.Disconnected }
        startApp().add { completeTx(it, url = "https://api.example.com/unrelated", responseBody = textBody("x".repeat(1024))) }
        session.connected()
        session.awaitLocal { it.size == 1 }
        // A barrier on the request thread (id 2 does not exist): the reconnect's own fetch ran before it.
        assertEquals(null, session.fetch(2))
        val row = session.store.value.get(1)!!
        assertEquals("https://api.example.com/unrelated", row.url)
        assertTrue(row.responseBody!!.isNotFetched, "nobody opened the new process's transaction 1")
    }

    /**
     * The app's clear reply says how far it reached: a transaction the tail brought after the
     * app cleared stays, and a summary from before it, arriving late, is ignored.
     */
    @Test
    fun clearCoversExactlyWhatTheAppCleared() {
        val script = ScriptedBridge()
        val session = session { script.endpoint() }.connected()
        val tail = script.tails.take()
        for (id in 1L..2L) tail.send(TransactionJson.bridgeSummary(completeTx(id), Redactor.Disabled))
        session.awaitLocal { it.size == 2 }
        script.onClear = {
            // The app cleared through 2, then started 3, whose summary beats this reply.
            tail.send(TransactionJson.bridgeSummary(pendingTx(3), Redactor.Disabled))
            session.awaitLocal { it.size == 3 }
            """{"type":"ok","through":2}"""
        }
        session.clear().get(15, TimeUnit.SECONDS)
        assertEquals(listOf(3L), session.awaitLocal { it.size == 1 }.map { it.id })

        tail.send(TransactionJson.bridgeSummary(completeTx(2), Redactor.Disabled))
        tail.send(TransactionJson.bridgeSummary(completeTx(3), Redactor.Disabled))
        val after = session.awaitLocal { list -> list.singleOrNull()?.state?.isTerminal == true }
        assertEquals(listOf(3L), after.map { it.id }, "the late summary of 2 was ignored")
        script.close()
    }

    private fun waitForNoThread(name: String): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (Thread.getAllStackTraces().keys.none { it.name == name && it.isAlive }) return true
            Thread.sleep(10)
        }
        return false
    }

    @Test
    fun fullBodiesFetchEveryEndedBodyAtOnceAndEndWithTheSession() {
        val app = startApp()
        val session = RemoteSession(::endpoint, fullBodies = { true }).also { sessions += it }.connected()
        (1..3).forEach { n -> app.add { completeTx(it, url = "https://api.example.com/$n", responseBody = textBody("""{"n":$n}""")) } }
        // Nothing selected: every body arrives anyway.
        session.awaitLocal { list -> list.size == 3 && list.all { it.responseBody?.isNotFetched == false } }
        assertTrue(FullBodies.cap > 0, "the app keeps full bodies while the viewer is attached")
        // A body over the app's cap arrives whole here, and the app cuts its own copy back.
        val big = "x".repeat(400 * 1024)
        val id = app.add { completeTx(it, url = "https://api.example.com/big", responseBody = textBody(big, contentType = "text/plain")) }.id
        val local = session.awaitLocal { list -> list.any { it.id == id && it.responseBody?.isNotFetched == false } }
        assertEquals(big.length, local.single { it.id == id }.responseBody!!.bytes.size)
        val deadlineCut = System.nanoTime() + 5_000_000_000
        while ((app.get(id)?.responseBody?.bytes?.size ?: 0) > FullBodies.trimTo && System.nanoTime() < deadlineCut) Thread.sleep(10)
        assertEquals(FullBodies.trimTo, app.get(id)!!.responseBody!!.bytes.size)
        assertTrue(app.get(id)!!.responseBody!!.truncated)
        session.close()
        val deadline = System.nanoTime() + 5_000_000_000
        while (FullBodies.cap != 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(0, FullBodies.cap)
    }
}

/**
 * A bridge speaking the wire protocol from a script: `hello` for every connection, [onGet]'s line
 * for `get` (by default the detail of a complete transaction), [onList]'s lines for `list`, [onClear]'s for `clear`, with
 * [rules] [onRulesList]'s rule lines for `rules.list`, and each `tail` handed to the test to drive.
 */
internal class ScriptedBridge(private val rules: Boolean = false) : AutoCloseable {
    private val server = ServerSocket(0, 8, BridgeClient.LOOPBACK)
    private val sockets = mutableListOf<Socket>()
    val tails = LinkedBlockingQueue<Tail>()
    val gets: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    /** The `rules.list` requests read so far, the one [onRulesList] answers included. */
    val lists = AtomicInteger()

    @Volatile
    var onGet: (Long) -> String = { id -> TransactionJson.bridgeDetail(completeTx(id, url = "https://api.example.com/one"), Redactor.Disabled) }

    /** The `since` of each `list` read so far. */
    val listed: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    /** The summary lines a `list` from `since` answers with (by default none: the app has nothing above it). */
    @Volatile
    var onList: (Long) -> List<String> = { emptyList() }

    @Volatile
    var onClear: () -> String = { """{"type":"ok"}""" }

    @Volatile
    var onRulesList: () -> List<String> = { emptyList() }

    class Tail(val since: Long, private val socket: Socket) {
        fun send(line: String) {
            socket.getOutputStream().write((line + "\n").encodeToByteArray())
            socket.getOutputStream().flush()
        }
    }

    init {
        thread(isDaemon = true, name = "scripted-bridge") {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    return@thread
                }
                synchronized(sockets) { sockets += socket }
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    fun endpoint() = BridgeEndpoint(server.localPort, TOKEN, Platform.Jvm, APP, "nothing serves $APP")

    private fun serve(socket: Socket) {
        val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val output = socket.getOutputStream()
        fun send(line: String) {
            output.write((line + "\n").encodeToByteArray())
            output.flush()
        }
        try {
            if (input.readLine() != TOKEN) return socket.close()
            input.readLine() // hello
            send("""{"type":"hello","protocol":1,"app":"$APP","platform":"jvm","count":0,"dropped":0${if (rules) ""","rules":true""" else ""}}""")
            while (true) {
                val line = input.readLine() ?: return
                val request = Json.parseToJsonElement(line) as JsonObject
                when (request.str("cmd")) {
                    "tail" -> tails.put(Tail(request.long("since") ?: 0, socket))
                    "get" -> {
                        val id = request.long("id") ?: 0
                        gets += id
                        send(onGet(id))
                    }
                    "clear" -> send(onClear())
                    "list" -> {
                        val since = request.long("since") ?: 0
                        listed += since
                        onList(since).forEach(::send)
                        send("""{"type":"end"}""")
                    }
                    "rules.list" -> {
                        lists.incrementAndGet()
                        onRulesList().forEach(::send)
                        send("""{"type":"end"}""")
                    }
                }
            }
        } catch (_: Exception) {
            // The session closed it.
        }
    }

    override fun close() {
        server.close()
        synchronized(sockets) { sockets.forEach { it.close() } }
    }

    companion object {
        val TOKEN = "ab".repeat(32)
    }
}
