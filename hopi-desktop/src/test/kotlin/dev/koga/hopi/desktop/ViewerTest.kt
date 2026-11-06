package dev.koga.hopi.desktop

import dev.koga.hopi.connect.BridgeClient
import dev.koga.hopi.connect.BridgeEndpoint
import dev.koga.hopi.connect.CliException
import dev.koga.hopi.connect.ExitCode
import dev.koga.hopi.connect.HostEnvironment
import dev.koga.hopi.connect.ProcessResult
import dev.koga.hopi.connect.RemoteSession
import dev.koga.hopi.connect.ShutdownHooks
import dev.koga.hopi.connect.StreamingProcess
import dev.koga.hopi.connect.Targets
import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.store.HopiStore
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal const val APP = "dev.koga.sample"

internal object NoHooks : ShutdownHooks {
    override fun add(hook: Thread) = Unit

    override fun remove(hook: Thread) = Unit
}

/** A host with no adb and no simulators: only JVM bridges are reachable. */
internal fun jvmOnlyTargets() = Targets(
    HostEnvironment(
        processes = { throw IOException("no subprocess in this test") },
        env = emptyMap(),
        userHome = "",
        isMac = false,
        isExecutable = { false },
        shutdownHooks = NoHooks,
        streams = { throw IOException("no subprocess in this test") },
    ),
)

internal fun <T> StateFlow<T>.await(timeoutMs: Long = 20_000, predicate: (T) -> Boolean): T = try {
    runBlocking { withTimeout(timeoutMs) { first(predicate) } }
} catch (e: TimeoutCancellationException) {
    throw AssertionError("none within $timeoutMs ms; the last value: $value; threads: ${hopiThreads()}", e)
}

/** The live threads of Hopi's desktop code (the viewer's worker, its watches), by name. */
internal fun hopiThreads(): List<String> =
    Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("hopi-desktop") }.map { it.name }

/** The app's side in-process: a store and its JVM bridge in [directory], as `agentBridge = true` starts it. */
internal class FakeApp(private val directory: Path) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = HopiStore(maxEntries = 100, scope = scope)
    private val bridge = JvmAgentBridge.start(store, directory, app = APP)
    val bridgeFile: Path = directory.resolve(JvmAgentBridge.BRIDGE_FILE_NAME)

    fun add(tx: (Long) -> HttpTransaction): HttpTransaction = tx(store.nextId()).also { check(store.submit(it)) }

    override fun close() {
        bridge.close()
        store.close()
        scope.cancel()
    }
}

