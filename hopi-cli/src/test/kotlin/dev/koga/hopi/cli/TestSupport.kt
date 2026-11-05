package dev.koga.hopi.cli

import dev.koga.hopi.connect.BridgeClient
import dev.koga.hopi.connect.ProcessResult
import dev.koga.hopi.connect.ProcessRunner
import dev.koga.hopi.connect.ShutdownHooks
import dev.koga.hopi.core.bridge.BridgeConnection
import dev.koga.hopi.core.bridge.BridgeListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections

internal fun fixture(name: String): String =
    checkNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

/** No usbmuxd: this Mac's real one at /var/run/usbmuxd must not answer a test. */
internal val NO_USBMUXD: Path = Paths.get("/hopi-test/no-usbmuxd")

internal class CliRun(val exitCode: Int, val stdout: String, val stderr: String) {
    val lines: List<String> get() = stdout.lines().filter { it.isNotEmpty() }

    override fun toString(): String = "exit=$exitCode\nstdout=$stdout\nstderr=$stderr"
}

/** Runs the CLI in-process with captured streams. */
internal fun runCapturing(
    vararg argv: String,
    processes: ProcessRunner = FakeProcessRunner(),
    hooks: ShutdownHooks = RecordingShutdownHooks(),
    env: Map<String, String> = emptyMap(),
    userHome: String = "/home/test",
    isMac: Boolean = false,
    executables: Set<Path> = emptySet(),
    usbmuxdSocket: Path = NO_USBMUXD,
): CliRun {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val environment = CliEnvironment(
        stdout = stdout,
        stderr = stderr,
        processes = processes,
        env = env,
        userHome = userHome,
        isMac = isMac,
        isExecutable = { it in executables },
        shutdownHooks = hooks,
        readTimeoutMs = 10_000,
        usbmuxdSocket = usbmuxdSocket,
    )
    val code = runCli(argv.toList(), environment)
    return CliRun(code, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
}

/** Answers subprocesses by their arguments (the program path dropped) and records every call. */
internal class FakeProcessRunner(
    private val answer: (List<String>) -> ProcessResult = { ProcessResult(1, "", "unexpected command") },
) : ProcessRunner {
    val calls: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

    override fun run(command: List<String>): ProcessResult {
        calls += command
        return answer(command.drop(1))
    }
}

internal fun ok(stdout: String = ""): ProcessResult = ProcessResult(0, stdout, "")

internal class RecordingShutdownHooks : ShutdownHooks {
    val added: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
    val removed: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

    val active: List<Thread> get() = added - removed.toSet()

    override fun add(hook: Thread) {
        added += hook
    }

    override fun remove(hook: Thread) {
        removed += hook
    }
}

/**
 * A token-less listener on host loopback, standing in for adb's forward to the Android
 * `LocalServerSocket` (which authenticates by peer uid, so the session has no token).
 */
internal class LoopbackListener : BridgeListener {
    private val server = ServerSocket(0, 8, BridgeClient.LOOPBACK)
    val port: Int get() = server.localPort

    override fun accept(): BridgeConnection? {
        val socket = try {
            server.accept()
        } catch (e: IOException) {
            if (server.isClosed) return null
            throw e
        }
        return SocketBridgeConnection(socket)
    }

    override fun close() = server.close()
}

private class SocketBridgeConnection(private val socket: Socket) : BridgeConnection {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(bytes: ByteArray, length: Int) {
        output.write(bytes, 0, length)
        output.flush()
    }

    override fun shutdown() {
        try {
            socket.close()
        } catch (_: IOException) {
            // Already closed.
        }
    }

    override fun close() = shutdown()
}
