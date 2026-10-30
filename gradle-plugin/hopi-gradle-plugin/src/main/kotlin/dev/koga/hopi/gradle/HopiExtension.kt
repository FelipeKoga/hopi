package dev.koga.hopi.gradle

import org.gradle.api.Action
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested

/**
 * The optional `hopi { }` block of an Android application module. Every value has a default, so
 * `plugins { id("dev.koga.hopi") }` alone is the whole setup. The store, redaction, log and bridge
 * settings at the top are the start's, shared by every tool, and mean what they mean for
 * `Hopi.start` on iOS and the JVM and for the Ktor plugin, whose every client's installation makes
 * the start's first; capture's own settings are in [network], the monitors' in [perf].
 */
public abstract class HopiExtension {
    /**
     * False: no variant gets a runtime, a transform or a generated class. The release guard still
     * runs on every non-debuggable variant: it refuses a runtime whatever this says.
     */
    public abstract val enabled: Property<Boolean>

    public abstract val maxEntries: Property<Int>
    public abstract val maxTotalBytes: Property<Long>
    public abstract val maxBodyBytes: Property<Long>

    /**
     * One NDJSON line per recorded request in logcat (tag `Hopi`), the start's own line, and the
     * performance monitors' rate-limited lines (tag `HopiPerf`, whose notices are written whatever
     * this says).
     */
    public abstract val summaryLog: Property<Boolean>
    public abstract val agentBridge: Property<Boolean>
    public abstract val redactedHeaders: ListProperty<String>
    public abstract val redactedQueryParameters: ListProperty<String>
    public abstract val redactionDisabled: Property<Boolean>

    /** Automatic OkHttp and `HttpURLConnection` capture (docs/design/android-capture.md); on unless `network { enabled = false }`. */
    @get:Nested
    public abstract val network: HopiNetworkExtension

    public fun network(action: Action<HopiNetworkExtension>) {
        action.execute(network)
    }

    /** The performance monitors (docs/design/perf-monitors.md); off unless `perf { enabled = true }`. */
    @get:Nested
    public abstract val perf: HopiPerfExtension

    public fun perf(action: Action<HopiPerfExtension>) {
        action.execute(perf)
    }

    /** Adds header names (case-insensitive) to the default redaction list. */
    public fun redactHeaders(vararg names: String) {
        redactedHeaders.addAll(*names)
    }

    /** Redacts the values of these query parameters (case-insensitive). */
    public fun redactQueryParameters(vararg names: String) {
        redactedQueryParameters.addAll(*names)
    }

    /** Turns redaction off for this installation's contribution. */
    public fun unsafeDisableRedaction() {
        redactionDisabled.set(true)
    }

    internal fun applyDefaults() {
        enabled.convention(true)
        maxEntries.convention(500)
        maxTotalBytes.convention(16L * 1024 * 1024)
        maxBodyBytes.convention(256L * 1024)
        summaryLog.convention(true)
        agentBridge.convention(false)
        redactionDisabled.convention(false)
        network.applyDefaults()
        perf.applyDefaults()
    }
}
