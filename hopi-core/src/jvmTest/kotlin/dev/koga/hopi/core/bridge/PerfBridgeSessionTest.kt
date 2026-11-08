package dev.koga.hopi.core.bridge

import dev.koga.hopi.core.json.parseJsonTree
import dev.koga.hopi.core.perf.CpuSample
import dev.koga.hopi.core.perf.HangEvent
import dev.koga.hopi.core.perf.PerfController
import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfHook
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfMonitorStatus
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfOwners
import dev.koga.hopi.core.perf.PerfPss
import dev.koga.hopi.core.perf.PerfResult
import dev.koga.hopi.core.perf.PerfStartRequest
import dev.koga.hopi.core.perf.PerfStatus
import dev.koga.hopi.core.perf.PerfThread
import dev.koga.hopi.core.perf.StallEvent
import dev.koga.hopi.core.store.HopiStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel

/** The `perf.*` commands over a real [BridgeSession] (docs/design/perf-monitors.md, "Bridge"). */
class PerfBridgeSessionTest {
    private val storeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val storeScope = CoroutineScope(SupervisorJob() + storeExecutor.asCoroutineDispatcher())
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = HopiStore(10_000, 1L shl 30, storeScope)

    /** Records what the bridge asked for; owners as the real runtime keeps them. */
    private class FakeController(override val remoteControl: Boolean = true) : PerfController {
        override val platform = "jvm"
        override val available = setOf(PerfMonitor.CPU, PerfMonitor.MEMORY, PerfMonitor.HANGS)
        override val data = PerfData()
        val owners = PerfOwners()
        val starts = ArrayList<Pair<PerfStartRequest, PerfOwner>>()
        var watching = false
        val cadence = java.util.concurrent.atomic.AtomicInteger(0)

        override fun start(request: PerfStartRequest, owner: PerfOwner): PerfResult = synchronized(this) {
            starts += request to owner
            request.monitors.forEach { owners.add(it, owner, 0) }
            PerfResult.Ok(status())
        }

        override fun stop(monitors: Set<PerfMonitor>): PerfStatus = synchronized(this) {
            (monitors.ifEmpty { PerfMonitor.entries.toSet() }).forEach { owners.clear(it) }
            status()
        }

        override fun releaseTail(session: Long) {
            synchronized(this) { owners.removeTail(session) }
        }

        override fun status(): PerfStatus = synchronized(this) {
            PerfStatus(
                platform, available.toList(), remoteControl,
                owners.heldMonitors.map { PerfMonitorStatus(it, 1, owners.describe(it), null, listOf("thresholdMs" to 250L)) },
            )
        }

        override fun threads(limit: Int): List<PerfThread> = listOf(PerfThread(1, "main", 10, 5.0, false))

        override fun watchThreads(watching: Boolean) {
            this.watching = watching
        }

        override fun measurePss(): PerfPss = PerfPss(1234, 3)

        override fun watchCadence(watching: Boolean) {
            cadence.addAndGet(if (watching) 1 else -1)
        }
    }

    @BeforeTest
    fun setUp() = PerfHook.reset()

    @AfterTest
    fun tearDown() {
        PerfHook.reset()
        sessionScope.cancel()
        storeScope.cancel()
        storeExecutor.shutdownNow()
    }

    private fun serve(connection: MemoryConnection = MemoryConnection(), liveCapacity: Int = BridgeSession.liveCapacityFor(store)): MemoryConnection {
        thread(name = "test-session") { BridgeSession(connection, store, BridgeInfo("dev.koga.sample", "jvm"), null, sessionScope, liveCapacity).run() }
        return connection
    }

    private fun session(connection: MemoryConnection = MemoryConnection(), liveCapacity: Int = BridgeSession.liveCapacityFor(store)): MemoryConnection =
        serve(connection, liveCapacity).also { assertEquals("hello", fields(it.request(HELLO))["type"]) }

    private fun fields(line: String): Map<String, Any?> = assertNotNull(parseJsonTree(line, Int.MAX_VALUE, 8), line)

