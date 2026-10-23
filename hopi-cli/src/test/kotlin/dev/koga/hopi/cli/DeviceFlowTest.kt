package dev.koga.hopi.cli

import dev.koga.hopi.core.bridge.AgentBridgeServer
import dev.koga.hopi.core.bridge.BridgeInfo
import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.store.HopiStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The adb and simctl flows with recorded subprocesses: a token-less bridge on loopback stands in
 * for adb's forward to the app's `LocalServerSocket`, and the iOS flow reads the `bridge.json` a
 * real bridge wrote into a fake app container.
 */
class DeviceFlowTest {
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = HopiStore(scope = storeScope)
    private val closeables = mutableListOf<AutoCloseable>()
    private val root: Path = Files.createTempDirectory("hopi-cli-devices")

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { it.close() }
        storeScope.cancel()
        root.toFile().deleteRecursively()
    }

    private fun record(path: String = "/users"): HttpTransaction {
        val id = store.nextId()
        val transaction = HttpTransaction.pending(id, 1_700_000_000_000, "GET", "https://api.example.com$path", emptyList(), null)
            .completed(200, listOf("Content-Type" to "application/json"), CapturedBody("{}".encodeToByteArray(), 2, false, "application/json", null), 12, "HTTP/1.1")
        store.submit(transaction)
        runBlocking { withTimeout(10_000) { store.transactions.first { list -> list.any { it.id == id } } } }
        return transaction
    }

    /** A token-less bridge, as an Android app serves it behind adb's forward; [proof] goes in its hello. */
    private fun androidBridge(app: String = APP, proof: String? = PROOF): Int {
        val listener = LoopbackListener()
        val server = AgentBridgeServer(listener, store, BridgeInfo(app, "android", proof), token = null)
        server.start()
        closeables += AutoCloseable { server.close() }
        return listener.port
    }

    private class Adb(
        private val devices: String,
        private val forwardList: () -> ProcessResult = { ProcessResult(1, "", "unexpected") },
        private val proof: () -> ProcessResult = { ok(PROOF) },
        private val remove: () -> ProcessResult = { ok() },
        private val forwardPort: () -> ProcessResult,
    ) {
        val runner = FakeProcessRunner { args ->
            when {
                args == listOf("devices", "-l") -> ok(devices)
                args.size == 4 && args[2] == "forward" && args[3] == "--list" -> forwardList()
                args.size == 5 && args[2] == "forward" && args[3] == "tcp:0" -> forwardPort()
                args.size == 5 && args[2] == "forward" && args[3] == "--remove" -> remove()
                args.drop(2) == listOf("exec-out", "run-as", APP, "cat", "cache/hopi/$APP.proof") -> proof()
                else -> ProcessResult(1, "", "unexpected")
            }
        }
        val forwards get() = runner.calls.map { it.drop(1) }.filter { it.getOrNull(3) == "tcp:0" }
        val removals get() = runner.calls.map { it.drop(1) }.filter { it.getOrNull(3) == "--remove" }
        val proofReads get() = runner.calls.map { it.drop(1) }.filter { it.getOrNull(3) == "run-as" }
    }

    private fun android(vararg argv: String, adb: Adb, hooks: RecordingShutdownHooks = RecordingShutdownHooks()) =
        runCapturing(*argv, processes = adb.runner, hooks = hooks, env = mapOf("ANDROID_HOME" to "/sdk"), executables = setOf(ADB))

    @Test
    fun androidForwardsTheAppSocketAndRemovesTheForward() {
        val transaction = record()
        val port = androidBridge()
        val adb = Adb(fixture("adb-devices.txt")) { ok("$port\n") }
        val hooks = RecordingShutdownHooks()
        val run = android("list", "--app", APP, "--json", adb = adb, hooks = hooks)
        assertEquals(ExitCode.OK, run.exitCode, run.toString())
        val line = Json.parseToJsonElement(run.lines.single()).jsonObject
        assertEquals(transaction.id.toString(), line["id"]!!.jsonPrimitive.content)

        assertEquals(listOf(ADB.toString()), adb.runner.calls.map { it.first() }.distinct(), "adb is run by its absolute path")
        assertEquals(listOf(listOf("-s", "emulator-5554", "forward", "tcp:0", "localabstract:hopi.$APP")), adb.forwards)
        assertEquals(listOf(listOf("-s", "emulator-5554", "forward", "--remove", "tcp:$port")), adb.removals)
        assertEquals(listOf(listOf("-s", "emulator-5554", "exec-out", "run-as", APP, "cat", "cache/hopi/$APP.proof")), adb.proofReads)
        assertEquals(1, hooks.added.size)
        assertEquals(emptyList(), hooks.active, "the shutdown hook outlived the command")
        assertFalse(PROOF in run.stdout || PROOF in run.stderr, "the proof leaked")
    }

    @Test
    fun androidRefusesASocketWhoseHelloDoesNotProveItIsTheApp() {
        record()
        val impostor = "hopi: localabstract:hopi.$APP on emulator-5554 is not $APP's bridge"
        // Another app bound the name first and answers hello with the right app id, but no proof.
        val squatter = androidBridge(proof = null)
        val missing = android("list", "--app", APP, "--json", adb = Adb(fixture("adb-devices.txt")) { ok("$squatter\n") })
        assertEquals(ExitCode.NOT_REACHABLE, missing.exitCode, missing.toString())
        assertEquals("", missing.stdout, "a squatter's transactions reached stdout")
        assertTrue(missing.stderr.startsWith(impostor), missing.stderr)

        val guessed = androidBridge(proof = "0".repeat(64))
        val clearAdb = Adb(fixture("adb-devices.txt")) { ok("$guessed\n") }
        val wrong = android("clear", "--app", APP, adb = clearAdb)
        assertEquals(ExitCode.NOT_REACHABLE, wrong.exitCode, wrong.toString())
        assertTrue(wrong.stderr.startsWith(impostor), wrong.stderr)
        assertEquals(1, store.since(0).size, "clear reached an unverified bridge")
        assertFalse(PROOF in wrong.stderr)
        assertEquals(1, clearAdb.removals.size)
    }

    @Test
    fun androidSaysWhenTheProofCannotBeRead() {
        val port = androidBridge()
        val adb = Adb(
            fixture("adb-devices.txt"),
            proof = { ProcessResult(1, "run-as: package not debuggable: $APP\n", "") },
        ) { ok("$port\n") }
        val run = android("list", "--app", APP, adb = adb)
        assertEquals(ExitCode.NOT_REACHABLE, run.exitCode, run.toString())
        assertEquals(
            "hopi: cannot verify the bridge of $APP on emulator-5554: run-as $APP could not read its proof " +
                "(run-as: package not debuggable: $APP); the app must be a debuggable build whose bridge started " +
                "(another app may hold the socket name)\n",
            run.stderr,
        )
    }

    @Test
    fun aFailingForwardRemovalNeverFailsTheCommandOrTheShutdownHook() {
        val transaction = record()
        val port = androidBridge()
        // adb cannot start, or `forward --remove` outlives the subprocess timeout.
        val adb = Adb(fixture("adb-devices.txt"), remove = { throw IOException("adb did not finish within 30 s") }) { ok("$port\n") }
        val hooks = RecordingShutdownHooks()
        val run = android("list", "--app", APP, "--json", adb = adb, hooks = hooks)
        assertEquals(ExitCode.OK, run.exitCode, run.toString())
        assertEquals("", run.stderr)
        assertEquals(transaction.id.toString(), Json.parseToJsonElement(run.lines.single()).jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(1, adb.removals.size)
    }

    @Test
    fun aFailingForwardRemovalDoesNotEscapeTheShutdownHook() {
        record()
        val port = androidBridge()
        val adb = Adb(fixture("adb-devices.txt"), remove = { throw IOException("adb did not finish within 30 s") }) { ok("$port\n") }
        val hooks = RecordingShutdownHooks()
        val lines = LinkedBlockingQueue<String>()
        val stdout = object : java.io.OutputStream() {
            private val pending = ByteArrayOutputStream()

            override fun write(b: Int) {
                if (b == '\n'.code) lines.put(pending.toString(Charsets.UTF_8)).also { pending.reset() } else pending.write(b)
            }
        }
        val tail = thread {
            runCli(
                listOf("tail", "--app", APP, "--json"),
                CliEnvironment(
                    stdout = stdout,
                    stderr = ByteArrayOutputStream(),
                    processes = adb.runner,
                    env = mapOf("ANDROID_HOME" to "/sdk"),
                    isMac = false,
                    isExecutable = { it == ADB },
                    shutdownHooks = hooks,
                ),
            )
        }
        assertNotNull(lines.poll(10, TimeUnit.SECONDS), "no tail line")
        // What the JVM does on SIGINT: the hook runs on its own thread, where an exception is uncaught.
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val hook = hooks.added.single()
        hook.setUncaughtExceptionHandler { _, e -> failures += e }
        hook.start()
        hook.join(10_000)
        assertEquals(emptyList(), failures.toList())
        assertEquals(1, adb.removals.size)

        closeables.removeAt(closeables.lastIndex).close()
        tail.join(10_000)
        assertFalse(tail.isAlive)
    }

    @Test
    fun androidAppNotRunningExitsTwoAndStillRemovesTheForward() {
        // adb accepts the forwarded connection and closes it when nothing listens in the app.
        val adbSide = ServerSocket(0, 8, BridgeClient.LOOPBACK)
        closeables += adbSide
        thread(isDaemon = true) {
            try {
                adbSide.accept().close()
            } catch (_: java.io.IOException) {
                // Closed by tearDown.
            }
        }
        val adb = Adb(fixture("adb-devices.txt")) { ok("${adbSide.localPort}\n") }
        val hooks = RecordingShutdownHooks()
        val run = android("get", "1", "--app", APP, adb = adb, hooks = hooks)
        assertEquals(ExitCode.NOT_REACHABLE, run.exitCode, run.toString())
        assertEquals(
            "hopi: app not running or agentBridge = false: nothing serves localabstract:hopi.$APP on emulator-5554\n",
            run.stderr,
        )
        assertEquals(1, adb.removals.size)
        assertEquals(emptyList(), hooks.active)
        assertEquals(emptyList(), adb.proofReads, "the proof is read only once a hello arrived")
    }

    /** Runs `tail` until its first line, then closes the bridge; returns the exit code and stderr. */
    private fun tailUntilBridgeCloses(adb: Adb): Pair<Int, String> {
        val lines = LinkedBlockingQueue<String>()
        val stdout = object : java.io.OutputStream() {
            private val pending = ByteArrayOutputStream()

            override fun write(b: Int) {
                if (b == '\n'.code) lines.put(pending.toString(Charsets.UTF_8)).also { pending.reset() } else pending.write(b)
            }
        }
        val stderr = ByteArrayOutputStream()
        var exit = -1
        val tail = thread {
            exit = runCli(
                listOf("tail", "--app", APP, "--json"),
                CliEnvironment(
                    stdout = stdout,
                    stderr = stderr,
                    processes = adb.runner,
                    env = mapOf("ANDROID_HOME" to "/sdk"),
                    isMac = false,
                    isExecutable = { it == ADB },
                    shutdownHooks = RecordingShutdownHooks(),
                ),
            )
        }
        assertNotNull(lines.poll(10, TimeUnit.SECONDS), "no tail line")
        closeables.removeAt(closeables.lastIndex).close()
        tail.join(10_000)
        assertFalse(tail.isAlive)
        return exit to stderr.toString(Charsets.UTF_8)
    }

    @Test
    fun androidTailSaysWhenAdbDroppedTheForward() {
        record()
        val port = androidBridge()
        // An emulator's adb transport reset: adb forgets every forward of the device, the app lives on.
        val adb = Adb(fixture("adb-devices.txt"), forwardList = { ok("emulator-5556 tcp:1234 localabstract:other\n") }) { ok("$port\n") }
        val (exit, stderr) = tailUntilBridgeCloses(adb)
        assertEquals(ExitCode.NOT_REACHABLE, exit)
        assertEquals(
            "hopi: adb lost its connection to emulator-5554 and dropped the forward; the app may still be running: run the command again\n",
            stderr,
        )
    }

    @Test
    fun androidTailSaysTheAppClosedWhenTheForwardIsStillThere() {
        record()
        val port = androidBridge()
        val adb = Adb(fixture("adb-devices.txt"), forwardList = { ok("emulator-5554 tcp:$port localabstract:hopi.$APP\n") }) { ok("$port\n") }
        val (exit, stderr) = tailUntilBridgeCloses(adb)
        assertEquals(ExitCode.NOT_REACHABLE, exit)
        assertEquals("hopi: the app closed the bridge connection (app stopped?)\n", stderr)
    }

    @Test
    fun androidShutdownHookRemovesTheForwardOnceDuringTail() {
        record()
        val port = androidBridge()
        val adb = Adb(fixture("adb-devices.txt")) { ok("$port\n") }
        val hooks = RecordingShutdownHooks()
        val lines = LinkedBlockingQueue<String>()
        val stdout = object : java.io.OutputStream() {
            private val pending = ByteArrayOutputStream()

            override fun write(b: Int) {
                if (b == '\n'.code) lines.put(pending.toString(Charsets.UTF_8)).also { pending.reset() } else pending.write(b)
            }
        }
        var exit = -1
        val tail = thread {
            exit = runCli(
                listOf("tail", "--app", APP, "--json"),
                CliEnvironment(
                    stdout = stdout,
                    stderr = ByteArrayOutputStream(),
                    processes = adb.runner,
                    env = mapOf("ANDROID_HOME" to "/sdk"),
                    isMac = false,
                    isExecutable = { it == ADB },
                    shutdownHooks = hooks,
                ),
            )
        }
        assertNotNull(lines.poll(10, TimeUnit.SECONDS), "no tail line")
        // What the JVM does on SIGINT.
        hooks.added.single().run()
        assertEquals(1, adb.removals.size)

        closeables.removeAt(closeables.lastIndex).close()
        tail.join(10_000)
        assertFalse(tail.isAlive)
        assertEquals(ExitCode.NOT_REACHABLE, exit)
        assertEquals(1, adb.removals.size, "the forward was removed twice")
        assertEquals(emptyList(), hooks.active)
    }

    @Test
    fun androidDeviceSelection() {
        val port = androidBridge()
        val two = Adb(fixture("adb-devices-two.txt")) { ok("$port\n") }
        val several = android("list", "--app", APP, adb = two)
        assertEquals(ExitCode.USAGE, several.exitCode, several.toString())
        assertTrue("several Android devices: pass --device <id> (emulator-5554, emulator-5556)" in several.stderr, several.stderr)
        assertEquals(emptyList(), two.forwards)

        val picked = android("list", "--app", APP, "--device", "emulator-5556", "--platform", "android", adb = two)
        assertEquals(ExitCode.OK, picked.exitCode, picked.toString())
        assertEquals("emulator-5556", two.forwards.single()[1])

        val unauthorized = android("list", "--app", APP, "--device", "R58M123ABCD", adb = Adb(fixture("adb-devices.txt")) { ok("$port") })
        assertEquals(ExitCode.NOT_REACHABLE, unauthorized.exitCode)
        assertTrue("Android device R58M123ABCD is unauthorized" in unauthorized.stderr, unauthorized.stderr)

        val unknown = android("list", "--app", APP, "--platform", "android", "--device", "emulator-9999", adb = two)
        assertEquals(ExitCode.NOT_REACHABLE, unknown.exitCode)
        assertTrue("emulator-9999 not found" in unknown.stderr, unknown.stderr)

        val none = android("list", "--app", APP, adb = Adb(fixture("adb-devices-empty.txt")) { ok("$port") })
        assertEquals(ExitCode.NOT_REACHABLE, none.exitCode)
        assertTrue("no device" in none.stderr, none.stderr)
    }

    @Test
    fun androidToolFailures() {
        val noAdb = runCapturing("list", "--app", APP, "--platform", "android")
        assertEquals(ExitCode.NOT_REACHABLE, noAdb.exitCode)
        assertEquals("hopi: adb not found: set ANDROID_HOME, or put adb on PATH\n", noAdb.stderr)

        val hooks = RecordingShutdownHooks()
        val refused = Adb(fixture("adb-devices.txt")) { ProcessResult(1, "", "error: cannot bind listener: Operation not permitted\n") }
        val run = android("list", "--app", APP, adb = refused, hooks = hooks)
        assertEquals(ExitCode.NOT_REACHABLE, run.exitCode)
        assertTrue("adb forward on emulator-5554 failed: error: cannot bind listener" in run.stderr, run.stderr)
        assertEquals(emptyList(), hooks.active)
        assertEquals(emptyList(), refused.removals)

        val noPort = android("list", "--app", APP, adb = Adb(fixture("adb-devices.txt")) { ok("") })
        assertEquals(ExitCode.NOT_REACHABLE, noPort.exitCode)
        assertTrue("printed no port" in noPort.stderr, noPort.stderr)
    }

    @Test
    fun androidAppMismatchIsRefused() {
        val port = androidBridge(app = "com.other.app")
        val run = android("clear", "--app", APP, adb = Adb(fixture("adb-devices.txt")) { ok("$port") })
        assertEquals(ExitCode.NOT_REACHABLE, run.exitCode)
        assertTrue("the bridge serves com.other.app, not $APP" in run.stderr, run.stderr)
    }

    private fun xcrun(container: Path?, simulators: String = fixture("simctl-booted.json")) = FakeProcessRunner { args ->
        when {
            args == listOf("simctl", "list", "devices", "booted", "-j") -> ok(simulators)
            args.take(2) == listOf("simctl", "get_app_container") && args.size == 5 && args[4] == "data" ->
                if (container != null && args[2] == UDID && args[3] == APP) ok("$container\n") else ProcessResult(2, "", "No such app")
            else -> ProcessResult(1, "", "unexpected")
        }
    }

    private fun ios(vararg argv: String, processes: ProcessRunner) =
        runCapturing(*argv, processes = processes, isMac = true, executables = setOf(Paths.get("/usr/bin/xcrun")))

    @Test
    fun iosReadsTheBridgeFileFromTheAppContainer() {
        val container = root.resolve("container")
        val bridge = JvmAgentBridge.start(store, container.resolve("Library/Caches/hopi"), app = APP)
        closeables += bridge
        val environment = CliEnvironment(
            stdout = ByteArrayOutputStream(),
            stderr = ByteArrayOutputStream(),
            processes = xcrun(container),
            env = emptyMap(),
            isMac = true,
            isExecutable = { it == Paths.get("/usr/bin/xcrun") },
        )
        val args = (parseInvocation(listOf("list", "--app", APP)) as Invocation.Run).args
        Targets(environment).open(args).use { endpoint ->
            assertEquals(Platform.Ios, endpoint.platform)
            assertEquals(bridge.port, endpoint.port)
            assertEquals(64, endpoint.token?.length)
            assertFalse(endpoint.token!! in endpoint.toString())
        }

        // The token was accepted (a hello came back); this bridge is a JVM one, so the platform check refuses it.
        val run = ios("list", "--app", APP, processes = xcrun(container))
        assertEquals(ExitCode.NOT_REACHABLE, run.exitCode)
        assertEquals("hopi: the bridge reports platform jvm, not ios\n", run.stderr)
    }

    @Test
    fun iosFailures() {
        val notInstalled = ios("list", "--app", APP, processes = xcrun(container = null))
        assertEquals(ExitCode.NOT_REACHABLE, notInstalled.exitCode)
        assertEquals("hopi: $APP is not installed on simulator iPhone 16 ($UDID)\n", notInstalled.stderr)

        val emptyContainer = root.resolve("empty").also { Files.createDirectories(it) }
        val notRunning = ios("list", "--app", APP, processes = xcrun(emptyContainer))
        assertEquals(ExitCode.NOT_REACHABLE, notRunning.exitCode)
        assertEquals("hopi: app not running or agentBridge = false: no bridge answers for $APP on iPhone 16\n", notRunning.stderr)

        val noneBooted = ios("list", "--app", APP, "--platform", "ios", processes = xcrun(emptyContainer, fixture("simctl-none.json")))
        assertEquals(ExitCode.NOT_REACHABLE, noneBooted.exitCode)
        assertTrue("no iOS simulator is booted" in noneBooted.stderr, noneBooted.stderr)

        val noXcode = runCapturing("list", "--app", APP, "--platform", "ios", isMac = false)
        assertEquals(ExitCode.NOT_REACHABLE, noXcode.exitCode)
        assertTrue("need macOS with Xcode" in noXcode.stderr, noXcode.stderr)
    }

    @Test
    fun platformInference() {
        val both = FakeProcessRunner { args ->
            when (args) {
                listOf("devices", "-l") -> ok(fixture("adb-devices.txt"))
                listOf("simctl", "list", "devices", "booted", "-j") -> ok(fixture("simctl-booted.json"))
                else -> ProcessResult(1, "", "unexpected")
            }
        }
        val executables = setOf(ADB, Paths.get("/usr/bin/xcrun"))
        val ambiguous = runCapturing("list", "--app", APP, processes = both, env = mapOf("ANDROID_HOME" to "/sdk"), isMac = true, executables = executables)
        assertEquals(ExitCode.USAGE, ambiguous.exitCode)
        assertTrue("pass --platform android or --platform ios" in ambiguous.stderr, ambiguous.stderr)

        val nothing = runCapturing("list", "--app", APP)
        assertEquals(ExitCode.NOT_REACHABLE, nothing.exitCode)
        assertTrue("no device: adb not found" in nothing.stderr, nothing.stderr)

        // A --device names its platform.
        val byDevice = runCapturing(
            "list", "--app", APP, "--device", UDID,
            processes = both, env = mapOf("ANDROID_HOME" to "/sdk"), isMac = true, executables = executables,
        )
        assertTrue("is not installed on simulator iPhone 16" in byDevice.stderr, byDevice.stderr)
    }

    @Test
    fun devicesListsBothPlatforms() {
        val both = FakeProcessRunner { args ->
            when (args) {
                listOf("devices", "-l") -> ok(fixture("adb-devices.txt"))
                listOf("simctl", "list", "devices", "booted", "-j") -> ok(fixture("simctl-booted.json"))
                else -> ProcessResult(1, "", "unexpected")
            }
        }
        val executables = setOf(ADB, Paths.get("/usr/bin/xcrun"))
        val json = runCapturing("devices", "--json", processes = both, env = mapOf("ANDROID_HOME" to "/sdk"), isMac = true, executables = executables)
        assertEquals(ExitCode.OK, json.exitCode, json.toString())
        assertEquals(
            listOf(
                """{"type":"device","platform":"android","id":"emulator-5554","state":"device","name":"sdk_gphone64_arm64","os":null}""",
                """{"type":"device","platform":"android","id":"R58M123ABCD","state":"unauthorized","name":null,"os":null}""",
                """{"type":"device","platform":"android","id":"192.168.1.20:5555","state":"offline","name":"Pixel_8_Pro","os":null}""",
                """{"type":"device","platform":"ios","id":"$UDID","state":"booted","name":"iPhone 16","os":"iOS 18.2"}""",
            ),
            json.lines,
        )

        val human = runCapturing("devices", processes = both, env = mapOf("ANDROID_HOME" to "/sdk"), isMac = true, executables = executables)
        val (header, emulator) = human.lines
        val simulator = human.lines[4]
        assertTrue(header.startsWith("PLATFORM  ID "), header)
        assertTrue(emulator.startsWith("android   emulator-5554 "), emulator)
        assertEquals(header.indexOf("STATE"), simulator.indexOf("booted"), human.stdout)
        assertEquals(header.indexOf("NAME"), simulator.indexOf("iPhone 16 (iOS 18.2)"), human.stdout)
        assertEquals(header.indexOf("NAME"), emulator.indexOf("sdk_gphone64_arm64"), human.stdout)

        val onlyIos = runCapturing("devices", "--platform", "ios", "--json", processes = both, env = mapOf("ANDROID_HOME" to "/sdk"), isMac = true, executables = executables)
        assertEquals(1, onlyIos.lines.size)

        val none = runCapturing("devices")
        assertEquals(ExitCode.OK, none.exitCode)
        assertEquals(listOf("no devices: start an emulator, connect a phone, or boot an iOS simulator"), none.lines)
    }

    private companion object {
        const val APP = "com.example.app"
        const val UDID = "90E767A1-5F25-4E8E-B1B2-D1A8F53295FA"
        val PROOF = "5e".repeat(32)
        val ADB: Path = Paths.get("/sdk/platform-tools/adb")
    }
}
