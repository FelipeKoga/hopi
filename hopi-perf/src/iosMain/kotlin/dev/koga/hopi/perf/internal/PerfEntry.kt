package dev.koga.hopi.perf.internal

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.start.HopiTool
import dev.koga.hopi.core.start.HopiToolContext
import dev.koga.hopi.perf.HopiPerfConfig

/**
 * `hopi-perf`'s tool in the iOS start sequence: `hopi-start`'s `Hopi.start` lists it unless its
 * `perf` is false. It registers the controller with the default settings for remote control (`hopi
 * perf start`, the desktop viewer's Perf panel) and starts only the monitors a `-hopiPerf` launch
 * argument names, read once ([PerfRuntime.install]). Nothing else: no note in the start line, no
 * bridge (the start sequence's `agentBridge` starts it, so perf's own stays false), and it calls
 * the runtime itself, never `HopiPerf.install`. Android's tool of the same name takes the plugin's
 * `perf { }` values.
 */
@HopiInternalApi
public class PerfEntry : HopiTool {
    override val name: String get() = "perf"

    override fun start(context: HopiToolContext): String? {
        PerfRuntime.install(HopiPerfConfig())
        return null
    }
}
