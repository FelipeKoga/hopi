package dev.koga.hopi.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * `hopi { network { } }`: automatic OkHttp and `HttpURLConnection` capture in the debuggable
 * variants (docs/design/android-capture.md), on by default. [enabled] false leaves `hopi-network`,
 * the bytecode transform and the network tool out of every variant; the start configuration is
 * still generated, so core's provider still starts the other tools (`perf { enabled = true }`), or
 * none, and `HopiRules` works for the Ktor plugin.
 */
public abstract class HopiNetworkExtension {
    /** False: no `hopi-network`, no transform and no network tool in the generated tool list. */
    public abstract val enabled: Property<Boolean>

    /** Rewrites OkHttp's `RealCall`, so every OkHttp call is recorded. */
    public abstract val okhttp: Property<Boolean>

    /** Rewrites every `java.net.URL` connection call site, so every `HttpURLConnection` is recorded. */
    public abstract val urlConnection: Property<Boolean>

    /**
     * Applies Hopi rules (mock, fail, delay, edit; docs/design/rules.md) to the requests the
     * interceptor and the wrappers record. False: they never read the rule state.
     */
    public abstract val rules: Property<Boolean>

    /** Class name patterns AGP's instrumentation leaves alone (`com.vendor.pinned.**`). */
    public abstract val excludedClasses: ListProperty<String>

    /** Leaves classes matching these patterns uninstrumented. */
    public fun excludeClasses(vararg patterns: String) {
        excludedClasses.addAll(*patterns)
    }

    internal fun applyDefaults() {
        enabled.convention(true)
        okhttp.convention(true)
        urlConnection.convention(true)
        rules.convention(true)
    }
}