    private fun type(line: String): String? = TYPE.find(line)?.groupValues?.get(1)

    private fun MemoryConnection.request(line: String): String {
        send(line)
        return nextLine()
    }

    private fun assertError(code: BridgeError, line: String, containing: String = "") {
        val f = fields(line)
        assertEquals("error", f["type"], line)
        assertEquals(code.name, f["code"], line)
        assertTrue(containing in (f["message"] as String), line)
    }

    @Test
    fun helloReportsPerfOnlyWithAController() {
        val without = fields(serve().request(HELLO))
        assertEquals(false, without["perf"])
        assertEquals(false, without["perfControl"])
        assertEquals(1L, without["protocol"], "the protocol stays 1")
        PerfHook.register(FakeController(remoteControl = false))
        val with = fields(serve().request(HELLO))
        assertEquals(true, with["perf"])
        assertEquals(false, with["perfControl"])
    }

    @Test
    fun perfCommandsNeedHelloAndAController() {
        val client = serve()
        assertError(BridgeError.BAD_REQUEST, client.request("""{"cmd":"perf.status"}"""), "hello first")
        client.request(HELLO)
        assertError(BridgeError.UNSUPPORTED, client.request("""{"cmd":"perf.status"}"""), "absent")
        assertError(BridgeError.UNSUPPORTED, client.request("""{"cmd":"perf.tail"}"""), "absent")
    }

    @Test
    fun startStopAndStatusWithALease() {
        val controller = FakeController()
        PerfHook.register(controller)
        val client = session()
        val started = fields(client.request("""{"cmd":"perf.start","monitors":["hangs","cpu"],"hangMs":300,"ttlSec":60}"""))
        assertEquals("perf.status", started["type"])
        val running = started["running"] as Map<*, *>
        assertEquals(setOf("hangs", "cpu"), running.keys)
        assertEquals(listOf("lease"), (running["hangs"] as Map<*, *>)["owners"])
        val (request, owner) = controller.starts.single()
        assertEquals(300L, request.hangThresholdMs)
        assertEquals(null, request.sampleIntervalMs, "a setting not given keeps the value in force")
        assertEquals(PerfOwner.Lease(60), owner)
        val stopped = fields(client.request("""{"cmd":"perf.stop","monitors":["cpu"]}"""))
        assertEquals(setOf("hangs"), (stopped["running"] as Map<*, *>).keys)
        assertEquals(setOf("hangs"), (fields(client.request("""{"cmd":"perf.status"}"""))["running"] as Map<*, *>).keys)
        assertEquals(emptySet<Any>(), (fields(client.request("""{"cmd":"perf.stop"}"""))["running"] as Map<*, *>).keys)
    }

    @Test
    fun startChecksLimitsAndPlatform() {
        PerfHook.register(FakeController())
        val client = session()
        assertError(BridgeError.LIMIT, client.request("""{"cmd":"perf.start","monitors":["hangs"],"hangMs":50}"""), "hangMs")
        assertError(BridgeError.LIMIT, client.request("""{"cmd":"perf.start","monitors":["cpu"],"intervalMs":20000}"""), "intervalMs")
        assertError(BridgeError.LIMIT, client.request("""{"cmd":"perf.start","monitors":["cpu"],"ttlSec":7200}"""), "ttlSec")
        assertError(BridgeError.UNSUPPORTED, client.request("""{"cmd":"perf.start","monitors":["frames"]}"""), "frames")
        assertError(BridgeError.BAD_REQUEST, client.request("""{"cmd":"perf.start","monitors":["fps"]}"""), "monitors")
        assertError(BridgeError.BAD_REQUEST, client.request("""{"cmd":"perf.start"}"""), "at least one")
        assertError(BridgeError.LIMIT, client.request("""{"cmd":"perf.snapshot","points":0}"""), "points")
    }