class ViewerTest {
    private val root: Path = Files.createTempDirectory("hopi-viewer-test")
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { it.close() }
        root.toFile().deleteRecursively()
        // A watch a closed viewer left running would fail the next test's thread checks instead.
        awaitThreads("no watch once the viewers closed") { names -> names.none { it.startsWith("hopi-desktop-watch") } }
    }

    private fun <T : AutoCloseable> T.closing(): T = also { closeables += it }

    @Test
    fun aRestartedJvmAppIsReconnectedByTheBridgeFileWatch() {
        val directory = root.resolve("4242")
        val first = FakeApp(directory).closing()
        first.add { completeTx(it, url = "https://api.example.com/before") }
        val viewer = Viewer(jvmOnlyTargets(), jvmBridgeRoot = root).closing()
        viewer.start()
        val device = viewer.discovery.await { found -> found.devices.any { it is JvmBridge } }.devices.single() as JvmBridge
        assertEquals(first.bridgeFile, device.file)
        assertTrue(viewer.discovery.value.notes.any { "adb not found" in it })

        viewer.connect(Target(device, APP))
        assertEquals(APP, (viewer.status.await { it is ViewerStatus.Connected } as ViewerStatus.Connected).app)
        val before = viewer.session.value!!.store.value
        before.transactions.await { it.size == 1 }

        // The app ends: its bridge closes both connections and deletes bridge.json.
        first.close()
        viewer.status.await { it is ViewerStatus.Waiting && it.kind == WaitKind.AppStopped }

        // It starts again: the new bridge.json is the event, no Reconnect needed.
        val second = FakeApp(directory).closing()
        second.add { completeTx(it, url = "https://api.example.com/after") }
        viewer.status.await { it is ViewerStatus.Connected }
        val after = viewer.session.value!!.store.value
        assertNotSame(before, after, "another process, another store")
        assertEquals("https://api.example.com/after", after.transactions.await { it.size == 1 }.single().url)
    }

    /**
     * Decision 6 on the JVM: with the viewer connected and the app quiet, the app's bridge
     * threads (one accept, a reader and a writer per connection) take no CPU at all, and the app
     * serves exactly the viewer's two connections.
     */
    @Test
    fun theAppsBridgeThreadsAreIdleWhileTheViewerIsConnected() {
        // An earlier test's bridge threads may still be ending: only this test's count.
        val earlier = Thread.getAllStackTraces().keys.toSet()
        val app = FakeApp(root.resolve("1")).closing()
        repeat(3) { app.add { completeTx(it, responseBody = textBody("""{"n":$it}""")) } }
        val viewer = Viewer(jvmOnlyTargets(), jvmBridgeRoot = root).closing()
        viewer.start()
        viewer.connect(Target(JvmBridge(app.bridgeFile), APP))
        viewer.status.await { it is ViewerStatus.Connected }
        val store = viewer.session.value!!.store.value
        store.transactions.await { it.size == 3 }
        viewer.select(2)
        store.transactions.await { list -> list.any { it.id == 2L && it.responseBody?.isNotFetched == false } }

        fun bridgeThreads() = Thread.getAllStackTraces().keys.filter { it.isAlive && it !in earlier && it.name.startsWith("hopi-bridge") }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        // Test runs name coroutine-hosting threads `hopi-bridge-client @coroutine#N`.
        fun clients() = bridgeThreads().count { it.name.startsWith("hopi-bridge-client") }
        while (clients() != 2 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(2, clients(), "one per connection: tail and requests")

        val mx = java.lang.management.ManagementFactory.getThreadMXBean()
        Thread.sleep(300)
        val before = bridgeThreads().associate { it.id to mx.getThreadCpuTime(it.id) }
        Thread.sleep(3_000)
        val busy = before.filter { (id, cpu) -> mx.getThreadCpuTime(id) - cpu > 0 }
        assertEquals(emptyMap(), busy, "bridge threads that used CPU while idle (thread id to ns before)")
    }

    @Test
    fun disconnectClosesTheSessionAndAnUnknownTargetIsNotThrown() {
        val viewer = Viewer(jvmOnlyTargets(), jvmBridgeRoot = root).closing()
        viewer.start()
        viewer.connect(Target(JvmBridge(root.resolve("missing/bridge.json")), null))
        val waiting = viewer.status.await { it is ViewerStatus.Waiting } as ViewerStatus.Waiting
        assertEquals(WaitKind.AppStopped, waiting.kind)
        viewer.disconnect()
        viewer.status.await { it == ViewerStatus.Idle }
        viewer.session.await { it == null }
    }

    @Test
    fun androidEventsReconnectWithABoundedSettleAndEveryForwardIsRemoved() {
        val adb = ScriptedAdb().closing()
        val viewer = Viewer(adb.targets, jvmBridgeRoot = null, settleDelaysMs = listOf(50, 50)).closing()
        viewer.start()
        val devices = adb.stream("track-devices")
        devices.send("emulator-5554\tdevice\n")
        // The start's discovery and the one for that snapshot each listed the bridges.
        awaitCount(adb.bridgeListings, 2)
        val android = viewer.discovery.value.devices.single() as AndroidDevice
        assertEquals(listOf(APP), android.bridges, "the picker lists the bridge from /proc/net/unix")

        // Nothing serves the socket yet (adb accepts and closes): the app is reported stopped.
        viewer.connect(Target(android, APP))
        viewer.status.await { it is ViewerStatus.Waiting && it.kind == WaitKind.AppStopped }
        assertEquals(1, adb.accepted.get())

        // A debuggable process starts: one attempt at once and one per settle delay, then nothing.
        val jdwp = adb.stream("track-jdwp")
        jdwp.send("100\n")
        jdwp.send("100\n200\n")
        awaitCount(adb.accepted, 4)
        Thread.sleep(400)
        assertEquals(4, adb.accepted.get(), "the settle is bounded")

        // adb loses the device, then lists it again: that is the next event.
        devices.send("emulator-5554\toffline\n")
        viewer.status.await { it is ViewerStatus.Waiting && it.kind == WaitKind.DeviceOffline }
        devices.send("emulator-5554\tdevice\n")
        awaitCount(adb.accepted, 7)

        viewer.close()
        assertEquals(adb.forwards.toList(), adb.removed.toList(), "every adb forward was removed")
        assertEquals(7, adb.forwards.size)
    }

    /**
     * Closing the window while a connect hangs (an app stopped at a breakpoint answers `hello`
     * only at the read timeout): the adb tracking subprocesses end at once, not when the worker
     * is free, which may be after the viewer is gone.
     */
    @Test
    fun closeEndsTheAdbTrackersWhileAConnectStillHangs() {
        val adb = ScriptedAdb().closing()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hanging = { _: () -> BridgeEndpoint ->
            RemoteSession({
                entered.countDown()
                release.await(30, TimeUnit.SECONDS)
                throw CliException(ExitCode.NOT_REACHABLE, "hung")
            })
        }
        val viewer = Viewer(adb.targets, jvmBridgeRoot = null, newSession = hanging, settleDelaysMs = emptyList())
        viewer.start()
        adb.stream("track-devices").send("emulator-5554\tdevice\n")
        val android = viewer.discovery.await { it.devices.isNotEmpty() }.devices.single()
        viewer.connect(Target(android, APP))
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        adb.stream("track-jdwp")

        val closing = thread { viewer.close() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (adb.closedStreams.size < 2 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(setOf("track-devices", "track-jdwp"), adb.closedStreams.toSet(), "closed while the worker is still in the connect")
            assertTrue(closing.isAlive, "the connect still holds the worker")
        } finally {
            release.countDown()
            closing.join(15_000)
        }
    }

    /** On macOS the JDK's watch service polls: connected, no watch runs; waiting, only the bridge file's. */
    @Test
    fun watchesRunOnlyWhileThereIsSomethingToWaitFor() {
        val directory = root.resolve("77")
        val first = FakeApp(directory).closing()
        val viewer = Viewer(jvmOnlyTargets(), jvmBridgeRoot = root).closing()
        viewer.start()
        viewer.windowFocused(true)
        awaitThreads("the picker's JVM root watch, while nothing is connected") { "hopi-desktop-watch-jvm" in it }

        viewer.connect(Target(JvmBridge(first.bridgeFile), APP))
        viewer.status.await { it is ViewerStatus.Connected }
        awaitThreads("no watch while connected") { names -> names.none { it.startsWith("hopi-desktop-watch") } }

        first.close()
        viewer.status.await { it is ViewerStatus.Waiting && it.kind == WaitKind.AppStopped }
        awaitThreads("the bridge file's watch while waiting") { "hopi-desktop-watch-bridge" in it }

        // Back in front of nothing: in the background the picker's watch stops too.
        viewer.windowFocused(false)
        awaitThreads("no JVM root watch in the background") { "hopi-desktop-watch-jvm" !in it }
        FakeApp(directory).closing()
        viewer.status.await { it is ViewerStatus.Connected }
        awaitThreads("none once connected again") { names -> names.none { it.startsWith("hopi-desktop-watch") } }
    }

    private fun awaitThreads(what: String, predicate: (List<String>) -> Boolean) {
        fun names() = Thread.getAllStackTraces().keys.filter { it.isAlive }.map { it.name }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!predicate(names()) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(predicate(names()), "$what: ${names().filter { it.startsWith("hopi-desktop") }}")
    }

    private fun awaitCount(counter: AtomicInteger, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (counter.get() < expected && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(expected, counter.get())
    }
}

/**
 * adb from a script: one ready emulator with a bridge listed in `/proc/net/unix`, forwards to a
 * loopback socket that accepts and closes (adb when nothing listens in the app), and tracking
 * streams the test writes.
 */
private class ScriptedAdb : AutoCloseable {
    private val path = "/sdk/platform-tools/adb"
    private val server = ServerSocket(0, 50, BridgeClient.LOOPBACK)
    val accepted = AtomicInteger()
    val bridgeListings = AtomicInteger()
    val forwards: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val removed: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val closedStreams: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val streams = ConcurrentHashMap<String, Stream>()

    class Stream {
        private val sink = PipedOutputStream()
        val source = PipedInputStream(sink, 64 * 1024)

        fun send(frame: String) {
            sink.write(("%04x".format(frame.length) + frame).toByteArray())
            sink.flush()
        }

        fun close() {
            sink.close()
        }
    }

    init {
        thread(isDaemon = true, name = "scripted-adb-server") {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    return@thread
                }
                accepted.incrementAndGet()
                socket.close()
            }
        }
    }

    fun stream(service: String): Stream {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            streams[service]?.let { return it }
            Thread.sleep(10)
        }
        error("$service was never started")
    }

    private fun run(command: List<String>): ProcessResult {
        val args = command.drop(1)
        return when {
            args == listOf("devices", "-l") -> ProcessResult(0, "List of devices attached\nemulator-5554  device product:sdk model:Pixel_9 transport_id:1\n", "")
            args.takeLast(2) == listOf("cat", "/proc/net/unix") -> {
                bridgeListings.incrementAndGet()
                ProcessResult(0, "Num RefCount Protocol Flags Type St Inode Path\n0: 00000002 0 00010000 0001 01 81 @hopi.$APP\n", "")
            }
            // The app's proof file: its proof, then the key a client sends first.
            "run-as" in args -> ProcessResult(0, "${"5e".repeat(32)}\n${"a7".repeat(32)}\n", "")
            "--remove" in args -> {
                removed += args.last().removePrefix("tcp:").toInt()
                ProcessResult(0, "", "")
            }
            "--list" in args -> ProcessResult(0, forwards.joinToString("") { "emulator-5554 tcp:$it localabstract:hopi.$APP\n" }, "")
            "forward" in args -> {
                // Every forward reports the one socket's port: adb's own mapping is not under test.
                forwards += server.localPort
                ProcessResult(0, "${server.localPort}\n", "")
            }
            else -> ProcessResult(1, "", "unexpected adb ${args.joinToString(" ")}")
        }
    }

    val targets = Targets(
        HostEnvironment(
            processes = ::run,
            env = mapOf("ANDROID_HOME" to "/sdk"),
            userHome = "",
            isMac = false,
            isExecutable = { it.toString() == path },
            shutdownHooks = NoHooks,
            streams = { command ->
                val service = command.drop(1).first { it.startsWith("track-") }
                val stream = Stream()
                streams[service] = stream
                object : StreamingProcess {
                    override val input: InputStream = stream.source

                    override fun close() {
                        closedStreams += service
                        stream.source.close()
                    }
                }
            },
        ),
    )

    override fun close() {
        streams.values.forEach { it.close() }
        server.close()
    }
}
