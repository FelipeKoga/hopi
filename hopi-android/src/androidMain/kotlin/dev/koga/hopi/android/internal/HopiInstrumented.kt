package dev.koga.hopi.android.internal

import dev.koga.hopi.core.HopiInternalApi

/**
 * The class annotation the `dev.koga.hopi` plugin adds to OkHttp's `RealCall` when it rewrote its
 * `OkHttpClient.interceptors()` call. The runtime defers the Ktor plugin to OkHttp capture only when
 * it finds it, so the plugin never defers to capture that is not there.
 */
@HopiInternalApi
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
public annotation class HopiInstrumented