    @Test
    fun withoutRemoteControlStartAndStopAreRefusedButReadsWork() {
        PerfHook.register(FakeController(remoteControl = false))
        val client = session()
        assertError(BridgeError.UNSUPPORTED, client.request("""{"cmd":"perf.start","monitors":["cpu"]}"""), "remoteControl")
        assertError(BridgeError.UNSUPPORTED, client.request("""{"cmd":"perf.stop"}"""), "remoteControl")
        assertError(BridgeError.UNSUPPORTED, client.request("""{"cmd":"perf.tail","start":["cpu"]}"""), "remoteControl")
        assertEquals("perf.status", fields(client.request("""{"cmd":"perf.status"}"""))["type"])
    }

    @Test
    fun snapshotSendsSeriesSummaryEventsThreadsAndPss() {
        val controller = FakeController()
        PerfHook.register(controller)
        controller.data.recordSample(1_000, CpuSample(12.5, 0.5, 8), null)
        val stack = List(20) { "a.B.m$it(B.kt:$it)" }
        controller.data.recordHang(hang(controller.data.nextSeq(), stack))
        val client = session()
        client.send("""{"cmd":"perf.snapshot","series":["cpu"],"points":10,"threads":true,"pss":true}""")
        val raw = generateSequence { client.nextLine() }.takeWhile { type(it) != "end" }.toList()
        assertEquals(listOf("perf.series", "perf.summary", "perf.event", "perf.threads", "perf.pss", "perf.status"), raw.map(::type))
        // core's tree parser reads integers only: the lines with fractions are checked as text.
        assertTrue(raw[0].contains("\"name\":\"cpu\",\"columns\":[\"t\",\"process\",\"hopi\"],\"points\":[[1000,12.5,0.5]]"), raw[0])
        val event = fields(raw[2])
        val pss = fields(raw[4])
        assertEquals(8, ((event["stacks"] as List<*>)[0] as List<*>).size, "stacks cut to 8 frames unless fullStacks")
        assertEquals(true, event["stacksCut"])
        assertEquals(1234L, pss["kb"])
        // perf.event sends the whole event; an unknown one is NOT_FOUND.
        val full = fields(client.request("""{"cmd":"perf.event","seq":${event["seq"]}}"""))
        assertEquals(20, ((full["stacks"] as List<*>)[0] as List<*>).size)
        assertError(BridgeError.NOT_FOUND, client.request("""{"cmd":"perf.event","seq":999}"""))
    }

