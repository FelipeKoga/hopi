package dev.koga.hopi.core.perf

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.concurrent.WakeSignal
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong

/**
 * What the hang watchdog reads and sleeps on: the main thread's packed busy state (see
 * [BusyState]) and its wake. [BusyState] is the Kotlin one (Android, the JVM tests); on iOS the
 * main run loop's observers, written in C, own the same packed value, and `hopi-perf` reads it
 * through this interface, so one [HangDetector] serves both. Only the watchdog calls the
 * park/sleep methods; [wake] may come from any thread.
 */
@HopiInternalApi
public interface MainBusy {
    /** The packed value; see [BusyState.sequenceOf] and [BusyState.startOf]. */
    public fun load(): Long

    /** A unit of work starts at [nowMs] (the heartbeat's begin, from the watchdog). */
    public fun begin(nowMs: Long)

    /** About to sleep with no timeout; false when the main thread turned busy meanwhile. */
    public fun parkWatchdog(): Boolean

    public fun unparkedWatchdog()

    public fun setHangOngoing(ongoing: Boolean)

    /** When the unit that was hanging ended (0: not seen). */
    public fun takeLastEnd(): Long

    /** Sleeps with no timeout until [wake] (or an idle-to-busy transition while parked). */
    public fun await()

    /** Sleeps at most [nanos]; [wake] ends it at once. */
    public fun doze(nanos: Long)

    /** Wakes the watchdog, from any thread. */
    public fun wake()
}

/**
 * Whether the main thread runs a unit of work (an Android message or input event, an iOS run-loop
 * iteration), published as **one packed 64-bit value** so the hang watchdog never reads the start
 * of one unit with the sequence number of another: the high 24 bits are a sequence number that
 * grows by one per unit, the low 40 bits the unit's start in milliseconds of the platform's
 * monotonic clock (Android `SystemClock.uptimeMillis()`), 0 while the main thread is idle.
 * (docs/design/perf-monitors.md, "Hangs".)
 *
 * Only the main thread calls [begin], [end] and the input bracket (or, in heartbeat mode, the
 * watchdog posts and main ends, never both at once); the watchdog only reads. [begin] costs one
 * atomic store plus one atomic read, and wakes the watchdog (one semaphore release) only when it
 * sleeps with no timeout: an idle-to-busy transition. [end] costs one store and one read.
 */
@HopiInternalApi
public class BusyState : MainBusy {
    private val packed = AtomicLong(0)
    private val watchdogParked = AtomicBoolean(false)
    private val hangOngoing = AtomicBoolean(false)
    private val lastEnd = AtomicLong(0)
    internal val signal: WakeSignal = WakeSignal()

    /** The packed value; see [sequenceOf] and [startOf]. */
    override fun load(): Long = packed.load()

    /** Main thread: a unit of work starts at [nowMs]. */
    override fun begin(nowMs: Long) {
        val seq = (sequenceOf(packed.load()) + 1) and SEQ_MASK
        packed.store(pack(seq, nowMs.coerceAtLeast(1)))
        if (watchdogParked.load() && watchdogParked.compareAndSet(true, false)) signal.wake()
    }

    /** Main thread: the unit of work that [begin] started ended at [nowMs]. */
    public fun end(nowMs: Long) {
        packed.store(pack(sequenceOf(packed.load()), 0))
        if (hangOngoing.load()) {
            lastEnd.store(nowMs)
            signal.wake()
        }
    }

    /**
     * Main thread, the input bracket: marks busy only when idle on entry (a batched `MOVE` inside
     * a frame message is already bracketed by the message). Returns whether [exitInput] must run.
     */
    public fun enterInput(nowMs: Long): Boolean {
        if (startOf(packed.load()) != 0L) return false
        begin(nowMs)
        return true
    }

    public fun exitInput(nowMs: Long) {
        end(nowMs)
    }

    /** Watchdog: about to sleep with no timeout; false when the main thread turned busy meanwhile. */
    override fun parkWatchdog(): Boolean {
        watchdogParked.store(true)
        if (startOf(packed.load()) != 0L) {
            watchdogParked.store(false)
            return false
        }
        return true
    }

    override fun unparkedWatchdog() {
        watchdogParked.store(false)
    }

    override fun setHangOngoing(ongoing: Boolean) {
        hangOngoing.store(ongoing)
    }

    /** When the unit that was hanging ended (0: not seen). Watchdog only. */
    override fun takeLastEnd(): Long = lastEnd.exchange(0)

    override fun await() {
        signal.await()
    }

    override fun doze(nanos: Long) {
        signal.doze(nanos)
    }

    override fun wake() {
        signal.wake()
    }

    @HopiInternalApi
    public companion object {
        public const val SEQ_BITS: Int = 24
        public const val START_BITS: Int = 40
        public const val SEQ_MASK: Long = (1L shl SEQ_BITS) - 1
        public const val START_MASK: Long = (1L shl START_BITS) - 1

        /** A start of 0 means idle, so a unit starting at clock 0 is recorded as starting at 1. */
        public fun pack(seq: Long, startMs: Long): Long {
            val start = if (startMs == 0L) 0L else (startMs and START_MASK).coerceAtLeast(1)
            return ((seq and SEQ_MASK) shl START_BITS) or start
        }

        public fun sequenceOf(packed: Long): Long = (packed ushr START_BITS) and SEQ_MASK

        public fun startOf(packed: Long): Long = packed and START_MASK

        public fun isIdle(packed: Long): Boolean = startOf(packed) == 0L
    }
}
