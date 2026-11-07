package dev.koga.hopi.connect

import dev.koga.hopi.api.HopiInternalApi
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What a finished subprocess produced. */
@HopiInternalApi
public class ProcessResult(public val exitCode: Int, public val stdout: String, public val stderr: String)

/**
 * Runs a subprocess from an argument array, never through a shell. Injected so the adb and simctl
 * flows are tested from fixtures without a device.
 */
@HopiInternalApi
public fun interface ProcessRunner {
    /** Throws [IOException] when the program cannot be started or does not finish in time. */
    public fun run(command: List<String>): ProcessResult
}

@HopiInternalApi
public object SystemProcessRunner : ProcessRunner {
    private const val TIMEOUT_SECONDS = 30L

    override fun run(command: List<String>): ProcessResult {
        val process = ProcessBuilder(command).start()
        process.outputStream.close()
        var stderr = ""
        val stderrReader = thread(isDaemon = true, name = "hopi-cli-stderr") {
            stderr = process.errorStream.readText()
        }
        var stdout = ""
        val stdoutReader = thread(isDaemon = true, name = "hopi-cli-stdout") {
            stdout = process.inputStream.readText()
        }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("${command.first()} did not finish within $TIMEOUT_SECONDS s")
        }
        stdoutReader.join()
        stderrReader.join()
        return ProcessResult(process.exitValue(), stdout, stderr)
    }

    private fun InputStream.readText(): String = use { it.readBytes().decodeToString() }
}

/**
 * A subprocess that runs until closed (`adb track-devices`): [input] is its stdout, read by one
 * thread blocked on it. [close] ends the process, which ends [input].
 */
@HopiInternalApi
public interface StreamingProcess : AutoCloseable {
    public val input: InputStream
}

/** Starts a [StreamingProcess] from an argument array, never through a shell. Injected for tests. */
@HopiInternalApi
public fun interface StreamStarter {
    /** Throws [IOException] when the program cannot be started. */
    public fun start(command: List<String>): StreamingProcess
}

@HopiInternalApi
public object SystemStreamStarter : StreamStarter {
    override fun start(command: List<String>): StreamingProcess {
        val process = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.outputStream.close()
        return object : StreamingProcess {
            override val input: InputStream = process.inputStream

            override fun close() {
                process.destroy()
                input.close()
            }
        }
    }
}

/** JVM shutdown hooks, so a SIGINT during `tail` still removes the adb forward. */
@HopiInternalApi
public interface ShutdownHooks {
    public fun add(hook: Thread)

    public fun remove(hook: Thread)
}

@HopiInternalApi
public object RuntimeShutdownHooks : ShutdownHooks {
    override fun add(hook: Thread): Unit = Runtime.getRuntime().addShutdownHook(hook)

    override fun remove(hook: Thread) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook)
        } catch (_: IllegalStateException) {
            // The JVM is already shutting down and runs the hook itself.
        }
    }
}

/** Everything reaching a target touches outside the process's own memory: subprocesses, env, files. */
@HopiInternalApi
public class HostEnvironment(
    public val processes: ProcessRunner = SystemProcessRunner,
    public val env: Map<String, String> = System.getenv(),
    public val userHome: String = System.getProperty("user.home") ?: "",
    public val isMac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac"),
    public val isExecutable: (Path) -> Boolean = { Files.isRegularFile(it) && Files.isExecutable(it) },
    public val shutdownHooks: ShutdownHooks = RuntimeShutdownHooks,
    /** Long-running subprocesses (the desktop viewer's `adb track-devices`); the CLI starts none. */
    public val streams: StreamStarter = SystemStreamStarter,
    /** usbmuxd's socket, through which iPhones on USB are listed and reached (macOS with Xcode only). */
    public val usbmuxdSocket: Path = USBMUXD_SOCKET,
    /** Where JVM processes on this host write `<pid>/bridge.json` (the JVM bridge's default directory). */
    public val jvmBridgeRoot: Path = Paths.get(System.getProperty("java.io.tmpdir") ?: "/tmp", "hopi"),
) {
    public companion object {
        /** Where macOS's usbmuxd (launchd starts it on demand) listens. */
        public val USBMUXD_SOCKET: Path = Paths.get("/var/run/usbmuxd")
    }
}

internal fun pathEntries(env: Map<String, String>): List<Path> =
    env["PATH"].orEmpty().split(java.io.File.pathSeparatorChar).filter { it.isNotEmpty() }.mapNotNull {
        try {
            Paths.get(it)
        } catch (_: java.nio.file.InvalidPathException) {
            null
        }
    }
