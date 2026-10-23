package dev.koga.hopi.cli

import dev.koga.hopi.bench.GiantBodies
import dev.koga.hopi.bench.GiantBodies.MB
import dev.koga.hopi.bench.Report
import dev.koga.hopi.bench.Shape
import dev.koga.hopi.bench.measure
import dev.koga.hopi.bench.mb
import dev.koga.hopi.bench.ms
import dev.koga.hopi.bench.ratio
import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.store.HopiStore
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * `hopi get` of a transaction with a giant response body, against the real JVM bridge: the
 * CLI's side only (read the line, parse it, render it), as `--json` and as human output.
 *
 * Opt-in: `./gradlew :hopi-cli:testGiantBodyBenchmark` (excluded from `test`).
 */
class CliGiantBodyBenchmark {
    @Test
    fun getOfAGiantBody() {
        val report = Report(
            "cli-get",
            "`hopi get` of one giant response body over the JVM bridge (median / p95 ms; allocation on the CLI's thread as a multiple of the captured size)",
            "shape", "captured", "get --json", "alloc", "get (human)", "alloc", "human output (MB)",
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val root = Files.createTempDirectory("hopi-cli-bench")
        try {
            for (shape in Shape.entries) {
                for ((bodySize, captured) in listOf(256 * GiantBodies.KB to 256 * GiantBodies.KB, MB to MB, 5 * MB to 5 * MB, 16 * MB to 16 * MB, 20 * MB to 16 * MB)) {
                    val all = GiantBodies.bytes(shape, bodySize)
                    val kept = if (bodySize != captured) all.copyOf(captured) else all
                    val store = HopiStore(maxTotalBytes = 1L shl 30, scope = scope)
                    val bridge = JvmAgentBridge.start(store, root.resolve("b${System.nanoTime()}"), app = "bench")
                    try {
                        val transaction = HttpTransaction.pending(store.nextId(), 0, "GET", "https://api.example.com/large", emptyList(), null)
                            .completed(
                                200, listOf("Content-Type" to "application/json"),
                                CapturedBody(kept, all.size.toLong(), kept.size < all.size, "application/json", null), 10, "HTTP/1.1",
                            )
                        store.submit(transaction)
                        runBlocking { withTimeout(10_000) { store.transactions.first { it.isNotEmpty() } } }
                        val file = bridge.bridgeFile.toString()
                        val runs = (96L * MB / kept.size).toInt().coerceIn(3, 20)
                        val json = measure(runs, warmup = 1, threadOnly = true) { get(file, json = true) }
                        var printed = 0L
                        val human = measure(runs, warmup = 1, threadOnly = true) { printed = get(file, json = false) }
                        val size = kept.size.toLong()
                        report.row(
                            shape.label, if (bodySize == captured) GiantBodies.label(captured) else "${GiantBodies.label(captured)} of ${GiantBodies.label(bodySize)}",
                            "${ms(json.medianNanos)} / ${ms(json.p95Nanos)}", ratio(json.medianAllocated, size),
                            "${ms(human.medianNanos)} / ${ms(human.p95Nanos)}", ratio(human.medianAllocated, size), mb(printed),
                        )
                    } finally {
                        bridge.close()
                        store.close()
                    }
                }
            }
        } finally {
            scope.cancel()
            root.toFile().deleteRecursively()
        }
        report.note("Allocation is the CLI's own thread (the bridge serves from its own threads, measured in :hopi-core's benchmark); stdout is counted, not kept.")
        report.save()
    }

    /** Runs `hopi get 1` and returns how many bytes it printed. */
    private fun get(bridgeFile: String, json: Boolean): Long {
        val stdout = CountingStream()
        val stderr = CountingStream()
        val environment = CliEnvironment(
            stdout = stdout,
            stderr = stderr,
            processes = FakeProcessRunner(),
            env = emptyMap(),
            userHome = "/home/test",
            isMac = false,
            isExecutable = { false },
            shutdownHooks = RecordingShutdownHooks(),
            readTimeoutMs = 60_000,
        )
        val argv = listOf("get", "1", "--bridge-file", bridgeFile) + if (json) listOf("--json") else emptyList()
        assertEquals(ExitCode.OK, runCli(argv, environment), "exit code")
        assertEquals(0L, stderr.count)
        return stdout.count
    }

    private class CountingStream : OutputStream() {
        var count = 0L

        override fun write(b: Int) {
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            count += len
        }
    }
}
