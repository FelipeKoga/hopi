package dev.koga.hopi.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * `hopi { network { } }`: the settings of the network capture, which the app gets by declaring
 * `debugImplementation("io.github.felipekoga.hopi:hopi-network:<v>")` (docs/design/android-capture.md).
 */
public abstract class HopiNetworkExtension {
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
        okhttp.convention(true)
        urlConnection.convention(true)
        rules.convention(true)
    }
}
