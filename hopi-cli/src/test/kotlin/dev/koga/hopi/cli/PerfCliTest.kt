package dev.koga.hopi.cli

import dev.koga.hopi.connect.CliException
import dev.koga.hopi.connect.ExitCode
import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.perf.PerfHook
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.store.HopiStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The perf commands (docs/design/perf-monitors.md, "CLI"): parsing, and end to end against the real JVM bridge. */
class PerfCliTest {
    private val store = HopiStore.default
    private val root: Path = Files.createTempDirectory("hopi-cli-perf")
    private val bridge = JvmAgentBridge.start(store, root.resolve("bridge"), app = APP)
    private val bridgeFile = bridge.bridgeFile.toString()

    @BeforeTest
    fun setUp() = PerfHook.reset()

    @AfterTest
    fun tearDown() {
        PerfHook.reset()
        bridge.close()
        root.toFile().deleteRecursively()
    }

    private fun parse(vararg argv: String): CliArgs = assertIs<Invocation.Run>(parseInvocation(argv.toList())).args

    private fun usage(vararg argv: String): String {
        val failure = assertFailsWith<CliException> { parseInvocation(argv.toList()) }
        assertEquals(ExitCode.USAGE, failure.exitCode)
        return failure.message.orEmpty()
    }

    private fun startError(vararg argv: String): String =
        assertFailsWith<CliException> { PerfArgs.start(parse("perf", "start", "--app", "a.b", *argv)) }.message.orEmpty()

    private fun cli(vararg argv: String, stdout: OutputStream = ByteArrayOutputStream()): CliRun {
        val stderr = ByteArrayOutputStream()
        val environment = CliEnvironment(stdout, stderr, FakeProcessRunner(), emptyMap(), "/home/test", isMac = false, isExecutable = { false }, RecordingShutdownHooks(), 10_000, ByteArrayInputStream(ByteArray(0)))
        val code = runCli(argv.toList() + listOf("--bridge-file", bridgeFile), environment)
        return CliRun(code, (stdout as? ByteArrayOutputStream)?.toString(Charsets.UTF_8).orEmpty(), stderr.toString(Charsets.UTF_8))
    }

    private fun fake(remoteControl: Boolean = true) = FakePerfController(remoteControl).also { PerfHook.register(it) }

    private fun json(line: String) = Json.parseToJsonElement(line).jsonObject

    @Test
    fun parsesPerfCommandsAndTheirSwitches() {
        val args = parse("perf", "start", "--app", "a.b", "--hangs", "--cpu", "--hang-ms", "300", "--interval", "500ms", "--for", "90s")
        assertEquals(Command.PerfStart, args.command)
        assertTrue(args.command.isPerfCommand)
        assertTrue(!args.command.isRuleCommand)
        val start = PerfArgs.start(args)
        assertEquals(listOf("hangs", "cpu"), start.monitors)
        assertEquals(300L, start.hangMs)
        assertEquals(500L, start.intervalMs)
        assertEquals(90L, start.ttlSec)
        assertEquals(0L, PerfArgs.start(parse("perf", "start", "--app", "a.b", "--frames", "--no-expiry")).ttlSec)
        assertNull(PerfArgs.start(parse("perf", "start", "--app", "a.b", "--memory")).ttlSec, "the app's default lease")
        assertEquals(600_000L, PerfArgs.duration("10m", "--for"))
        assertEquals(listOf("hangs", "frames"), PerfArgs.tailStart(parse("perf", "tail", "--app", "a.b", "--start", "hangs, frames,hangs")))
        assertEquals(Command.Hangs, parse("hangs", "--app", "a.b", "--since", "12").command)
        assertEquals(12L, parse("hangs", "--app", "a.b", "--since", "12").since)
    }

