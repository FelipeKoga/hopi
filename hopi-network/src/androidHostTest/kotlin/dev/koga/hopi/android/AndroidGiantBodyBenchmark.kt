package dev.koga.hopi.android

import dev.koga.hopi.bench.GiantBodies
import dev.koga.hopi.bench.GiantBodies.MB
import dev.koga.hopi.bench.Report
import dev.koga.hopi.bench.Samples
import dev.koga.hopi.bench.mb
import dev.koga.hopi.bench.ms
import dev.koga.hopi.bench.ratio
import dev.koga.hopi.core.store.HopiStore
import java.util.function.Function
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * What the app's own request pays for a giant body under automatic capture (E1 R7 of the
 * runtime-split evaluation, `docs/design/runtime-split-evaluation/E1.md`): real OkHttp and the
 * JDK's `HttpURLConnection` against the loopback [TestServer]'s streamed `/giant?mb=N`, with a
 * `Content-Length` and chunked, 1, 5, 20 and 50 MiB. Each request is made by app code loaded
 * through the plugin's rewriter ([hopitest.GiantBodyScenario]), so it runs the hooks an
 * instrumented app runs, and reads the body to its end. Arms: capture not started (the hooks pass
 * through), and capture with a 256 KiB, 8 MiB and 16 MiB cap, the store twice the cap. Per request:
 * wall time and the bytes every live thread allocated, as `KtorGiantBodyBenchmark` measures the
 * Ktor plugin; with capture, the allocation also counts the store's pass that builds the request's
 * record (the join of a chunked body's blocks among it), which the scenario waits for after taking
 * the wall time. The bound to expect: capture allocates about 1x the cap more than no capture with
 * a `Content-Length`, about 2x chunked, whatever the body.
 *
 * Opt-in: `./gradlew :hopi-network:androidHostGiantBodyBenchmark` (excluded from
 * `testAndroidHostTest`); the table lands in `hopi-network/build/giant-body/android-request.md`.
 */
class AndroidGiantBodyBenchmark {
    private class Arm(val label: String, val maxBodyBytes: Long?)

    private val arms = listOf(
        Arm("no capture", null),
        Arm("cap 256 KB", 256L * 1024),
        Arm("cap 8 MB", 8L * MB),
        Arm("cap 16 MB", 16L * MB),
    )

    private enum class Transport(val label: String, val client: String, val chunked: Boolean) {
        OkHttpLength("OkHttp, Content-Length", "okhttp", false),
        OkHttpChunked("OkHttp, chunked", "okhttp", true),
        UrlLength("HttpURLConnection, Content-Length", "urlconnection", false),
        UrlChunked("HttpURLConnection, chunked", "urlconnection", true),
    }

    @Test
    fun appPerceivedRequestTime() {
        val report = Report(
            "android-request",
            "App-perceived request time with a giant body under automatic capture (median / p95 ms; alloc = bytes allocated by all threads per request)",
            "transport", "body", "arm", "median / p95 (ms)", "Δ median vs no capture (ms)", "alloc (MB)", "Δ alloc vs no capture (MB)", "Δ alloc / cap", "captured",
        )
        TestServer().use { server ->
            for (transport in Transport.entries) {
                for (size in SIZES_MB) {
                    // Made before any arm measures: the server streams the same bytes to each.
                    val body = TestServer.giantBody(size)
                    val runs = (600L * MB / body.size).toInt().coerceIn(8, 100)
                    val url = "${server.base}/giant?mb=$size" + if (transport.chunked) "&chunked=1" else ""
                    val results = runArms(transport, url, body.size.toLong(), runs)
                    val baseline = results.getValue(arms[0]).first
                    for (arm in arms) {
                        val (samples, captured) = results.getValue(arm)
                        val cap = arm.maxBodyBytes
                        val deltaAlloc = samples.medianAllocated - baseline.medianAllocated
                        report.row(
                            transport.label, GiantBodies.label(size * MB), arm.label,
                            "${ms(samples.medianNanos)} / ${ms(samples.p95Nanos)}",
                            if (cap == null) "-" else ms(samples.medianNanos - baseline.medianNanos),
                            mb(samples.medianAllocated),
                            if (cap == null) "-" else mb(deltaAlloc),
                            if (cap == null) "-" else ratio(deltaAlloc, minOf(cap, body.size.toLong())),
                            captured,
                        )
                    }
                }
            }
        }
        report.note("Arms run interleaved in $ROUNDS rounds per cell, after 2 warm-up requests each; each arm has a loader of its own, so its own rewritten OkHttp and its own capture, started with the cap or not at all.")
        report.note("'Δ alloc / cap' divides the extra allocation by the bytes capture may keep (min(cap, body)): a constant means the cost is bounded by the cap, not by the body. Expected about 1x with a Content-Length, about 2x chunked.")
        report.note("Allocation is summed over all live threads (JDK 17 has no total counter); the loopback server's threads, alive for the whole run, count alike in every arm.")
        report.note("With capture, each request's allocation includes the store's pass that builds its record (the join of a chunked body's blocks among it): the scenario waits for that pass (awaitApplied) after taking the wall time, so the store's thread counts in its own request's window, not in a later request's or another arm's.")
        report.save()
    }

    private fun runArms(transport: Transport, url: String, size: Long, runs: Int): Map<Arm, Pair<Samples, String>> {
        val stores = arms.associateWith { arm -> arm.maxBodyBytes?.let { HopiStore(maxTotalBytes = 2 * it) } }
        val scenarios = arms.associateWith { scenario() }
        val samples = arms.associateWith { Samples(runs) }
        try {
            val request: Map<String, Any?> = mapOf("client" to transport.client, "url" to url)
            for (arm in arms) {
                val warm = scenarios.getValue(arm).apply(request + mapOf("runs" to 2, "store" to stores[arm], "maxBodyBytes" to arm.maxBodyBytes))
                assertEquals(arm.maxBodyBytes != null, warm["started"], "capture started in the arm ${arm.label}")
            }
            repeat(ROUNDS) { round ->
                val perRound = runs / ROUNDS + if (round < runs % ROUNDS) 1 else 0
                for (arm in arms) {
                    val out = scenarios.getValue(arm).apply(request + ("runs" to perRound))
                    assertEquals(size, out["received"], "the arm ${arm.label} read the whole body")
                    val nanos = out["nanos"] as LongArray
                    val allocated = out["allocated"] as LongArray
                    for (i in nanos.indices) samples.getValue(arm).add(nanos[i], allocated[i])
                }
            }
            return arms.associateWith { arm -> samples.getValue(arm) to captured(stores[arm]) }
        } finally {
            stores.values.forEach { it?.close() }
        }
    }

    /** A fresh rewriting loader's scenario: its own OkHttp and its own `AndroidCapture`. */
    private fun scenario(): Function<Map<String, Any?>, Map<String, Any?>> {
        val loader = RewritingClassLoader(TestServer::class.java.classLoader)
        @Suppress("UNCHECKED_CAST")
        val scenario = loader.loadClass("hopitest.GiantBodyScenario").getDeclaredConstructor().newInstance() as Function<Map<String, Any?>, Map<String, Any?>>
        return scenario
    }

    /** What the cell's last request kept of its body. */
    private fun captured(store: HopiStore?): String {
        store ?: return "-"
        val last = runBlocking { withTimeout(30_000) { store.transactions.first { list -> list.isNotEmpty() && list.last().state.isTerminal } } }.last()
        val body = last.responseBody ?: return "none"
        body.skippedReason?.let { return it }
        return GiantBodies.label(body.bytes.size) + if (body.truncated) " (truncated)" else ""
    }

    private companion object {
        const val ROUNDS = 4
        val SIZES_MB = listOf(1, 5, 20, 50)
    }
}
