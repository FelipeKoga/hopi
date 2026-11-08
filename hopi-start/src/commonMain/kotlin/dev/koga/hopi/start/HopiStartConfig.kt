package dev.koga.hopi.start

import dev.koga.hopi.core.start.StartConfig
import dev.koga.hopi.core.store.HopiStore

/**
 * Configuration of [Hopi.start]: what every tool shares (the store's caps, redaction, the summary
 * log, the agent bridge) and the tools' own settings ([rules]; [perf] and [perfRemoteControl]).
 * Primitive members only, so Swift sees this class and no core type. The store is the process-wide
 * one every recorder shares, and its redaction is the union of every installation's lists. Called
 * first thing, before anything created the store, the start presets these caps, so they win over a
 * recorder's own (a Ktor client's on the JVM included); a store that exists already keeps its caps.
 * On the JVM every Ktor client's installation makes this configuration's first (its redaction, its
 * summary log), then the client's own.
 */
public class HopiStartConfig internal constructor() {
    /** Runtime kill switch. When false [Hopi.start] starts nothing and writes nothing. */
    public var enabled: Boolean = true

    /** Ring buffer entry cap. Taken from the first installation in the process. */
    public var maxEntries: Int = HopiStore.DEFAULT_MAX_ENTRIES

    /** Ring buffer cap on captured body bytes across all entries. Taken from the first installation. */
    public var maxTotalBytes: Long = HopiStore.DEFAULT_MAX_TOTAL_BYTES

    /** Per-body capture cap; longer bodies are truncated. Clamped to half of the store's [maxTotalBytes]. */
    public var maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES

    /**
     * One NDJSON summary line per completed or failed transaction in the platform log (os_log on
     * iOS, stderr on the JVM), plus notices, and the performance monitors' rate-limited lines (on
     * iOS os_log category `perf`, on the JVM stderr; their notices are written whatever this says).
     */
    public var summaryLog: Boolean = true

    /** Starts the device-local agent bridge at start, before any request, so the CLI and the macOS viewer can connect; on by default. */
    public var agentBridge: Boolean = true

    /**
     * Applies Hopi rules (mock, delay, fail, request edit) to URLSession requests on iOS, and to the
     * Ktor plugin's requests on the JVM (mock, delay, fail, edit; a client's own `rules` must be true
     * too). On iOS, with no rule set, a session created after [Hopi.start] pays one
     * `canInitWithTask:` message per task and nothing else, and false installs no rule hook at all;
     * on the JVM a recorded request pays one read of the rule state, and false keeps the recorder
     * from ever reading it (docs/design/rules.md).
     */
    public var rules: Boolean = true

    /**
     * Starts the network capture (on iOS URLSession's, on the JVM the Ktor plugin's recorder). The
     * Gradle plugin's `Hopi` sets it from the module's `debugImplementation` of `hopi-network`.
     */
    public var network: Boolean = true

    /**
     * Registers the performance monitors for remote control (`hopi perf start`, the macOS
     * viewer's Perf panel) and for `HopiPerf` from code, and starts none: a monitor costs nothing
     * until it starts. On iOS a `-hopiPerf frames,hangs` launch argument starts those at launch; on
     * the JVM only CPU and memory exist. False leaves perf out of the start, and `HopiPerf` then
     * does nothing.
     */
    public var perf: Boolean = true

    /**
     * Lets the bridge start and stop the monitors (`hopi perf start` and `stop`, a tail's start, the
     * macOS viewer's switches); false refuses those, while reads still work and code and
     * `-hopiPerf` still start monitors. Set at start only.
     */
    public var perfRemoteControl: Boolean = true

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
        /** Core's default ([StartConfig.maxBodyBytes]); a test compares the two. */
        const val DEFAULT_MAX_BODY_BYTES: Long = 256L * 1024
    }
}

/** Core's start configuration for [this]; the lists are copied, so the start keeps what was set at the call. */
internal fun HopiStartConfig.toStartConfig(): StartConfig = StartConfig(
    enabled = enabled,
    maxEntries = maxEntries,
    maxTotalBytes = maxTotalBytes,
    maxBodyBytes = maxBodyBytes,
    redactedHeaders = redactedHeaders.toList(),
    redactedQueryParameters = redactedQueryParameters.toList(),
    redactionDisabled = redactionDisabled,
    summaryLog = summaryLog,
    agentBridge = agentBridge,
)
