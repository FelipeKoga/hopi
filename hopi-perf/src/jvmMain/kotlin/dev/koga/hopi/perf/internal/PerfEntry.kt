package dev.koga.hopi.perf.internal

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.perf.PerfLimits
import dev.koga.hopi.core.start.HopiTool
import dev.koga.hopi.core.start.HopiToolContext

/**
 * `hopi-perf`'s tool on the JVM (CPU and memory, see [JvmBackend]), the iOS tool's shape: it creates
 * the controller with the default threshold and interval and the start's `summaryLog`, registers it
 * for remote control and as `HopiPerf`'s runtime ([PerfRuntime.registerApi]), and starts nothing.
 * `hopi-start`'s JVM `Hopi.start` lists it unless its `perf` is false; `hopi-perf`'s JVM tests also
 * start it.
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
