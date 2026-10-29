package dev.koga.hopi.perf

import dev.koga.hopi.api.HopiRuntimes

/**
 * Hopi's performance monitors (docs/design/perf-monitors.md): **frames** (FPS and jank), **hangs**
 * (main-thread stalls with the main thread's stack), **CPU** and **memory**. Each is off by default
 * and opt-in on its own, and a monitor that is off costs nothing: no thread, no listener, no timer,
 * no class of its own loaded. A monitor that is on states its cost in the design's budget table.
 * Data stays in memory, in bounded rings, and is never persisted or sent anywhere; it reaches the
 * realtime log (`HopiPerf` lines) and the bridge (`hopi perf …`, the desktop viewer's Perf panel).
 *
 * Every call does nothing until a debug build's start registers the perf runtime, as in a release
 * build: on Android the Gradle plugin's `perf { enabled = true }`, on iOS `Hopi.shared.start`
 * unless its `perf` is false, on the JVM nothing before the runtime-split plan's stage 10. No call
 * starts the runtime itself. The block always runs. A [start] made before the registration is
 * dropped and counted, and the start that registers the runtime writes one notice line with the
 * count (an iOS Debug binary also writes one line at the first dropped start); [screen] keeps its
 * last label for a runtime that registers later. A start with invalid caps registers nothing, so,
 * unlike `HopiRules`, every call stays inert, and [start] keeps counting, until a valid start runs
 * the tools.
 *
 * [start] applies only what its block sets: a setting left unset keeps the value in force (the
 * plugin's `perf { }`, a remote `perf.start`'s), and it starts the monitors set true. Out-of-range
 * values write the runtime's notice line and start nothing. The summary log, the agent bridge and
 * remote control belong to the start's configuration (set at start only), not to this block.
 * Thread-safe; every call returns at once, and nothing throws to the caller.
 */
public object HopiPerf {
    /** Starts the monitors set true in [block], with the settings it sets; the others keep the values in force. */
    public fun start(block: HopiPerfConfig.() -> Unit) {
        val config = HopiPerfConfig().apply(block)
        val runtime = HopiRuntimes.perf
        if (runtime == null) {
            HopiRuntimes.countDroppedPerfStart()
            perfStartDroppedWithoutRuntime()
            return
        }
        runtime.start(config.monitors(), config.hangThresholdMsSet, config.hangHeartbeatSet, config.sampleIntervalMsSet)
    }

    /** Stops [monitors]; none named: all. Removes every owner, remote ones included. */
    public fun stop(vararg monitors: HopiMonitor) {
        val runtime = HopiRuntimes.perf ?: return
        runtime.stop(monitors.toSet())
    }

    /** The monitors running now. */
    public val running: Set<HopiMonitor> get() = HopiRuntimes.perf?.running ?: emptySet()

    /**
     * Labels what is on screen for the janky frames and hangs that follow (a route name, never user
     * data), cut to 64 chars; null falls back to the resumed activity's class name on Android.
     */
    public fun screen(name: String?) {
        // Kept before the slot is read, so a runtime that registers meanwhile still gets it.
        HopiRuntimes.keepPerfScreen(name)
        HopiRuntimes.perf?.screen(name)
    }
}

public enum class HopiMonitor { Frames, Hangs, Cpu, Memory }

/**
 * What [HopiPerf.start] reads. Every monitor is off by default: true starts it, false leaves it as
 * it is (never a stop). A setting applies only when the block sets it; read back, an unset one has
 * its default.
 */
public class HopiPerfConfig internal constructor() {
    /** FPS and jank (Android FrameMetrics; iOS main-thread stalls). */
    public var frames: Boolean = false

    /** Main-thread units of work longer than [hangThresholdMs], with the main thread's stacks. */
    public var hangs: Boolean = false

    /** 100..10 000; 250 by default. */
    public var hangThresholdMs: Long
        get() = hangThresholdMsSet ?: 250
        set(value) {
            hangThresholdMsSet = value
        }

    /**
     * Android: a heartbeat message every `threshold / 2` instead of the Looper printer, for an app
     * that needs another tool's printer; it wakes both threads while the app is idle (each 8 times
     * a second at 250 ms), which the printer does not. It takes effect at the next start of hangs.
     */
    public var hangHeartbeat: Boolean
        get() = hangHeartbeatSet ?: false
        set(value) {
            hangHeartbeatSet = value
        }

    /** Process CPU, Hopi's own share, and the per-thread list while a viewer shows it. */
    public var cpu: Boolean = false

    /** Heaps, resident memory, GC counts, and the OS's memory signals. */
    public var memory: Boolean = false

    /** The sampler's interval for CPU and memory, 250..10 000; 1 000 by default. */
    public var sampleIntervalMs: Long
        get() = sampleIntervalMsSet ?: 1_000
        set(value) {
            sampleIntervalMsSet = value
        }

    /** What the block set, null while unset: the runtime applies only these. */
    internal var hangThresholdMsSet: Long? = null
        private set
    internal var hangHeartbeatSet: Boolean? = null
        private set
    internal var sampleIntervalMsSet: Long? = null
        private set

    /** The monitors set true, in [HopiMonitor] order. */
    internal fun monitors(): Set<HopiMonitor> {
        val set = LinkedHashSet<HopiMonitor>()
        if (frames) set.add(HopiMonitor.Frames)
        if (hangs) set.add(HopiMonitor.Hangs)
        if (cpu) set.add(HopiMonitor.Cpu)
        if (memory) set.add(HopiMonitor.Memory)
        return set
    }
}

/**
 * Called by each [HopiPerf.start] that found no perf runtime registered, once it is counted.
 * Nothing on Android and the JVM: there a missing runtime is a release build, a build without the
 * plugin's `perf { enabled = true }`, a test that registers none, or an Android app's code that ran
 * before its provider, which the start's notice counts. On iOS the start is a call the app makes
 * (`Hopi.shared.start`), so a Debug binary's first such start writes one line to the system log, in
 * case that call, or its perf, is missing.
 */
internal expect fun perfStartDroppedWithoutRuntime()