    @Test
    fun perfUsageErrorsBeforeConnecting() {
        assertTrue("needs a subcommand" in usage("perf"))
        assertTrue("unknown command 'perf go'" in usage("perf", "go", "--app", "a.b"))
        assertTrue("does not take --hang-ms" in usage("perf", "stop", "--app", "a.b", "--hang-ms", "300"))
        assertTrue("takes no value" in usage("perf", "start", "--app", "a.b", "--hangs=yes"))
        assertTrue("perf event seq" in usage("hangs", "--app", "a.b", "--since", "-1"))
        assertTrue("at least one of --frames" in startError())
        assertTrue("goes with --hangs" in startError("--cpu", "--hang-ms", "300"))
        assertTrue("100..10000" in startError("--hangs", "--hang-ms", "50"))
        assertTrue("250ms..10s" in startError("--cpu", "--interval", "11s"))
        assertTrue("exclude each other" in startError("--cpu", "--for", "5m", "--no-expiry"))
        assertTrue("1s..60m" in startError("--cpu", "--for", "2h"))
        assertTrue("not a duration" in startError("--cpu", "--for", "soon"))
        assertTrue("unknown monitor 'gpu'" in assertFailsWith<CliException> { PerfArgs.tailStart(parse("perf", "tail", "--app", "a.b", "--start", "gpu")) }.message.orEmpty())
    }

    @Test
    fun anAppWithoutPerfExitsFour() {
        for (argv in listOf(arrayOf("perf", "status"), arrayOf("perf", "start", "--cpu"), arrayOf("hangs"))) {
            val run = cli(*argv)
            assertEquals(ExitCode.UNSUPPORTED, run.exitCode, run.toString())
            assertTrue("no perf monitors" in run.stderr, run.stderr)
        }
    }

    @Test
    fun startStatusStopEndToEnd() {
        val app = fake()
        val started = cli("perf", "start", "--hangs", "--hang-ms", "300", "--cpu", "--for", "2m")
        assertEquals(ExitCode.OK, started.exitCode, started.toString())
        assertEquals(setOf(PerfMonitor.HANGS, PerfMonitor.CPU), app.starts.single().first.monitors)
        assertEquals(300L, app.starts.single().first.hangThresholdMs)
        assertEquals(PerfOwner.Lease(120), app.starts.single().second)
        assertTrue(started.lines.first().startsWith("jvm · remote control on"), started.stdout)
        assertTrue(started.lines.any { Regex("""^hangs\s+on\s+250 ms\s+lease\s+0 hangs$""").matches(it) }, started.stdout)
        assertTrue(started.lines.any { Regex("""^frames\s+unavailable$""").matches(it) }, started.stdout)
        assertTrue(started.lines.any { Regex("""^memory\s+off$""").matches(it) }, started.stdout)

        app.hang(612)
        val status = cli("perf", "status")
        assertTrue(status.lines.any { it.startsWith("hangs") && it.endsWith("1 hang, worst 612 ms") }, status.stdout)
        val statusJson = json(cli("perf", "status", "--json").lines.single())
        assertEquals("perf.status", statusJson["type"]?.jsonPrimitive?.content)

        val stopped = cli("perf", "stop", "--hangs", "--json")
        assertEquals(ExitCode.OK, stopped.exitCode, stopped.toString())
        assertTrue("hangs" !in json(stopped.lines.single())["running"]!!.jsonObject)
        cli("perf", "stop")
        assertTrue(app.status().running.isEmpty())
    }

    @Test
    fun withoutRemoteControlStartExitsFourButStatusWorks() {
        fake(remoteControl = false)
        val refused = cli("perf", "start", "--cpu")
        assertEquals(ExitCode.UNSUPPORTED, refused.exitCode, refused.toString())
        assertTrue("remote control is off in its Hopi configuration" in refused.stderr, refused.stderr)
        val status = cli("perf", "status")
        assertEquals(ExitCode.OK, status.exitCode)
        assertTrue("remote control off" in status.lines.first(), status.stdout)
    }

