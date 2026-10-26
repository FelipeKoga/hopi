package dev.koga.hopi.perf.internal

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import dev.koga.hopi.core.perf.MemorySample
import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfLimits
import dev.koga.hopi.core.perf.PerfPss
import dev.koga.hopi.core.perf.PerfThread
import dev.koga.hopi.core.perf.PerfTimer
import dev.koga.hopi.core.perf.perfWallClockMs
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Hopi's own CPU time: each `hopi-perf-*` thread adds what it used since its last call just before
 * it parks (one thread-CPU clock read), so the sampler reads Hopi's share without opening a file.
 */
internal class SelfCpu {
    private val total = AtomicLong(0)
    private val last = ThreadLocal<LongArray>()

    val nanos: Long get() = total.get()

    fun account() {
        val now = Debug.threadCpuTimeNanos()
        if (now < 0) return
        val previous = last.get() ?: LongArray(1).also { last.set(it) }
        total.addAndGet(now - previous[0])
        previous[0] = now
    }
}

/**
 * The sampler thread `hopi-perf-samp`, shared by CPU and memory: one wake per interval for both,
 * parked with a deadline, started only while either is on (docs/design/perf-monitors.md, "CPU",
 * "Memory"). It also closes the frames second that is past (no frames timer), carries a remote
 * lease's expiry in its timed wait, and scans `/proc/self/task` only while a viewer shows the
 * thread list.
 */
internal class AndroidSampler(
    private val data: PerfData,
    private val lease: PerfTimer,
    private val selfCpu: SelfCpu,
    /** Whether the hang monitor's printer is set: its garbage perturbs the GC figures. */
    private val printerSet: () -> Boolean,
) {
    @Volatile var cpu = false

    @Volatile var memory = false

    @Volatile var intervalMs = PerfLimits.DEFAULT_SAMPLE_INTERVAL_MS

    @Volatile var watchingThreads = false

    @Volatile private var running = true

    // Woken with `LockSupport.unpark`, not a `Semaphore`: a debug app runs this thread in ART's
    // interpreter (once a second never gets it compiled, and a debuggable app is never AOT
    // compiled), where a timed `Semaphore.tryAcquire` walks AbstractQueuedSynchronizer's queue
    // code; the park saved about 5 % of the tick (64 to 61 ms per minute, schedstat, emulator).
    private val thread = Thread(::loop, "hopi-perf-samp").apply { isDaemon = true }
    private val threads = ThreadScan()

    // Sampler thread only.
    private val cpuRate = CpuRate(Process.getElapsedCpuTime() * 1_000_000, selfCpu.nanos, SystemClock.elapsedRealtime())
    private val cores = Runtime.getRuntime().availableProcessors()
    private var statm: FileDescriptor? = null
    private val statmBuffer = ByteArray(128)
    private val pageSize = try {
        Os.sysconf(OsConstants._SC_PAGESIZE)
    } catch (_: Exception) {
        4096L
    }

    fun start() = thread.start()

    fun poke() = LockSupport.unpark(thread)

    fun stop() {
        running = false
        LockSupport.unpark(thread)
    }

    private fun loop() {
        try {
            Process.setThreadPriority(SAMPLER_PRIORITY)
        } catch (_: Exception) {
            // Default priority, then.
        }
        var next = SystemClock.elapsedRealtime() + intervalMs
        while (running) {
            val now = SystemClock.elapsedRealtime()
            if (now >= next) {
                sample(now)
                next = now + intervalMs
            }
            data.tick(perfWallClockMs() / 1000)
            var leaseMs = lease.msUntilDue()
            if (leaseMs <= 0) {
                lease.fire()
                leaseMs = lease.msUntilDue()
            }
            if (!running) break
            selfCpu.account()
            val waitMs = minOf(next - SystemClock.elapsedRealtime(), leaseMs)
            // A poke (or a spurious return) only makes the loop re-read its clocks.
            if (waitMs > 0) LockSupport.parkNanos(this, waitMs * 1_000_000)
        }
        statm?.let {
            try {
                Os.close(it)
            } catch (_: Exception) {
                // Closing a proc file cannot fail in a way that matters.
            }
        }
        selfCpu.account()
    }

    private fun sample(nowMs: Long) {
        val cpuSample = if (cpu) {
            cpuRate.sample(Process.getElapsedCpuTime() * 1_000_000, selfCpu.nanos, nowMs, cores)
        } else {
            cpuRate.skip()
            null
        }
        val memorySample = if (memory) {
            val runtime = Runtime.getRuntime()
            MemorySample(
                javaHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
                javaMaxBytes = runtime.maxMemory(),
                nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
                residentBytes = residentBytes(),
                availableBytes = null,
                gcCount = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull(),
                gcBlockingMs = Debug.getRuntimeStat("art.gc.blocking-gc-time")?.toLongOrNull(),
                perturbed = printerSet(),
            )
        } else {
            null
        }
        if (cpuSample != null || memorySample != null) data.recordSample(perfWallClockMs(), cpuSample, memorySample)
        if (watchingThreads) threads.scan()
    }

    /** RSS from `/proc/self/statm`, kept open and re-read from offset 0 into a reused buffer: no open per sample. */
    private fun residentBytes(): Long? = try {
        val fd = statm ?: Os.open("/proc/self/statm", OsConstants.O_RDONLY, 0).also { statm = it }
        val n = Os.pread(fd, statmBuffer, 0, statmBuffer.size, 0)
        // "size resident shared …" in pages: the second number.
        var i = 0
        while (i < n && statmBuffer[i] != ' '.code.toByte()) i++
        i++
        var pages = 0L
        while (i < n && statmBuffer[i] in '0'.code.toByte()..'9'.code.toByte()) {
            pages = pages * 10 + (statmBuffer[i] - '0'.code.toByte())
            i++
        }
        pages * pageSize
    } catch (_: Exception) {
        null
    }

    /** The latest scan, or one now when nobody keeps it running (a `perf.snapshot` with `threads`). */
    fun threads(limit: Int): List<PerfThread> = threads.top(limit, scanIfStale = !watchingThreads || !running)

    private companion object {
        /** Nice 5, as the store's thread. */
        const val SAMPLER_PRIORITY = 5
    }
}

