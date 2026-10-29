package dev.koga.hopi.network.internal.ktor

import com.sun.net.httpserver.HttpServer
import dev.koga.hopi.bench.GiantBodies
import dev.koga.hopi.bench.GiantBodies.MB
import dev.koga.hopi.bench.Measure
import dev.koga.hopi.bench.Report
import dev.koga.hopi.bench.Samples
import dev.koga.hopi.bench.Shape
import dev.koga.hopi.bench.mb
import dev.koga.hopi.bench.ms
import dev.koga.hopi.bench.ratio
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ktor.HopiLogSink
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.discard
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
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
 * What the app's own request pays for a giant body: `client.get(url)` and reading the body to its
 * end (or the same with a giant request body), with and without the plugin, for every size, over
 * MockEngine and over a real local socket (JDK HttpServer on 127.0.0.1, OkHttp engine), with a
 * `Content-Length` and chunked, and with the default 256 KB cap and 8 MB / 16 MB caps.
 *
 * The plugin handles bytes, never JSON structure, so one shape (minified records) stands for all.
 * The app reads the body by draining its channel, the same way in every arm, so decoding (the
 * app's own cost) does not hide the difference.
 *
 * Opt-in: `./gradlew :hopi-network:jvmGiantBodyBenchmark` (excluded from `jvmTest`).
 */
class KtorGiantBodyBenchmark {
    private class Arm(val label: String, val maxBodyBytes: Long?, val maxTotalBytes: Long)

    private val arms = listOf(
        Arm("no plugin", null, 0),
        Arm("cap 256 KB", 256L * 1024, HopiStore.DEFAULT_MAX_TOTAL_BYTES),
        Arm("cap 8 MB", 8L * MB, 16L * MB),
        Arm("cap 16 MB", 16L * MB, 32L * MB),
    )

    private enum class Transport(val label: String) {
        MockLength("MockEngine, Content-Length"),
        MockChunked("MockEngine, no length"),
        SocketLength("socket, Content-Length"),
        SocketChunked("socket, chunked"),
        SocketStreamed("socket, chunked, streamed (execute {})"),
        MockUpload("MockEngine, request body (POST ByteArray)"),
    }

    private val bodies = HashMap<Int, ByteArray>()

    private fun body(size: Int): ByteArray = bodies.getOrPut(size) { GiantBodies.bytes(Shape.Minified, size) }

    @Test
    fun appPerceivedRequestTime() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            val bytes = body(query.getValue("size").toInt())
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, if (query["chunked"] == "1") 0 else bytes.size.toLong())
            exchange.responseBody.use { out ->
                var offset = 0
                while (offset < bytes.size) {
                    val n = minOf(64 * 1024, bytes.size - offset)
                    out.write(bytes, offset, n)
                    offset += n
                }
            }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}/body"
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val report = Report(
            "ktor-request",
            "App-perceived request time with a giant body (median / p95 ms; alloc = bytes allocated by all threads per request)",
            "transport", "body", "arm", "median / p95 (ms)", "Δ median vs no plugin (ms)", "alloc (MB)", "Δ alloc vs no plugin (MB)", "Δ alloc / cap", "captured",
        )
        try {
            for (transport in Transport.entries) {
                for (size in GiantBodies.SIZES) {
                    val bytes = body(size)
                    val runs = (600L * MB / bytes.size).toInt().coerceIn(8, 100)
                    val chunked = transport == Transport.SocketChunked || transport == Transport.SocketStreamed
                    val results = runArms(transport, bytes, "$base?size=$size&chunked=${if (chunked) 1 else 0}", runs, storeScope)
                    val baseline = results.getValue(arms[0])
                    for (arm in arms) {
                        val (samples, captured) = results.getValue(arm)
                        val deltaAlloc = samples.medianAllocated - baseline.first.medianAllocated
                        report.row(
                            transport.label, GiantBodies.label(size), arm.label,
                            "${ms(samples.medianNanos)} / ${ms(samples.p95Nanos)}",
                            if (arm.maxBodyBytes == null) "-" else ms(samples.medianNanos - baseline.first.medianNanos),
                            mb(samples.medianAllocated),
                            if (arm.maxBodyBytes == null) "-" else mb(deltaAlloc),
                            if (arm.maxBodyBytes == null) "-" else ratio(deltaAlloc, minOf(arm.maxBodyBytes, bytes.size.toLong())),
                            captured,
                        )
                    }
                }
            }
        } finally {
            storeScope.cancel()
            server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
        }
        report.note("Arms run interleaved in 4 rounds per cell, each arm with its own client; a new client every 20 requests bounds MockEngine's history.")
        report.note("'Δ alloc / cap' divides the extra allocation by the bytes the plugin may keep (min(cap, body)): a constant means the cost is bounded by the cap, not by the body.")
        report.note("Allocation is summed over all live threads (JDK 17 has no total counter); engine and dispatcher threads live for the whole run.")
        report.save()
    }

    private fun runArms(
        transport: Transport,
        bytes: ByteArray,
        target: String,
        runs: Int,
        storeScope: CoroutineScope,
    ): Map<Arm, Pair<Samples, String>> = runBlocking {
        val stores = arms.associateWith { arm ->
            arm.maxBodyBytes?.let { HopiStore(maxTotalBytes = arm.maxTotalBytes, scope = storeScope) }
        }
        val clients = arms.associateWith { arm -> ArmClient(transport, bytes, arm, stores[arm]) }
        val samples = arms.associateWith { Samples(runs) }
        try {
            val rounds = 4
            // Warm-up: two requests per arm.
            for (arm in arms) repeat(2) { clients.getValue(arm).request(target) }
            repeat(rounds) { round ->
                val perRound = runs / rounds + if (round < runs % rounds) 1 else 0
                for (arm in arms) {
                    val client = clients.getValue(arm)
                    repeat(perRound) {
                        val before = Measure.allocatedBytes()
                        val start = System.nanoTime()
                        val received = client.request(target)
                        val elapsed = System.nanoTime() - start
                        val after = Measure.allocatedBytes()
                        if (transport != Transport.MockUpload) assertEquals(bytes.size.toLong(), received)
                        samples.getValue(arm).add(elapsed, after - before)
                    }
                }
            }
            arms.associateWith { arm -> samples.getValue(arm) to captured(stores[arm], transport) }
        } finally {
            clients.values.forEach { it.close() }
            stores.values.forEach { it?.close() }
        }
    }

    /** What the last transaction kept of the body this cell measured. */
    private suspend fun captured(store: HopiStore?, transport: Transport): String {
        store ?: return "-"
        val last = withTimeout(30_000) { store.transactions.first { list -> list.isNotEmpty() && list.last().state.isTerminal } }.last()
        val body = (if (transport == Transport.MockUpload) last.requestBody else last.responseBody) ?: return "none"
        body.skippedReason?.let { return it }
        return "${GiantBodies.label(body.bytes.size)}${if (body.truncated) " (truncated)" else ""}"
    }

    private class ArmClient(
        private val transport: Transport,
        private val bytes: ByteArray,
        private val arm: Arm,
        private val armStore: HopiStore?,
    ) : AutoCloseable {
        private var client = newClient()
        private var used = 0

        private fun newClient(): HttpClient {
            val withLength = headersOf(
                HttpHeaders.ContentType to listOf("application/json"),
                HttpHeaders.ContentLength to listOf(bytes.size.toString()),
            )
            val withoutLength = headersOf(HttpHeaders.ContentType, "application/json")
            val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
                if (armStore != null) {
                    hopi(armStore) {
                        maxBodyBytes = arm.maxBodyBytes!!
                        maxTotalBytes = armStore.maxTotalBytes
                        logSink = HopiLogSink.Off
                    }
                }
            }
            return when (transport) {
                Transport.MockLength -> HttpClient(MockEngine { respond(bytes, HttpStatusCode.OK, withLength) }, configure)
                Transport.MockChunked -> HttpClient(MockEngine { respond(ByteReadChannel(bytes), HttpStatusCode.OK, withoutLength) }, configure)
                Transport.MockUpload -> HttpClient(MockEngine { respond("{\"ok\":true}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }, configure)
                Transport.SocketLength, Transport.SocketChunked, Transport.SocketStreamed -> HttpClient(OkHttp, configure)
            }
        }

        /** One request as the app makes it; returns the body bytes the app read. */
        suspend fun request(url: String): Long {
            if (used == FRESH_CLIENT_EVERY) {
                client.close()
                client = newClient()
                used = 0
            }
            used++
            return when (transport) {
                Transport.SocketStreamed -> client.prepareGet(url).execute { it.bodyAsChannel().discard() }
                Transport.MockUpload -> client.post(url) {
                    setBody(bytes)
                    headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                }.bodyAsChannel().discard()
                else -> client.get(url).bodyAsChannel().discard()
            }
        }

        override fun close() = client.close()
    }

    private companion object {
        const val FRESH_CLIENT_EVERY = 20
    }
}
