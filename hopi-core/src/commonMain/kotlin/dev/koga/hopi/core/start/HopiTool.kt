package dev.koga.hopi.core.start

import dev.koga.hopi.api.HopiInternalApi

/**
 * One tool of the start sequence ([HopiStart]): network capture, the performance monitors. Its own
 * settings are its constructor's (on Android the plugin's generated configuration calls it), the
 * global ones come in [HopiToolContext.config].
 */
@HopiInternalApi
public interface HopiTool {
    /** The tool's word in the start line ("network", "perf"). */
    public val name: String

    /**
     * Starts the tool on the thread that runs the start sequence (Android: the main thread, before
     * `Application.onCreate`), so it only sets state and hands work off. Returns a short note for
     * the start line (the network tool: "okhttp instrumented"), or null.
     */
    public fun start(context: HopiToolContext): String?
}

/**
 * What every tool gets at start: the global [config] and the [platform] object, the Android
 * `Application` (an Android tool casts it, null-safe), null on the JVM and iOS. Common code names
 * no Android type, as `HopiRuntimes.ktorHooks` names no Ktor type.
 */
@HopiInternalApi
public class HopiToolContext(public val config: StartConfig, public val platform: Any?)
