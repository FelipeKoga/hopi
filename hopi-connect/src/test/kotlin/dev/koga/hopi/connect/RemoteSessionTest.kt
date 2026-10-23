package dev.koga.hopi.connect

import dev.koga.hopi.core.bridge.BridgeSession
import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.model.HttpTransaction
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
        session.awaitLocal { list -> list.single().responseBody?.isNotFetched == false }
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
        // The app clears its own store (its in-app screen, say): no event reaches the tail.
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
        session.awaitLocal { list -> list.single().responseBody?.isNotFetched == false }

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
}

/**
 * A bridge speaking the wire protocol from a script: `hello` for every connection, [onGet]'s line
 * for `get` (by default the detail of a complete transaction), [onClear]'s for `clear`, and each
 * `tail` handed to the test to drive.
 */
private class ScriptedBridge : AutoCloseable {
    private val server = ServerSocket(0, 8, BridgeClient.LOOPBACK)
    private val sockets = mutableListOf<Socket>()
    val tails = LinkedBlockingQueue<Tail>()
    val gets: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var onGet: (Long) -> String = { id -> TransactionJson.bridgeDetail(completeTx(id, url = "https://api.example.com/one"), Redactor.Disabled) }

    @Volatile
    var onClear: () -> String = { """{"type":"ok"}""" }

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
            send("""{"type":"hello","protocol":1,"app":"$APP","platform":"jvm","count":0,"dropped":0}""")
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
