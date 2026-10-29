package dev.koga.hopi.core.perf

import dev.koga.hopi.api.HopiInternalApi
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Wall clock, epoch milliseconds. */
@OptIn(ExperimentalTime::class)
@HopiInternalApi
public fun perfWallClockMs(): Long = Clock.System.now().toEpochMilliseconds()

/** Receives the model's items synchronously on the writer's thread; it must only hand them off (`trySend`). */
@HopiInternalApi
public fun interface PerfListener {
    public fun onPerf(item: PerfItem)
}

/**
 * The perf model (docs/design/perf-monitors.md, "Data model"): bounded series of primitive
 * arrays, one event ring per kind, and summaries since start in constant memory. Written straight
 * from each monitor's thread (no hop); a write wakes nothing unless a listener is registered (a
 * bridge `perf.tail`), and per-frame writes never reach listeners: only closed seconds, samples and
 * events do. In memory only, never persisted. Nothing here starts a thread.
 */
@HopiInternalApi
public class PerfData {
    public val frames: SeriesRing = SeriesRing(PerfLimits.SERIES_POINTS, FRAME_COLUMNS.size)
    public val cpu: SeriesRing = SeriesRing(PerfLimits.SERIES_POINTS, CPU_COLUMNS.size)
    public val memory: SeriesRing = SeriesRing(PerfLimits.SERIES_POINTS, MEMORY_COLUMNS.size)

    public val janks: EventRing<JankEvent> = EventRing(PerfLimits.JANK_EVENTS)
    public val stalls: EventRing<StallEvent> = EventRing(PerfLimits.STALL_EVENTS)
    public val hangs: EventRing<HangEvent> = EventRing(PerfLimits.HANG_EVENTS)
    public val memoryEvents: EventRing<MemoryEvent> = EventRing(PerfLimits.MEMORY_EVENTS)
    private val previousAnr = AtomicReference<PreviousAnrEvent?>(null)

    private val seq = AtomicLong(0)
    private val listeners = AtomicReference<List<PerfListener>>(emptyList())

    /** The rate-limited log lines; null: none (`summaryLog = false`). */
    private val logSink = AtomicReference<PerfLogSink?>(null)

    private val summaryLock = PerfLock()
    private val histogram = FrameHistogram()
    private var frameCount = 0L
    private var slowCount = 0L
    private var jankyCount = 0L
    private var frozenCount = 0L
    private var excludedCount = 0L
    private var firstFrameCount = 0L
    private var metricsLostCount = 0L
    private var hangCount = 0L
    private var hangTotalMs = 0L
    private var hangWorstMs = 0L
    private var debuggerHangs = 0L
    private var cpuSum = 0.0
    private var cpuSamples = 0L
    private var cpuMax = 0.0
    private var residentMin = Long.MAX_VALUE
    private var residentMax = 0L
    private var lastMemory: MemorySample? = null
    private var lastCpu: CpuSample? = null

    // Hopi's own pauses of the main thread (stack captures, PSS), in the frames' monotonic
    // nanoseconds: a frame overlapping one is flagged and left out.
    private val pauseLock = PerfLock()
    private val pauseStarts = LongArray(PAUSES)
    private val pauseEnds = LongArray(PAUSES)
    private var pauseNext = 0

    // Sampler thread only.
    private val rowCpu = FloatArray(CPU_COLUMNS.size)
    private val rowMemory = FloatArray(MEMORY_COLUMNS.size)

    private val app = AtomicReference<String?>(null)
    private val activity = AtomicReference<String?>(null)

    public val seconds: FrameSeconds = FrameSeconds { closed -> writeSecond(closed) }

    public fun nextSeq(): Long = seq.incrementAndFetch()

    /** The latest sequence number handed out. */
    public val lastSeq: Long get() = seq.load()

    // ---- what was on screen

    /** `HopiPerf.screen(…)`: the app's own label, cut to 64 chars; null falls back to the activity. */
    public fun setScreen(label: String?) {
        app.store(PerfLimits.label(label))
    }

