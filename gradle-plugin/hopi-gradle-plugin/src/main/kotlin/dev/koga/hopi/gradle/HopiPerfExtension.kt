package dev.koga.hopi.gradle

import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/** The monitors of `hopi-perf` (docs/design/perf-monitors.md), in its `HopiMonitor` order. */
public enum class HopiMonitor { Frames, Hangs, Cpu, Memory }

/**
 * `hopi { perf { } }`: the settings of the performance monitors, which the app gets by declaring
 * `debugImplementation("io.github.felipekoga.hopi:hopi-perf:<v>")`. Its tool registers them for
 * remote control (`hopi perf start`) and for `HopiPerf` at start, none started unless [autoStart]
 * names it. A monitor that is off costs nothing.
 */
public abstract class HopiPerfExtension {
    /** Monitors perf's tool starts at start, before `Application.onCreate`; none by default. */
    public abstract val autoStart: SetProperty<HopiMonitor>

    /** 100..10 000. */
    public abstract val hangThresholdMs: Property<Long>

    /** A heartbeat instead of the Looper printer, so another tool's printer stays; it wakes the app while idle. */
    public abstract val hangHeartbeat: Property<Boolean>

    /**
     * True (the default) lets the bridge start and stop the monitors (`hopi perf start` and `stop`,
     * a tail's start, the desktop viewer's switches); false refuses those, while reads still work
     * and code (`HopiPerf.start`) and [autoStart] still start monitors.
     */
    public abstract val remoteControl: Property<Boolean>

    public fun autoStart(vararg monitors: HopiMonitor) {
        autoStart.addAll(*monitors)
    }

    internal fun applyDefaults() {
        autoStart.convention(emptySet())
        hangThresholdMs.convention(250L)
        hangHeartbeat.convention(false)
        remoteControl.convention(true)
    }

    internal companion object {
        /** The bit set hopi-perf's tool reads (`PerfEntry`): one bit per monitor, by its order. */
        fun bits(monitors: Set<HopiMonitor>): Int = monitors.fold(0) { bits, monitor -> bits or (1 shl monitor.ordinal) }
    }
}
