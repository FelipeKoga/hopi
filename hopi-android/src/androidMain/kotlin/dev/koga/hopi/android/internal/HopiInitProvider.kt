package dev.koga.hopi.android.internal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.log.emitPlatformLogLine

/**
 * Starts automatic capture with no code in the app: the manifest of the real runtime declares it,
 * and only debuggable variants depend on that runtime. It runs on the main thread before
 * `Application.onCreate`, so it only reads the generated configuration and looks OkHttp's
 * `RealCall` up by name without initializing it; the store is installed by the first request.
 */
@HopiInternalApi
public class HopiInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val begin = SystemClock.elapsedRealtimeNanos()
        val loader = javaClass.classLoader
        val config = GeneratedConfig.read(loader)
        val configured = SystemClock.elapsedRealtimeNanos()
        val okHttp = okHttpState(loader)
        val looked = SystemClock.elapsedRealtimeNanos()
        val started = AndroidCapture.start(config.settings, okHttp, okHttpWanted = config.okhttp, urlConnection = config.urlConnection)
        val end = SystemClock.elapsedRealtimeNanos()
        if (started && config.settings.summaryLog) {
            // Its own cost on the main thread, in parts, so a device run can see where it goes.
            val parts = "config ${(configured - begin) / 1_000} us, okhttp lookup ${(looked - configured) / 1_000} us, start ${(end - looked) / 1_000} us"
            emitPlatformLogLine(AndroidCapture.notice("android capture started (okhttp ${okHttp.name.lowercase()}, ${(end - begin) / 1_000} us; $parts)"))
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    @HopiInternalApi
    public companion object {
        /**
         * Whether this APK's OkHttp `RealCall` was rewritten, read without initializing any OkHttp
         * class. A rewritten 3.x `RealCall` counts only from 3.14 on, the first version with
         * `RequestBody.isDuplex()`, which the interceptor calls: one reflective lookup, made only
         * for 3.x.
         */
        public fun okHttpState(loader: ClassLoader?): AndroidCapture.OkHttp {
            val realCall = REAL_CALL_NAMES.firstNotNullOfOrNull { name ->
                try {
                    Class.forName(name, false, loader)
                } catch (_: ClassNotFoundException) {
                    null
                } catch (_: LinkageError) {
                    null
                }
            } ?: return AndroidCapture.OkHttp.Absent
            if (!realCall.isAnnotationPresent(HopiInstrumented::class.java)) return AndroidCapture.OkHttp.NotInstrumented
            if (realCall.name == LEGACY_REAL_CALL && !linksAgainst(loader)) return AndroidCapture.OkHttp.Unsupported
            return AndroidCapture.OkHttp.Instrumented
        }

        private fun linksAgainst(loader: ClassLoader?): Boolean = try {
            Class.forName("okhttp3.RequestBody", false, loader).getMethod("isDuplex")
            true
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: LinkageError) {
            false
        }

        private const val LEGACY_REAL_CALL = "okhttp3.RealCall"
        private val REAL_CALL_NAMES = listOf("okhttp3.internal.connection.RealCall", LEGACY_REAL_CALL)
    }
}

/**
 * The `hopi { }` block of the app's build. The plugin generates
 * `dev.koga.hopi.android.generated.HopiGeneratedConfig`, a subclass whose no-argument constructor
 * passes the block's values here, so reading it is one class lookup and one constructor call, no
 * field reflection and no resources. Only debuggable variants get it, and only they depend on this
 * module.
 */
@HopiInternalApi
public abstract class HopiConfigValues(
    public val okhttp: Boolean,
    public val urlConnection: Boolean,
    maxEntries: Int,
    maxTotalBytes: Long,
    maxBodyBytes: Long,
    summaryLog: Boolean,
    agentBridge: Boolean,
    redactionDisabled: Boolean,
    redactHeaders: Array<String>,
    redactQueryParameters: Array<String>,
    rules: Boolean,
) {
    public val settings: AndroidCapture.Settings = AndroidCapture.Settings(
        maxEntries = maxEntries,
        maxTotalBytes = maxTotalBytes,
        maxBodyBytes = maxBodyBytes,
        // java.util.Arrays, not Kotlin's array extensions: this runs on the main thread at startup,
        // and the JDK classes are already loaded.
        redactedHeaders = java.util.Arrays.asList(*redactHeaders),
        redactedQueryParameters = java.util.Arrays.asList(*redactQueryParameters),
        redactionDisabled = redactionDisabled,
        summaryLog = summaryLog,
        agentBridge = agentBridge,
        rules = rules,
    )
}

/** The generated configuration, or the defaults when it is absent (the runtime added without the plugin). */
@HopiInternalApi
public class GeneratedConfig(
    public val okhttp: Boolean,
    /** The plugin rewrote the `URL` calls of Ktor's Android engine (false without the plugin: nothing was rewritten). */
    public val urlConnection: Boolean,
    public val settings: AndroidCapture.Settings,
) {
    @HopiInternalApi
    public companion object {
        public const val CLASS_NAME: String = "dev.koga.hopi.android.generated.HopiGeneratedConfig"

        public fun read(loader: ClassLoader?): GeneratedConfig {
            val values = try {
                Class.forName(CLASS_NAME, true, loader).getDeclaredConstructor().newInstance() as? HopiConfigValues
            } catch (_: ReflectiveOperationException) {
                null
            } catch (_: LinkageError) {
                null
            }
            return if (values == null) {
                GeneratedConfig(okhttp = true, urlConnection = false, AndroidCapture.Settings())
            } else {
                GeneratedConfig(values.okhttp, values.urlConnection, values.settings)
            }
        }
    }
}
