package dev.koga.hopi.core.start

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.capture.CaptureInstallation
import dev.koga.hopi.core.concurrent.startBackgroundThread
import dev.koga.hopi.core.log.emitPlatformLogLine
import dev.koga.hopi.core.log.noticeLine
import dev.koga.hopi.core.store.DefaultStoreCaps
import dev.koga.hopi.core.store.HopiStore
import kotlin.concurrent.atomics.AtomicReference

/**
 * The global start configuration, what every tool shares: the store's caps, redaction, the summary
 * log and the agent bridge. A tool's own settings are its own (the network tool's `rules`, perf's
 * monitors). On Android the plugin generates it from the `hopi { }` block.
 */
@HopiInternalApi
public data class StartConfig(
    /** False: [HopiStart.start] does nothing and writes nothing. */
    val enabled: Boolean = true,
    val maxEntries: Int = HopiStore.DEFAULT_MAX_ENTRIES,
    val maxTotalBytes: Long = HopiStore.DEFAULT_MAX_TOTAL_BYTES,
    /** Each recorder's per-body cap, clamped to half of the store's [maxTotalBytes]. */
    val maxBodyBytes: Long = 256L * 1024,
    val redactedHeaders: List<String> = emptyList(),
    val redactedQueryParameters: List<String> = emptyList(),
    val redactionDisabled: Boolean = false,
    /** The NDJSON line per transaction, and the notice that a later start was ignored. */
    val summaryLog: Boolean = true,
    /** Starts the device-local agent bridge at start, before any request. */
    val agentBridge: Boolean = false,
)

/** A tool [HopiStart.start] started: its name, the note it returned, and its start's duration on the caller's thread. */
@HopiInternalApi
public class ToolStart(public val name: String, public val note: String?, public val nanos: Long)

/**
 * The one start sequence of a debug build (docs/ARCHITECTURE.md, "Start sequence"): the global
 * configuration and a list of tools in, each tool started in list order. On Android the provider
 * runs it with the plugin's generated tool list, on the main thread before `Application.onCreate`;
 * on iOS `hopi-start`'s `Hopi.start`, with the network tool and perf's; JVM tests call it
 * directly. Once per process: the first valid call wins.
 */
@HopiInternalApi
public object HopiStart {
    private val started = AtomicReference<StartConfig?>(null)

    /** The configuration of the call that started, or null while none did. */
    public val config: StartConfig? get() = started.load()

    /**
     * Starts [tools] with [config]; returns each started tool, or null when nothing started.
     *
     * `enabled = false` does nothing and writes nothing. Invalid caps write one notice line whatever
     * `summaryLog` says and start nothing, without using up the first call (iOS's rule: a Swift
     * caller cannot catch). A later call starts nothing, and writes one notice line (with its
     * `summaryLog`) when its configuration differs from the first one. The first valid call presets
     * the default store's caps without creating the store, starts the installation on a background
     * thread with `agentBridge`, then starts each tool in list order on this thread, timed. A tool
     * that throws writes one notice line and the next one still starts: nothing throws to the
     * caller. [platform] reaches every tool: the Android `Application`, null on the JVM and iOS.
     */
    public fun start(config: StartConfig, tools: List<HopiTool>, platform: Any? = null): List<ToolStart>? =
        start(config, tools, platform, null)

    /**
     * [start], each notice line going to [emit] (tests read them), or to the platform log when it
     * is null: no function value on Android's main thread, whose class and Kotlin's
     * function-reference classes would load there.
     */
    internal fun start(config: StartConfig, tools: List<HopiTool>, platform: Any?, emit: ((String) -> Unit)?): List<ToolStart>? {
        // The runtime-split plan's stage 7 registers the rules runtime here, before anything else.
        if (!config.enabled) return null
        val problem = CaptureInstallation.invalidCaps(config.maxEntries, config.maxTotalBytes, config.maxBodyBytes)
        if (problem != null) {
            write(emit, noticeLine("$NOT_STARTED_NOTICE$problem"))
            return null
        }
        if (!started.compareAndSet(null, config)) {
            if (started.load() != config && config.summaryLog) write(emit, noticeLine(IGNORED_CONFIG_NOTICE))
            return null
        }
        // HopiStore.presetDefault's holder, not the store's companion: the store's class stays
        // unloaded on Android's main thread.
        DefaultStoreCaps.preset(config.maxEntries, config.maxTotalBytes)
        if (config.agentBridge) installNow(config, emit)
        val context = HopiToolContext(config, platform)
        // A plain loop and the JDK's list on Android's main thread: Kotlin's collection extensions
        // would load their classes there.
        val starts = ArrayList<ToolStart>(tools.size)
        for (tool in tools) {
            val begin = monotonicNanos()
            val note = try {
                tool.start(context)
            } catch (e: Throwable) {
                write(emit, noticeLine("${tool.name} not started: ${describe(e)}"))
                continue
            }
            starts.add(ToolStart(tool.name, note, monotonicNanos() - begin))
        }
        return starts
    }

    /**
     * The installation the first recorded request would make (the store with the preset caps, the
     * redaction, the summary sink with `summaryLog`, the bridge), made at once on a thread of its
     * own, so the CLI and the desktop viewer can connect before any request. A recorder's own
     * installation later finds each part done: each runs once per process.
     */
    private fun installNow(config: StartConfig, emit: ((String) -> Unit)?) {
        try {
            startBackgroundThread(START_THREAD) {
                CaptureInstallation.install(
                    maxEntries = config.maxEntries,
                    maxTotalBytes = config.maxTotalBytes,
                    maxBodyBytes = config.maxBodyBytes,
                    redactedHeaders = config.redactedHeaders,
                    redactedQueryParameters = config.redactedQueryParameters,
                    redactionDisabled = config.redactionDisabled,
                    summaryLog = config.summaryLog,
                    agentBridge = true,
                )
            }
        } catch (e: Throwable) {
            // A thread the platform refuses must not reach the app; the first request installs instead.
            write(emit, noticeLine("agent bridge not started: ${describe(e)}"))
        }
    }

    /** One notice line to [emit], or to the platform log without one. */
    private fun write(emit: ((String) -> Unit)?, line: String) {
        if (emit == null) emitPlatformLogLine(line) else emit(line)
    }

    /** `"<Class>: <message>"`, as the recorders write an error. */
    private fun describe(error: Throwable): String {
        val name = error::class.simpleName ?: "Throwable"
        val message = error.message
        return if (message.isNullOrEmpty()) name else "$name: $message"
    }

    /** Forgets the first call and the default store's preset caps (not a store already created), so a test can start again. */
    public fun resetForTests() {
        started.store(null)
        DefaultStoreCaps.clear()
    }

    private const val START_THREAD = "hopi-start"
    private const val NOT_STARTED_NOTICE = "not started, invalid configuration: "
    private const val IGNORED_CONFIG_NOTICE = "already started; this call's configuration is ignored"
}

/** The platform's monotonic clock in nanoseconds, which times each tool's start. */
internal expect fun monotonicNanos(): Long
