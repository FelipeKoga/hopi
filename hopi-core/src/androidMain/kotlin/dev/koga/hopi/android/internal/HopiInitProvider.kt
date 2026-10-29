package dev.koga.hopi.android.internal

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.log.emitPlatformLogLine
import dev.koga.hopi.core.log.noticeLine
import dev.koga.hopi.core.perf.CurrentActivityCallbacks
import dev.koga.hopi.core.start.HopiStart
import dev.koga.hopi.core.start.HopiTool
import dev.koga.hopi.core.start.StartConfig
import dev.koga.hopi.core.start.ToolStart

/**
 * Starts Hopi with no code in the app: `hopi-core`'s manifest declares it, and the `dev.koga.hopi`
 * Gradle plugin adds `hopi-core`, with the runtimes the `hopi { }` block enables, to debuggable
 * build types only (a release build that resolves one fails the plugin's release guard). It runs on the
 * main thread before `Application.onCreate`, so it starts nothing in an app that is not debuggable,
 * reads the generated configuration with one class lookup, and hands the generated tool list to the
 * start sequence ([HopiStart]), whose tools only set state; the store is installed by the first
 * recorded request, or at start on a background thread with `agentBridge`.
 */
@HopiInternalApi
public class HopiInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val context = context
        // First the run-time half of the release guard: only the flags are read before it.
        if (!debuggable(context?.applicationInfo?.flags)) {
            emitPlatformLogLine(notDebuggableNotice(context?.packageName))
            return true
        }
        start(javaClass.classLoader, context?.applicationContext as? Application)
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
         * Whether the app's `ApplicationInfo.flags` say it is debuggable; false without them, so a
         * provider that cannot tell starts nothing.
         */
        public fun debuggable(flags: Int?): Boolean = flags != null && flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        /**
         * The one line an app that is not debuggable gets, whatever its configuration says (it is
         * not read), and nothing else: the run-time half of the release guard. [packageName] is
         * the app's, "the app" without one.
         */
        public fun notDebuggableNotice(packageName: String?): String =
            noticeLine("not started: ${packageName ?: "the app"} is not debuggable (FLAG_DEBUGGABLE unset); a Hopi runtime reached a non-debuggable build, check the app's dependencies")

        /**
         * What [onCreate] does once the app is debuggable, from its [loader] and [application],
         * each line going to [emit] (tests read them), or to the platform log when it is null, so
         * the provider creates no function value. Without the generated configuration (a Hopi
         * module linked without the plugin), one notice line and nothing started: the tool list
         * lives in that class, and nothing was rewritten anyway. Otherwise core's current-activity
         * callbacks, then the start sequence with the generated tools, then, with `summaryLog`, one
         * line with its own cost on the main thread, in parts, so a device run can see where it
         * goes. Returns what started.
         */
        public fun start(loader: ClassLoader?, application: Application?, emit: ((String) -> Unit)? = null): List<ToolStart>? {
            val begin = SystemClock.elapsedRealtimeNanos()
            val values = GeneratedConfig.read(loader)
            if (values == null) {
                write(emit, noticeLine("not started: no generated configuration; apply the dev.koga.hopi Gradle plugin to the app"))
                return null
            }
            val configured = SystemClock.elapsedRealtimeNanos()
            application?.let(CurrentActivityCallbacks::register)
            val started = HopiStart.start(values.config, values.tools, application, emit) ?: return null
            val end = SystemClock.elapsedRealtimeNanos()
            if (values.config.summaryLog) write(emit, noticeLine(startedLine(started, end - begin, configured - begin)))
            return started
        }

        private fun write(emit: ((String) -> Unit)?, line: String) {
            if (emit == null) emitPlatformLogLine(line) else emit(line)
        }

        /**
         * `android capture started (<notes>, <total> us; config <n> us, <tool> <n> us, …)`, the
         * tools' notes and parts in start order: the line that marks the end of the start
         * sequence, whatever tools ran (with `network { enabled = false }` it has no `network`
         * part, with no tool only `config`); the prefix stays for the device runs that read it. A
         * `StringBuilder` and plain loops: Kotlin's collection extensions would load their classes
         * on the main thread.
         */
        private fun startedLine(started: List<ToolStart>, totalNanos: Long, configNanos: Long): String {
            val line = StringBuilder("android capture started (")
            for (tool in started) {
                val note = tool.note ?: continue
                line.append(note).append(", ")
            }
            line.append(totalNanos / 1_000).append(" us; config ").append(configNanos / 1_000).append(" us")
            for (tool in started) line.append(", ").append(tool.name).append(' ').append(tool.nanos / 1_000).append(" us")
            return line.append(')').toString()
        }
    }
}

/**
 * The `hopi { }` block of the app's build. The plugin generates
 * `dev.koga.hopi.android.generated.HopiGeneratedConfig`, a Java subclass whose no-argument
 * constructor passes the block's global settings here, then the tool list: the tools the block
 * enables, possibly none, each constructed with its own settings (`new NetworkTool(…)` with
 * `network { enabled }`, `new PerfEntry(…)` with `perf { enabled = true }`), so reading it is one
 * class lookup and one constructor call, no field reflection and no resources. Every debuggable
 * variant gets it, and only they do.
 */
@HopiInternalApi
public abstract class HopiConfigValues(
    maxEntries: Int,
    maxTotalBytes: Long,
    maxBodyBytes: Long,
    summaryLog: Boolean,
    agentBridge: Boolean,
    redactionDisabled: Boolean,
    redactHeaders: Array<String>,
    redactQueryParameters: Array<String>,
    vararg tools: HopiTool,
) {
    public val config: StartConfig = StartConfig(
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
    )

    /** The tools to start, in the plugin's order. */
    public val tools: List<HopiTool> = java.util.Arrays.asList(*tools)
}

/** The generated configuration, read by its class name. */
@HopiInternalApi
public object GeneratedConfig {
    public const val CLASS_NAME: String = "dev.koga.hopi.android.generated.HopiGeneratedConfig"

    /** One `Class.forName` (initialized) and one constructor call; null when the app has no such class. */
    public fun read(loader: ClassLoader?): HopiConfigValues? = try {
        Class.forName(CLASS_NAME, true, loader).getDeclaredConstructor().newInstance() as? HopiConfigValues
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: LinkageError) {
        null
    }
}
