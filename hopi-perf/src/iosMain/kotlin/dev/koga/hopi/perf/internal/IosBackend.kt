package dev.koga.hopi.perf.internal

import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfPss
import dev.koga.hopi.core.perf.PerfThread
import dev.koga.hopi.core.perf.PerfTimer
import dev.koga.hopi.core.perf.StallEvent
import dev.koga.hopi.core.perf.perfWallClockMs
import dev.koga.hopi.perf.internal.native.HOPI_PERF_MEMORY_PRESSURE_CRITICAL
import dev.koga.hopi.perf.internal.native.HOPI_PERF_MEMORY_PRESSURE_WARN
import dev.koga.hopi.perf.internal.native.HOPI_PERF_MEMORY_WARNING
import dev.koga.hopi.perf.internal.native.hopi_perf_cadence_running
import dev.koga.hopi.perf.internal.native.hopi_perf_cadence_start
import dev.koga.hopi.perf.internal.native.hopi_perf_cadence_stop
import dev.koga.hopi.perf.internal.native.hopi_perf_debugger_attached
import dev.koga.hopi.perf.internal.native.hopi_perf_high_rate_allowed
import dev.koga.hopi.perf.internal.native.hopi_perf_max_fps_main_thread
import dev.koga.hopi.perf.internal.native.hopi_perf_memory_events_start
import dev.koga.hopi.perf.internal.native.hopi_perf_memory_events_stop
import dev.koga.hopi.perf.internal.native.hopi_perf_now_ms
import dev.koga.hopi.perf.internal.native.hopi_perf_now_ns
import dev.koga.hopi.perf.internal.native.hopi_perf_observers_install
import dev.koga.hopi.perf.internal.native.hopi_perf_observers_installed
import dev.koga.hopi.perf.internal.native.hopi_perf_observers_remove
import dev.koga.hopi.perf.internal.native.hopi_perf_set_stall
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.cinterop.staticCFunction
import platform.Foundation.NSThread
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.DISPATCH_QUEUE_PRIORITY_LOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/**
 * The iOS monitors (docs/design/perf-monitors.md). Nothing here runs until a monitor starts: no
 * thread, no observer, no display link while every monitor is off.
 *
 * - **Frames, tier 1** (the default): the main run loop's observers report each unit of work over
 *   twice the frame deadline as a stall, entering Kotlin only for it. No thread.
 * - **Frames, tier 2** (only while a viewer asks, [watchCadence]): a display link on the main run
 *   loop writes the frames series with its callback cadence (it fires every vsync, idle included).
 * - **Hangs**: the same observers plus the watchdog thread ([IosHangMonitor]).
 * - **CPU and memory**: one sampler thread ([IosSampler]); memory adds the memory-warning
 *   notification and a memory-pressure source.
 *
 * Every call comes under the runtime's lock, which on iOS is not reentrant: nothing here calls
 * back into the controller synchronously (the frames-only lease is read on a utility queue).
 */
internal class IosBackend private constructor() : MonitorBackend {
    override val platform: String = "ios"
    override val available: Set<PerfMonitor> = PerfMonitor.entries.toSet()

    private lateinit var data: PerfData
    private lateinit var lease: PerfTimer
    private val selfCpu = IosSelfCpu()
    private var settings = PerfSettings.DEFAULT

    private var hang: IosHangMonitor? = null
    private var frames = false
    private var sampler: IosSampler? = null
    private var memoryEvents = false
    private var watchingThreads = false
    private var cadenceViewers = 0
    private val threadsOnDemand by lazy { IosThreadScan() }

    /** The frame deadline, nanoseconds: 60 Hz until the main queue read the screen's rate. */
    private val deadlineNs = AtomicLong(DEFAULT_DEADLINE_NS)

    /** The frames-only lease: a scheduled expiry runs only if no later change superseded it. */
    private val leaseGeneration = AtomicLong(0)

    override fun nowMs(): Long = hopi_perf_now_ms()

    override fun attach(data: PerfData, lease: PerfTimer) {
        this.data = data
        this.lease = lease
        sink.store(this)
    }

