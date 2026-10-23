package dev.koga.hopi.cli

import java.io.BufferedWriter
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What a finished subprocess produced. */
internal class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Runs a subprocess from an argument array, never through a shell. Injected so the adb and simctl
 * flows are tested from fixtures without a device.
 */
internal fun interface ProcessRunner {
    /** Throws [IOException] when the program cannot be started or does not finish in time. */
    fun run(command: List<String>): ProcessResult
}

internal object SystemProcessRunner : ProcessRunner {
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

/** JVM shutdown hooks, so a SIGINT during `tail` still removes the adb forward. */
internal interface ShutdownHooks {
    fun add(hook: Thread)

    fun remove(hook: Thread)
}

internal object RuntimeShutdownHooks : ShutdownHooks {
    override fun add(hook: Thread) = Runtime.getRuntime().addShutdownHook(hook)

    override fun remove(hook: Thread) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook)
        } catch (_: IllegalStateException) {
            // The JVM is already shutting down and runs the hook itself.
        }
    }
}

/** Everything the CLI touches outside its own memory. */
internal class CliEnvironment(
    val stdout: OutputStream,
    val stderr: OutputStream,
    val processes: ProcessRunner = SystemProcessRunner,
    val env: Map<String, String> = System.getenv(),
    val userHome: String = System.getProperty("user.home") ?: "",
    val isMac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac"),
    val isExecutable: (Path) -> Boolean = { Files.isRegularFile(it) && Files.isExecutable(it) },
    val shutdownHooks: ShutdownHooks = RuntimeShutdownHooks,
    /** Socket read timeout for one-shot commands; `tail` waits without one. */
    val readTimeoutMs: Int = 15_000,
) {
    companion object {
        /**
         * The process's real stdout and stderr as raw streams: `System.out` is a `PrintStream`, which
         * swallows a broken pipe instead of reporting it.
         */
        fun system(): CliEnvironment = CliEnvironment(
            stdout = FileOutputStream(FileDescriptor.out),
            stderr = FileOutputStream(FileDescriptor.err),
        )
    }
}

/** Thrown when stdout is gone (`hopi tail | head -1`): the CLI then exits quietly. */
internal class StdoutClosedException(cause: IOException) : Exception(cause)

/** Buffered UTF-8 line output. A write error means the reader went away. */
internal class LineOutput(stream: OutputStream) {
    private val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 64 * 1024)

    fun line(text: String) {
        try {
            writer.write(text)
            writer.write('\n'.code)
        } catch (e: IOException) {
            throw StdoutClosedException(e)
        }
    }

    fun flush() {
        try {
            writer.flush()
        } catch (e: IOException) {
            throw StdoutClosedException(e)
        }
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
