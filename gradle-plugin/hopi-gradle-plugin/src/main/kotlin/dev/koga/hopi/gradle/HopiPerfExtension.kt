package dev.koga.hopi.gradle

import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/** The monitors of `hopi-perf` (docs/design/perf-monitors.md), in its `HopiMonitor` order. */
public enum class HopiMonitor { Frames, Hangs, Cpu, Memory }

/**
 * `hopi { perf { } }`: Hopi's performance monitors in the debuggable variants. Everything is off
 * by default; [enabled] puts `hopi-perf` in debuggable variants (and `hopi-perf-noop` in the
 * others) and its tool in the generated tool list, which registers it for remote control
 * (`hopi perf start`) at start without any code in the app. A monitor that is off costs nothing.
 */
public abstract class HopiPerfExtension {
    public abstract val enabled: Property<Boolean>

    /** Monitors perf's tool starts at start, before `Application.onCreate`; none by default. */
    public abstract val autoStart: SetProperty<HopiMonitor>

    /** 100..10 000. */
    public abstract val hangThresholdMs: Property<Long>

    /** A heartbeat instead of the Looper printer, so another tool's printer stays; it wakes the app while idle. */
    public abstract val hangHeartbeat: Property<Boolean>

    public fun autoStart(vararg monitors: HopiMonitor) {
        autoStart.addAll(*monitors)
    }

    internal fun applyDefaults() {
        enabled.convention(false)
        autoStart.convention(emptySet())
        hangThresholdMs.convention(250L)
        hangHeartbeat.convention(false)
    }

    internal companion object {
        /** The bit set hopi-perf's tool reads (`PerfEntry`): one bit per monitor, by its order. */
        fun bits(monitors: Set<HopiMonitor>): Int = monitors.fold(0) { bits, monitor -> bits or (1 shl monitor.ordinal) }
    }
}
