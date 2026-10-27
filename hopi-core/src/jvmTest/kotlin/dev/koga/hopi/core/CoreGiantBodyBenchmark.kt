package dev.koga.hopi.core

import dev.koga.hopi.bench.GiantBodies
import dev.koga.hopi.bench.GiantBodies.MB
import dev.koga.hopi.bench.Measure
import dev.koga.hopi.bench.Report
import dev.koga.hopi.bench.Shape
import dev.koga.hopi.bench.iterationsFor
import dev.koga.hopi.bench.mb
import dev.koga.hopi.bench.measure
import dev.koga.hopi.bench.ms
import dev.koga.hopi.bench.ratio
import dev.koga.hopi.core.body.isJson
import dev.koga.hopi.core.body.prettyPrintJson
import dev.koga.hopi.core.body.textOrNull
import dev.koga.hopi.core.bridge.BridgeConnection
import dev.koga.hopi.core.bridge.BridgeInfo
import dev.koga.hopi.core.bridge.BridgeSession
import dev.koga.hopi.core.curl.toCurl
import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.store.HopiStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Core's formatting and encoding of giant bodies, as the UI, the CLI and the bridge use them, for
 * every shape and every captured size up to 16 MB (a 20 MB body captured with a 16 MB cap is the
 * truncated case), plus the store's byte cap under a flood of large transactions.
 *
 * Opt-in: `./gradlew :hopi-core:jvmGiantBodyBenchmark` (excluded from `jvmTest`).
 */
class CoreGiantBodyBenchmark {
    private val redactor = Redactor.Default

    /** Captured sizes: complete bodies, then a 20 MB body captured with a 16 MB cap. */
    private val cases: List<Case> = buildList {
        for (size in listOf(256 * GiantBodies.KB, MB, 5 * MB, 16 * MB)) add(Case(size, size))
        add(Case(20 * MB, 16 * MB))
    }

    private class Case(val bodyBytes: Int, val capturedBytes: Int) {
        val label: String
            get() = if (bodyBytes == capturedBytes) GiantBodies.label(bodyBytes) else "${GiantBodies.label(capturedBytes)} of ${GiantBodies.label(bodyBytes)}"
    }

    private fun captured(shape: Shape, case: Case): CapturedBody {
        val all = GiantBodies.bytes(shape, case.bodyBytes)
        // A complete case keeps the few closing bytes a fixture has beyond its nominal size.
        val kept = if (case.bodyBytes != case.capturedBytes) all.copyOf(case.capturedBytes) else all
        return CapturedBody(kept, all.size.toLong(), truncated = kept.size < all.size, "application/json", null)
    }

    @Test
    fun unicodeFixtureCutsAMultiByteSequenceAtTheDefaultCap() {
        val all = GiantBodies.bytes(Shape.Unicode, MB)
        assertEquals(0x80, all[GiantBodies.DEFAULT_CAP].toInt() and 0xC0, "byte 262144 is a continuation byte")
        val body = CapturedBody(all.copyOf(GiantBodies.DEFAULT_CAP), all.size.toLong(), true, "application/json", null)
        val text = assertNotNull(body.textOrNull(), "a truncated Unicode body still decodes")
        assertTrue(text.encodeToByteArray().size in GiantBodies.DEFAULT_CAP - 3..GiantBodies.DEFAULT_CAP)
    }