    @Test
    fun tailSendsTheBacklogThenLiveEventsAndReleasesWhatItStarted() {
        val controller = FakeController()
        PerfHook.register(controller)
        val old = hang(controller.data.nextSeq())
        controller.data.recordHang(old)
        controller.owners.add(PerfMonitor.HANGS, PerfOwner.Code, 0)
        val client = session()
        client.send("""{"cmd":"perf.tail","samples":true,"threads":true,"start":["hangs","cpu"]}""")
        val status = fields(client.nextLine())
        assertEquals("perf.status", status["type"])
        assertEquals(listOf("code", "tail:${(controller.starts.single().second as PerfOwner.Tail).session}"), ((status["running"] as Map<*, *>)["hangs"] as Map<*, *>)["owners"])
        assertEquals(old.seq, fields(client.nextLine())["seq"], "the backlog")
        // Live: the old hang's end (a new instance with the same seq) is sent; a sample, then the threads.
        controller.data.updateHang(old.copy(ended = true, durationMs = 900))
        val ended = fields(client.nextLine())
        assertEquals("ended", ended["state"])
        controller.data.recordSample(2_000, CpuSample(50.0, 1.0, 8), null)
        assertEquals("perf.sample", fields(client.nextLine())["type"])
        assertEquals("perf.threads", fields(client.nextLine())["type"])
        assertTrue(controller.watching)
        client.endInput()
        client.awaitClosed()
        // The session's own owner is gone; code still holds hangs, so it runs on.
        val deadline = System.nanoTime() + 2_000_000_000
        while (controller.owners.held(PerfMonitor.CPU) && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(setOf(PerfMonitor.HANGS), controller.owners.heldMonitors)
        assertEquals(listOf("code"), controller.owners.describe(PerfMonitor.HANGS))
        assertTrue(!controller.watching)
    }

    /**
     * A stream resumed from the highest seq seen (the viewer's reopen, a CLI resume) still gets the
     * later versions of a hang at or below it: a hang is updated in place under its seq.
     */
    @Test
    fun aTailResumedAtAnOngoingHangsSeqStillGetsItsEnd() {
        val controller = FakeController()
        PerfHook.register(controller)
        val ongoing = hang(controller.data.nextSeq())
        controller.data.recordHang(ongoing)
        val client = session()
        client.send("""{"cmd":"perf.tail","since":${ongoing.seq}}""")
        assertEquals("perf.status", fields(client.nextLine())["type"])
        controller.data.updateHang(ongoing.copy(ended = true, durationMs = 3_000))
        val ended = fields(client.nextLine())
        assertEquals(ongoing.seq, ended["seq"])
        assertEquals("ended", ended["state"])
        // Any other event at or below `since` is one the client has: not resent.
        controller.data.recordStall(StallEvent(controller.data.nextSeq(), 5, 120, "S"))
        assertEquals("stall", fields(client.nextLine())["kind"])
        client.endInput()
        client.awaitClosed()
    }

    /** SLOW_CLIENT's `since` stays below a hang the client was sent as ongoing: its end may be among what was dropped. */
    @Test
    fun aSlowPerfTailResumesBelowAnOngoingHang() {
        val controller = FakeController()
        PerfHook.register(controller)
        // hello, the status and the hang go through; the next write parks, as on a full socket.
        val client = session(MemoryConnection(blockWritesAfterLines = 3), liveCapacity = 4)
        client.send("""{"cmd":"perf.tail"}""")
        assertEquals("perf.status", fields(client.nextLine())["type"])
        val ongoing = hang(controller.data.nextSeq())
        controller.data.recordHang(ongoing)
        assertEquals(ongoing.seq, fields(client.nextLine())["seq"])
        // One stall parked in the write, four queued, the sixth overflows; two more stay under the cut.
        for (n in 1..8) controller.data.recordStall(StallEvent(controller.data.nextSeq(), 5, 120, "S"))
        client.awaitParkedWrite()
        client.releaseWrites()
        val lines = generateSequence { client.nextLineOrEnd() }.toList()
        val error = fields(lines.last())
        assertEquals(BridgeError.SLOW_CLIENT.name, error["code"], lines.toString())
        assertEquals(ongoing.seq - 1, error["since"], lines.last())
    }

    @Test
    fun tailWithFpsAsksForTheCadenceUntilTheSessionEnds() {
        val controller = FakeController()
        PerfHook.register(controller)
        val client = session()
        client.send("""{"cmd":"perf.tail","fps":true}""")
        assertEquals("perf.status", fields(client.nextLine())["type"])
        assertEquals(1, controller.cadence.get(), "one viewer while the tail runs")
        val other = session()
        other.send("""{"cmd":"perf.tail"}""")
        assertEquals("perf.status", fields(other.nextLine())["type"])
        assertEquals(1, controller.cadence.get(), "a tail without fps asks for nothing")
        client.endInput()
        client.awaitClosed()
        val deadline = System.nanoTime() + 2_000_000_000
        while (controller.cadence.get() != 0 && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(0, controller.cadence.get(), "the viewer is gone with its session")
        other.endInput()
        other.awaitClosed()
    }

    private fun hang(seq: Long, stack: List<String> = listOf("a.B.c(B.kt:1)")) = HangEvent(
        seq = seq, atMs = 5, thresholdMs = 250, ended = false, durationMs = 250, screen = "S",
        stacks = listOf(stack), captureUs = listOf(40), stackMissed = false, debugger = false, frozenMs = 0, heartbeat = false,
    )

    private companion object {
        const val HELLO = """{"cmd":"hello"}"""
        val TYPE = Regex(""""type":"([^"]+)"""")
    }
}
