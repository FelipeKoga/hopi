package dev.koga.hopi.perf

/**
 * The noop twin of `hopi-perf`'s HopiPerf: the same API, and nothing runs. Every call returns at
 * once without effect and [running] is always empty; no thread, no listener, no Hopi code.
 */
public object HopiPerf {
    public fun install(block: HopiPerfConfig.() -> Unit = {}) {
        HopiPerfConfig().apply(block)
    }

    public fun start(block: HopiPerfConfig.() -> Unit) {
        HopiPerfConfig().apply(block)
    }

    public fun stop(vararg monitors: HopiMonitor) {
        // Nothing runs.
    }

    public val running: Set<HopiMonitor> get() = emptySet()

    public fun screen(name: String?) {
        // Nothing records it.
    }
}

public enum class HopiMonitor { Frames, Hangs, Cpu, Memory }

/** The same settings as the real module, with the same defaults; nothing reads them. */
public class HopiPerfConfig internal constructor() {
    public var frames: Boolean = false
    public var hangs: Boolean = false
    public var hangThresholdMs: Long = 250
    public var hangHeartbeat: Boolean = false
    public var cpu: Boolean = false
    public var memory: Boolean = false
    public var sampleIntervalMs: Long = 1_000
    public var remoteControl: Boolean = true
    public var agentBridge: Boolean = false
    public var summaryLog: Boolean = true
}
