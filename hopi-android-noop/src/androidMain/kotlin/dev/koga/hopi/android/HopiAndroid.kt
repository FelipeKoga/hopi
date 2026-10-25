package dev.koga.hopi.android

/**
 * The noop twin of hopi-android's `HopiAndroid`, which the `dev.koga.hopi` plugin gives every
 * non-debuggable variant: capture never starts and OkHttp is never instrumented.
 */
public object HopiAndroid {
    /** Always false: release variants record nothing. */
    public val isStarted: Boolean get() = false

    /** Always false: release variants are not instrumented. */
    public val okHttpInstrumented: Boolean get() = false
}