    @Test
    fun snapshotPrintsSummariesEventsThreadsAndPss() {
        val app = fake()
        app.sample(1_700_000_000_000, cpu = 12.5, residentMb = 140)
        app.hang(612)
        val run = cli("perf", "snapshot", "--threads", "--pss")
        assertEquals(ExitCode.OK, run.exitCode, run.toString())
        assertTrue(run.lines.any { it.startsWith("hangs") && "1 hang, total 612 ms, worst 612 ms" in it }, run.stdout)
        assertTrue(run.lines.any { it.startsWith("cpu") && "avg 12.5%, max 12.5%" in it && "now 12.5% (Hopi 0.5%)" in it }, run.stdout)
        assertTrue(run.lines.any { it.startsWith("memory") && "resident 140.0 MB" in it }, run.stdout)
        assertTrue(run.lines.any { "hang 612 ms  CheckoutActivity  at dev.koga.sample.PerfLab.frame0(PerfLab.kt:1)" in it }, run.stdout)
        assertTrue(run.lines.any { Regex("""^\s*7\s+42\.5%\s+120\s+main$""").matches(it) }, run.stdout)
        assertTrue(run.lines.any { it.endsWith("hopi-perf-samp  (Hopi)") }, run.stdout)
        assertTrue(run.lines.contains("pss 53.0 MB"), run.stdout)

        val raw = cli("perf", "snapshot", "--json").lines.map { json(it)["type"]?.jsonPrimitive?.content }
        assertEquals(listOf("perf.series", "perf.series", "perf.series", "perf.summary", "perf.event", "perf.status"), raw)
    }

    @Test
    fun hangsPrintsEveryStackInFull() {
        val app = fake()
        val first = app.hang(612)
        app.hang(1_400)
        val run = cli("hangs")
        assertEquals(ExitCode.OK, run.exitCode, run.toString())
        assertEquals(2, run.lines.count { "hang " in it && it.startsWith("#") }, run.stdout)
        // Full stacks: all 12 frames, not the snapshot's 8.
        assertEquals(24, run.lines.count { it.startsWith("    at dev.koga.sample.PerfLab.frame") }, run.stdout)
        val since = cli("hangs", "--since", first.seq.toString(), "--json")
        assertEquals(1, since.lines.size, since.stdout)
        assertEquals(1_400L, json(since.lines.single())["ms"]?.jsonPrimitive?.content?.toLong())
        PerfHook.reset()
        fake()
        assertEquals(listOf("no hangs"), cli("hangs").lines)
    }

    @Test
    fun tailStreamsEventsAndSamplesAndHoldsWhatItStarted() {
        val app = fake()
        app.hang(500)
        val lines = LinkedBlockingQueue<String>()
        var exit = -1
        val runner = thread(name = "test-perf-tail") { exit = cli("perf", "tail", "--samples", "--start", "cpu", stdout = LineSink(lines)).exitCode }
        fun next(): String = assertNotNull(lines.poll(10, TimeUnit.SECONDS), "no line")
        assertTrue(next().startsWith("jvm · remote control on"))
        val status = generateSequence { next() }.takeWhile { !it.startsWith("#") }.toList()
        assertTrue(status.any { it.startsWith("cpu") && "tail:" in it }, status.toString())
        assertTrue(app.held(PerfMonitor.CPU))
        app.sample(1_700_000_003_000, cpu = 33.0, residentMb = 120)
        val sample = next()
        assertTrue("cpu 33.0% (Hopi 0.5%)  resident 120.0 MB" in sample, sample)
        // The app goes away: the tail ends with exit 2, and the app released the monitor it held.
        bridge.close()
        runner.join(15_000)
        assertEquals(ExitCode.NOT_REACHABLE, exit)
        val deadline = System.nanoTime() + 15_000_000_000
        while (app.held(PerfMonitor.CPU)) {
            check(System.nanoTime() < deadline) { "the tail's monitor was never released" }
            Thread.sleep(10)
        }
    }

    private class LineSink(private val lines: LinkedBlockingQueue<String>) : OutputStream() {
        private val pending = ByteArrayOutputStream()

        override fun write(b: Int) {
            if (b == '\n'.code) {
                lines.put(pending.toString(Charsets.UTF_8))
                pending.reset()
            } else {
                pending.write(b)
            }
        }
    }

    private companion object {
        const val APP = "dev.koga.cli.perf"
    }
}
