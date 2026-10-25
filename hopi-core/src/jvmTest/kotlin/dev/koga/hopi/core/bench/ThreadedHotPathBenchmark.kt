package dev.koga.hopi.core.bench

import dev.koga.hopi.core.log.launchSummaryLogSink
import dev.koga.hopi.core.store.HopiStore
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a request costs the threads behind the caller with the production wiring (the default store
 * and the summary log sink, as `CaptureInstallation` starts them): the caller submits a request's
 * start, waits, submits its end and waits again, about one request per millisecond like the device
 * A/B, and the benchmark reports the process CPU per request outside the caller's own thread, minus
 * an arm that only paces. That is the hand-off cost (wakes, scheduler spinning) plus the consumer's
 * and the sink's work, which in the app compete with the request thread for CPUs.
 *
 * Opt-in: `./gradlew :hopi-core:jvmTest -Phopi.hotpath`.
 */
class ThreadedHotPathBenchmark {
    private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    private val threads = ManagementFactory.getThreadMXBean()

    private class Arm(val backgroundCpuNsPerRequest: Double, val callerCpuNsPerRequest: Double)

    private fun run(requests: Int, store: HopiStore?): Arm {
        val done = CountDownLatch(1)
        val last = AtomicLong(0)
        val remove = store?.addEventListener { if (it.transaction.id == last.get() && it.transaction.state.isTerminal) done.countDown() }
        val processBefore = os.processCpuTime
        val callerBefore = threads.currentThreadCpuTime
        for (i in 0 until requests) {
            if (store != null) {
                val id = store.nextId()
                last.set(id)
                store.submit(benchPending(id))
                LockSupport.parkNanos(300_000)
                store.submit(benchComplete(id))
            } else {
                LockSupport.parkNanos(300_000)
            }
            LockSupport.parkNanos(700_000)
        }
        if (store != null) assertTrue(done.await(10, TimeUnit.SECONDS), "the store applied every write")
        val caller = threads.currentThreadCpuTime - callerBefore
        val process = os.processCpuTime - processBefore
        remove?.invoke()
        return Arm((process - caller).toDouble() / requests, caller.toDouble() / requests)
    }

    @Test
    fun productionWiringBackgroundCpuPerRequest() {
        if (!hotPathBenchmarkEnabled()) return
        val store = HopiStore()
        launchSummaryLogSink(store) { }
        repeat(HopiStore.DEFAULT_MAX_ENTRIES) { store.submit(benchComplete(store.nextId())) }
        val requests = 3000
        run(requests, store)
        run(requests, null)
        val rounds = List(5) { Pair(run(requests, null), run(requests, store)) }
        val deltas = rounds.map { (pace, hopi) -> hopi.backgroundCpuNsPerRequest - pace.backgroundCpuNsPerRequest }.sorted()
        val callers = rounds.map { (pace, hopi) -> hopi.callerCpuNsPerRequest - pace.callerCpuNsPerRequest }.sorted()
        println(
            "HOTPATH threaded default-store+sink background-cpu median=${format(deltas[2] / 1000)} µs/req " +
                "range=${format(deltas.first() / 1000)}..${format(deltas.last() / 1000)} caller-cpu median=${format(callers[2] / 1000)} µs/req (5x$requests)",
        )
        store.close()
    }
}
