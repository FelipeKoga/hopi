package dev.koga.hopi.start

import dev.koga.hopi.core.start.HopiStart
import dev.koga.hopi.core.start.HopiTool

/**
 * Hopi's start on iOS: one call, first thing at startup, in a Debug build
 * (`Hopi.shared.start { $0.agentBridge = true }` from Swift, inside `#if HOPI`). It runs core's
 * start sequence (docs/ARCHITECTURE.md, "Start sequence") with this platform's tools: automatic
 * URLSession capture, then the performance monitors, registered for remote control
 * ([HopiStartConfig.perfRemoteControl]) and for `HopiPerf`, with none started
 * ([HopiStartConfig.perf]): a `HopiPerf` call does nothing before it. The shared module adds this
 * module, and exports it from the framework, only for a Debug build (`-Phopi.enabled=true`), so a
 * Release framework has no `Hopi`; Swift's `HOPI` condition follows the same Xcode build setting.
 *
 * Idempotent: the first valid call's configuration wins for the process, and a later call with a
 * different one writes a notice line instead. Nothing throws, since Swift cannot catch: invalid
 * caps write a notice line and start nothing, without using up the first call, and a tool that
 * fails to start writes one while the next tool still starts.
 */
public object Hopi {
    /** True once a call started the start sequence in this process. */
    public val isStarted: Boolean get() = HopiStart.config != null

    /** Starts Hopi with the default configuration. Idempotent; the first call's configuration wins. */
    public fun start() {
        start {}
    }

    /** Starts Hopi configured by [configure]. Idempotent; the first call's configuration wins. */
    public fun start(configure: HopiStartConfig.() -> Unit) {
        val config = HopiStartConfig().apply(configure)
        HopiStart.start(config.toStartConfig(), tools(config))
    }
}

/** The tools [Hopi.start] starts on this platform, in start order. */
internal expect fun tools(config: HopiStartConfig): List<HopiTool>
