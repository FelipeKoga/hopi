package dev.koga.hopi.core.perf

import dev.koga.hopi.core.HopiInternalApi
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/** What the hang watchdog needs of its platform. Called on the watchdog thread only. */
@HopiInternalApi
public interface HangPlatform {
    /** The clock [BusyState] starts are in (Android `SystemClock.uptimeMillis()`). */
    public fun nowMs(): Long

    /** The frames' monotonic clock, for [PerfData.markPause]. */
    public fun monotonicNs(): Long

    /**
     * The main thread's stack, innermost frame first, at most [PerfLimits.MAX_STACK_FRAMES] frames;
     * empty when the runtime gave none (ART's suspension timed out).
     */
    public fun captureMainStack(): List<String>

    /**
     * Names a stack [captureMainStack] returned as raw addresses; null when it is already named
     * (Android, the JVM). iOS names with `dladdr`, which takes the dyld lock, so it runs only once
     * the hang's unit ended: the event is recorded and logged with the raw addresses at detection,
     * and completed with the names. Naming while the unit still ran held the lock against a main
     * thread that needed it to finish the unit (lazy binding on a first launch), which made the hang
     * read up to 120 ms long on the simulator; and a main thread hung inside dyld delays only the
     * names, never the detection.
     */
    public fun symbolicate(stack: List<String>): List<String>? = null

    /** One call per detection, never per message. */
    public fun debuggerAttached(): Boolean

    /**
     * Heartbeat mode: posts one empty message to the main thread that calls [BusyState.end] when
     * it runs; [BusyState.begin] was called just before.
     */
    public fun postHeartbeat()
}

/** How the watchdog sleeps next. */
@HopiInternalApi
public sealed interface HangWait {
    /** With no timeout: the main thread is idle; [BusyState.begin] wakes it. */
    public data object Untimed : HangWait

    /** With no timeout: a hang has all its stacks; its end ([BusyState.end]) wakes it. */
    public data object UntilEnd : HangWait

    /**
     * Until [deadlineMs] of [HangPlatform.nowMs]; only a hang's end, a stop or a lease change cut
     * it short. [busy]: the deadline is a running unit's, so a wake far past it means the process
     * was stopped (see [HangDetector]).
     */
    public data class Until(val deadlineMs: Long, val busy: Boolean) : HangWait
}

/**
 * The hang watchdog's state machine (docs/design/perf-monitors.md, "Hangs"), with no thread and
 * no clock of its own, so the JVM tests drive it with a fake clock. [next] reads [busy], detects,
 * captures, records into [data], and says how to sleep:
 *
 * - main idle: one doze of one threshold (so an animation's short idle gaps cost at most one wake
 *   per threshold), then untimed until [BusyState.begin] wakes it; no doze when the unit last seen
 *   busy is the last one that ran (a lone short unit: its begin's wake and its deadline's, no third);
 * - main busy: until the unit's start plus the threshold; the same unit still running then is a
 *   hang, captured at 1x, 2x and 4x the threshold (at most 3 stacks), each capture discarded when
 *   the sequence number changed meanwhile (the stack belongs to another unit);
 * - a wake later than its deadline by more than one threshold means the whole process was stopped
 *   (the freezer, a debugger suspending every thread): the unit's start moves to the late wake, so
 *   the freeze is never reported as a hang; one that goes on after the thaw is, one threshold later,
 *   with the frozen span as `frozenMs`;
 * - a detection while a debugger is attached takes no stack and is left out of the counts.
 *
 * The event is written and logged at detection and completed when the unit ends, with the
 * stacks' names where the platform names raw addresses ([HangPlatform.symbolicate]).
 */
