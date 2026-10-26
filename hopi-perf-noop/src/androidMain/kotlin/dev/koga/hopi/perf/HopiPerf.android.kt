package dev.koga.hopi.perf

import android.app.Application

/** The noop twin of `HopiPerf.install(application)`: does nothing. */
public fun HopiPerf.install(application: Application, block: HopiPerfConfig.() -> Unit = {}) {
    HopiPerfConfig().apply(block)
}
