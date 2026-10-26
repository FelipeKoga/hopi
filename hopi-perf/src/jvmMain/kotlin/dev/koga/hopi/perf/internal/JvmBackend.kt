package dev.koga.hopi.perf.internal

import dev.koga.hopi.core.perf.CpuSample
import dev.koga.hopi.core.perf.MemorySample
import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfPss
import dev.koga.hopi.core.perf.PerfThread
import dev.koga.hopi.core.perf.PerfTimer
import dev.koga.hopi.core.perf.perfWallClockMs
import java.lang.management.ManagementFactory
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The JVM target, for tests and host tools: CPU and memory only (there is no main looper, so no
 * hangs and no frames). One sampler thread `hopi-perf-samp` while either is on.
 */
internal class JvmBackend : MonitorBackend {
    override val platform: String = "jvm"
    override val available: Set<PerfMonitor> = setOf(PerfMonitor.CPU, PerfMonitor.MEMORY)

    private lateinit var data: PerfData
    private lateinit var lease: PerfTimer
    private var sampler: Sampler? = null
    private var cpu = false
    private var memory = false
    private var intervalMs = 1_000L

    override fun nowMs(): Long = System.nanoTime() / 1_000_000

    override fun attach(data: PerfData, lease: PerfTimer) {
        this.data = data
        this.lease = lease
    }

    override fun start(monitor: PerfMonitor, settings: PerfSettings): String? {
        when (monitor) {
            PerfMonitor.CPU -> cpu = true
            PerfMonitor.MEMORY -> memory = true
            else -> return "not available on the JVM"
        }
        intervalMs = settings.sampleIntervalMs
        val current = sampler
        if (current == null) sampler = Sampler().also { it.start() } else current.poke()
        return null
    }

    override fun stop(monitor: PerfMonitor) {
        when (monitor) {
            PerfMonitor.CPU -> cpu = false
            PerfMonitor.MEMORY -> memory = false
            else -> return
        }
        if (!cpu && !memory) {
            sampler?.stop()
            sampler = null
        }
    }

    override fun apply(settings: PerfSettings, running: Set<PerfMonitor>) {
        intervalMs = settings.sampleIntervalMs
        sampler?.poke()
    }

    override fun details(monitor: PerfMonitor): List<Pair<String, Any?>> = listOf("intervalMs" to intervalMs)

    override fun leaseChanged(running: Set<PerfMonitor>) {
        sampler?.poke()
    }

    override fun threads(limit: Int): List<PerfThread>? = null

    override fun watchThreads(watching: Boolean) = Unit

    override fun measurePss(): PerfPss = PerfPss(null, 0, "not available on the JVM")

    /** Thread names of the samplers alive now (tests: none while nothing runs). */
    val samplerAlive: Boolean get() = sampler?.thread?.isAlive == true

    private inner class Sampler {
        @Volatile private var running = true
        private val wake = Semaphore(0)
        val thread = Thread(::loop, "hopi-perf-samp").apply { isDaemon = true }
        private val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        private val threadsBean = ManagementFactory.getThreadMXBean()
        private var lastCpuNs = os?.processCpuTime ?: 0L
        private var lastWallNs = System.nanoTime()
        private var ownNs = 0L

        fun start() = thread.start()

        fun poke() = wake.release()

        fun stop() {
            running = false
            wake.release()
        }

        private fun loop() {
            var next = System.nanoTime()
            while (running) {
                val now = System.nanoTime()
                if (now >= next) {
                    sample(now)
                    next = now + intervalMs * 1_000_000
                }
                data.tick(perfWallClockMs() / 1000)
                if (lease.msUntilDue() <= 0) lease.fire()
                if (!running) break
                ownNs = threadsBean.currentThreadCpuTime.coerceAtLeast(0)
                val waitNs = minOf(next - System.nanoTime(), lease.msUntilDue().let { if (it == Long.MAX_VALUE) Long.MAX_VALUE else it * 1_000_000 })
                if (waitNs > 0) wake.tryAcquire(waitNs, TimeUnit.NANOSECONDS)
            }
        }

        private var lastOwnNs = 0L

        private fun sample(now: Long) {
            val cpuSample = if (cpu && os != null) {
                val cpuNs = os.processCpuTime
                val dt = (now - lastWallNs).coerceAtLeast(1)
                val process = (cpuNs - lastCpuNs) * 100.0 / dt
                val hopi = (ownNs - lastOwnNs) * 100.0 / dt
                lastCpuNs = cpuNs
                lastOwnNs = ownNs
                CpuSample(process, hopi, Runtime.getRuntime().availableProcessors())
            } else {
                null
            }
            lastWallNs = now
            val memorySample = if (memory) {
                val runtime = Runtime.getRuntime()
                val gc = ManagementFactory.getGarbageCollectorMXBeans()
                MemorySample(
                    javaHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
                    javaMaxBytes = runtime.maxMemory(),
                    nativeHeapBytes = null,
                    residentBytes = null,
                    availableBytes = null,
                    gcCount = gc.sumOf { it.collectionCount.coerceAtLeast(0) },
                    gcBlockingMs = gc.sumOf { it.collectionTime.coerceAtLeast(0) },
                    perturbed = false,
                )
            } else {
                null
            }
            if (cpuSample != null || memorySample != null) data.recordSample(perfWallClockMs(), cpuSample, memorySample)
        }
    }
}
