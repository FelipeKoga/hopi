package dev.koga.hopi.urlsession

import dev.koga.hopi.core.store.HopiStore

/**
 * Configuration of [HopiUrlSession.start]. The store settings mean what they mean for the Ktor
 * plugin, and the store is the same process-wide one: the first installation of either sets the
 * caps, redaction is the union of every installation's lists.
 */
public class HopiUrlSessionConfig {
    /** Runtime kill switch. When false [HopiUrlSession.start] installs nothing. */
    public var enabled: Boolean = true

    /** Ring buffer entry cap. Taken from the first installation in the process. */
    public var maxEntries: Int = HopiStore.DEFAULT_MAX_ENTRIES

    /** Ring buffer cap on captured body bytes across all entries. Taken from the first installation. */
    public var maxTotalBytes: Long = HopiStore.DEFAULT_MAX_TOTAL_BYTES

    /** Per-body capture cap; longer bodies are truncated. Clamped to half of the store's [maxTotalBytes]. */
    public var maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES

    /** One NDJSON summary line per completed or failed transaction in the platform log, plus notices. */
    public var summaryLog: Boolean = true

    /** Starts the device-local agent bridge. */
    public var agentBridge: Boolean = false

    /**
     * Applies Hopi rules (mock, delay, fail, request edit) to URLSession requests. With no
     * rule set, a session created after [HopiUrlSession.start] pays one `canInitWithTask:` message
     * per task and nothing else; false installs no rule hook at all (docs/design/rules.md).
     */
    public var rules: Boolean = true

    internal val redactedHeaders = mutableListOf<String>()
    internal val redactedQueryParameters = mutableListOf<String>()
    internal var redactionDisabled = false

    /** Adds header names (case-insensitive) to the default redaction list. */
    public fun redactHeaders(vararg names: String) {
        redactedHeaders += names
    }

    /** Redacts the values of these query parameters (case-insensitive) in every outward channel. */
    public fun redactQueryParameters(vararg names: String) {
        redactedQueryParameters += names
    }

    /** Turns redaction off for this installation's contribution; it stays on while any installation keeps it. */
    public fun unsafeDisableRedaction() {
        redactionDisabled = true
    }

    private companion object {
        const val DEFAULT_MAX_BODY_BYTES: Long = 256L * 1024
    }
}
