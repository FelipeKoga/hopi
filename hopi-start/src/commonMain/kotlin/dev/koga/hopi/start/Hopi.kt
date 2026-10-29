package dev.koga.hopi.start

import dev.koga.hopi.core.start.HopiStart
import dev.koga.hopi.core.start.HopiTool

/**
 * Hopi's start on iOS and the JVM: one call, first thing at startup. On iOS, in a Debug build
 * (`Hopi.shared.start { $0.agentBridge = true }` from Swift, inside `#if HOPI`); on the JVM, first
 * in `main`, before any `HttpClient` is built (`HopiStart.start { agentBridge = true }`, imported
 * under an alias: the Ktor plugin is also named `Hopi`). It runs core's start sequence
 * (docs/ARCHITECTURE.md, "Start sequence") with this platform's tools: on iOS automatic URLSession
 * capture, on the JVM the Ktor plugin's recorder, then the performance monitors (on the JVM CPU and
 * memory only), registered for remote control ([HopiStartConfig.perfRemoteControl]) and for
 * `HopiPerf`, with none started ([HopiStartConfig.perf]): a `HopiPerf` call does nothing before
 * it. The Ktor plugin reads the recorder when a client is built, so on the JVM a client built
 * before this call stays unrecorded; the JVM writes no start line. On iOS the shared module adds
 * this module, and exports it from the framework, only for a Debug build (`-Phopi.enabled=true`),
 * so a Release framework has no `Hopi`; Swift's `HOPI` condition follows the same Xcode build
 * setting. The JVM has no release guard: a JVM app names this module only where it records.
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
