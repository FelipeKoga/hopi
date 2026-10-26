package dev.koga.hopi.core.perf

import dev.koga.hopi.core.HopiInternalApi

/** The four monitors of docs/design/perf-monitors.md, by their wire name. */
@HopiInternalApi
public enum class PerfMonitor(public val wireName: String) {
    FRAMES("frames"),
    HANGS("hangs"),
    CPU("cpu"),
    MEMORY("memory"),
    ;

    public companion object {
        public fun fromWire(name: String): PerfMonitor? = entries.firstOrNull { it.wireName == name }
    }
}

/** Limits of the perf commands and settings (docs/design/perf-monitors.md, "Bridge"). */
@HopiInternalApi
public object PerfLimits {
    public const val MIN_HANG_THRESHOLD_MS: Long = 100
    public const val MAX_HANG_THRESHOLD_MS: Long = 10_000
    public const val DEFAULT_HANG_THRESHOLD_MS: Long = 250
    public const val MIN_SAMPLE_INTERVAL_MS: Long = 250
    public const val MAX_SAMPLE_INTERVAL_MS: Long = 10_000
    public const val DEFAULT_SAMPLE_INTERVAL_MS: Long = 1_000
    public const val DEFAULT_LEASE_SEC: Long = 600
    public const val MAX_LEASE_SEC: Long = 3_600

    /** Points per series. 20 minutes at 1 Hz. */
    public const val SERIES_POINTS: Int = 1_200
    public const val JANK_EVENTS: Int = 1_000
    public const val STALL_EVENTS: Int = 1_000
    public const val HANG_EVENTS: Int = 100
    public const val MEMORY_EVENTS: Int = 200

    /** Stacks per hang (1x, 2x and 4x the threshold), frames per stack, chars per frame, bytes per hang. */
    public const val MAX_STACKS_PER_HANG: Int = 3
    public const val MAX_STACK_FRAMES: Int = 64
    public const val MAX_FRAME_CHARS: Int = 512
    public const val MAX_HANG_STACK_BYTES: Int = 16 * 1024

    /** Screen labels and thread names the app supplies are cut to this many chars. */
    public const val MAX_LABEL_CHARS: Int = 64

    /** A frame longer than this is frozen (Android vitals' definition). */
    public const val FROZEN_FRAME_MS: Long = 700

    /** The previous run's ANR keeps at most this much of the main thread's section of its trace. */
    public const val MAX_ANR_TRACE_BYTES: Int = 16 * 1024

    /** Stack frames a `perf.snapshot` sends per stack unless `fullStacks`. */
    public const val SNAPSHOT_STACK_FRAMES: Int = 8

    /** PSS is measured at most once per this many milliseconds. */
    public const val PSS_MIN_INTERVAL_MS: Long = 10_000

    /** Threads the per-thread list shows by default. */
    public const val DEFAULT_TOP_THREADS: Int = 10

    public fun label(text: String?): String? = text?.let { if (it.length > MAX_LABEL_CHARS) it.substring(0, MAX_LABEL_CHARS) else it }
}