    @Test
    fun formatting() {
        val report = Report(
            "core-formatting",
            "Core formatting per captured body (median / p95 ms, allocated bytes per call as a multiple of the captured size)",
            "shape", "captured", "textOrNull", "alloc", "isJson", "prettyPrintJson", "alloc", "pretty out/in", "toCurl (request body)", "alloc",
            "toCurl, 100k chars (copy, share)", "alloc",
        )
        for (shape in Shape.entries) {
            for (case in cases) {
                val body = captured(shape, case)
                val size = body.bytes.size.toLong()
                val runs = iterationsFor(body.bytes.size, budgetBytes = 160L * MB, min = 5, max = 40)
                var text: String? = null
                val decode = measure(runs, threadOnly = true) { text = body.textOrNull() }
                val decoded = assertNotNull(text, "${shape.label} ${case.label} decodes")
                val json = measure(runs, threadOnly = true) { check(isJson(body.contentType, decoded)) }
                var pretty = ""
                val print = measure(runs, threadOnly = true) { pretty = prettyPrintJson(decoded) }
                val request = HttpTransaction.pending(
                    1, 0, "POST", "https://api.example.com/upload",
                    listOf("Content-Type" to "application/json", "Content-Length" to case.bodyBytes.toString()), body,
                )
                var curl = ""
                val curlSamples = measure(runs, threadOnly = true) { curl = toCurl(request, redactor) }
                assertTrue(curl.length > decoded.length)
                var capped = ""
                val cappedSamples = measure(runs, threadOnly = true) { capped = toCurl(request, redactor, maxChars = 100_000) }
                assertTrue(capped.length <= 100_000)
                report.row(
                    shape.label, case.label,
                    "${ms(decode.medianNanos)} / ${ms(decode.p95Nanos)}", ratio(decode.medianAllocated, size),
                    "${ms(json.medianNanos)} / ${ms(json.p95Nanos)}",
                    "${ms(print.medianNanos)} / ${ms(print.p95Nanos)}", ratio(print.medianAllocated, size),
                    if (pretty === decoded) "unchanged" else ratio(pretty.length.toLong(), decoded.length.toLong()),
                    "${ms(curlSamples.medianNanos)} / ${ms(curlSamples.p95Nanos)}", ratio(curlSamples.medianAllocated, size),
                    "${ms(cappedSamples.medianNanos)} / ${ms(cappedSamples.p95Nanos)}", ratio(cappedSamples.medianAllocated, size),
                )
            }
        }
        report.note("textOrNull = strict UTF-8 decode plus the NUL scan; allocation is per call on the calling thread (exact).")
        report.note("'unchanged' means prettyPrintJson returned its input: invalid (truncated) JSON or nesting beyond 256.")
        report.note("toCurl, 100k chars: the bound the UI's copy cURL and share use (maxChars = 100 000); it decodes and quotes only what fits.")
        report.save()
    }

