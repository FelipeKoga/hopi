package dev.koga.hopi.connect

import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.perf.PerfHook
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfThread
import dev.koga.hopi.core.perf.StallEvent
import dev.koga.hopi.core.store.HopiStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
import kotlinx.serialization.json.jsonObject

/** The perf commands from the host side ([PerfClient], the decoders, [RemotePerf]) against the real JVM bridge. */
class PerfClientTest {
    private val root: Path = Files.createTempDirectory("hopi-perf-client")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = HopiStore(maxEntries = 100, scope = scope)
    private val bridge = JvmAgentBridge.start(store, root, app = APP)
    private val closeables = mutableListOf<AutoCloseable>()

    @BeforeTest
    fun setUp() = PerfHook.reset()

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { it.close() }
        PerfHook.reset()
        bridge.close()
        store.close()
        scope.cancel()
        root.toFile().deleteRecursively()
    }

    private fun endpoint(): BridgeEndpoint {
        val (port, token) = readBridgeFile(root.resolve(JvmAgentBridge.BRIDGE_FILE_NAME), missing = "no bridge file")
        return BridgeEndpoint(port, token, Platform.Jvm, APP, unreachableMessage = "nothing serves $APP")
    }

    private fun connect(): BridgeClient = BridgeClient.connect(endpoint(), 10_000).also {
        closeables += it
        it.handshake(null)
    }

    private fun fake(remoteControl: Boolean = true) = FakePerfController(remoteControl).also { PerfHook.register(it) }

    private fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = runBlocking { withTimeout(15_000) { first(predicate) } }

    @Test
    fun anAppWithoutPerfIsUnsupportedBeforeAnyPerfLine() {
        val client = connect()
        assertEquals(false, client.hello.json.bool("perf"))
        val refused = assertFailsWith<CliException> { PerfClient(client) }
        assertEquals(ExitCode.UNSUPPORTED, refused.exitCode)
        assertTrue("no perf monitors" in refused.message.orEmpty())
    }

    @Test
    fun startStopAndStatusDecode() {
        val app = fake()
        val perf = PerfClient(connect())
        assertTrue(perf.control)
        val started = assertNotNull(RemotePerfStatus.decode(perf.start(listOf("hangs", "cpu"), hangMs = 300, ttlSec = 120).json))
        assertEquals(setOf("cpu", "memory", "hangs"), started.available.toSet())
        val hangs = assertNotNull(started.monitor("hangs"))
        assertEquals(listOf("lease"), hangs.owners)
        assertEquals(250L, hangs.thresholdMs)
        assertEquals(1_000L, hangs.sinceMs)
        val request = app.starts.single().first
        assertEquals(setOf(PerfMonitor.HANGS, PerfMonitor.CPU), request.monitors)
        assertEquals(300L, request.hangThresholdMs)
        assertEquals(PerfOwner.Lease(120), app.starts.single().second)

        val stopped = assertNotNull(RemotePerfStatus.decode(perf.stop(listOf("hangs")).json))
        assertNull(stopped.monitor("hangs"))
        assertNotNull(stopped.monitor("cpu"))
        assertTrue(RemotePerfStatus.decode(perf.stop().json)!!.running.isEmpty())
        assertTrue(RemotePerfStatus.decode(perf.status().json)!!.running.isEmpty())
    }

    @Test
    fun withoutRemoteControlStartIsUnsupported() {
        fake(remoteControl = false)
        val perf = PerfClient(connect())
        assertFalse(perf.control)
        assertEquals(ExitCode.UNSUPPORTED, assertFailsWith<CliException> { perf.start(listOf("cpu")) }.exitCode)
        // Reading stays allowed.
        assertNotNull(RemotePerfStatus.decode(perf.status().json))
    }

    @Test
    fun snapshotDecodesEveryPart() {
        val app = fake()
        app.sample(1_700_000_000_000, cpu = 12.5, residentMb = 140)
        app.sample(1_700_000_001_000, cpu = 30.0, residentMb = 150)
        val hang = app.hang(612)
        val lines = PerfClient(connect()).snapshot(threads = true, pss = true)
        val series = lines.mapNotNull { RemotePerfSeries.decode(it.json) }.associateBy { it.name }
        assertEquals(setOf("frames", "cpu", "memory"), series.keys)
        assertEquals(listOf(12.5, 30.0), series.getValue("cpu").column("process").toList())
        assertEquals(150.0 * 1024, series.getValue("memory").column("residentKb").last())
        assertTrue(series.getValue("memory").column("availableKb").all { it.isNaN() }, "null values are NaN")
        val samples = RemotePerf.samplesOf(series["cpu"], series["memory"])
        assertEquals(listOf(140L shl 20, 150L shl 20), samples.map { it.residentBytes })
        assertEquals(listOf(12.5, 30.0), samples.map { it.cpu })

        val summary = assertNotNull(lines.firstNotNullOfOrNull { RemotePerfSummary.decode(it.json) })
        assertEquals(1, summary.hangs)
        assertEquals(612, summary.hangWorstMs)
        assertEquals(30.0, summary.cpuMaxPercent)
        assertEquals(150L shl 20, summary.residentLastBytes)

        val event = assertNotNull(lines.firstNotNullOfOrNull { RemotePerfEvent.decode(it.json) })
        assertEquals(hang.seq, event.seq)
        assertEquals("hang", event.kind)
        assertFalse(event.ongoing)
        assertEquals(612.0, event.ms)
        assertEquals("CheckoutActivity", event.screen)
        assertEquals(8, event.stacks.single().size, "a snapshot cuts stacks to 8 frames")
        assertTrue(event.stacksCut)

        val threads = assertNotNull(lines.firstNotNullOfOrNull { RemotePerfThread.decodeAll(it.json) })
        assertEquals(listOf("main", "hopi-perf-samp"), threads.map { it.name })
        assertTrue(threads.last().hopi)
        assertEquals(42.5, threads.first().percent)
        assertEquals(54_321L, lines.single { it.type == "perf.pss" }.json.long("kb"))
        assertNotNull(lines.last().let { RemotePerfStatus.decode(it.json) }, "the status comes last, before end")
    }

    @Test
    fun eventHasFullStacksAndAMissingOneIsNotFound() {
        val app = fake()
        val hang = app.hang(700)
        val perf = PerfClient(connect())
        val event = assertNotNull(RemotePerfEvent.decode(perf.event(hang.seq).json))
        assertEquals(12, event.stacks.single().size)
        assertFalse(event.stacksCut)
        assertTrue(event.stacksText().startsWith("  at dev.koga.sample.PerfLab.frame0(PerfLab.kt:1)\n"))
        assertEquals(ExitCode.NOT_FOUND, assertFailsWith<CliException> { perf.event(hang.seq + 100) }.exitCode)
    }

    @Test
    fun tailStreamsBacklogThenLiveAndHoldsWhatItStarted() {
        val app = fake()
        val first = app.hang(400)
        val client = connect()
        PerfClient(client).tail(since = 0, samples = true, start = listOf("cpu"))
        val status = assertNotNull(RemotePerfStatus.decode(client.reply().json))
        assertTrue(status.monitor("cpu")!!.owners.single().startsWith("tail:"))
        assertEquals(first.seq, RemotePerfEvent.decode(client.reply().json)?.seq)
        app.sample(1_700_000_002_000, cpu = 55.0, residentMb = 160)
        val sample = assertNotNull(RemotePerfSample.decode(client.reply().json))
        assertEquals(55.0, sample.cpu)
        assertEquals(160L shl 20, sample.residentBytes)
        assertEquals(10L shl 20, sample.nativeHeapBytes)
        client.close()
        waitUntil { !app.held(PerfMonitor.CPU) }
    }

    @Test
    fun framesLineDecodes() {
        val line = """{"type":"perf.frames","sec":1729850000,"frames":57,"slow":9,"janky":4,"frozen":0,"worstMs":118.4,"missedVsyncs":3,"screen":null,"provisional":true}"""
        val second = assertNotNull(RemoteFrameSecond.decode(Json.parseToJsonElement(line).jsonObject))
        assertEquals(57, second.frames)
        assertEquals(4, second.janky)
        assertEquals(118.4, second.worstMs)
        assertTrue(second.provisional)
        assertNull(RemoteFrameSecond.decode(Json.parseToJsonElement("""{"type":"perf.sample","at":1}""").jsonObject))
    }

    @Test
    fun framesAreUnattachedOnlyWithNoAttachOnTheWay() {
        fun frames(details: String): RemotePerfMonitor {
            val line = """{"type":"perf.status","platform":"android","available":["frames"],"remoteControl":true,"running":{"frames":{"since":1,"owners":["lease"]$details}}}"""
            return assertNotNull(assertNotNull(RemotePerfStatus.decode(Json.parseToJsonElement(line).jsonObject)).monitor("frames"))
        }
        assertTrue(frames(""","attached":false""").unattached, "an older app sends no attaching")
        assertTrue(frames(""","attached":false,"attaching":false""").unattached)
        assertFalse(frames(""","attached":false,"attaching":true""").unattached, "the attach is one main-thread message away")
        assertFalse(frames(""","attached":true,"attaching":false""").unattached)
        assertFalse(frames("").unattached, "iOS sends no attached")
    }

    @Test
    fun remotePerfStreamsOnlyWhileWatchedAndHoldsItsMonitors() {
        val app = fake()
        app.sample(1_700_000_000_000, cpu = 10.0, residentMb = 100)
        val session = RemoteSession(::endpoint).also { closeables += it }
        session.connect()
        assertIs<RemoteState.Connected>(session.state.value)
        val view = session.perf.view.await { it.supported }
        assertTrue(view.control)
        assertFalse(view.streaming, "nothing is sent for perf until a viewer watches")
        assertTrue(app.starts.isEmpty())

        val watch = session.perf.watch()
        // The backfill brings what the app already held.
        session.perf.view.await { v -> v.streaming && v.samples.any { it.cpu == 10.0 } }
        session.perf.setMonitor("hangs", on = true).get(15, TimeUnit.SECONDS)
        waitUntil { app.held(PerfMonitor.HANGS) }
        assertTrue(app.starts.last().second is PerfOwner.Tail)
        session.perf.view.await { it.status?.monitor("hangs") != null }

        // Turning a second monitor on reopens the stream; the first is never dropped meanwhile.
        session.perf.setMonitor("cpu", on = true).get(15, TimeUnit.SECONDS)
        waitUntil { app.held(PerfMonitor.CPU) }
        assertTrue(app.held(PerfMonitor.HANGS))

        val hang = app.hang(900, ended = false)
        session.perf.view.await { v -> v.hangs.any { it.seq == hang.seq && it.ongoing } }
        app.data.updateHang(hang.copy(ended = true, durationMs = 1_200))
        session.perf.view.await { v -> v.hangs.single { it.seq == hang.seq }.let { !it.ongoing && it.ms == 1_200.0 } }
        app.sample(1_700_000_005_000, cpu = 77.0, residentMb = 120)
        session.perf.view.await { v -> v.samples.last().cpu == 77.0 }

        session.perf.watchThreads(true).get(15, TimeUnit.SECONDS)
        app.sample(1_700_000_006_000, cpu = 1.0, residentMb = 120)
        session.perf.view.await { v -> v.threads?.map { it.name } == listOf("main", "hopi-perf-samp") }
        assertTrue(app.watchingThreads)

        // Off is perf.stop: every owner.
        session.perf.setMonitor("cpu", on = false).get(15, TimeUnit.SECONDS)
        assertFalse(app.held(PerfMonitor.CPU))
        // The last watcher leaving closes the stream, and the app drops what it held for it.
        watch.close()
        waitUntil { !app.held(PerfMonitor.HANGS) }
        assertFalse(session.perf.view.value.streaming)
    }

    /** The history comes from the cumulative CPU between scans, never the line's `percent` (iOS: a decayed average). */
    @Test
    fun threadHistoryMeasuresEachThreadBetweenItsScans() {
        val app = fake()
        val session = RemoteSession(::endpoint).also { closeables += it }
        session.connect()
        session.perf.view.await { it.supported }
        val watch = session.perf.watch(threads = true)
        closeables += watch
        session.perf.view.await { it.streaming }
        waitUntil { app.watchingThreads }
        val start = 1_700_000_000_000

        fun scan(atMs: Long, vararg cpuMs: Pair<Long, Long>) {
            app.threadList = cpuMs.map { (tid, ms) -> PerfThread(tid, if (tid == 7L) "main" else "", ms, 99.0, false) }
            app.sample(atMs, cpu = 50.0, residentMb = 100)
            session.perf.view.await { v -> v.threadTicks.lastOrNull()?.atMs == atMs }
        }
        scan(start, 7L to 1_000, 8L to 500)
        scan(start + 1_000, 7L to 1_200, 8L to 500)
        // Thread 8 left the top list for three seconds: what it used then is spread over them.
        scan(start + 2_000, 7L to 1_300)
        scan(start + 3_000, 7L to 1_300)
        scan(start + 4_000, 7L to 1_300, 8L to 1_100)

        val ticks = session.perf.view.value.threadTicks
        assertEquals(emptyList(), ticks.first().loads, "a first sighting is only a baseline")
        val main = ticks[1].loads.single()
        assertEquals(listOf(7L, 200L, start), listOf(main.tid, main.cpuMs, main.sinceMs))

        val first = RemotePerf.threadsBetween(ticks, start, start + 1_000)
        assertEquals(listOf("main"), first.map { it.name })
        assertEquals(20.0, first.single().percent, 0.01, "percent of one core over the range, not the line's 99")

        val all = RemotePerf.threadsBetween(ticks, start, start + 4_000).associateBy { it.tid }
        assertEquals(300.0, assertNotNull(all[7]).cpuMs, 0.01)
        assertEquals(600.0, assertNotNull(all[8]).cpuMs, 0.01)
        // Thread 8's 600 ms cover the three seconds since its last report; two of them fall inside this range.
        assertEquals(400.0, RemotePerf.threadsBetween(ticks, start + 2_000, start + 4_000).single { it.tid == 8L }.cpuMs, 0.01)
        assertTrue(RemotePerf.threadsBetween(ticks, start + 5_000, start + 6_000).isEmpty())

        // A tid reused by a new thread starts over rather than going negative.
        scan(start + 5_000, 7L to 10, 8L to 1_200)
        assertEquals(listOf(8L), session.perf.view.value.threadTicks.last().loads.map { it.tid })
    }

    @Test
    fun stallsKeepTheirMomentAndCountOnceAcrossAReopen() {
        val app = fake()
        val session = RemoteSession(::endpoint).also { closeables += it }
        session.connect()
        session.perf.view.await { it.supported }
        val watch = session.perf.watch()
        closeables += watch
        session.perf.view.await { it.streaming }
        app.data.recordStall(StallEvent(app.data.nextSeq(), 1_700_000_000_000, 260, null))
        val view = session.perf.view.await { it.stallEvents.isNotEmpty() }
        assertEquals(listOf(1_700_000_000_000 to 260.0), view.stallEvents.map { it.atMs to it.ms })
        assertEquals(1, view.stalls)
    }

    /**
     * A reopen while a hang runs (a monitor toggled in the panel), from a seq above the hang's: the
     * view still gets the hang's end, and the events re-sent above the hang are not counted twice.
     */
    @Test
    fun aReopenDuringAHangKeepsItsEndAndCountsOnce() {
        val app = fake()
        val session = RemoteSession(::endpoint).also { closeables += it }
        session.connect()
        session.perf.view.await { it.supported }
        session.perf.watch().use {
            session.perf.view.await { it.streaming }
            val hang = app.hang(250, ended = false)
            app.data.recordStall(StallEvent(app.data.nextSeq(), 5, 120, "S"))
            session.perf.view.await { v -> v.hangs.any { it.seq == hang.seq && it.ongoing } && v.stalls == 1L }
            // The stream reopens from the stall's seq, above the hang's; then the hang ends.
            session.perf.setMonitor("cpu", on = true).get(15, TimeUnit.SECONDS)
            waitUntil { app.held(PerfMonitor.CPU) }
            app.data.updateHang(hang.copy(ended = true, durationMs = 3_000))
            val view = session.perf.view.await { v -> v.hangs.single { it.seq == hang.seq }.let { !it.ongoing && it.ms == 3_000.0 } }
            assertEquals(1L, view.stalls, "a stall re-sent by the resumed stream was counted twice")
        }
    }

    @Test
    fun aViewerOfAnAppWithoutPerfSendsNothing() {
        val session = RemoteSession(::endpoint).also { closeables += it }
        session.connect()
        assertFalse(session.perf.view.value.supported)
        session.perf.watch().use {
            val refused = runCatching { session.perf.stopAll().get(15, TimeUnit.SECONDS) }.exceptionOrNull()
            assertEquals(ExitCode.UNSUPPORTED, (refused?.cause as? CliException)?.exitCode)
            assertFalse(session.perf.view.value.streaming)
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val APP = "dev.koga.sample"
    }
}
