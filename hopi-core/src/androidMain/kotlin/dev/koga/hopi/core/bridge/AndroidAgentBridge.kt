package dev.koga.hopi.core.bridge

import android.app.Application
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Process
import dev.koga.hopi.core.store.HopiStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * `LocalServerSocket` on the abstract name `hopi.<process name>`: no TCP listener, and the
 * kernel reports each peer's uid. Only the shell uid (adbd forwarding the CLI's `adb forward`) and
 * the app's own uid are served; anything else is closed unread. adbd forwards whatever reaches the
 * forward's port on the host, so a connection also sends the key of the proof file first, as a
 * token transport's token. The name itself is not owned by anyone, so `hello` carries the file's
 * proof (see [BridgeInfo.proof]).
 */
private class AndroidListener(private val socket: LocalServerSocket) : BridgeListener {
    private val ownUid = Process.myUid()

    override fun accept(): BridgeConnection? {
        while (true) {
            val client = socket.accept()
            val uid = try {
                client.peerCredentials.uid
            } catch (_: IOException) {
                -1
            }
            if (uid == SHELL_UID || uid == ownUid) return LocalConnection(client)
            try {
                client.close()
            } catch (_: IOException) {
                // Nothing was sent; closing is all that is left.
            }
        }
    }

    private val closed = AtomicBoolean(false)

    // Closed only when accept failed for good (nothing is parked in it then); production never
    // closes it otherwise, and LocalServerSocket.close() may not unblock a parked accept().
    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }

    private companion object {
        const val SHELL_UID = 2000
    }
}

private class LocalConnection(private val socket: LocalSocket) : BridgeConnection {
    private val input: InputStream = socket.inputStream
    private val output: OutputStream = socket.outputStream

    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(bytes: ByteArray, length: Int) {
        output.write(bytes, 0, length)
        output.flush()
    }

    // LocalSocket.close() alone does not reliably unblock a read parked on another thread.
    override fun shutdown() {
        try {
            socket.shutdownInput()
        } catch (_: IOException) {
            // Already shut down.
        }
        try {
            socket.shutdownOutput()
        } catch (_: IOException) {
            // Already shut down.
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
            // Already closed.
        }
    }
}

/** `Application.getProcessName()` on API 28+, else `/proc/self/cmdline` up to the first NUL. */
internal fun currentProcessName(): String {
    if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName()
    val bytes = File("/proc/self/cmdline").readBytes()
    val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
    return bytes.decodeToString(0, end).trim()
}

private val secureRandom = SecureRandom()

internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

internal actual fun startBridgeThread(name: String, body: () -> Unit) {
    thread(isDaemon = true, name = name) {
        try {
            body()
        } catch (e: Throwable) {
            emitBridgeNotice("agent bridge thread failed", e)
        }
    }
}

/**
 * `<cache dir>/hopi/<process name>.proof`: two lines, the proof `hello` shows, then the key a
 * client sends first, each 64 lowercase hex characters. The framework sets `java.io.tmpdir` to the app's
 * cache dir before `Application.onCreate`, so core needs no `Context`; the CLI reads the file as
 * `cache/hopi/<process name>.proof` relative to the package's data dir with `run-as`.
 */
private fun proofFile(processName: String): File {
    val cache = System.getProperty("java.io.tmpdir")?.takeIf { it.isNotEmpty() } ?: error("no java.io.tmpdir")
    return File(File(cache, "hopi"), "$processName.proof")
}

/** Written to a temporary file (owner-only, in an owner-only directory) and renamed into place. */
private fun writeProofFile(file: File, contents: String) {
    val directory = checkNotNull(file.parentFile)
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create $directory")
    directory.setReadable(false, false)
    directory.setWritable(false, false)
    directory.setExecutable(false, false)
    if (!directory.setReadable(true, true) || !directory.setWritable(true, true) || !directory.setExecutable(true, true)) {
        throw IOException("cannot restrict $directory")
    }
    val temp = File.createTempFile("proof", ".tmp", directory)
    try {
        temp.setReadable(false, false)
        temp.setWritable(false, false)
        if (!temp.setReadable(true, true) || !temp.setWritable(true, true)) throw IOException("cannot restrict $temp")
        temp.writeBytes(contents.encodeToByteArray())
        if (!temp.renameTo(file)) throw IOException("cannot rename $temp")
    } catch (e: Throwable) {
        temp.delete()
        throw e
    }
}

internal actual fun startPlatformAgentBridge(store: HopiStore) {
    startBridgeThread("hopi-bridge") {
        val server = try {
            val processName = currentProcessName()
            require(processName.isNotEmpty()) { "no process name" }
            val name = "hopi.$processName"
            val file = proofFile(processName)
            val socket = try {
                LocalServerSocket(name)
            } catch (e: IOException) {
                // Abstract names have no owner: whoever binds first holds it. A stale proof must not
                // outlive that, so the CLI reports this app's bridge as unverifiable, not reachable.
                file.delete()
                emitBridgeNotice(
                    "agent bridge failed to start: the abstract socket @$name is already bound " +
                        "(another process, possibly another app, holds it; the CLI will refuse it)",
                    e,
                )
                return@startBridgeThread
            }
            try {
                // Fresh secrets per start, so a file left by an earlier run never matches this one.
                // The key is the client's first line, as a token transport's token: adbd forwards
                // anything on the host that reaches the forward's port (another user's process, a
                // web page's request to localhost), and the uid check cannot tell them from the CLI.
                // The proof answers in hello: a squatter that got the key still cannot show it.
                val key = BridgeToken.generate()
                val proof = StringBuilder(BridgeToken.BYTES * 2).also(BridgeToken.generate()::writeTo).toString()
                writeProofFile(file, proof + "\n" + StringBuilder(BridgeToken.BYTES * 2).also(key::writeTo) + "\n")
                AgentBridgeServer(
                    AndroidListener(socket),
                    store,
                    BridgeInfo(processName, "android", proof),
                    token = key,
                    onStop = { file.delete() },
                )
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        } catch (e: Exception) {
            emitBridgeNotice("agent bridge failed to start", e)
            return@startBridgeThread
        }
        server.acceptLoop()
    }
}
