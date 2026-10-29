package dev.koga.hopi.gradle

import org.gradle.api.Action
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested

/**
 * The optional `hopi { }` block of an Android application module. Every value has a default, so
 * `plugins { id("dev.koga.hopi") }` alone is the whole setup. The store settings mean what they mean
 * for the Ktor plugin and `HopiUrlSession`.
 */
public abstract class HopiExtension {
    /** False: every variant gets the noop runtime and nothing is instrumented. */
    public abstract val enabled: Property<Boolean>

    /** Rewrites OkHttp's `RealCall`, so every OkHttp call is recorded. */
    public abstract val okhttp: Property<Boolean>

    /** Rewrites every `java.net.URL` connection call site, so every `HttpURLConnection` is recorded. */
    public abstract val urlConnection: Property<Boolean>

    /**
     * Gives debuggable variants the real `hopi-ktor` and the others its noop twin, whichever of the
     * two the app's modules name, so no `-Phopi.enabled` is needed on Android. (`HopiRules` and
     * `HopiPerf` are in `hopi-api`, which has no twin: every variant gets it.)
     */
    public abstract val selectTwins: Property<Boolean>

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

    /**
     * Applies Hopi rules (mock, fail, delay, edit; docs/design/rules.md) to the requests the
     * interceptor and the wrappers record. False: they never read the rule state.
     */
    public abstract val rules: Property<Boolean>
    public abstract val redactedHeaders: ListProperty<String>
    public abstract val redactedQueryParameters: ListProperty<String>
    public abstract val redactionDisabled: Property<Boolean>

    /** Class name patterns AGP's instrumentation leaves alone (`com.vendor.pinned.**`). */
    public abstract val excludedClasses: ListProperty<String>

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

    /** Leaves classes matching these patterns uninstrumented. */
    public fun excludeClasses(vararg patterns: String) {
        excludedClasses.addAll(*patterns)
    }

    internal fun applyDefaults() {
        enabled.convention(true)
        okhttp.convention(true)
        urlConnection.convention(true)
        selectTwins.convention(true)
        maxEntries.convention(500)
        maxTotalBytes.convention(16L * 1024 * 1024)
        maxBodyBytes.convention(256L * 1024)
        summaryLog.convention(true)
        agentBridge.convention(false)
        rules.convention(true)
        redactionDisabled.convention(false)
        perf.applyDefaults()
    }
}