@HopiInternalApi
public class HangDetector(
    private val busy: MainBusy,
    private val platform: HangPlatform,
    private val data: PerfData,
    thresholdMs: Long,
    private val heartbeat: Boolean = false,
) {
    /** Changed by a `perf.start` with `hangMs`; read on the watchdog thread at its next wake. */
    private val threshold = AtomicReference(thresholdMs)
    public var thresholdMs: Long
        get() = threshold.load()
        set(value) {
            threshold.store(value)
        }

    private var watchedSeq = -1L
    private var watchedStart = 0L
    private var originalStart = 0L
    private var captures = 0
    private var frozenMs = 0L
    private var current: HangEvent? = null
    private var dozed = false
    private var lastBusySeq = -1L
    private var lastDeadline = 0L
    private var nextBeatAt = 0L

    /** Captures made, and how many were discarded (tests). */
    public var captureCount: Int = 0
        private set
    public var discardedCaptures: Int = 0
        private set

    /** Decides at [now] what to do, does it, and returns how to sleep next. Watchdog thread only. */
    public fun next(now: Long = platform.nowMs()): HangWait {
        var time = now
        val threshold = threshold.load()
        val overslept = lastDeadline != 0L && time - lastDeadline > threshold
        val frozen = if (overslept) time - lastDeadline else 0L
        lastDeadline = 0L
        while (true) {
            val packed = busy.load()
            val idle = BusyState.isIdle(packed)
            val seq = BusyState.sequenceOf(packed)
            if (current != null && (idle || seq != watchedSeq)) finish(time)
            if (idle) {
                watchedSeq = -1
                if (heartbeat) {
                    if (time >= nextBeatAt) {
                        nextBeatAt = time + threshold / 2
                        busy.begin(time)
                        platform.postHeartbeat()
                        continue
                    }
                    return HangWait.Until(nextBeatAt, busy = false)
                }
                if (!dozed) {
                    dozed = true
                    // Nothing began since the unit last seen busy: the main thread went quiet, so
                    // the doze would only cost a wake; units in between mean it is active, and the
                    // doze absorbs their idle-to-busy wakes.
                    if (seq != lastBusySeq) return HangWait.Until(time + threshold, busy = false)
                }
                return HangWait.Untimed
            }
            dozed = false
            lastBusySeq = seq
            if (seq != watchedSeq) {
                watchedSeq = seq
                watchedStart = BusyState.startOf(packed)
                originalStart = watchedStart
                captures = 0
                frozenMs = 0
            } else if (overslept) {
                // The process was stopped: the freeze is not the unit's time.
                watchedStart = time
                frozenMs += frozen
            }
            if (captures >= PerfLimits.MAX_STACKS_PER_HANG) {
                // Nothing left to capture: sleep until the unit ends ([BusyState.end] wakes a hang's watchdog).
                return HangWait.UntilEnd
            }
            val due = watchedStart + threshold * MULTIPLIERS[captures]
            if (time < due) {
                lastDeadline = due
                return HangWait.Until(due, busy = true)
            }
            detect(time, seq, threshold)
            time = platform.nowMs()
        }
    }

    private fun detect(now: Long, seq: Long, threshold: Long) {
        val existing = current
        val debugger = existing?.debugger ?: platform.debuggerAttached()
        var stack: List<String>? = null
        var captureUs: Long? = null
        if (!debugger) {
            val startNs = platform.monotonicNs()
            val captured = platform.captureMainStack()
            val endNs = platform.monotonicNs()
            data.markPause(startNs, endNs)
            captureUs = (endNs - startNs) / 1_000
            captureCount++
            // The unit may have ended during the walk: then the stack is another unit's.
            if (BusyState.sequenceOf(busy.load()) != seq || BusyState.isIdle(busy.load())) {
                discardedCaptures++
            } else if (captured.isNotEmpty()) {
                stack = bounded(captured, existing?.stacks.orEmpty())
            }
        }
        captures = if (debugger) PerfLimits.MAX_STACKS_PER_HANG else captures + 1
        val duration = now - watchedStart
        if (existing == null) {
            val event = HangEvent(
                seq = data.nextSeq(),
                atMs = perfWallClockMs() - duration,
                thresholdMs = threshold,
                ended = false,
                durationMs = duration,
                screen = data.screen,
                stacks = listOfNotNull(stack),
                captureUs = listOfNotNull(captureUs),
                stackMissed = !debugger && stack == null,
                debugger = debugger,
                frozenMs = frozenMs,
                heartbeat = heartbeat,
            )
            current = event
            busy.setHangOngoing(true)
            data.recordHang(event)
        } else {
            val stacks = if (stack != null) existing.stacks + listOf(stack) else existing.stacks
            val event = existing.copy(
                durationMs = duration,
                stacks = stacks,
                captureUs = existing.captureUs + listOfNotNull(captureUs),
                stackMissed = !debugger && stacks.isEmpty(),
                frozenMs = frozenMs,
            )
            current = event
            data.updateHang(event)
        }
    }

    /** [stacks] with their names where the platform has them (after the unit ended), bounded together. */
    private fun named(stacks: List<List<String>>): List<List<String>> {
        val out = ArrayList<List<String>>(stacks.size)
        for (raw in stacks) {
            val names = platform.symbolicate(raw) ?: return stacks
            out += bounded(names, out) ?: bounded(raw, out) ?: break
        }
        return out
    }

    private fun finish(now: Long) {
        val event = current ?: return
        current = null
        busy.setHangOngoing(false)
        val end = busy.takeLastEnd().takeIf { it != 0L } ?: now
        val duration = (end - watchedStart).coerceAtLeast(event.durationMs)
        // The end is read before the names: the duration never includes the naming. The stack's
        // log line is written again with the names, just before the ended line.
        val stacks = named(event.stacks)
        if (stacks !== event.stacks) data.log?.namedHang(event.copy(stacks = stacks))
        data.updateHang(event.copy(ended = true, durationMs = duration, frozenMs = frozenMs, stacks = stacks))
    }

    /** Stopping mid-hang: completes the event with what is known. */
    public fun close() {
        if (current != null) finish(platform.nowMs())
    }

    /** Cuts frames to 512 chars and the hang's stacks together to 16 KB, at a frame boundary. */
    private fun bounded(stack: List<String>, previous: List<List<String>>): List<String>? {
        var budget = PerfLimits.MAX_HANG_STACK_BYTES - previous.sumOf { frames -> frames.sumOf { it.length + 1 } }
        val out = ArrayList<String>(minOf(stack.size, PerfLimits.MAX_STACK_FRAMES))
        for (frame in stack) {
            if (out.size == PerfLimits.MAX_STACK_FRAMES) break
            val cut = if (frame.length > PerfLimits.MAX_FRAME_CHARS) frame.substring(0, PerfLimits.MAX_FRAME_CHARS) else frame
            budget -= cut.length + 1
            if (budget < 0) break
            out += cut
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private companion object {
        val MULTIPLIERS = longArrayOf(1, 2, 4)
    }
}

/**
 * Runs a [HangDetector] on the calling thread (the platform's `hopi-perf-hang`) until [stop]:
 * sleeps with no timeout while the main thread is idle, timed while it is busy, on [BusyState]'s
 * signal. [timer] folds one more deadline (a remote lease's expiry) into the timed waits, so the
 * lease needs no thread of its own.
 */
@HopiInternalApi
public class HangWatchdog(
    private val busy: MainBusy,
    public val detector: HangDetector,
    private val clock: () -> Long,
    private val timer: PerfTimer? = null,
    /** Called just before each sleep (Hopi's own CPU accounting). */
    private val beforeSleep: () -> Unit = {},
) {
    private val running = AtomicBoolean(true)

    public fun run() {
        while (running.load()) {
            val wait = detector.next(clock())
            if (!running.load()) break
            val timerMs = timer?.msUntilDue() ?: Long.MAX_VALUE
            if (timerMs <= 0) {
                timer?.fire()
                continue
            }
            beforeSleep()
            when (wait) {
                // Parked either way, so [BusyState.begin] wakes it: a lease bounds the sleep, never
                // blinds it to a unit that starts meanwhile.
                HangWait.Untimed -> {
                    if (busy.parkWatchdog()) {
                        if (timerMs == Long.MAX_VALUE) busy.await() else busy.doze(timerMs * 1_000_000)
                    }
                    busy.unparkedWatchdog()
                }
                HangWait.UntilEnd -> if (timerMs == Long.MAX_VALUE) busy.await() else busy.doze(timerMs * 1_000_000)
                is HangWait.Until -> {
                    val ms = minOf(wait.deadlineMs - clock(), timerMs)
                    if (ms > 0) busy.doze(ms * 1_000_000)
                }
            }
        }
        detector.close()
    }

    /** Ends [run] at once, from any thread. */
    public fun stop() {
        running.store(false)
        busy.wake()
    }

    /** Wakes the watchdog to re-read its timer (a lease changed). */
    public fun poke() {
        busy.wake()
    }
}

/** A deadline a perf thread folds into its timed waits (a remote lease's expiry). */
@HopiInternalApi
public interface PerfTimer {
    /** Milliseconds until the deadline, `Long.MAX_VALUE` for none. */
    public fun msUntilDue(): Long

    public fun fire()
}
