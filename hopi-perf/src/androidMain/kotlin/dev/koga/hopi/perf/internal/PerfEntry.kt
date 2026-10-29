package dev.koga.hopi.perf.internal

import android.app.Application
import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.perf.PerfLimits
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfStartRequest
import dev.koga.hopi.core.start.HopiTool
import dev.koga.hopi.core.start.HopiToolContext

/**
 * `hopi-perf`'s tool in the start sequence: the Gradle plugin's generated configuration lists it,
 * with the `perf { }` block's values, when the plugin put `hopi-perf` in the variant
 * (`hopi { perf { enabled = true } }`). On the main thread before `Application.onCreate` it hands
 * the `Application` to the monitors, creates the controller with the block's settings and the
 * start's `summaryLog`, registers it for remote control and as `HopiPerf`'s runtime
 * ([PerfRuntime.registerApi]), then starts the [autoStart] monitors. Nothing else: no note in the
 * start line. Like everything on that path it names no monitor class (see [AndroidBackend]) and
 * creates no lambda.
 */
@HopiInternalApi
public class PerfEntry(
    /** A bit set of [bit], in the plugin's `HopiMonitor` order. */
    private val autoStart: Int,
    private val hangThresholdMs: Long,
    private val hangHeartbeat: Boolean,
    /** False: the bridge's `perf.start`, `perf.stop` and a tail's start are refused. */
    private val remoteControl: Boolean,
) : HopiTool {
    override val name: String get() = "perf"

    override fun start(context: HopiToolContext): String? {
        val application = context.platform as? Application
        if (application != null) AndroidBackend.application(application)
        val settings = PerfSettings(hangThresholdMs, hangHeartbeat, PerfLimits.DEFAULT_SAMPLE_INTERVAL_MS, remoteControl, context.config.summaryLog)
        val controller = PerfRuntime.install(settings) ?: return null
        PerfRuntime.registerApi(controller)
        if (autoStart == 0) return null
        val monitors = LinkedHashSet<PerfMonitor>()
        for (monitor in PerfMonitor.values()) if (autoStart and bit(monitor) != 0) monitors.add(monitor)
        // Before any activity: frames attaches at the first start, so no "attach later" notice.
        val backend = AndroidBackend.instance
        backend.autoStarting = true
        try {
            controller.start(PerfStartRequest(monitors), PerfOwner.Code)
        } finally {
            backend.autoStarting = false
        }
        return null
    }

    /** [monitor]'s bit in [autoStart]: `1 shl` its ordinal, which the plugin's `HopiMonitor` order matches. */
    private fun bit(monitor: PerfMonitor): Int = 1 shl monitor.ordinal
}
