package dev.koga.hopi.core.perf

import dev.koga.hopi.api.HopiInternalApi
import kotlin.concurrent.atomics.AtomicReference

/** Who holds a running monitor (docs/design/perf-monitors.md, "Owners"). */
@HopiInternalApi
public sealed interface PerfOwner {
    /** Started in code or by the Gradle plugin's `autoStart`: no lease. */
    public data object Code : PerfOwner

    /** A `perf.start`: held for [ttlSec] seconds (0: no expiry), extended by a later start. */
    public data class Lease(val ttlSec: Long) : PerfOwner

    /** A `perf.tail` with `"start"`: held until that bridge session ends. */
    public data class Tail(val session: Long) : PerfOwner
}

/**
 * The owners of each monitor: a monitor runs while at least one holds it. A lease expiring or a
 * tail session ending removes only its own owner, so it never stops a monitor that code or another
 * client also holds; `perf.stop` removes every owner. Not thread-safe: its controller locks.
 * Times are milliseconds of the controller's monotonic clock.
 */
@HopiInternalApi
public class PerfOwners {
    private class Holders {
        var code = false

        /** 0: no lease; [NO_EXPIRY]: a lease without expiry. */
        var leaseUntil = 0L
        val tails = LinkedHashSet<Long>()

        val held: Boolean get() = code || leaseUntil != 0L || tails.isNotEmpty()
    }

    private val holders = HashMap<PerfMonitor, Holders>()

    /** Adds [owner] to [monitor]; returns whether the monitor was not held before (it must start). */
    public fun add(monitor: PerfMonitor, owner: PerfOwner, nowMs: Long): Boolean {
        val entry = holders.getOrPut(monitor) { Holders() }
        val wasHeld = entry.held
        when (owner) {
            PerfOwner.Code -> entry.code = true
            is PerfOwner.Lease -> {
                val until = if (owner.ttlSec == 0L) NO_EXPIRY else nowMs + owner.ttlSec * 1000
                entry.leaseUntil = if (entry.leaseUntil == NO_EXPIRY || until == NO_EXPIRY) NO_EXPIRY else maxOf(entry.leaseUntil, until)
            }
            is PerfOwner.Tail -> entry.tails += owner.session
        }
        return !wasHeld
    }

    /** Removes every owner of [monitor] (`perf.stop`, `HopiPerf.stop`); returns whether it was held. */
    public fun clear(monitor: PerfMonitor): Boolean = holders.remove(monitor)?.held == true

    /** Removes tail [session] everywhere; returns the monitors no one holds any more. */
    public fun removeTail(session: Long): Set<PerfMonitor> {
        val released = LinkedHashSet<PerfMonitor>()
        for ((monitor, entry) in holders) {
            if (entry.tails.remove(session) && !entry.held) released += monitor
        }
        released.forEach { holders.remove(it) }
        return released
    }

    /** Ends every lease due at [nowMs]; returns the monitors no one holds any more. */
    public fun expire(nowMs: Long): Set<PerfMonitor> {
        val released = LinkedHashSet<PerfMonitor>()
        for ((monitor, entry) in holders) {
            if (entry.leaseUntil != 0L && entry.leaseUntil != NO_EXPIRY && entry.leaseUntil <= nowMs) {
                entry.leaseUntil = 0L
                if (!entry.held) released += monitor
            }
        }
        released.forEach { holders.remove(it) }
        return released
    }

    public fun held(monitor: PerfMonitor): Boolean = holders[monitor]?.held == true

    public val heldMonitors: Set<PerfMonitor> get() = holders.filterValues { it.held }.keys

    /** The lease's end, null for none or no expiry. */
    public fun leaseUntil(monitor: PerfMonitor): Long? = holders[monitor]?.leaseUntil?.takeIf { it != 0L && it != NO_EXPIRY }

    /** The earliest lease end, null for none. */
    public fun nextExpiry(): Long? = holders.values.mapNotNull { e -> e.leaseUntil.takeIf { it != 0L && it != NO_EXPIRY } }.minOrNull()

    /** `code`, `lease`, `lease:no-expiry`, `tail:N`. */
    public fun describe(monitor: PerfMonitor): List<String> {
        val entry = holders[monitor] ?: return emptyList()
        return buildList {
            if (entry.code) add("code")
            if (entry.leaseUntil == NO_EXPIRY) add("lease:no-expiry") else if (entry.leaseUntil != 0L) add("lease")
            entry.tails.forEach { add("tail:$it") }
        }
    }

    private companion object {
        const val NO_EXPIRY = Long.MAX_VALUE
    }
}

