package dev.koga.hopi.core.bench

import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.json.encodeUtf8
import dev.koga.hopi.core.log.launchSummaryLogSink
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.store.HopiStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The store's and the summary line's CPU cost per request, measured inline: the store's consumer
 * (and the sink) run on an unconfined scope, so each submit applies, publishes and logs on the
 * benchmark's own thread before it returns. One request is a pending and a terminal snapshot of a
 * small exchange into a store that is already full (500 entries), the steady state of an app.
 *
 * Opt-in: `./gradlew :hopi-core:jvmTest -Phopi.hotpath` or `:hopi-core:iosSimulatorArm64Test -Phopi.hotpath`.
 */
class StoreHotPathBenchmark {
    private val perRound = 20_000

    private fun fullStore(scope: CoroutineScope): HopiStore {
        val store = HopiStore(maxEntries = HopiStore.DEFAULT_MAX_ENTRIES, scope = scope)
        repeat(HopiStore.DEFAULT_MAX_ENTRIES) { store.submit(benchComplete(store.nextId())) }
        return store
    }

    private fun HopiStore.request() {
        val id = nextId()
        submit(benchPending(id))
        submit(benchComplete(id))
    }

    @Test
    fun storeUpdateFullNoObserver() {
        if (!hotPathBenchmarkEnabled()) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = fullStore(scope)
        benchmark("store.update full=500 observers=none", perRound) { n -> repeat(n) { store.request() } }
        assertEquals(HopiStore.DEFAULT_MAX_ENTRIES, store.transactions.value.size)
        scope.cancel()
    }

    @Test
    fun storeUpdateFullWithCollector() {
        if (!hotPathBenchmarkEnabled()) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = fullStore(scope)
        var seen = 0
        scope.launch(start = CoroutineStart.UNDISPATCHED) { store.transactions.collect { seen += it.size } }
        benchmark("store.update full=500 observers=transactions-collector", perRound) { n -> repeat(n) { store.request() } }
        assertEquals(HopiStore.DEFAULT_MAX_ENTRIES, store.transactions.value.size)
        scope.cancel()
    }

    @Test
    fun storeUpdateFullWithSummarySink() {
        if (!hotPathBenchmarkEnabled()) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = fullStore(scope)
        var chars = 0L
        launchSummaryLogSink(store, scope = scope) { chars += it.length }
        benchmark("store.update+summary-sink full=500 (emit counts chars)", perRound) { n -> repeat(n) { store.request() } }
        scope.cancel()
    }

    @Test
    fun summaryLineAsString() {
        if (!hotPathBenchmarkEnabled()) return
        val transaction = benchComplete(42)
        val redactor = Redactor.Default
        var chars = 0L
        benchmark("summary-line String (Android Log.i input)", perRound) { n ->
            repeat(n) { chars += TransactionJson.summaryLogLine(transaction, redactor).length }
        }
    }

    @Test
    fun summaryLineAsUtf8() {
        if (!hotPathBenchmarkEnabled()) return
        val transaction = benchComplete(42)
        val redactor = Redactor.Default
        var bytes = 0L
        benchmark("summary-line String+UTF-8 copy (iOS os_log input)", perRound) { n ->
            repeat(n) { bytes += TransactionJson.summaryLogLine(transaction, redactor).encodeToByteArray().size }
        }
    }

    @Test
    fun summaryLineIntoReusedBuffers() {
        if (!hotPathBenchmarkEnabled()) return
        val transaction = benchComplete(42)
        val redactor = Redactor.Default
        val line = StringBuilder(512)
        var scratch = ByteArray(0)
        var bytes = 0L
        benchmark("summary-line reused builder+UTF-8 into reused bytes (sink's iOS path)", perRound) { n ->
            repeat(n) {
                line.setLength(0)
                TransactionJson.appendSummaryLogLine(transaction, redactor, TransactionJson.MAX_SUMMARY_LINE_BYTES, line)
                if (scratch.size < line.length * 3) scratch = ByteArray(line.length * 3)
                bytes += encodeUtf8(line, scratch)
            }
        }
        var chars = 0L
        benchmark("summary-line reused builder+toString (sink's Android path)", perRound) { n ->
            repeat(n) {
                line.setLength(0)
                TransactionJson.appendSummaryLogLine(transaction, redactor, TransactionJson.MAX_SUMMARY_LINE_BYTES, line)
                chars += line.toString().length
            }
        }
        benchmark("summary-line reused builder+toString+encodeToByteArray (iOS alternative)", perRound) { n ->
            repeat(n) {
                line.setLength(0)
                TransactionJson.appendSummaryLogLine(transaction, redactor, TransactionJson.MAX_SUMMARY_LINE_BYTES, line)
                bytes += line.toString().encodeToByteArray().size
            }
        }
    }
}
