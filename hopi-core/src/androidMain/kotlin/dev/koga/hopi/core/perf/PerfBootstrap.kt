package dev.koga.hopi.core.perf

import android.app.Application
import dev.koga.hopi.core.HopiInternalApi

/**
 * How `hopi-android`'s provider hands the app to `hopi-perf` when the Gradle plugin put it in the
 * variant (`hopi { perf { enabled = true } }`): it loads [CLASS_NAME] by name, only then (it never
 * probes for an absent class), and makes one call. [boot] registers the controller in [PerfHook]
 * and starts the monitors of [autoStart], a bit set of [bit]; it starts nothing else.
 */
@HopiInternalApi
public interface PerfBootstrap {
    public fun boot(application: Application?, autoStart: Int, hangThresholdMs: Long, hangHeartbeat: Boolean)

    @HopiInternalApi
    public companion object {
        public const val CLASS_NAME: String = "dev.koga.hopi.perf.internal.PerfEntry"

        public fun bit(monitor: PerfMonitor): Int = 1 shl monitor.ordinal
    }
}