    /** The resumed activity's class name, written by the lifecycle callbacks while a monitor is on. */
    public fun setActivity(label: String?) {
        activity.store(PerfLimits.label(label))
    }

    public val screen: String? get() = app.load() ?: activity.load()

    // ---- listeners and log

    public fun addListener(listener: PerfListener): () -> Unit {
        while (true) {
            val current = listeners.load()
            if (listeners.compareAndSet(current, current + listener)) break
        }
        return {
            while (true) {
                val current = listeners.load()
                if (listeners.compareAndSet(current, current - listener)) break
            }
        }
    }

    public val hasListeners: Boolean get() = listeners.load().isNotEmpty()

    public fun setLogSink(sink: PerfLogSink?) {
        logSink.store(sink)
    }

    public val log: PerfLogSink? get() = logSink.load()

    private fun publish(item: PerfItem) {
        logSink.load()?.onItem(item)
        val current = listeners.load()
        for (listener in current) listener.onPerf(item)
    }

    // ---- frames

    /**
     * One rendered frame, from the frames thread: [atSecond] is its epoch second, [startNs] its
     * start in the monotonic nanoseconds of [markPause], [stagesNs] a buffer the caller reuses
     * (copied only for a janky frame). Allocates nothing unless the frame is janky.
     */
    public fun recordFrame(
        atSecond: Long,
        startNs: Long,
        totalNs: Long,
        deadlineNs: Long,
        firstFrame: Boolean,
        missedVsyncs: Int,
        metricsLost: Int,
        stagesNs: LongArray?,
    ) {
        val klass = FrameClassifier.classify(totalNs, deadlineNs)
        val exclude = overlapsPause(startNs, startNs + totalNs)
        val screen = screen
        if (!exclude && !firstFrame) {
            summaryLock.locked {
                histogram.add(totalNs / 1_000_000.0)
                frameCount++
                if (klass != FrameClass.NORMAL) slowCount++
                if (klass == FrameClass.JANKY || klass == FrameClass.FROZEN) jankyCount++
                if (klass == FrameClass.FROZEN) frozenCount++
                metricsLostCount += metricsLost
            }
        } else {
            summaryLock.locked {
                if (exclude) excludedCount++ else firstFrameCount++
                metricsLostCount += metricsLost
            }
        }
        if (!exclude && !firstFrame && (klass == FrameClass.JANKY || klass == FrameClass.FROZEN)) {
            janks.add(
                JankEvent(
                    seq = nextSeq(),
                    atMs = atSecond * 1000,
                    durationMs = totalNs / 1_000_000.0,
                    deadlineMs = deadlineNs / 1_000_000.0,
                    frozen = klass == FrameClass.FROZEN,
                    screen = screen,
                    stagesNs = stagesNs?.copyOf(),
                ),
            )
        }
        seconds.add(atSecond, totalNs, klass, firstFrame, missedVsyncs, metricsLost, exclude, screen)
    }

    /** Closes the frames second before [nowSecond], if one is open: the sampler's tick and readers call it. */
    public fun tick(nowSecond: Long) {
        seconds.closeBefore(nowSecond)
    }

    // Once per closed second, from whichever thread closed it (frames thread, sampler, reader): a row of its own.
    private fun writeSecond(closed: FrameSecond) {
        val rowFrames = FloatArray(FRAME_COLUMNS.size)
        rowFrames[0] = closed.frames.toFloat()
        rowFrames[1] = closed.slow.toFloat()
        rowFrames[2] = closed.janky.toFloat()
        rowFrames[3] = closed.frozen.toFloat()
        rowFrames[4] = closed.worstMs.toFloat()
        rowFrames[5] = closed.missedVsyncs.toFloat()
        rowFrames[6] = closed.metricsLost.toFloat()
        rowFrames[7] = closed.firstFrames.toFloat()
        rowFrames[8] = closed.excluded.toFloat()
        frames.add(closed.second * 1000, rowFrames)
        publish(closed)
    }

    /** A Hopi pause of the main thread from [startNs] to [endNs] (monotonic nanoseconds). */
    public fun markPause(startNs: Long, endNs: Long) {
        pauseLock.locked {
            pauseStarts[pauseNext] = startNs
            pauseEnds[pauseNext] = endNs
            pauseNext = (pauseNext + 1) % PAUSES
        }
    }

