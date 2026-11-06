package dev.koga.hopi.android.internal

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.capture.FullBodies
import dev.koga.hopi.core.capture.CaptureInstallation
import dev.koga.hopi.core.capture.PlatformCapture
import dev.koga.hopi.core.log.emitPlatformLogLine
import dev.koga.hopi.core.log.noticeLine
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.store.HopiStore

/**
 * Process-wide state of automatic capture on Android: whether it runs (read by every hook, one
 * volatile read), its settings, and, from the first recorded request on, its store installation. Nothing here runs on the main thread except [start], from [NetworkTool] in the provider's start sequence.
 */
@HopiInternalApi
public object AndroidCapture {
    /** The start configuration plus [NetworkTool]'s `rules`, as the plugin generated them (the defaults are for tests). */
    public data class Settings(
        val maxEntries: Int = HopiStore.DEFAULT_MAX_ENTRIES,
        val maxTotalBytes: Long = HopiStore.DEFAULT_MAX_TOTAL_BYTES,
        val maxBodyBytes: Long = 256L * 1024,
        val redactedHeaders: List<String> = emptyList(),
        val redactedQueryParameters: List<String> = emptyList(),
        val redactionDisabled: Boolean = false,
        val summaryLog: Boolean = true,
        val agentBridge: Boolean = true,
        /** Whether the interceptor and the wrappers apply rules (docs/design/rules.md, "Configuration switch"). */
        val rules: Boolean = true,
    )

    /** What [NetworkTool] found of OkHttp in this APK. */
    public enum class OkHttp {
        /** No OkHttp class: nothing to cover. */
        Absent,

        /** `RealCall` carries [HopiInstrumented]: every call is recorded by the interceptor. */
        Instrumented,

        /** `RealCall` exists without the annotation: an unknown OkHttp, or `hopi { network { okhttp = false } }`. */
        NotInstrumented,

        /**
         * `RealCall` was rewritten, but this OkHttp is older than 3.14 (no `RequestBody.isDuplex()`),
         * which the interceptor does not link against: nothing joins its calls' chains.
         */
        Unsupported,
    }

    /** The package of Ktor's OkHttp engine, which sends through OkHttp. */
    public const val KTOR_OKHTTP_ENGINE_PACKAGE: String = "io.ktor.client.engine.okhttp."

    /** The package of Ktor's Android engine, which sends through `URL.openConnection()` in its own classes. */
    public const val KTOR_ANDROID_ENGINE_PACKAGE: String = "io.ktor.client.engine.android."

    /** True once [start] ran; every hook reads it first and does nothing else while it is false. */
    @Volatile
    @JvmField
    public var started: Boolean = false

    @Volatile
    @JvmField
    public var okHttpInstrumented: Boolean = false

    /**
     * The `rules` switch, read by the hooks only after [started] was read true: written once, before
     * [started]'s volatile write, so it needs no volatile read of its own.
     */
    @JvmField
    public var rules: Boolean = false

    @Volatile
    private var settings: Settings? = null

    @Volatile
    private var injectedStore: HopiStore? = null

    /** The store installation, created by the first recorded request. */
    /**
     * Recorders queue themselves to [store] ([HopiStore.submitStart], then [HopiStore.submitEnd]):
     * the store's consumer thread builds each record from the recorder's fields, applies it and
     * writes its log line in the same pass; the request thread only queues it
     * (docs/design/android-capture.md, "Threading"). A failure while building drops that record,
     * never reaching the app.
     */
    public class Target(public val store: HopiStore, private val baseMaxBodyBytes: Int) {
        /** The cap for a capture that starts now: the installation's, or full bodies' while a viewer holds them. */
        public val maxBodyBytes: Int get() = FullBodies.bodyCap(baseMaxBodyBytes)
    }

    private val installed: Target by lazy {
        val s = checkNotNull(settings) { "capture is not started" }
        val installation = CaptureInstallation.install(
            maxEntries = s.maxEntries,
            maxTotalBytes = s.maxTotalBytes,
            maxBodyBytes = s.maxBodyBytes,
            redactedHeaders = s.redactedHeaders,
            redactedQueryParameters = s.redactedQueryParameters,
            redactionDisabled = s.redactionDisabled,
            summaryLog = s.summaryLog,
            agentBridge = s.agentBridge,
            injectedStore = injectedStore,
        )
        Target(installation.store, installation.maxBodyBytes)
    }

    /** The installation, made once, on the thread of the first recorded request. */
    public fun target(): Target = installed

    /**
     * Starts capture once per process; a later call changes nothing. Invalid caps write a notice
     * line and start nothing. Covers Ktor's OkHttp engine only when [okHttp] proves the rewrite is in
     * the APK, and its Android engine only when [urlConnection] says the plugin rewrote that engine's
     * `URL` calls (the generated configuration), so the Ktor plugin can never defer to capture that is
     * not there. Both honor the plugin's pass-through marker. [injectedStore] is for tests.
     */
    public fun start(
        settings: Settings,
        okHttp: OkHttp,
        okHttpWanted: Boolean = true,
        urlConnection: Boolean = false,
        injectedStore: HopiStore? = null,
    ): Boolean {
        val problem = CaptureInstallation.invalidCaps(settings.maxEntries, settings.maxTotalBytes, settings.maxBodyBytes)
        if (problem != null) {
            if (settings.summaryLog) emitPlatformLogLine(notice("android capture not started: $problem"))
            return false
        }
        synchronized(this) {
            if (this.settings != null) return started
            this.settings = settings
            this.injectedStore = injectedStore
            if (okHttp == OkHttp.Instrumented) {
                okHttpInstrumented = true
                PlatformCapture.coverWithPassThrough(KTOR_OKHTTP_ENGINE_PACKAGE)
            }
            if (urlConnection) PlatformCapture.coverWithPassThrough(KTOR_ANDROID_ENGINE_PACKAGE)
            rules = settings.rules
            RuleEngine.recorderStarted(settings.rules)
            started = true
        }
        if (okHttpWanted && settings.summaryLog) {
            when (okHttp) {
                OkHttp.NotInstrumented -> emitPlatformLogLine(notice("okhttp: not instrumented (unknown OkHttp version), Ktor's OkHttp engine stays with the plugin"))
                OkHttp.Unsupported -> emitPlatformLogLine(notice("okhttp: not recorded (OkHttp before 3.14), Ktor's OkHttp engine stays with the plugin"))
                else -> Unit
            }
        }
        return true
    }

    /** A notice line of the summary log (no `id`, so agents skip it): core's [noticeLine]. */
    public fun notice(text: String): String = noticeLine(text)

    /** `"<Class>: <message>"`, the Ktor plugin's error text. */
    public fun describe(error: Throwable): String {
        val name = error::class.java.simpleName.ifEmpty { "Throwable" }
        val message = error.message
        return if (message.isNullOrEmpty()) name else "$name: $message"
    }

    /** Whether a response to [method] with status [code] has a body at all. */
    public fun hasBody(method: String, code: Int): Boolean =
        !method.equals("HEAD", ignoreCase = true) && code !in 100..199 && code != 204 && code != 304
}
