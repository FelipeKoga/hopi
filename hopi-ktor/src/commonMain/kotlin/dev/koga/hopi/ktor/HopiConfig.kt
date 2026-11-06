package dev.koga.hopi.ktor

import dev.koga.hopi.api.HopiInternalApi
import io.ktor.utils.io.KtorDsl

/** Where completed transactions are announced for agents reading the platform log. */
public enum class HopiLogSink {
    /** Nothing is written to the platform log. */
    Off,

    /**
     * One NDJSON summary line per completed or failed transaction, without bodies, plus notice
     * lines (redaction disabled, transactions dropped under load).
     */
    Summary,
}

/**
 * Configuration of the [Hopi] plugin. It is read only where a debug build's network runtime
 * registered its recorder (a debuggable Android build after its provider's start, a JVM process
 * after `Hopi.start`): the recorder then makes this client's installation from it, after the
 * start's, with the store semantics below. Elsewhere (a release build, iOS, a JVM process before
 * `Hopi.start`) every setting is accepted and ignored, invalid caps included; where a recorder is
 * registered, invalid caps throw at `install(Hopi)`.
 */
@KtorDsl
public class HopiConfig {
    /** Runtime kill switch. When false the plugin installs no hooks at all, whatever is registered. */
    public var enabled: Boolean = true

    /**
     * Ring buffer entry cap. Taken from the first installation in the process unless the start
     * preset the store's caps, which every start that registers a recorder did: in practice the
     * start's cap (`hopi { maxEntries }`, `Hopi.start { maxEntries }`).
     */
    public var maxEntries: Int = 500

    /**
     * Ring buffer cap on captured body bytes across all entries. Taken from the first installation
     * unless the start preset the store's caps, which every start that registers a recorder did: in
     * practice the start's cap.
     */
    public var maxTotalBytes: Long = 16L * 1024 * 1024

    /**
     * Per-body capture cap for this client's requests; longer bodies are truncated. Zero records
     * sizes only. Clamped to half of the store's [maxTotalBytes]. One entry keeps at most three
     * quarters of the store: when a transaction's two bodies together exceed that (both near the
     * largest cap), the store drops the larger one (the request's on a tie), so a raised cap never
     * evicts the whole history. At the largest cap the store holds two entries of full-size bodies.
     */
    public var maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES

    /**
     * [HopiLogSink.Summary] starts the process's summary sink at this client's installation (once
     * per process), even where the start said `summaryLog = false`; the start's `summaryLog` starts
     * it at the latest at the first client's installation, whatever this says. [HopiLogSink.Off]
     * only keeps this installation from starting it.
     */
    public var logSink: HopiLogSink = HopiLogSink.Summary

    /** Requests the device-local agent bridge (once per process; the start's `agentBridge` usually did, and is on by default there). */
    public var agentBridge: Boolean = false

    /**
     * Applies Hopi rules (mock, delay, fail, edit) to the requests this plugin records, where the
     * network tool's own `rules` is also true. With no rule set a request pays one read of the rule
     * state; false makes the plugin never read it.
     */
    public var rules: Boolean = true

    private val headers = mutableListOf<String>()
    private val queryParameters = mutableListOf<String>()
    private var disabled = false

    /** The header names [redactHeaders] added, for the recorder. */
    @HopiInternalApi
    public val redactedHeaders: List<String> get() = headers

    /** The query parameter names [redactQueryParameters] added, for the recorder. */
    @HopiInternalApi
    public val redactedQueryParameters: List<String> get() = queryParameters

    /** Whether [unsafeDisableRedaction] was called, for the recorder. */
    @HopiInternalApi
    public val redactionDisabled: Boolean get() = disabled

    /** Adds header names (case-insensitive) to the default redaction list. */
    public fun redactHeaders(vararg names: String) {
        headers += names
    }

    /** Redacts the values of these query parameters (case-insensitive) in every outward channel. */
    public fun redactQueryParameters(vararg names: String) {
        queryParameters += names
    }

    /**
     * Turns redaction off for this installation's contribution. Redaction stays on while any
     * installation keeps it, and the start's installation comes before every client's: it takes
     * effect only where the start also disabled redaction (`hopi { unsafeDisableRedaction() }`,
     * `Hopi.start { unsafeDisableRedaction() }`). When it is off after this installation and
     * [logSink] is [HopiLogSink.Summary], one notice line is written to the platform log, once per
     * process.
     */
    public fun unsafeDisableRedaction() {
        disabled = true
    }

    private companion object {
        const val DEFAULT_MAX_BODY_BYTES: Long = 256L * 1024
    }
}