    private fun overlapsPause(startNs: Long, endNs: Long): Boolean = pauseLock.locked {
        var overlaps = false
        for (i in 0 until PAUSES) {
            if (pauseEnds[i] != 0L && pauseStarts[i] < endNs && pauseEnds[i] > startNs) overlaps = true
        }
        overlaps
    }

    // ---- hangs

    /** A hang detected, written and logged at detection (an ANR may kill the process before its end). */
    public fun recordHang(event: HangEvent) {
        hangs.add(event)
        publish(event)
    }

    /** A hang updated (another stack) or completed ([HangEvent.ended]). */
    public fun updateHang(event: HangEvent) {
        if (!hangs.replace(event)) hangs.add(event)
        if (event.ended) {
            summaryLock.locked {
                if (event.debugger) {
                    debuggerHangs++
                } else {
                    hangCount++
                    hangTotalMs += event.durationMs
                    if (event.durationMs > hangWorstMs) hangWorstMs = event.durationMs
                }
            }
        }
        publish(event)
    }

    public fun recordStall(event: StallEvent) {
        stalls.add(event)
        publish(event)
    }

    // ---- memory events and the previous ANR

    public fun recordMemoryEvent(event: String, level: Int?, detail: String? = null) {
        val recorded = MemoryEvent(nextSeq(), perfWallClockMs(), event, level, detail)
        memoryEvents.add(recorded)
        publish(recorded)
    }

    /** At most once per process: later calls are ignored. */
    public fun recordPreviousAnr(atMs: Long, description: String?, mainThreadTrace: String?) {
        val event = PreviousAnrEvent(nextSeq(), atMs, description, mainThreadTrace)
        if (previousAnr.compareAndSet(null, event)) publish(event)
    }

    public val previousAnrEvent: PreviousAnrEvent? get() = previousAnr.load()

    // ---- samples

    /** One sampler tick; [cpu] and [memory] null for a monitor that is off. */
    public fun recordSample(atMs: Long, cpu: CpuSample?, memory: MemorySample?) {
        if (cpu != null) {
            rowCpu[0] = cpu.processPercent.toFloat()
            rowCpu[1] = cpu.hopiPercent.toFloat()
            this.cpu.add(atMs, rowCpu)
        }
        if (memory != null) {
            rowMemory[0] = kib(memory.javaHeapBytes)
            rowMemory[1] = kib(memory.javaMaxBytes)
            rowMemory[2] = kib(memory.nativeHeapBytes)
            rowMemory[3] = kib(memory.residentBytes)
            rowMemory[4] = kib(memory.availableBytes)
            rowMemory[5] = memory.gcCount?.toFloat() ?: Float.NaN
            rowMemory[6] = memory.gcBlockingMs?.toFloat() ?: Float.NaN
            this.memory.add(atMs, rowMemory)
        }
        summaryLock.locked {
            if (cpu != null) {
                cpuSum += cpu.processPercent
                cpuSamples++
                if (cpu.processPercent > cpuMax) cpuMax = cpu.processPercent
                lastCpu = cpu
            }
            if (memory != null) {
                memory.residentBytes?.let {
                    if (it < residentMin) residentMin = it
                    if (it > residentMax) residentMax = it
                }
                lastMemory = memory
            }
        }
        publish(PerfSample(atMs, cpu, memory))
    }

    private fun kib(bytes: Long?): Float = if (bytes == null) Float.NaN else (bytes / 1024).toFloat()

    // ---- reading