/**
 * The per-thread CPU list: one open and read of `/proc/self/task/<tid>/stat` per thread, so it runs
 * only while watched or asked for. Names come from the same line (`comm`, cut by the kernel at 15
 * bytes); Hopi's own threads are recognised by the `hopi-` prefix.
 */
internal class ThreadScan {
    private class Entry(val tid: Long, val name: String, val ticks: Long)

    private val lock = Any()
    private var previous: Map<Long, Long> = emptyMap()
    private var previousAtMs = 0L
    private var latest: List<PerfThread> = emptyList()
    private val ticksPerSecond = try {
        Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(1)
    } catch (_: Exception) {
        100L
    }

    fun scan() {
        val now = SystemClock.elapsedRealtime()
        val entries = (File("/proc/self/task").list() ?: return).mapNotNull { read(it) }
        synchronized(lock) {
            val dtMs = (now - previousAtMs).coerceAtLeast(1)
            val hadPrevious = previous.isNotEmpty()
            latest = entries.map { entry ->
                val before = previous[entry.tid]
                val percent = if (hadPrevious && before != null) (entry.ticks - before) * 1000.0 / ticksPerSecond * 100.0 / dtMs else null
                PerfThread(entry.tid, PerfLimits.label(entry.name) ?: "", entry.ticks * 1000 / ticksPerSecond, percent, entry.name.startsWith("hopi-"))
            }
            previous = entries.associate { it.tid to it.ticks }
            previousAtMs = now
        }
    }

    fun top(limit: Int, scanIfStale: Boolean): List<PerfThread> {
        if (scanIfStale) scan()
        return synchronized(lock) { latest }.sortedWith(compareByDescending<PerfThread> { it.percent ?: -1.0 }.thenByDescending { it.cpuMs }).take(limit)
    }

    private fun read(tid: String): Entry? = try {
        val line = File("/proc/self/task/$tid/stat").readText()
        // "tid (comm) state ppid …": comm may hold spaces and parentheses, so split at the last ')'.
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        val name = line.substring(open + 1, close)
        val fields = line.substring(close + 2).split(' ')
        // After comm: state(0) … utime(11) stime(12), fields 14 and 15 of the whole line.
        Entry(tid.toLong(), name, fields[11].toLong() + fields[12].toLong())
    } catch (_: Exception) {
        null
    }
}

/** `Debug.getPss()`, at most once per 10 s, recorded as a Hopi pause (it holds the process's mm lock). */
internal class PssProbe(private val data: PerfData) {
    private var lastAtMs = 0L

    @Synchronized
    fun measure(): PerfPss {
        val now = SystemClock.elapsedRealtime()
        if (lastAtMs != 0L && now - lastAtMs < PerfLimits.PSS_MIN_INTERVAL_MS) {
            return PerfPss(null, 0, "at most once per ${PerfLimits.PSS_MIN_INTERVAL_MS / 1000} s")
        }
        lastAtMs = now
        val start = System.nanoTime()
        val kb = Debug.getPss()
        val end = System.nanoTime()
        data.markPause(start, end)
        return PerfPss(kb, (end - start) / 1_000_000)
    }
}
