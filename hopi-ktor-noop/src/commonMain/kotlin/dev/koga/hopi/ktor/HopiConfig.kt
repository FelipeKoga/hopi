package dev.koga.hopi.ktor

import io.ktor.utils.io.KtorDsl

/** Where completed transactions are announced. The noop announces nothing, whatever is chosen. */
public enum class HopiLogSink {
    /** Nothing is written to the platform log. */
    Off,

    /** In `hopi-ktor`, one NDJSON summary line per transaction. The noop writes nothing. */
    Summary,
}

/**
 * Configuration of the noop [Hopi] plugin: every setting of `hopi-ktor` is accepted, kept
 * so that reading it back returns what was set, and otherwise ignored.
 */
@KtorDsl
public class HopiConfig {
    /** Accepted and ignored: the noop installs no hooks either way. */
    public var enabled: Boolean = true

    /** Accepted and ignored: the noop has no store. */
    public var maxEntries: Int = DEFAULT_MAX_ENTRIES

    /** Accepted and ignored: the noop has no store. */
    public var maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES

    /** Accepted and ignored: the noop captures no body. */
    public var maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES

    /** Accepted and ignored: the noop writes nothing to the platform log. */
    public var logSink: HopiLogSink = HopiLogSink.Summary

    /** Accepted and ignored: the noop starts no agent bridge. */
    public var agentBridge: Boolean = false

    /** Accepted and ignored: the noop applies no rules. */
    public var rules: Boolean = true

    /** Accepted and ignored: nothing is captured, so nothing needs redacting. */
    public fun redactHeaders(vararg names: String) {}

    /** Accepted and ignored: nothing is captured, so nothing needs redacting. */
    public fun redactQueryParameters(vararg names: String) {}

    /** Accepted and ignored: nothing is captured, so nothing is exposed. */
    public fun unsafeDisableRedaction() {}

    // The same defaults as hopi-ktor, so code that reads a setting back behaves the same.
    private companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 500
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 16L * 1024 * 1024
        const val DEFAULT_MAX_BODY_BYTES: Long = 256L * 1024
    }
}
