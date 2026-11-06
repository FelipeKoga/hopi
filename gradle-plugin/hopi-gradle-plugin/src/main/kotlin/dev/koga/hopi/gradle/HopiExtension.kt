package dev.koga.hopi.gradle

import org.gradle.api.Action
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Nested

/**
 * The optional `hopi { }` block of an Android application module or a Kotlin Multiplatform module.
 * Every value has a default, so `plugins { id("io.github.felipekoga.hopi") }` alone is the whole setup. The
 * store, redaction, log and bridge settings at the top are the start's, shared by every tool, and
 * mean what they mean for `Hopi.start` on iOS and the JVM and for the Ktor plugin, whose every
 * client's installation makes the start's first; capture's own settings are in [network], the
 * monitors' in [perf].
 *
 * In a KMP module the generated `HopiSetup` passes the top settings, `network { rules }`,
 * `perf { enabled }` (so iOS registers the monitors only with `perf { enabled = true }`, as Android
 * does) and `perf { remoteControl }` to `Hopi.start`; the others are Android's (iOS always captures
 * URLSession, and starts monitors at launch with `-hopiPerf`).
 */
public abstract class HopiExtension {
    /**
     * False: no variant gets a runtime, a transform or a generated class, and in a KMP module no
     * build gets `hopi-start` and `HopiSetup.start()` does nothing. The release guards still run on
     * every non-debuggable variant and every iOS Release link: they refuse a runtime whatever this
     * says.
     */
    public abstract val enabled: Property<Boolean>

    /** Set by the plugin in a KMP module: this Gradle invocation is an iOS Debug build (`IosBuild`). */
    internal var iosDebug: Boolean = false

    /**
     * Read-only, for a KMP module's own build logic (a debug-only source set, a build label): true
     * when this Gradle invocation gives the module's iOS compilations `hopi-start` and the generated
     * `HopiSetup.start()` starts Hopi, which is a Debug build (`-Phopi.enabled=true`, or without the
     * property Xcode's Debug configuration) with [enabled]; false in any other build, and in an
     * Android application module.
     */
    public val iosRuntime: Provider<Boolean> get() = enabled.map { it && iosDebug }

    public abstract val maxEntries: Property<Int>
    public abstract val maxTotalBytes: Property<Long>
    public abstract val maxBodyBytes: Property<Long>

    /**
     * One NDJSON line per recorded request in logcat (tag `Hopi`), the start's own line, and the
     * performance monitors' rate-limited lines (tag `HopiPerf`, whose notices are written whatever
     * this says).
     */
    public abstract val summaryLog: Property<Boolean>

    /**
     * The device-local bridge the CLI and the desktop viewer connect to, in debug builds only; on
     * by default (it authenticates every connection, and a release build has no runtime to serve it).
     */
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
        agentBridge.convention(true)
        redactionDisabled.convention(false)
        network.applyDefaults()
        perf.applyDefaults()
    }
}
