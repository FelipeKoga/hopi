package dev.koga.hopi.perf.internal

import dev.koga.hopi.core.perf.MemorySample
import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfJson
import dev.koga.hopi.core.perf.PerfLimits
import dev.koga.hopi.core.perf.PerfThread
import dev.koga.hopi.core.perf.PerfTimer
import dev.koga.hopi.core.perf.emitPerfLogLine
import dev.koga.hopi.core.perf.perfWallClockMs
import dev.koga.hopi.perf.internal.native.hopi_perf_available_memory
import dev.koga.hopi.perf.internal.native.hopi_perf_footprint
import dev.koga.hopi.perf.internal.native.hopi_perf_now_ms
import dev.koga.hopi.perf.internal.native.hopi_perf_process_cpu_ns
import dev.koga.hopi.perf.internal.native.hopi_perf_thread_cpu_ns
import dev.koga.hopi.perf.internal.native.hopi_perf_thread_info
import dev.koga.hopi.perf.internal.native.hopi_perf_threads
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSQualityOfServiceUtility
import platform.Foundation.NSThread
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

/**
 * Hopi's own CPU time: each `hopi-perf-*` thread adds what it used since its last call just before
 * it parks (one `CLOCK_THREAD_CPUTIME_ID` read), so the sampler reads Hopi's share with no scan.
 * Each thread keeps its own last reading in the one-element array it passes.
 */
internal class IosSelfCpu {
    private val total = AtomicLong(0)

    val nanos: Long get() = total.load()

    fun begin(state: LongArray) {
        state[0] = hopi_perf_thread_cpu_ns()
    }

    fun account(state: LongArray) {
        val now = hopi_perf_thread_cpu_ns()
        total.addAndFetch(now - state[0])
        state[0] = now
    }
}

/**
 * The sampler thread `hopi-perf-samp` at the utility QoS, shared by CPU and memory: one wake per
 * interval for both, parked with a deadline on a dispatch semaphore, started only while either is
 * on (docs/design/perf-monitors.md, "CPU", "Memory"). It also closes the frames second that is
 * past (tier 2 writes frame seconds; no frames timer), carries a remote lease's expiry in its
 * timed wait, and scans the task's threads only while a viewer shows the list.
 */
internal class IosSampler(
    private val data: PerfData,
    private val lease: PerfTimer,
    private val selfCpu: IosSelfCpu,
) {
    private val cpuOn = AtomicBoolean(false)
    private val memoryOn = AtomicBoolean(false)
    private val interval = AtomicLong(PerfLimits.DEFAULT_SAMPLE_INTERVAL_MS)
    private val watching = AtomicBoolean(false)
    private val running = AtomicBoolean(true)
    private val wake = dispatch_semaphore_create(0)
    private val threads = IosThreadScan()

    var cpu: Boolean
        get() = cpuOn.load()
        set(value) = cpuOn.store(value)

    var memory: Boolean
        get() = memoryOn.load()
        set(value) = memoryOn.store(value)

    var intervalMs: Long
        get() = interval.load()
        set(value) = interval.store(value)

    var watchingThreads: Boolean
        get() = watching.load()
        set(value) = watching.store(value)

    // Sampler thread only.
    private val cpuRate = CpuRate(hopi_perf_process_cpu_ns(), selfCpu.nanos, hopi_perf_now_ms())
    private val cpuState = LongArray(1)
    private val cores = NSProcessInfo.processInfo.activeProcessorCount.toInt()

    fun start() {
        val thread = NSThread {
            selfCpu.begin(cpuState)
            try {
                loop()
            } catch (e: Throwable) {
                emitPerfLogLine(PerfJson.noticeLine("$THREAD_NAME thread failed: $e"))
            }
            selfCpu.account(cpuState)
        }
        thread.name = THREAD_NAME
        thread.qualityOfService = NSQualityOfServiceUtility
        thread.start()
    }

    fun poke() {
        dispatch_semaphore_signal(wake)
    }

    fun stop() {
        running.store(false)
        dispatch_semaphore_signal(wake)
    }

    private fun loop() {
        var next = hopi_perf_now_ms() + intervalMs
        while (running.load()) {
            val now = hopi_perf_now_ms()
            if (now >= next) {
                sample(now)
                next = now + intervalMs
            }
            data.tick(perfWallClockMs() / 1000)
            if (lease.msUntilDue() <= 0) lease.fire()
            if (!running.load()) break
            selfCpu.account(cpuState)
            val waitMs = minOf(next - hopi_perf_now_ms(), lease.msUntilDue())
            if (waitMs > 0) dispatch_semaphore_wait(wake, dispatch_time(DISPATCH_TIME_NOW, waitMs * 1_000_000))
        }
    }

    private fun sample(nowMs: Long) {
        val cpuSample = if (cpu) {
            cpuRate.sample(hopi_perf_process_cpu_ns(), selfCpu.nanos, nowMs, cores)
        } else {
            cpuRate.skip()
            null
        }
        val memorySample = if (memory) memorySample() else null
        if (cpuSample != null || memorySample != null) data.recordSample(perfWallClockMs(), cpuSample, memorySample)
        if (watchingThreads) threads.scan()
    }

    /** The latest scan, or one now when nobody keeps it running (a `perf.snapshot` with `threads`). */
    fun threads(limit: Int): List<PerfThread> = threads.top(limit, scanIfStale = !watchingThreads || !running.load())

    private companion object {
        const val THREAD_NAME = "hopi-perf-samp"
    }
}

/**
 * `phys_footprint` (what jetsam counts) as the resident figure and `os_proc_available_memory()`
 * as the headroom (null where the OS sets no limit: the simulator). The Kotlin/Native heap is not
 * sampled (its only source, `GC.lastGCInfo`, is experimental runtime API).
 */
internal fun memorySample(): MemorySample = MemorySample(
    javaHeapBytes = null,
    javaMaxBytes = null,
    nativeHeapBytes = null,
    residentBytes = hopi_perf_footprint().takeIf { it >= 0 },
    availableBytes = hopi_perf_available_memory().takeIf { it > 0 },
    gcCount = null,
    gcBlockingMs = null,
    perturbed = false,
)

/**
 * The per-thread CPU list: `task_threads` and three `thread_info` calls per thread, every port
 * deallocated in the same C call. The percentage is the kernel's own decayed `cpu_usage`, so a
 * first scan has one too. Hopi's own threads are recognised by the `hopi-` prefix.
 */
internal class IosThreadScan {
    private val latest = AtomicReference<List<PerfThread>>(emptyList())

    fun scan(): List<PerfThread> {
        val scanned = memScoped {
            val buffer = allocArray<hopi_perf_thread_info>(MAX_THREADS)
            val count = hopi_perf_threads(buffer, MAX_THREADS)
            List(count.coerceAtLeast(0)) { index ->
                val entry = buffer[index]
                val name = entry.name.toKString()
                PerfThread(
                    tid = entry.tid.toLong(),
                    name = PerfLimits.label(name) ?: "",
                    cpuMs = (entry.user_us + entry.system_us) / 1_000,
                    percent = entry.cpu_usage / 10.0,
                    hopi = name.startsWith("hopi-"),
                )
            }
        }
        latest.store(scanned)
        return scanned
    }

    fun top(limit: Int, scanIfStale: Boolean): List<PerfThread> {
        val threads = if (scanIfStale) scan() else latest.load()
        return threads.sortedWith(compareByDescending<PerfThread> { it.percent ?: -1.0 }.thenByDescending { it.cpuMs }).take(limit)
    }

    private companion object {
        const val MAX_THREADS = 512
    }
}
