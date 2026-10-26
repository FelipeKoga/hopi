package dev.koga.hopi.core.perf

import dev.koga.hopi.core.HopiInternalApi

/**
 * A lock for the perf model's short critical sections (a ring append, a copy for a reader), which
 * are uncontended in practice: one writer per ring, a reader now and then. The main thread takes
 * it (a frame, a stall) while lower-priority readers (the bridge, a viewer) may hold it, so it is
 * never a bare spin: a JVM monitor, and on iOS an `os_unfair_lock`, which lends the waiter's
 * priority to the holder (a spin lock behind a preempted low-QoS holder would stall main for the
 * rest of a scheduler quantum, which Hopi would then report as the app's jank). Free, either
 * costs one compare-and-set. Not reentrant.
 */
@HopiInternalApi
public class PerfLock {
    private val lock = PlatformLock()

    public fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/** The platform's mutex under [PerfLock]: the same thread unlocks it. */
internal expect class PlatformLock() {
    fun lock()

    fun unlock()
}

/**
 * One time series: [capacity] points of a timestamp (epoch milliseconds) and [columns] float
 * values, in primitive arrays allocated at the first point (never while its monitor stays off) and
 * then kept, so a point costs no allocation and no boxing. Single writer (its monitor's thread);
 * readers copy under the lock.
 */
@HopiInternalApi
public class SeriesRing(public val capacity: Int, public val columns: Int) {
    private var times = EMPTY_TIMES
    private var values = EMPTY_VALUES
    private var head = 0
    private var size = 0
    private val lock = PerfLock()

    public val count: Int get() = lock.locked { size }

    /** Whether the arrays exist yet (tests: nothing is allocated while every monitor is off). */
    internal val allocated: Boolean get() = lock.locked { times.isNotEmpty() }

    /** Appends one point; [row] holds [columns] values and is not kept. */
    public fun add(timeMs: Long, row: FloatArray) {
        lock.locked {
            if (times.isEmpty()) {
                times = LongArray(capacity)
                values = FloatArray(capacity * columns)
            }
            val slot = (head + size) % capacity
            times[slot] = timeMs
            row.copyInto(values, slot * columns, 0, columns)
            if (size < capacity) size++ else head = (head + 1) % capacity
        }
    }

    /** The newest [limit] points, oldest first. */
    public fun last(limit: Int): SeriesPoints = lock.locked {
        val n = minOf(limit, size)
        val outTimes = LongArray(n)
        val outValues = FloatArray(n * columns)
        for (i in 0 until n) {
            val slot = (head + size - n + i) % capacity
            outTimes[i] = times[slot]
            values.copyInto(outValues, i * columns, slot * columns, slot * columns + columns)
        }
        SeriesPoints(columns, outTimes, outValues)
    }

    public fun clear() {
        lock.locked {
            head = 0
            size = 0
        }
    }
}

/** A copy of some points of a [SeriesRing]. */
@HopiInternalApi
public class SeriesPoints(public val columns: Int, public val times: LongArray, public val values: FloatArray) {
    public val size: Int get() = times.size

    public fun value(point: Int, column: Int): Float = values[point * columns + column]
}

/** Something with a process-wide, monotonic sequence number. */
@HopiInternalApi
public interface Sequenced {
    public val seq: Long
}

/**
 * The events of one kind, newest kept: at most [capacity], each kind in its own ring so a jank
 * flood never evicts a hang. An event can be replaced by a newer version with the same sequence
 * number (a hang completed at its end).
 */
@HopiInternalApi
public class EventRing<T : Sequenced>(public val capacity: Int) {
    // Allocated at the first event, so a kind that never occurs holds no slots.
    private var slots: Array<Any?> = EMPTY_SLOTS
    private var head = 0
    private var size = 0
    private val lock = PerfLock()

    public val count: Int get() = lock.locked { size }

    /** Whether the slots exist yet (tests). */
    internal val allocated: Boolean get() = lock.locked { slots.isNotEmpty() }

    public fun add(event: T) {
        lock.locked {
            if (slots.isEmpty()) slots = arrayOfNulls(capacity)
            slots[(head + size) % capacity] = event
            if (size < capacity) size++ else head = (head + 1) % capacity
        }
    }

    /** Replaces the event with [event]'s sequence number; false when it was evicted. */
    public fun replace(event: T): Boolean = lock.locked {
        for (i in 0 until size) {
            val slot = (head + i) % capacity
            @Suppress("UNCHECKED_CAST")
            if ((slots[slot] as T).seq == event.seq) {
                slots[slot] = event
                return@locked true
            }
        }
        false
    }

    /** The events after [since], oldest first, at most the newest [limit] of them. */
    @Suppress("UNCHECKED_CAST")
    public fun since(since: Long, limit: Int = capacity): List<T> = lock.locked {
        val out = ArrayList<T>()
        for (i in 0 until size) {
            val event = slots[(head + i) % capacity] as T
            if (event.seq > since) out += event
        }
        if (out.size > limit) out.subList(out.size - limit, out.size).toList() else out
    }

    @Suppress("UNCHECKED_CAST")
    public fun find(seq: Long): T? = lock.locked {
        for (i in 0 until size) {
            val event = slots[(head + i) % capacity] as T
            if (event.seq == seq) return@locked event
        }
        null
    }

    public fun clear() {
        lock.locked {
            if (slots.isNotEmpty()) slots.fill(null)
            head = 0
            size = 0
        }
    }
}

/**
 * Frame times since start, in constant memory: 1 ms buckets to 200 ms, 10 ms buckets to 1 s, and
 * one overflow bucket, for p50/p90/p99; the maximum is kept exactly.
 */
@HopiInternalApi
public class FrameHistogram {
    private val buckets = LongArray(FINE + COARSE + 1)
    public var total: Long = 0
        private set
    public var maxMs: Double = 0.0
        private set

    public fun add(ms: Double) {
        val index = when {
            ms < FINE -> ms.toInt().coerceAtLeast(0)
            ms < 1_000 -> FINE + ((ms.toInt() - FINE) / 10)
            else -> FINE + COARSE
        }
        buckets[index]++
        total++
        if (ms > maxMs) maxMs = ms
    }

    /** The upper bound of the bucket holding the [fraction] quantile (0..1); null with no frame. */
    public fun percentile(fraction: Double): Double? {
        if (total == 0L) return null
        val rank = kotlin.math.ceil(fraction * total).toLong().coerceIn(1, total)
        var seen = 0L
        for (i in buckets.indices) {
            seen += buckets[i]
            if (seen >= rank) {
                val upper = when {
                    i < FINE -> (i + 1).toDouble()
                    i < FINE + COARSE -> (FINE + (i - FINE + 1) * 10).toDouble()
                    else -> maxMs
                }
                return minOf(upper, maxMs)
            }
        }
        return maxMs
    }

    public fun copy(): FrameHistogram = FrameHistogram().also { copy ->
        buckets.copyInto(copy.buckets)
        copy.total = total
        copy.maxMs = maxMs
    }

    public fun clear() {
        buckets.fill(0)
        total = 0
        maxMs = 0.0
    }

    private companion object {
        const val FINE = 200
        const val COARSE = 80
    }
}

private val EMPTY_TIMES = LongArray(0)
private val EMPTY_VALUES = FloatArray(0)
private val EMPTY_SLOTS = arrayOfNulls<Any>(0)
