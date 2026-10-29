package dev.koga.hopi.perf

import dev.koga.hopi.api.HopiInternalApi

/**
 * The perf runtime [HopiPerf] delegates to once one is registered
 * ([dev.koga.hopi.api.HopiRuntimes.perf]): `hopi-perf`'s, which perf's tool registers when a debug
 * build's start starts it. [HopiPerf] hands it the values its block set, null for each setting
 * left unset (keep the value in force); the runtime validates them, writes its own notice for one
 * out of range, and never throws. `hopi-api` comes from the app, and the runtime needs it at its
 * own version. On Android the Gradle plugin adds `hopi-perf` at the plugin's version and holds
 * `hopi-api` to it in debuggable builds. On iOS both come from the app's shared module
 * (`hopi-start` brings `hopi-perf`) at the versions it names, so it names one version for both.
 * Nothing registers a runtime on the JVM before the runtime-split plan's stage 10.
 */
@HopiInternalApi
public interface HopiPerfRuntime {
    /** Applies each setting given (null: keep the value in force), then starts [monitors] (empty: none). */
    public fun start(monitors: Set<HopiMonitor>, hangThresholdMs: Long?, hangHeartbeat: Boolean?, sampleIntervalMs: Long?)

    /** Stops [monitors] (empty: all), removing every owner. */
    public fun stop(monitors: Set<HopiMonitor>)

    /** The monitors running now. */
    public val running: Set<HopiMonitor>

    /** Labels what is on screen (null: none). */
    public fun screen(name: String?)
}
