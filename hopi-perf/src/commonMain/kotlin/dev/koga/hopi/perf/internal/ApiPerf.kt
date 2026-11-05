package dev.koga.hopi.perf.internal

import dev.koga.hopi.api.HopiRuntimes
import dev.koga.hopi.core.perf.PerfJson
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfStartRequest
import dev.koga.hopi.core.perf.emitPerfLogLine
import dev.koga.hopi.perf.HopiMonitor
import dev.koga.hopi.perf.HopiPerfRuntime

/**
 * The perf runtime behind `hopi-api`'s `HopiPerf` (docs/design/perf-monitors.md, "Consumer API"),
 * the one perf's tool registers ([register], through [PerfRuntime.registerApi]). Each call goes to
 * [ApiPerfCalls], and that is all this object does: a debug build runs from its APK, unoptimized,
 * so ART verifies a class when it is first initialized and loads every class its methods name. The
 * Android tool registers it on the main thread before `Application.onCreate`, where core's rules
 * runtime, while it held its calls, made its registration load 42 more classes (the runtime-split
 * plan's stage 7, on the emulator); [ApiPerfCalls] loads the classes of the calls at the first
 * `HopiPerf` call instead.
 */
internal object ApiPerf : HopiPerfRuntime {
    /**
     * Registers this runtime with `hopi-api`'s registry. Returns how many `HopiPerf.start` calls were
     * dropped before, or `-1` when a perf runtime was registered already. Writes nothing: the tool's
     * registration step writes the notice.
     */
    fun register(): Int = if (HopiRuntimes.registerPerf(this)) HopiRuntimes.droppedPerfStarts else -1

    override fun start(monitors: Set<HopiMonitor>, hangThresholdMs: Long?, hangHeartbeat: Boolean?, sampleIntervalMs: Long?) {
        ApiPerfCalls.start(monitors, hangThresholdMs, hangHeartbeat, sampleIntervalMs)
    }

    override fun stop(monitors: Set<HopiMonitor>) {
        ApiPerfCalls.stop(monitors)
    }

    override val running: Set<HopiMonitor> get() = ApiPerfCalls.running()

    override fun screen(name: String?) {
        ApiPerfCalls.screen(name)
    }
}

/**
 * What [ApiPerf] forwards to, over the controller perf's tool created before it registered
 * ([PerfRuntime.existing]). A start validates only the values given (one out of range: its notice
 * line, written past the sink's cap as the tool's start writes it, nothing applied, nothing
 * started), merges them into the settings in force, also when it names no monitor, then starts
 * the monitors set true that the platform has, as owner `code`, with a notice line for each it
 * lacks. Nothing throws to `HopiPerf`'s caller, Swift included: a call that fails writes a notice
 * line instead.
 */
internal object ApiPerfCalls {
    fun start(monitors: Set<HopiMonitor>, hangThresholdMs: Long?, hangHeartbeat: Boolean?, sampleIntervalMs: Long?) {
        val controller = PerfRuntime.existing() ?: return
        try {
            val problem = PerfRuntime.invalid(hangThresholdMs, sampleIntervalMs)
            if (problem != null) {
                // Past the sink's cap, as the tool's start writes it: a start that does nothing always says why.
                emitPerfLogLine(PerfJson.noticeLine("HopiPerf: $problem; nothing started"))
                return
            }
            controller.update(hangThresholdMs, hangHeartbeat, sampleIntervalMs)
            if (monitors.isEmpty()) return
            val wanted = monitors.mapTo(LinkedHashSet()) { it.monitor }
            val available = wanted.filterTo(LinkedHashSet()) { it in controller.available }
            (wanted - available).forEach { controller.notice("${it.wireName} is not available on ${controller.platform}") }
            if (available.isNotEmpty()) controller.start(PerfStartRequest(available), PerfOwner.Code)
        } catch (e: Throwable) {
            controller.notice("HopiPerf.start failed: ${describe(e)}")
        }
    }

    fun stop(monitors: Set<HopiMonitor>) {
        val controller = PerfRuntime.existing() ?: return
        try {
            controller.stop(monitors.mapTo(LinkedHashSet()) { it.monitor })
        } catch (e: Throwable) {
            controller.notice("HopiPerf.stop failed: ${describe(e)}")
        }
    }

    fun running(): Set<HopiMonitor> {
        val controller = PerfRuntime.existing() ?: return emptySet()
        return try {
            controller.running.mapTo(LinkedHashSet()) { it.consumer }
        } catch (e: Throwable) {
            controller.notice("HopiPerf.running failed: ${describe(e)}")
            emptySet()
        }
    }

    fun screen(name: String?) {
        val controller = PerfRuntime.existing() ?: return
        try {
            controller.data.setScreen(name)
        } catch (e: Throwable) {
            controller.notice("HopiPerf.screen failed: ${describe(e)}")
        }
    }

    /** `"<Class>: <message>"`, as the start sequence writes a tool that failed. */
    private fun describe(error: Throwable): String {
        val name = error::class.simpleName ?: "Throwable"
        val message = error.message
        return if (message.isNullOrEmpty()) name else "$name: $message"
    }

    private val HopiMonitor.monitor: PerfMonitor
        get() = when (this) {
            HopiMonitor.Frames -> PerfMonitor.FRAMES
            HopiMonitor.Hangs -> PerfMonitor.HANGS
            HopiMonitor.Cpu -> PerfMonitor.CPU
            HopiMonitor.Memory -> PerfMonitor.MEMORY
        }

    private val PerfMonitor.consumer: HopiMonitor
        get() = when (this) {
            PerfMonitor.FRAMES -> HopiMonitor.Frames
            PerfMonitor.HANGS -> HopiMonitor.Hangs
            PerfMonitor.CPU -> HopiMonitor.Cpu
            PerfMonitor.MEMORY -> HopiMonitor.Memory
        }
}
