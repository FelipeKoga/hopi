package dev.koga.hopi.perf.internal

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.perf.PerfLimits
import dev.koga.hopi.core.start.HopiTool
import dev.koga.hopi.core.start.HopiToolContext

/**
 * `hopi-perf`'s tool in the iOS start sequence: `hopi-start`'s `Hopi.start` lists it unless its
 * `perf` is false, with its `perfRemoteControl`. It creates the controller with the default
 * threshold and interval and the start's `summaryLog`, registers it for remote control (`hopi perf
 * start`, the desktop viewer's Perf panel) and as `HopiPerf`'s runtime ([PerfRuntime.registerApi]),
 * and starts only the monitors a `-hopiPerf` launch argument names, read once ([PerfRuntime.install]).
 * Nothing else: no note in the start line, and no bridge (the start sequence's `agentBridge` starts
 * it). Android's tool of the same name takes the plugin's `perf { }` values.
 */
@HopiInternalApi
public class PerfEntry(
    /** False: the bridge's `perf.start`, `perf.stop` and a tail's start are refused. */
    public val remoteControl: Boolean,
) : HopiTool {
    override val name: String get() = "perf"

    override fun start(context: HopiToolContext): String? {
        val settings = PerfSettings(PerfLimits.DEFAULT_HANG_THRESHOLD_MS, false, PerfLimits.DEFAULT_SAMPLE_INTERVAL_MS, remoteControl, context.config.summaryLog)
        val controller = PerfRuntime.install(settings) ?: return null
        PerfRuntime.registerApi(controller)
        return null
    }
}