    override fun start(monitor: PerfMonitor, settings: PerfSettings): String? {
        this.settings = settings
        return when (monitor) {
            PerfMonitor.FRAMES -> {
                frames = true
                val installed = updateObservers()
                framesObserved = installed
                readDeadline()
                updateCadence()
                if (!installed) "the run-loop observers could not be created" else null
            }
            PerfMonitor.HANGS -> {
                if (!updateObservers(wantHangs = true)) return "the run-loop observers could not be created"
                hang = IosHangMonitor(data, settings.hangThresholdMs, lease, selfCpu).also { it.start() }
                if (settings.hangHeartbeat) "hangHeartbeat is Android-only: iOS uses its run-loop observers" else null
            }
            PerfMonitor.CPU -> {
                sampler().cpu = true
                null
            }
            PerfMonitor.MEMORY -> {
                sampler().memory = true
                hopi_perf_memory_events_start(memoryCallback)
                memoryEvents = true
                null
            }
        }
    }

    /** Whether frames' run-loop observers were created by its last start. */
    private var framesObserved = false

    override fun started(monitor: PerfMonitor): Boolean = when (monitor) {
        PerfMonitor.FRAMES -> framesObserved
        PerfMonitor.HANGS -> hang != null
        else -> true
    }

    override fun stop(monitor: PerfMonitor) {
        when (monitor) {
            PerfMonitor.FRAMES -> {
                frames = false
                hopi_perf_set_stall(0, null)
                updateCadence()
            }
            PerfMonitor.HANGS -> {
                hang?.stop()
                hang = null
            }
            PerfMonitor.CPU -> sampler?.cpu = false
            PerfMonitor.MEMORY -> {
                sampler?.memory = false
                if (memoryEvents) hopi_perf_memory_events_stop()
                memoryEvents = false
            }
        }
        sampler?.let {
            if (!it.cpu && !it.memory) {
                it.stop()
                sampler = null
            }
        }
        updateObservers()
    }

    private fun sampler(): IosSampler = sampler ?: IosSampler(data, lease, selfCpu).also {
        it.intervalMs = settings.sampleIntervalMs
        it.watchingThreads = watchingThreads
        sampler = it
        it.start()
    }

    /** The observers exist while frames or hangs runs; returns whether they are installed when wanted. */
    private fun updateObservers(wantHangs: Boolean = false): Boolean {
        val wanted = frames || hang != null || wantHangs
        if (!wanted) {
            if (hopi_perf_observers_installed()) hopi_perf_observers_remove()
            return true
        }
        val installed = hopi_perf_observers_install()
        if (installed && frames) hopi_perf_set_stall(STALL_DEADLINES * deadlineNs.load(), stallCallback)
        return installed
    }

    /**
     * `1 / UIScreen.maximumFramesPerSecond`, read on the main queue (a hop when started elsewhere),
     * capped at 60 Hz unless the Info.plist sets `CADisableMinimumFrameDurationOnPhone`: without it a
     * ProMotion iPhone gives the app 60 Hz, and a 120 Hz deadline would report every 9 ms unit.
     */
    private fun readDeadline() {
        if (NSThread.isMainThread) {
            storeDeadline()
            hopi_perf_set_stall(STALL_DEADLINES * deadlineNs.load(), stallCallback)
        } else {
            dispatch_async(dispatch_get_main_queue()) {
                storeDeadline()
                perfLocked(this) { if (frames) hopi_perf_set_stall(STALL_DEADLINES * deadlineNs.load(), stallCallback) }
            }
        }
    }

    // Main thread.
    private fun storeDeadline() {
        val max = hopi_perf_max_fps_main_thread()
        val fps = if (hopi_perf_high_rate_allowed()) max else minOf(max, 60)
        deadlineNs.store(1_000_000_000L / fps.coerceAtLeast(1))
    }

    /** Tier 2 runs while frames runs and at least one viewer asks for it. */
    private fun updateCadence() {
        val wanted = frames && cadenceViewers > 0
        if (wanted && !hopi_perf_cadence_running()) hopi_perf_cadence_start(vsyncCallback)
        if (!wanted && hopi_perf_cadence_running()) hopi_perf_cadence_stop()
    }

    override fun watchCadence(watching: Boolean) {
        cadenceViewers = (cadenceViewers + if (watching) 1 else -1).coerceAtLeast(0)
        updateCadence()
    }

    override fun apply(settings: PerfSettings, running: Set<PerfMonitor>) {
        this.settings = settings
        hang?.detector?.thresholdMs = settings.hangThresholdMs
        hang?.poke()
        sampler?.let {
            it.intervalMs = settings.sampleIntervalMs
            it.poke()
        }
    }

