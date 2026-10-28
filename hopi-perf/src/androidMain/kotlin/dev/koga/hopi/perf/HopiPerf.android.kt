package dev.koga.hopi.perf

import android.app.Application
import dev.koga.hopi.perf.internal.AndroidBackend
import dev.koga.hopi.perf.internal.PerfRuntime

/**
 * [HopiPerf.install] for an app without the provider's start sequence (no `dev.koga.hopi` Gradle
 * plugin): [application] gives the frames monitor each activity's window, the hang monitor its
 * input bracket and the memory monitor the OS's trim callbacks. With the plugin, the start hands
 * the `Application` to perf's tool, so there is nothing to pass. Starts nothing.
 */
public fun HopiPerf.install(application: Application, block: HopiPerfConfig.() -> Unit = {}) {
    AndroidBackend.application(application)
    PerfRuntime.install(HopiPerfConfig().apply(block))
}
