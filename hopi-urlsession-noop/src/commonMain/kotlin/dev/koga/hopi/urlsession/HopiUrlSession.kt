package dev.koga.hopi.urlsession

/**
 * The noop twin of `hopi-urlsession`'s automatic URLSession capture: the same API, and nothing is
 * installed, captured or started. Release builds link this one.
 */
public object HopiUrlSession {
    /** Does nothing. */
    public fun start() {}

    /** Runs [configure] on a configuration that is then ignored, so the caller's code behaves the same. */
    public fun start(configure: HopiUrlSessionConfig.() -> Unit) {
        HopiUrlSessionConfig().apply(configure)
    }

    /** Always false: the noop never captures. */
    public val isStarted: Boolean get() = false
}