    @Test
    fun bridgeGetDetail() {
        val report = Report(
            "core-bridge-get",
            "Bridge `get` of one transaction with a giant response body, through a real BridgeSession (median / p95 ms; allocation over all threads; transient peak heap)",
            "shape", "captured", "bridgeDetail()", "alloc", "session get", "bytes sent", "alloc", "peak heap delta (MB)",
        )
        val shapes = Shape.entries.map { it.label to { case: Case -> captured(it, case) } } +
            ("binary (base64)" to { case: Case -> binary(case.capturedBytes) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            for ((label, make) in shapes) {
                for (case in cases) {
                    val body = make(case)
                    val size = body.bytes.size.toLong()
                    val store = HopiStore(maxTotalBytes = 1L shl 30, scope = scope)
                    try {
                        val transaction = HttpTransaction.pending(store.nextId(), 0, "GET", "https://api.example.com/large", emptyList(), null)
                            .completed(200, listOf("Content-Type" to "application/json"), body, 10, "HTTP/1.1")
                        store.submit(transaction)
                        runBlocking { withTimeout(10_000) { store.transactions.first { it.isNotEmpty() } } }
                        val runs = iterationsFor(body.bytes.size, budgetBytes = 160L * MB, min = 5, max = 30)
                        val detail = measure(runs, threadOnly = true) { TransactionJson.bridgeDetail(transaction, redactor) }
                        var sent = 0L
                        var otherThreads = 0L
                        // The writer is a new thread per session that ends with it, so its
                        // allocation is read by the connection on that thread, not by Measure.
                        val session = measure(runs, threadOnly = true) {
                            val result = getOverSession(store, scope)
                            sent = result.first
                            otherThreads = result.second
                        }
                        Measure.retainedHeap()
                        Measure.resetPeak()
                        val base = Measure.peakHeap()
                        getOverSession(store, scope)
                        val peak = Measure.peakHeap() - base
                        report.row(
                            label, case.label,
                            "${ms(detail.medianNanos)} / ${ms(detail.p95Nanos)}", ratio(detail.medianAllocated, size),
                            "${ms(session.medianNanos)} / ${ms(session.p95Nanos)}", mb(sent),
                            ratio(session.medianAllocated + otherThreads, size), mb(peak),
                        )
                    } finally {
                        store.close()
                    }
                }
            }
        } finally {
            scope.cancel()
        }
        report.note("'session get' runs BridgeSession.run for one `get` line and waits until the writer closed the connection; bytes are counted, not kept.")
        report.note("Peak heap delta is the sum of the heap pools' peaks after a reset minus their use at the reset: an upper bound of the transient.")
        report.save()
    }

    @Test
    fun storeByteCap() {
        val report = Report(
            "core-store",
            "Store under a flood of large transactions (default 500 entries; body sizes and byte caps as listed)",
            "body", "maxTotalBytes", "consumer", "submitted", "submit median / p95 (µs)", "retained entries",
            "store bytes (MB)", "retained heap delta (MB)", "retained beyond cap (MB)",
        )
        for ((bodySize, cap) in listOf(256 * GiantBodies.KB to 16L * MB, MB to 16L * MB, 8 * MB to 32L * MB, 16 * MB to 32L * MB, 20 * MB to 32L * MB)) {
            report.row(*storeCase(bodySize, cap, stalled = false))
        }
        report.row(*storeCase(8 * MB, 32L * MB, stalled = true))
        report.note("Each case runs in its own frame and waits for its store's consumer to end, so nothing of the previous case is reachable; deltas are against a full GC before the case.")
        report.note("G1 stores an array of 1 MB or more as a humongous object rounded up to whole 1 MB regions (a 1 MB array takes 2 MB), which is most of the 'beyond cap' column on this JVM; ART has no such rounding.")
        report.note("'stalled' blocks the store's consumer thread while submitting: the retained heap is then the queued bodies, at most maxTotalBytes of them (later ends go in with their sizes only, 'store backlog over byte cap'), measured before the consumer is released.")
        report.save()
    }

    private fun storeCase(bodySize: Int, cap: Long, stalled: Boolean): Array<String> {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        try {
            val baseline = Measure.retainedHeap()
            val store = HopiStore(maxTotalBytes = cap, scope = scope)
            val gate = CountDownLatch(1)
            if (stalled) executor.execute { gate.await() }
            val count = if (stalled) 32 else (640L * MB / bodySize).toInt().coerceIn(32, 200)
            val submit = dev.koga.hopi.bench.Samples(count)
            repeat(count) { i ->
                // A fresh array per transaction, as captures are: the store must not retain them all.
                val body = CapturedBody(ByteArray(bodySize) { (it % 251).toByte() }, bodySize.toLong(), false, "application/json", null)
                val transaction = HttpTransaction.pending(store.nextId(), 0, "GET", "https://api.example.com/$i", emptyList(), null)
                    .completed(200, emptyList(), body, 1, "HTTP/1.1")
                val start = System.nanoTime()
                check(store.submit(transaction))
                submit.add(System.nanoTime() - start, 0)
            }
            val stalledRetained = if (stalled) Measure.retainedHeap() - baseline else 0L
            gate.countDown()
            val last = runBlocking { withTimeout(30_000) { store.transactions.first { it.lastOrNull()?.id == count.toLong() } } }
            val storeBytes = last.sumOf { it.capturedBytes }
            val retained = if (stalled) stalledRetained else Measure.retainedHeap() - baseline
            assertTrue(storeBytes <= cap, "store bytes $storeBytes within $cap")
            store.close()
            return arrayOf(
                GiantBodies.label(bodySize), GiantBodies.label(cap.toInt()), if (stalled) "stalled" else "running", count.toString(),
                "${dev.koga.hopi.bench.us(submit.medianNanos)} / ${dev.koga.hopi.bench.us(submit.p95Nanos)}",
                last.size.toString(), mb(storeBytes), mb(retained), mb(retained - cap),
            )
        } finally {
            runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.join() } }
            scope.cancel()
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    private fun binary(size: Int): CapturedBody {
        val bytes = Random(7).nextBytes(size)
        bytes[0] = 0 // a NUL makes it binary even where the random bytes happen to be UTF-8
        return CapturedBody(bytes, size.toLong(), false, "application/json", null)
    }

    /**
     * Serves one `get` over a fresh session; returns the bytes it wrote and what the session's
     * other threads (its writer) allocated.
     */
    private fun getOverSession(store: HopiStore, scope: CoroutineScope): Pair<Long, Long> {
        val connection = OneRequestConnection("{\"cmd\":\"get\",\"id\":1}\n")
        BridgeSession(connection, store, BridgeInfo("bench", "jvm"), token = null, scope).run()
        check(connection.closed.await(60, TimeUnit.SECONDS)) { "the session did not close" }
        check(connection.lastByte == '\n'.code.toByte())
        val caller = Thread.currentThread().id
        return connection.written to connection.allocatedByThread.filterKeys { it != caller }.values.sum()
    }

    /** Sends one request, then end of input; counts what the session writes and keeps none of it. */
    private class OneRequestConnection(request: String) : BridgeConnection {
        private var pending: ByteArray? = request.encodeToByteArray()
        val closed = CountDownLatch(1)

        @Volatile var written = 0L
        @Volatile var lastByte: Byte = 0

        /** Per thread that called in: what it had allocated at its last call (threads start near 0). */
        val allocatedByThread = java.util.concurrent.ConcurrentHashMap<Long, Long>()

        private fun note() {
            allocatedByThread[Thread.currentThread().id] = Measure.threadAllocatedBytes()
        }

        override fun read(buffer: ByteArray): Int {
            val bytes = pending ?: return -1
            pending = null
            bytes.copyInto(buffer)
            return bytes.size
        }

        override fun write(bytes: ByteArray, length: Int) {
            written += length
            if (length > 0) lastByte = bytes[length - 1]
            note()
        }

        override fun shutdown() = note()

        override fun close() {
            note()
            closed.countDown()
        }
    }
}
