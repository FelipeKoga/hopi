package dev.koga.hopi.urlsession

import kotlin.concurrent.atomics.AtomicBoolean

/**
 * Automatic capture of every URLSession request of the process on iOS, including those of
 * third-party SDKs and of Ktor's Darwin engine, into the Hopi store: one call at startup, in debug
 * builds (`HopiUrlSession.shared.start()` from Swift). Observe only: no request is re-sent and
 * nothing the app sees changes (docs/design/urlsession-capture.md). On Android and the JVM it
 * does nothing, so common code can call it.
 */
public object HopiUrlSession {
    private val started = AtomicBoolean(false)

    /** Starts capture with the default configuration. Idempotent; the first call's configuration wins. */
    public fun start() {
        start {}
    }

    /** Starts capture configured by [configure]. Idempotent; the first call's configuration wins. */
    public fun start(configure: HopiUrlSessionConfig.() -> Unit) {
        val config = HopiUrlSessionConfig().apply(configure)
        if (!config.enabled) return
        if (platformStart(config)) started.store(true)
    }

    /** True once capture runs in this process (never on Android or the JVM). */
    public val isStarted: Boolean get() = started.load()
}

/** Installs the platform capture; false where there is none. */
internal expect fun platformStart(config: HopiUrlSessionConfig): Boolean