    override fun details(monitor: PerfMonitor): List<Pair<String, Any?>> = when (monitor) {
        PerfMonitor.FRAMES -> listOf(
            "tier" to if (hopi_perf_cadence_running()) "cadence" else "stalls",
            "deadlineMs" to deadlineNs.load() / 1_000_000.0,
            "stallMs" to STALL_DEADLINES * deadlineNs.load() / 1_000_000.0,
            "cadence" to hopi_perf_cadence_running(),
            "observers" to hopi_perf_observers_installed(),
        )
        PerfMonitor.HANGS -> listOf(
            "thresholdMs" to settings.hangThresholdMs,
            "observers" to hopi_perf_observers_installed(),
            "debugger" to hopi_perf_debugger_attached(),
        )
        PerfMonitor.CPU -> listOf("intervalMs" to settings.sampleIntervalMs, "threads" to watchingThreads)
        PerfMonitor.MEMORY -> listOf("intervalMs" to settings.sampleIntervalMs, "events" to memoryEvents)
    }

    override fun leaseChanged(running: Set<PerfMonitor>) {
        sampler?.poke()
        hang?.poke()
        val generation = leaseGeneration.incrementAndFetch()
        // Frames alone has no thread: one dispatch_after per lease. Read off the lock, which the
        // lease's own reading takes and which is not reentrant here.
        if (sampler != null || hang != null || !frames) return
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_LOW.toLong(), 0u)) {
            val ms = lease.msUntilDue()
            if (ms == Long.MAX_VALUE || leaseGeneration.load() != generation) return@dispatch_async
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, ms * 1_000_000), dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_LOW.toLong(), 0u)) {
                if (leaseGeneration.load() == generation) lease.fire()
            }
        }
    }

    override fun threads(limit: Int): List<PerfThread> = sampler?.threads(limit) ?: threadsOnDemand.top(limit, scanIfStale = true)

    override fun watchThreads(watching: Boolean) {
        watchingThreads = watching
        sampler?.watchingThreads = watching
    }

    override fun measurePss(): PerfPss = PerfPss(null, 0, "not available on iOS (the memory series has phys_footprint)")

    // ---- callbacks from C

    /** Main thread, from the end of a unit over the stall threshold. */
    internal fun onStall(durationNs: Long, endNs: Long) {
        val durationMs = durationNs / 1_000_000
        val endedAgoMs = (hopi_perf_now_ns() - endNs) / 1_000_000
        data.recordStall(StallEvent(data.nextSeq(), perfWallClockMs() - endedAgoMs - durationMs, durationMs, data.screen))
    }

    internal fun onMemory(level: Int) {
        when (level) {
            HOPI_PERF_MEMORY_WARNING -> data.recordMemoryEvent("warning", null)
            HOPI_PERF_MEMORY_PRESSURE_WARN -> data.recordMemoryEvent("pressure", null, "warn")
            HOPI_PERF_MEMORY_PRESSURE_CRITICAL -> data.recordMemoryEvent("pressure", null, "critical")
        }
    }

    /** Main thread, every vsync while tier 2 runs: the callback's cadence, not frames the app drew. */
    internal fun onVsync(nowNs: Long, intervalNs: Long, periodNs: Long, missed: Int) {
        data.recordFrame(perfWallClockMs() / 1000, nowNs - intervalNs, intervalNs, periodNs, false, missed, 0, null)
    }

    internal companion object {
        val instance: IosBackend by lazy { IosBackend() }

        const val DEFAULT_DEADLINE_NS = 16_666_667L

        /** A stall is a unit over twice the frame deadline, Android's janky rule. */
        const val STALL_DEADLINES = 2L

        // The C callbacks cannot capture: they reach the backend through this.
        private val sink = AtomicReference<IosBackend?>(null)

        private val stallCallback = staticCFunction { durationNs: Long, endNs: Long ->
            sink.load()?.onStall(durationNs, endNs)
            Unit
        }

        private val memoryCallback = staticCFunction { level: Int ->
            sink.load()?.onMemory(level)
            Unit
        }

        private val vsyncCallback = staticCFunction { nowNs: Long, intervalNs: Long, periodNs: Long, missed: Int ->
            sink.load()?.onVsync(nowNs, intervalNs, periodNs, missed)
            Unit
        }
    }
}
