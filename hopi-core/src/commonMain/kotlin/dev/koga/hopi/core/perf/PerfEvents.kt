package dev.koga.hopi.core.perf

import dev.koga.hopi.api.HopiInternalApi

/** What the perf model hands its listeners (a bridge `perf.tail`, the HUD): events, closed seconds, samples. */
@HopiInternalApi
public sealed interface PerfItem

/** An event of the perf model, with its process-wide sequence number. */
@HopiInternalApi
public sealed interface PerfEvent : PerfItem, Sequenced {
    /** Wall clock, epoch milliseconds. */
    public val atMs: Long

    /** The event's kind on the wire: `hang`, `jank`, `stall`, `memory`, `anr`. */
    public val kind: String
}

/** One main-thread unit of work longer than the hang threshold. */
@HopiInternalApi
public class HangEvent(
    override val seq: Long,
    override val atMs: Long,
    public val thresholdMs: Long,
    /** True once the unit ended; until then [durationMs] is the time at the last detection. */
    public val ended: Boolean,
    public val durationMs: Long,
    public val screen: String?,
    /** At most [PerfLimits.MAX_STACKS_PER_HANG] stacks of the main thread, innermost frame first. */
    public val stacks: List<List<String>>,
    /** The pause each capture cost the main thread, in microseconds (a timed-out capture included). */
    public val captureUs: List<Long>,
    /** A capture ran but no stack survived (the unit ended during it, or the runtime returned none). */
    public val stackMissed: Boolean,
    /** Detected while a debugger was attached: no stack, left out of counts and summaries. */
    public val debugger: Boolean,
    /** Time the whole process was stopped (freezer, suspension) inside the unit, not counted as hang. */
    public val frozenMs: Long,
    /** Detected by the heartbeat rather than the Looper printer. */
    public val heartbeat: Boolean,
) : PerfEvent {
    override val kind: String get() = "hang"

    public fun copy(
        ended: Boolean = this.ended,
        durationMs: Long = this.durationMs,
        stacks: List<List<String>> = this.stacks,
        captureUs: List<Long> = this.captureUs,
        stackMissed: Boolean = this.stackMissed,
        frozenMs: Long = this.frozenMs,
    ): HangEvent = HangEvent(seq, atMs, thresholdMs, ended, durationMs, screen, stacks, captureUs, stackMissed, debugger, frozenMs, heartbeat)
}

/** One janky frame (over twice its deadline; frozen over 700 ms), with its stage durations. */
@HopiInternalApi
public class JankEvent(
    override val seq: Long,
    override val atMs: Long,
    public val durationMs: Double,
    public val deadlineMs: Double,
    public val frozen: Boolean,
    public val screen: String?,
    /** Stage durations in nanoseconds, in the order of [FrameStages.NAMES]; -1 where the API has none. */
    public val stagesNs: LongArray?,
) : PerfEvent {
    override val kind: String get() = "jank"
}

/** iOS tier 1: one main run-loop unit busier than twice the frame deadline. */
@HopiInternalApi
public class StallEvent(
    override val seq: Long,
    override val atMs: Long,
    public val durationMs: Long,
    public val screen: String?,
) : PerfEvent {
    override val kind: String get() = "stall"
}

/** An OS memory signal: `trim` (with its level), `low`, iOS `warning` and `pressure` (`warn`, `critical`). */
@HopiInternalApi
public class MemoryEvent(
    override val seq: Long,
    override val atMs: Long,
    public val event: String,
    public val level: Int?,
    public val detail: String? = null,
) : PerfEvent {
    override val kind: String get() = "memory"
}

/** The previous run's ANR, read from the OS when the hang monitor first started in this process. */
@HopiInternalApi
public class PreviousAnrEvent(
    override val seq: Long,
    /** When the OS recorded the exit. */
    override val atMs: Long,
    public val description: String?,
    /** The `"main"` thread's section of the ANR trace, at most [PerfLimits.MAX_ANR_TRACE_BYTES]. */
    public val mainThreadTrace: String?,
) : PerfEvent {
    override val kind: String get() = "anr"
}

/** One closed second of frames. Seconds in which nothing drew may have no point at all. */
@HopiInternalApi
public class FrameSecond(
    /** Epoch seconds. */
    public val second: Long,
    public val frames: Int,
    public val slow: Int,
    public val janky: Int,
    public val frozen: Int,
    public val worstMs: Double,
    public val missedVsyncs: Int,
    /** Metrics reports the framework discarded because Hopi's thread fell behind: the figures undercount. */
    public val metricsLost: Int,
    /** First frames of a window, counted apart. */
    public val firstFrames: Int,
    /** Frames left out because the inspector was open or a Hopi capture paused the main thread. */
    public val excluded: Int,
    public val screen: String?,
) : PerfItem

/** One sampler tick: CPU and/or memory, whichever is on. */
@HopiInternalApi
public class PerfSample(
    public val atMs: Long,
    public val cpu: CpuSample?,
    public val memory: MemorySample?,
) : PerfItem

@HopiInternalApi
public class CpuSample(
    /** Percent of one core (top's convention): 200 is two cores busy. */
    public val processPercent: Double,
    /** Hopi's own threads' share of it. */
    public val hopiPercent: Double,
    public val cores: Int,
)

/** Byte values; null where the platform has none. */
@HopiInternalApi
public class MemorySample(
    public val javaHeapBytes: Long?,
    public val javaMaxBytes: Long?,
    public val nativeHeapBytes: Long?,
    /** Android RSS, iOS `phys_footprint`. */
    public val residentBytes: Long?,
    /** iOS `os_proc_available_memory()`. */
    public val availableBytes: Long?,
    public val gcCount: Long?,
    public val gcBlockingMs: Long?,
    /** The hang monitor's printer adds garbage per message, so the GC figures are not independent of it. */
    public val perturbed: Boolean,
)

/** The stage durations a frame event carries. */
@HopiInternalApi
public object FrameStages {
    public val NAMES: List<String> = listOf("input", "animation", "layout", "draw", "sync", "command", "swap", "gpu")
}

/** One thread of the per-thread CPU list. */
@HopiInternalApi
public class PerfThread(
    public val tid: Long,
    public val name: String,
    /** CPU time since the thread started. */
    public val cpuMs: Long,
    /** Percent of one core since the previous scan; null on the first scan. */
    public val percent: Double?,
    public val hopi: Boolean,
)