/** A `perf.start`: the monitors, and the settings given (null: keep the value in force). */
@HopiInternalApi
public class PerfStartRequest(
    public val monitors: Set<PerfMonitor>,
    public val hangThresholdMs: Long? = null,
    public val sampleIntervalMs: Long? = null,
)

/** One running monitor in a status. */
@HopiInternalApi
public class PerfMonitorStatus(
    public val monitor: PerfMonitor,
    /** Wall clock, epoch ms. */
    public val sinceMs: Long,
    public val owners: List<String>,
    /** Wall clock, epoch ms; null without a lease or with one that does not expire. */
    public val expiresAtMs: Long?,
    /** Monitor-specific facts (`thresholdMs`, `printer`, `input`, `attached`, `windows`, …): strings, numbers, booleans, null. */
    public val details: List<Pair<String, Any?>>,
)

@HopiInternalApi
public class PerfStatus(
    public val platform: String,
    public val available: List<PerfMonitor>,
    public val remoteControl: Boolean,
    public val running: List<PerfMonitorStatus>,
    /** What the last start or stop could not do as asked (a displaced printer, frames unavailable). */
    public val notices: List<String> = emptyList(),
)

@HopiInternalApi
public sealed interface PerfResult {
    public class Ok(public val status: PerfStatus) : PerfResult

    /** [code] is `UNSUPPORTED` or `LIMIT`. */
    public class Failed(public val code: String, public val message: String) : PerfResult
}

/** A PSS measurement: [kb] null when refused ([refused] says why, e.g. the 10 s rate limit). */
@HopiInternalApi
public class PerfPss(public val kb: Long?, public val ms: Long, public val refused: String? = null)

/**
 * What `hopi-perf` registers in [PerfHook]: starts, stops and reports the monitors. Every call
 * returns at once (a stop never waits on a hung main thread).
 */
@HopiInternalApi
public interface PerfController {
    public val platform: String
    public val available: Set<PerfMonitor>

    /** False: `perf.start`/`perf.stop` over the bridge are refused (`remoteControl = false`). */
    public val remoteControl: Boolean

    public val data: PerfData

    public fun start(request: PerfStartRequest, owner: PerfOwner): PerfResult

    /** Removes every owner of [monitors] (empty: all) and stops them. */
    public fun stop(monitors: Set<PerfMonitor>): PerfStatus

    /** A bridge session that started monitors ended: removes only its own owner. */
    public fun releaseTail(session: Long)

    /**
     * Calls [listener] with the monitors running, at once and then each time that set changes (a
     * start, a stop, a lease's expiry, a tail session's end), so a viewer of their figures drops a
     * stopped monitor's. It runs on the changing thread under the controller's lock, before a
     * started monitor writes and after a stopped one was told to stop, so it must only hand the set
     * off and never call the controller. Returns the function that removes it. A controller that
     * does not report the set never calls it.
     */
    public fun addRunningListener(listener: (Set<PerfMonitor>) -> Unit): () -> Unit = {}

    public fun status(): PerfStatus

    /** The top [limit] threads by CPU; null where the platform has no per-thread list. */
    public fun threads(limit: Int): List<PerfThread>?

    /** Keeps the per-thread scan running with the sampler while a viewer shows it. Counted: each true is matched by one false. */
    public fun watchThreads(watching: Boolean)

    public fun measurePss(): PerfPss

    /**
     * iOS frames tier 2: a viewer (a `perf.tail` with `"fps":true`) asks for the display-link
     * cadence while frames runs. Counted: each true is matched by one false, and the display link
     * exists only while the count is above 0. Nothing elsewhere (Android's FrameMetrics already
     * gives every frame).
     */
    public fun watchCadence(watching: Boolean) {}
}

/**
 * The single point where `hopi-perf` plugs into core: at most one [PerfController], registered by
 * `HopiPerf.install`/`start` or by `hopi-perf`'s tool in the start sequence (on Android when the
 * Gradle plugin put `hopi-perf` in the variant, on iOS unless `hopi-start`'s `perf` is false).
 * Holds nothing else, starts nothing: an app without `hopi-perf` only ever loads this object (the
 * bridge's `hello` reads it).
 */
@HopiInternalApi
public object PerfHook {
    private val registered = AtomicReference<PerfController?>(null)

    public val controller: PerfController? get() = registered.load()

    /** Registers [controller] unless one is; returns the one in force. */
    public fun register(controller: PerfController): PerfController {
        registered.compareAndSet(null, controller)
        return registered.load() ?: controller
    }

    /** Tests only. */
    public fun reset() {
        registered.store(null)
    }
}
