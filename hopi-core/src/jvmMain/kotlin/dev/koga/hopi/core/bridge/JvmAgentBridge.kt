package dev.koga.hopi.core.bridge

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.store.HopiStore
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import kotlin.concurrent.thread

/** A running JVM bridge: loopback port plus token, advertised in [bridgeFile]. */
@HopiInternalApi
public class JvmAgentBridge internal constructor(
    /** The bound address: always `127.0.0.1`. */
    public val address: InetAddress,
    public val port: Int,
    public val bridgeFile: Path,
    private val server: AgentBridgeServer,
) : AutoCloseable {
    /** Stops the server, disconnects its clients and deletes [bridgeFile]. */
    override fun close() {
        server.close()
        deleteBridgeFile(bridgeFile)
    }

    public companion object {
        public const val BRIDGE_DIR_PROPERTY: String = "hopi.bridgeDir"
        public const val APP_ID_PROPERTY: String = "hopi.appId"
        public const val BRIDGE_FILE_NAME: String = "bridge.json"

        /** `hopi.bridgeDir`, else `<java.io.tmpdir>/hopi/<pid>`. */
        public fun defaultDirectory(): Path {
            System.getProperty(BRIDGE_DIR_PROPERTY)?.takeIf { it.isNotEmpty() }?.let { return Paths.get(it) }
            return Paths.get(System.getProperty("java.io.tmpdir"), "hopi", ProcessHandle.current().pid().toString())
        }

        /**
         * Binds `127.0.0.1` port 0, writes `bridge.json` into [directory] (created 0700, file 0600,
         * written to a temporary file and renamed over the old one) and starts accepting.
         */
        public fun start(
            store: HopiStore,
            directory: Path = defaultDirectory(),
            app: String = System.getProperty(APP_ID_PROPERTY)?.takeIf { it.isNotEmpty() } ?: "jvm",
        ): JvmAgentBridge {
            val socket = ServerSocket(0, BACKLOG, LOOPBACK)
            try {
                val token = BridgeToken.generate()
                val file = writeBridgeFile(directory, bridgeFileJson(socket.localPort, token))
                val server = AgentBridgeServer(JvmListener(socket), store, BridgeInfo(app, "jvm"), token, onStop = { deleteBridgeFile(file) })
                server.start()
                return JvmAgentBridge(socket.inetAddress, socket.localPort, file, server)
            } catch (e: Throwable) {
                socket.close()
                throw e
            }
        }

        /** Never `0.0.0.0`, and not `getLoopbackAddress()`, which is `::1` under `preferIPv6Addresses`. */
        internal val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private const val BACKLOG = 8

        private fun writeBridgeFile(directory: Path, json: String): Path {
            val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
            if (posix) {
                val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
                directory.parent?.let { if (Files.notExists(it)) Files.createDirectories(it, ownerOnly) }
                if (Files.notExists(directory)) Files.createDirectory(directory, ownerOnly)
                // Also tightens a directory that already existed.
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            } else {
                Files.createDirectories(directory)
            }
            val temp = if (posix) {
                Files.createTempFile(
                    directory,
                    "bridge",
                    ".json.tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                )
            } else {
                Files.createTempFile(directory, "bridge", ".json.tmp")
            }
            try {
                Files.write(temp, json.encodeToByteArray())
                val target = directory.resolve(BRIDGE_FILE_NAME)
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return target
            } catch (e: Throwable) {
                Files.deleteIfExists(temp)
                throw e
            }
        }
    }
}

private class JvmListener(private val socket: ServerSocket) : BridgeListener {
    override fun accept(): BridgeConnection? {
        val client = try {
            socket.accept()
        } catch (e: IOException) {
            if (socket.isClosed) return null
            throw e
        }
        return SocketConnection(client)
    }

    override fun close() {
        socket.close()
    }
}

private class SocketConnection(private val socket: Socket) : BridgeConnection {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(bytes: ByteArray, length: Int) {
        output.write(bytes, 0, length)
        output.flush()
    }

    // Closing a java.net.Socket unblocks a read or write parked on another thread.
    override fun shutdown() {
        try {
            socket.close()
        } catch (_: IOException) {
            // Already closed.
        }
    }

    override fun close() = shutdown()
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

private fun deleteBridgeFile(file: Path) {
    try {
        Files.deleteIfExists(file)
    } catch (_: IOException) {
        // A stale file only points at a closed port.
    }
}

internal actual fun startPlatformAgentBridge(store: HopiStore) {
    startBridgeThread("hopi-bridge-start") {
        try {
            // A directory named by the system property belongs to whoever named it; the per-pid default is ours.
            val ownsDirectory = System.getProperty(JvmAgentBridge.BRIDGE_DIR_PROPERTY).isNullOrEmpty()
            startProcessBridge(store, JvmAgentBridge.defaultDirectory(), ownsDirectory) { Runtime.getRuntime().addShutdownHook(it) }
        } catch (e: Exception) {
            emitBridgeNotice("agent bridge failed to start", e)
        }
    }
}

/**
 * The process's own bridge, closed by a shutdown hook so `bridge.json` (and [directory] when
 * [ownsDirectory], once empty) does not outlive a normal exit or SIGTERM. Only SIGKILL leaves it,
 * pointing at a port nothing listens on.
 */
internal fun startProcessBridge(
    store: HopiStore,
    directory: Path,
    ownsDirectory: Boolean,
    addShutdownHook: (Thread) -> Unit,
): JvmAgentBridge {
    val bridge = JvmAgentBridge.start(store, directory)
    addShutdownHook(
        Thread({
            bridge.close()
            if (ownsDirectory) {
                try {
                    Files.deleteIfExists(directory)
                } catch (_: IOException) {
                    // Not empty, or already gone.
                }
            }
        }, "hopi-bridge-cleanup"),
    )
    return bridge
}
