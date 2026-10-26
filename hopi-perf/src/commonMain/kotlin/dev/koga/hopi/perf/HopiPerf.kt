package dev.koga.hopi.perf

import dev.koga.hopi.perf.internal.PerfRuntime

/**
 * Hopi's performance monitors (docs/design/perf-monitors.md): **frames** (FPS and jank), **hangs**
 * (main-thread stalls with the main thread's stack), **CPU** and **memory**. Each is off by default
 * and opt-in on its own, and a monitor that is off costs nothing: no thread, no listener, no timer,
 * no class of its own loaded. A monitor that is on states its cost in the design's budget table.
 * Data stays in memory, in bounded rings, and is never persisted or sent anywhere; it reaches the
 * realtime log (`HopiPerf` lines), the bridge (`hopi perf …`) and, later, the in-app screen.
 *
 * [install] registers the monitors for remote control (the bridge's `perf.start`) and starts
 * nothing; [start] installs and starts the monitors its block sets true. Invalid values write a
 * notice line and start nothing. Thread-safe; every call returns at once.
 *
 * The noop twin (`hopi-perf-noop`) has this API and does nothing: [running] is always empty.
 */
public object HopiPerf {
    /** Registers the monitors for remote control (and starts the bridge with [HopiPerfConfig.agentBridge]); starts nothing. */
    public fun install(block: HopiPerfConfig.() -> Unit = {}) {
        PerfRuntime.install(HopiPerfConfig().apply(block))
    }

    /** [install], then starts the monitors set true in [block]. */
    public fun start(block: HopiPerfConfig.() -> Unit) {
        PerfRuntime.start(HopiPerfConfig().apply(block))
    }

    /** Stops [monitors]; none named: all. Removes every owner, remote ones included. */
    public fun stop(vararg monitors: HopiMonitor) {
        PerfRuntime.stop(monitors.toSet())
    }

    /** The monitors running now. */
    public val running: Set<HopiMonitor> get() = PerfRuntime.running()

    /**
     * Labels what is on screen for the janky frames and hangs that follow (a route name, never user
     * data), cut to 64 chars; null falls back to the resumed activity's class name on Android.
     */
    public fun screen(name: String?) {
        PerfRuntime.screen(name)
    }
}

public enum class HopiMonitor { Frames, Hangs, Cpu, Memory }

/** What [HopiPerf.install] and [HopiPerf.start] read. Every monitor is off by default. */
public class HopiPerfConfig internal constructor() {
    /** FPS and jank (Android FrameMetrics; iOS main-thread stalls). */
    public var frames: Boolean = false

    /** Main-thread units of work longer than [hangThresholdMs], with the main thread's stacks. */
    public var hangs: Boolean = false

    /** 100..10 000. */
    public var hangThresholdMs: Long = 250

    /**
     * Android: a heartbeat message every `threshold / 2` instead of the Looper printer, for an app
     * that needs another tool's printer; it wakes both threads while the app is idle (8 per second
     * at 250 ms), which the printer does not.
     */
    public var hangHeartbeat: Boolean = false

    /** Process CPU, Hopi's own share, and the per-thread list while a viewer shows it. */
    public var cpu: Boolean = false

    /** Heaps, resident memory, GC counts, and the OS's memory signals. */
    public var memory: Boolean = false

    /** The sampler's interval for CPU and memory, 250..10 000. */
    public var sampleIntervalMs: Long = 1_000

    /** `perf.start` and `perf.stop` over the bridge. */
    public var remoteControl: Boolean = true

    /** Starts the agent bridge, as the recorders' `agentBridge` does. */
    public var agentBridge: Boolean = false

    /** Perf lines in the platform log (rate-limited: at most 5 per second, a sample line every 10 s). */
    public var summaryLog: Boolean = true
}
