package dev.koga.hopi.urlsession

import kotlin.concurrent.atomics.AtomicBoolean

/**
 * Automatic capture of every URLSession request of the process on iOS, including those of
 * third-party SDKs and of Ktor's Darwin engine, into the Hopi store: one call at startup, in debug
 * builds (`HopiUrlSession.shared.start()` from Swift). Observe only: no request is re-sent and
 * nothing the app sees changes (docs/design/urlsession-capture.md). On Android and the JVM it
 * does nothing, so common code can call it. Since the runtime-split plan's stage 6 the iOS start
 * is `hopi-start`'s `Hopi.start`, whose network tool starts the same capture (and which registers
 * the performance monitors); this one starts the capture alone, until the plan's stage 9.
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

    /**
     * True once a [start] call here found capture running: one that started it, or one made after
     * `hopi-start`'s `Hopi.start` had. It stays false while only `Hopi.start` started capture
     * (`Hopi.isStarted` says that), and on Android and the JVM.
     */
    public val isStarted: Boolean get() = started.load()
}

/** Installs the platform capture; false where there is none. */
internal expect fun platformStart(config: HopiUrlSessionConfig): Boolean