    /** The summaries since start (or [clear]), computed when asked. */
    public fun summary(): PerfSummary = summaryLock.locked {
        val h = histogram.copy()
        PerfSummary(
            frames = frameCount,
            slow = slowCount,
            janky = jankyCount,
            frozen = frozenCount,
            excluded = excludedCount,
            firstFrames = firstFrameCount,
            metricsLost = metricsLostCount,
            p50Ms = h.percentile(0.5),
            p90Ms = h.percentile(0.9),
            p99Ms = h.percentile(0.99),
            maxMs = if (h.total > 0) h.maxMs else null,
            hangs = hangCount,
            hangTotalMs = hangTotalMs,
            hangWorstMs = hangWorstMs,
            debuggerHangs = debuggerHangs,
            cpuAveragePercent = if (cpuSamples > 0) cpuSum / cpuSamples else null,
            cpuMaxPercent = if (cpuSamples > 0) cpuMax else null,
            cpuLast = lastCpu,
            residentMinBytes = if (residentMin == Long.MAX_VALUE) null else residentMin,
            residentMaxBytes = if (residentMax == 0L) null else residentMax,
            memoryLast = lastMemory,
        )
    }

    /** Every event after [since], all kinds, in sequence order, at most [limitPerKind] per kind. */
    public fun eventsSince(since: Long, limitPerKind: Int = PerfLimits.JANK_EVENTS): List<PerfEvent> {
        val all = ArrayList<PerfEvent>()
        all += hangs.since(since, limitPerKind)
        all += janks.since(since, limitPerKind)
        all += stalls.since(since, limitPerKind)
        all += memoryEvents.since(since, limitPerKind)
        previousAnr.load()?.takeIf { it.seq > since }?.let { all += it }
        all.sortBy { it.seq }
        return all
    }

    public fun event(seq: Long): PerfEvent? =
        hangs.find(seq) ?: janks.find(seq) ?: stalls.find(seq) ?: memoryEvents.find(seq) ?: previousAnr.load()?.takeIf { it.seq == seq }

    /** Forgets every point, event and summary (tests; never the sequence counter). */
    public fun clear() {
        frames.clear()
        cpu.clear()
        memory.clear()
        janks.clear()
        stalls.clear()
        hangs.clear()
        memoryEvents.clear()
        seconds.clear()
        summaryLock.locked {
            histogram.clear()
            frameCount = 0
            slowCount = 0
            jankyCount = 0
            frozenCount = 0
            excludedCount = 0
            firstFrameCount = 0
            metricsLostCount = 0
            hangCount = 0
            hangTotalMs = 0
            hangWorstMs = 0
            debuggerHangs = 0
            cpuSum = 0.0
            cpuSamples = 0
            cpuMax = 0.0
            residentMin = Long.MAX_VALUE
            residentMax = 0
            lastMemory = null
            lastCpu = null
        }
        pauseLock.locked {
            pauseStarts.fill(0)
            pauseEnds.fill(0)
        }
    }

    @HopiInternalApi
    public companion object {
        public val FRAME_COLUMNS: List<String> = listOf("frames", "slow", "janky", "frozen", "worstMs", "missedVsyncs", "metricsLost", "firstFrames", "excluded")
        public val CPU_COLUMNS: List<String> = listOf("process", "hopi")

        /** KiB, and counts for the two GC columns; NaN where the platform has no value. */
        public val MEMORY_COLUMNS: List<String> = listOf("javaHeapKb", "javaMaxKb", "nativeHeapKb", "residentKb", "availableKb", "gcCount", "gcBlockingMs")

        /** Series by their wire name. */
        public val SERIES: List<String> = listOf("frames", "cpu", "memory")

        private const val PAUSES = 8
    }
}

/** Summaries since start. */
@HopiInternalApi
public class PerfSummary(
    public val frames: Long,
    public val slow: Long,
    public val janky: Long,
    public val frozen: Long,
    public val excluded: Long,
    public val firstFrames: Long,
    public val metricsLost: Long,
    public val p50Ms: Double?,
    public val p90Ms: Double?,
    public val p99Ms: Double?,
    public val maxMs: Double?,
    public val hangs: Long,
    public val hangTotalMs: Long,
    public val hangWorstMs: Long,
    public val debuggerHangs: Long,
    public val cpuAveragePercent: Double?,
    public val cpuMaxPercent: Double?,
    public val cpuLast: CpuSample?,
    public val residentMinBytes: Long?,
    public val residentMaxBytes: Long?,
    public val memoryLast: MemorySample?,
)
