package dev.koga.hopi.android

import dev.koga.hopi.android.internal.AndroidCapture

/**
 * Automatic capture of every OkHttp call and every `HttpURLConnection` of the app, third-party
 * SDKs included, into the Hopi store (docs/design/android-capture.md). Nothing to call: the
 * `dev.koga.hopi` Gradle plugin instruments the debuggable variants of the app and starts capture
 * from a provider of their manifest. Release variants get the noop twin, where both values are false.
 */
public object HopiAndroid {
    /** True once automatic capture runs in this process (never in the noop, never in release). */
    public val isStarted: Boolean get() = AndroidCapture.started

    /** True when this APK's OkHttp was instrumented (its `RealCall` carries Hopi's annotation). */
    public val okHttpInstrumented: Boolean get() = AndroidCapture.okHttpInstrumented
}
