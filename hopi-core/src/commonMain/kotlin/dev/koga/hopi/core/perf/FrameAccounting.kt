package dev.koga.hopi.core.perf

import dev.koga.hopi.api.HopiInternalApi

/** How a frame compares with its deadline (docs/design/perf-monitors.md, "Frames"). */
@HopiInternalApi
public enum class FrameClass {
    NORMAL,

    /** Over the deadline. */
    SLOW,

    /** Over twice the deadline (JankStats' default: a frame just over it on an emulator is noise). */
    JANKY,

    /** Over 700 ms (Android vitals). Also janky. */
    FROZEN,
}

@HopiInternalApi
public object FrameClassifier {
    private const val FROZEN_NS: Long = PerfLimits.FROZEN_FRAME_MS * 1_000_000

    public fun classify(totalNs: Long, deadlineNs: Long): FrameClass = when {
        totalNs > FROZEN_NS -> FrameClass.FROZEN
        deadlineNs > 0 && totalNs > 2 * deadlineNs -> FrameClass.JANKY
        deadlineNs > 0 && totalNs > deadlineNs -> FrameClass.SLOW
        else -> FrameClass.NORMAL
    }

    /** The deadline for a display at [refreshRate] Hz (Android 24 to 30, which has no `DEADLINE` metric). */
    public fun deadlineNs(refreshRate: Float): Long =
        if (refreshRate > 1f) (1_000_000_000.0 / refreshRate).toLong() else 16_666_667L

    /**
     * Vsyncs missed between two consecutive frames' intended vsync timestamps: a gap of more than
     * 1.5 frame intervals counts the intervals skipped. 0 when either timestamp is unknown.
     */
    public fun missedVsyncs(previousVsyncNs: Long, vsyncNs: Long, intervalNs: Long): Int {
        if (previousVsyncNs <= 0 || vsyncNs <= previousVsyncNs || intervalNs <= 0) return 0
        val gap = vsyncNs - previousVsyncNs
        if (gap * 2 <= intervalNs * 3) return 0
        return ((gap + intervalNs / 2) / intervalNs - 1).toInt().coerceAtLeast(0)
    }
}

/**
 * Sums frames into the current second's bucket, with no timer. Only a second strictly in the past
 * is ever closed and written as a point: by the first frame of a later second, by the sampler's
 * tick, or when a reader asks ([closeBefore]). The current second is shown to a reader
 * provisionally ([provisional]) and never written, so a frame later in the same second can never
 * open a second point for it. Thread-safe: the frames thread writes, the sampler and readers close.
 */
@HopiInternalApi
public class FrameSeconds(private val onClosed: (FrameSecond) -> Unit) {
    private val lock = PerfLock()
    private var second = -1L
    private var closedThrough = -1L
    private var frames = 0
    private var slow = 0
    private var janky = 0
    private var frozen = 0
    private var worstNs = 0L
    private var missed = 0
    private var lost = 0
    private var first = 0
    private var excluded = 0
    private var screen: String? = null

    /**
     * Adds one frame of epoch second [atSecond]. [klass] is its classification; [firstFrame], the
     * first frame of a window; [exclude], left out of the figures (a Hopi pause; the inspector flag
     * is kept but unset since the runtime-split plan's stage 5).
     */
    public fun add(atSecond: Long, totalNs: Long, klass: FrameClass, firstFrame: Boolean, missedVsyncs: Int, metricsLost: Int, exclude: Boolean, screen: String?) {
        val closed = lock.locked {
            // A frame of a second already closed (delivered late, or clock skew between frames) lands
            // in the next open one: a closed second is never written twice.
            val target = maxOf(atSecond, second, closedThrough + 1)
            val closed = if (target > second && second >= 0) takeLocked() else null
            if (target != second) {
                second = target
                reset()
            }
            lost += metricsLost
            when {
                exclude -> excluded++
                firstFrame -> first++
                else -> {
                    frames++
                    when (klass) {
                        FrameClass.NORMAL -> Unit
                        FrameClass.SLOW -> slow++
                        FrameClass.JANKY -> {
                            slow++
                            janky++
                        }
                        FrameClass.FROZEN -> {
                            slow++
                            janky++
                            frozen++
                        }
                    }
                    if (totalNs > worstNs) worstNs = totalNs
                    missed += missedVsyncs
                }
            }
            this.screen = screen
            closed
        }
        closed?.let(onClosed)
    }

    /** Closes the open bucket if its second is before [nowSecond]; writes nothing otherwise. */
    public fun closeBefore(nowSecond: Long) {
        val closed = lock.locked {
            if (second in 0 until nowSecond) {
                takeLocked().also {
                    closedThrough = second
                    second = -1
                }
            } else {
                null
            }
        }
        closed?.let(onClosed)
    }

    /** The open second, shown provisionally and never written; null when none is open. */
    public fun provisional(): FrameSecond? = lock.locked { if (second >= 0) snapshotLocked() else null }

    public fun clear() {
        lock.locked {
            second = -1
            closedThrough = -1
            reset()
        }
    }

    private fun takeLocked(): FrameSecond {
        closedThrough = maxOf(closedThrough, second)
        return snapshotLocked()
    }

    private fun snapshotLocked(): FrameSecond =
        FrameSecond(second, frames, slow, janky, frozen, worstNs / 1_000_000.0, missed, lost, first, excluded, screen)

    private fun reset() {
        frames = 0
        slow = 0
        janky = 0
        frozen = 0
        worstNs = 0
        missed = 0
        lost = 0
        first = 0
        excluded = 0
        screen = null
    }
}
