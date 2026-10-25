package dev.koga.hopi.urlsession

/**
 * Configuration of the noop [HopiUrlSession.start]: every setting of `hopi-urlsession` is
 * accepted, kept so that reading it back returns what was set, and otherwise ignored.
 */
public class HopiUrlSessionConfig {
    /** Accepted and ignored: the noop installs nothing either way. */
    public var enabled: Boolean = true

    /** Accepted and ignored: the noop has no store. */
    public var maxEntries: Int = DEFAULT_MAX_ENTRIES

    /** Accepted and ignored: the noop has no store. */
    public var maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES

    /** Accepted and ignored: the noop captures no body. */
    public var maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES

    /** Accepted and ignored: the noop writes nothing to the platform log. */
    public var summaryLog: Boolean = true

    /** Accepted and ignored: the noop starts no agent bridge. */
    public var agentBridge: Boolean = false

    /** Accepted and ignored: the noop has no rules. */
    public var rules: Boolean = true

    /** Accepted and ignored: nothing is captured, so nothing needs redacting. */
    public fun redactHeaders(vararg names: String) {}

    /** Accepted and ignored: nothing is captured, so nothing needs redacting. */
    public fun redactQueryParameters(vararg names: String) {}

    /** Accepted and ignored: nothing is captured, so nothing is exposed. */
    public fun unsafeDisableRedaction() {}

    // The same defaults as hopi-urlsession, so code that reads a setting back behaves the same.
    private companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 500
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 16L * 1024 * 1024
        const val DEFAULT_MAX_BODY_BYTES: Long = 256L * 1024
    }
}
