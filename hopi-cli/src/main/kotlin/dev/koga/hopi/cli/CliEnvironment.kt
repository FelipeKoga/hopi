package dev.koga.hopi.cli

import dev.koga.hopi.connect.BridgeEndpoint
import dev.koga.hopi.connect.HostEnvironment
import dev.koga.hopi.connect.ProcessRunner
import dev.koga.hopi.connect.RuntimeShutdownHooks
import dev.koga.hopi.connect.ShutdownHooks
import dev.koga.hopi.connect.SystemProcessRunner
import dev.koga.hopi.connect.Targets
import java.io.BufferedWriter
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path

/** Everything the CLI touches outside its own memory: its streams, plus the [host] targets are reached through. */
internal class CliEnvironment(
    val stdout: OutputStream,
    val stderr: OutputStream,
    processes: ProcessRunner = SystemProcessRunner,
    env: Map<String, String> = System.getenv(),
    userHome: String = System.getProperty("user.home") ?: "",
    isMac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac"),
    isExecutable: (Path) -> Boolean = { Files.isRegularFile(it) && Files.isExecutable(it) },
    shutdownHooks: ShutdownHooks = RuntimeShutdownHooks,
    /** Socket read timeout for one-shot commands; `tail` waits without one. */
    val readTimeoutMs: Int = 15_000,
    /** What `--body @-` reads. */
    val stdin: InputStream = InputStream.nullInputStream(),
) {
    val host: HostEnvironment = HostEnvironment(processes, env, userHome, isMac, isExecutable, shutdownHooks)

    companion object {
        /**
         * The process's real stdout and stderr as raw streams: `System.out` is a `PrintStream`, which
         * swallows a broken pipe instead of reporting it.
         */
        fun system(): CliEnvironment = CliEnvironment(
            stdout = FileOutputStream(FileDescriptor.out),
            stderr = FileOutputStream(FileDescriptor.err),
            stdin = FileInputStream(FileDescriptor.`in`),
        )
    }
}

/** The targets reachable from the CLI's [environment]. */
internal fun Targets(environment: CliEnvironment): Targets = Targets(environment.host)

/** The bridge endpoint the command line names. */
internal fun Targets.open(args: CliArgs): BridgeEndpoint = open(args.platform, args.app, args.device, args.bridgeFile)

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
