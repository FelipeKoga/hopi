package dev.koga.hopi.ktor

import dev.koga.hopi.core.store.HopiStore
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

/** Configuration of the [Hopi] plugin. */
@KtorDsl
public class HopiConfig {
    /** Runtime kill switch. When false the plugin installs no hooks at all. */
    public var enabled: Boolean = true

    /** Ring buffer entry cap. Taken from the first installation in the process. */
    public var maxEntries: Int = HopiStore.DEFAULT_MAX_ENTRIES

    /** Ring buffer cap on captured body bytes across all entries. Taken from the first installation. */
    public var maxTotalBytes: Long = HopiStore.DEFAULT_MAX_TOTAL_BYTES

    /**
     * Per-body capture cap; longer bodies are truncated. Zero records sizes only. Clamped to half
     * of the store's [maxTotalBytes]. One entry keeps at most three quarters of the store: when a
     * transaction's two bodies together exceed that (both near the largest cap), the store drops
     * the larger one (the request's on a tie), so a raised cap never evicts the whole history. At
     * the largest cap the store holds two entries of full-size bodies.
     */
    public var maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES

    public var logSink: HopiLogSink = HopiLogSink.Summary

    /** Starts the device-local agent bridge. */
    public var agentBridge: Boolean = false

    internal val redactedHeaders = mutableListOf<String>()
    internal val redactedQueryParameters = mutableListOf<String>()
    internal var redactionDisabled = false

    internal var store: HopiStore? = null

    /** Adds header names (case-insensitive) to the default redaction list. */
    public fun redactHeaders(vararg names: String) {
        redactedHeaders += names
    }

    /** Redacts the values of these query parameters (case-insensitive) in every outward channel. */
    public fun redactQueryParameters(vararg names: String) {
        redactedQueryParameters += names
    }

    /**
     * Turns redaction off for this installation's contribution. Redaction stays on while any
     * installation keeps it. When it is off after this installation and [logSink] is
     * [HopiLogSink.Summary], one notice line is written to the platform log, once per process.
     */
    public fun unsafeDisableRedaction() {
        redactionDisabled = true
    }

    private companion object {
        const val DEFAULT_MAX_BODY_BYTES: Long = 256L * 1024
    }
}
