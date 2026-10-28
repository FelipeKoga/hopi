package dev.koga.hopi.android.internal

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.start.HopiTool
import dev.koga.hopi.core.start.HopiToolContext

/**
 * Automatic OkHttp and `HttpURLConnection` capture as a tool of the start sequence: the Gradle
 * plugin's generated configuration lists it with its own settings ([okhttp] and [urlConnection],
 * the rewrites the plugin made; [rules]), the global ones come from the start configuration. On the
 * main thread before `Application.onCreate` it looks OkHttp's `RealCall` up by name without
 * initializing it and sets the started flag ([AndroidCapture.start]); the store is installed by
 * the first recorded request.
 */
@HopiInternalApi
public class NetworkTool(
    private val okhttp: Boolean,
    /** The plugin rewrote the `URL` calls of Ktor's Android engine. */
    private val urlConnection: Boolean,
    private val rules: Boolean,
) : HopiTool {
    override val name: String get() = "network"

    /** `okhttp instrumented` (or `absent`, `notinstrumented`, `unsupported`) once capture started; null otherwise. */
    override fun start(context: HopiToolContext): String? {
        val okHttp = okHttpState(javaClass.classLoader)
        val config = context.config
        val settings = AndroidCapture.Settings(
            maxEntries = config.maxEntries,
            maxTotalBytes = config.maxTotalBytes,
            maxBodyBytes = config.maxBodyBytes,
            redactedHeaders = config.redactedHeaders,
            redactedQueryParameters = config.redactedQueryParameters,
            redactionDisabled = config.redactionDisabled,
            summaryLog = config.summaryLog,
            agentBridge = config.agentBridge,
            rules = rules,
        )
        if (!AndroidCapture.start(settings, okHttp, okHttpWanted = okhttp, urlConnection = urlConnection)) return null
        return "okhttp ${okHttp.name.lowercase()}"
    }

    @HopiInternalApi
    public companion object {
        /**
         * Whether this APK's OkHttp `RealCall` was rewritten, read without initializing any OkHttp
         * class. A rewritten 3.x `RealCall` counts only from 3.14 on, the first version with
         * `RequestBody.isDuplex()`, which the interceptor calls: one reflective lookup, made only
         * for 3.x.
         */
        public fun okHttpState(loader: ClassLoader?): AndroidCapture.OkHttp {
            val realCall = find(REAL_CALL, loader) ?: find(LEGACY_REAL_CALL, loader) ?: return AndroidCapture.OkHttp.Absent
            if (!realCall.isAnnotationPresent(HopiInstrumented::class.java)) return AndroidCapture.OkHttp.NotInstrumented
            if (realCall.name == LEGACY_REAL_CALL && !linksAgainst(loader)) return AndroidCapture.OkHttp.Unsupported
            return AndroidCapture.OkHttp.Instrumented
        }

        /**
         * [name]'s class, not initialized, or null. No Kotlin collection here: this runs on the main
         * thread at startup, and its classes would load there.
         */
        private fun find(name: String, loader: ClassLoader?): Class<*>? = try {
            Class.forName(name, false, loader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
        }

        private fun linksAgainst(loader: ClassLoader?): Boolean = try {
            Class.forName("okhttp3.RequestBody", false, loader).getMethod("isDuplex")
            true
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: LinkageError) {
            false
        }

        private const val REAL_CALL = "okhttp3.internal.connection.RealCall"
        private const val LEGACY_REAL_CALL = "okhttp3.RealCall"
    }
}
