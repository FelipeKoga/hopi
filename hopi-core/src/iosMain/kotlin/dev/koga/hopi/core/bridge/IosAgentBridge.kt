@file:OptIn(ExperimentalForeignApi::class)

package dev.koga.hopi.core.bridge

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.store.HopiStore
import kotlin.concurrent.atomics.AtomicBoolean
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSThread
import platform.Foundation.NSUserDomainMask
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.AF_INET
import platform.posix.ECONNABORTED
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_NOFOLLOW
import platform.posix.O_WRONLY
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.accept
import platform.posix.bind
import platform.posix.chmod
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.getpid
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.memset
import platform.posix.mkdir
import platform.posix.open
import platform.posix.recv
import platform.posix.rename
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.unlink
import platform.posix.write

/**
 * A running iOS bridge: `127.0.0.1` port 0 plus token, advertised in [bridgeFile]. macOS caps
 * `sun_path` at 104 bytes and simulator container paths are longer, hence loopback TCP.
 */
@HopiInternalApi
public class IosAgentBridge internal constructor(
    public val port: Int,
    public val bridgeFile: String,
    private val server: AgentBridgeServer,
) {
    /** Stops the server, disconnects its clients and deletes [bridgeFile]. */
    public fun close() {
        server.close()
        unlink(bridgeFile)
    }

    public companion object {
        public const val BRIDGE_FILE_NAME: String = "bridge.json"

        /** `<app data container>/Library/Caches/hopi`. */
        public fun defaultDirectory(): String {
            val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
                ?: error("no caches directory")
            return "$caches/hopi"
        }

        public fun defaultAppId(): String = NSBundle.mainBundle.bundleIdentifier ?: NSProcessInfo.processInfo.processName

        /**
         * Binds `127.0.0.1` port 0, writes `bridge.json` into [directory] (0700, file 0600, written
         * to a temporary file and renamed over the old one) and starts accepting.
         */
        public fun start(
            store: HopiStore,
            directory: String = defaultDirectory(),
            app: String = defaultAppId(),
        ): IosAgentBridge {
            val listener = PosixListener.bindLoopback()
            try {
                val token = BridgeToken.generate()
                val file = writeBridgeFile(directory, bridgeFileJson(listener.port, token))
                val server = AgentBridgeServer(listener, store, BridgeInfo(app, "ios"), token, onStop = { unlink(file) })
                server.start()
                return IosAgentBridge(listener.port, file, server)
            } catch (e: Throwable) {
                listener.close()
                throw e
            }
        }

        private fun writeBridgeFile(directory: String, json: String): String {
            if (mkdir(directory, OWNER_ONLY_DIR.convert()) != 0 && errno != EEXIST) posixError("mkdir $directory")
            if (chmod(directory, OWNER_ONLY_DIR.convert()) != 0) posixError("chmod $directory")
            val target = "$directory/$BRIDGE_FILE_NAME"
            val temp = "$target.tmp.${getpid()}"
            unlink(temp)
            // Created 0600 by open itself, never widened; the rename makes the new content appear at once.
            val fd = open(temp, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, OWNER_ONLY_FILE)
            if (fd < 0) posixError("open $temp")
            try {
                val bytes = json.encodeToByteArray()
                bytes.usePinned { pinned ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val written = write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
                        if (written < 0) {
                            if (errno == EINTR) continue
                            posixError("write $temp")
                        }
                        offset += written.toInt()
                    }
                }
            } catch (e: Throwable) {
                close(fd)
                unlink(temp)
                throw e
            }
            close(fd)
            if (rename(temp, target) != 0) {
                unlink(temp)
                posixError("rename $temp")
            }
            return target
        }

        private const val OWNER_ONLY_DIR = 0x1C0 // 0700
        private const val OWNER_ONLY_FILE = 0x180 // 0600
    }
}

/** A listening `AF_INET` socket bound to `127.0.0.1` only, never `INADDR_ANY`. */
internal class PosixListener private constructor(private val fd: Int, val port: Int) : BridgeListener {
    private val closed = AtomicBoolean(false)

    override fun accept(): BridgeConnection? {
        while (true) {
            val client = accept(fd, null, null)
            if (closed.load()) {
                if (client >= 0) close(client)
                return null
            }
            if (client >= 0) {
                // Writing to a socket the CLI closed must fail with EPIPE, not kill the app with SIGPIPE.
                setIntOption(client, SO_NOSIGPIPE, 1)
                return PosixConnection(client)
            }
            val error = errno
            if (error == EINTR || error == ECONNABORTED) continue
            posixError("accept")
        }
    }

    /**
     * Darwin's `close` or `shutdown` on a listening socket does not wake a parked `accept`, so the
     * listener connects to itself once to unblock it, then closes.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val wake = socket(AF_INET, SOCK_STREAM, 0)
        if (wake >= 0) {
            setIntOption(wake, SO_NOSIGPIPE, 1)
            memScoped {
                val address = loopbackAddress(port)
                connect(wake, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            }
            close(wake)
        }
        close(fd)
    }

    companion object {
        fun bindLoopback(): PosixListener {
            val fd = socket(AF_INET, SOCK_STREAM, 0)
            if (fd < 0) posixError("socket")
            try {
                val port = memScoped {
                    val address = loopbackAddress(0)
                    if (bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) posixError("bind")
                    if (listen(fd, BACKLOG) != 0) posixError("listen")
                    val length = alloc<socklen_tVar>()
                    length.value = sizeOf<sockaddr_in>().convert()
                    if (getsockname(fd, address.ptr.reinterpret(), length.ptr) != 0) posixError("getsockname")
                    check(address.sin_addr.s_addr == LOOPBACK_NETWORK_ORDER) { "bridge not bound to 127.0.0.1" }
                    swapBytes(address.sin_port).toInt()
                }
                return PosixListener(fd, port)
            } catch (e: Throwable) {
                close(fd)
                throw e
            }
        }

        private const val BACKLOG = 8
    }
}

private class PosixConnection(private val fd: Int) : BridgeConnection {
    private val isShutdown = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)

    override fun read(buffer: ByteArray): Int {
        while (true) {
            val count = buffer.usePinned { recv(fd, it.addressOf(0), buffer.size.convert(), 0) }
            if (count > 0) return count.toInt()
            if (count == 0L || errno != EINTR) return -1
        }
    }

    override fun write(bytes: ByteArray, length: Int) {
        if (length == 0) return
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < length) {
                val sent = send(fd, pinned.addressOf(0) + offset, (length - offset).convert(), 0)
                if (sent < 0) {
                    if (errno == EINTR) continue
                    posixError("send")
                }
                offset += sent.toInt()
            }
        }
    }

    override fun shutdown() {
        if (isShutdown.compareAndSet(false, true)) shutdown(fd, SHUT_RDWR)
    }

    // Only after both threads left the fd, so the number is never reused under a parked recv.
    override fun close() {
        if (isClosed.compareAndSet(false, true)) close(fd)
    }
}

/** `127.0.0.1` in network byte order, as `s_addr` holds it on little-endian arm64. */
internal const val LOOPBACK_NETWORK_ORDER: UInt = 0x0100007Fu

/** `htons`/`ntohs` are macros cinterop does not bind; every iOS target is little-endian. */
internal fun swapBytes(value: UShort): UShort = (((value.toInt() and 0xFF) shl 8) or ((value.toInt() ushr 8) and 0xFF)).toUShort()

internal fun kotlinx.cinterop.MemScope.loopbackAddress(port: Int): sockaddr_in {
    val address = alloc<sockaddr_in>()
    memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
    address.sin_len = sizeOf<sockaddr_in>().convert()
    address.sin_family = AF_INET.convert()
    address.sin_port = swapBytes(port.toUShort())
    address.sin_addr.s_addr = LOOPBACK_NETWORK_ORDER
    return address
}

internal fun setIntOption(fd: Int, option: Int, value: Int) {
    memScoped {
        val flag = alloc<IntVar>()
        flag.value = value
        setsockopt(fd, SOL_SOCKET, option, flag.ptr, sizeOf<IntVar>().convert())
    }
}

private fun posixError(what: String): Nothing = throw IllegalStateException("$what failed: errno $errno")

internal actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
    check(status == 0) { "SecRandomCopyBytes failed: $status" }
    return bytes
}

internal actual fun startBridgeThread(name: String, body: () -> Unit) {
    val thread = NSThread {
        // An exception escaping a Kotlin/Native thread terminates the app; report it instead.
        try {
            body()
        } catch (e: Throwable) {
            emitBridgeNotice("agent bridge thread failed", e)
        }
    }
    thread.name = name
    thread.start()
}

internal actual fun startPlatformAgentBridge(store: HopiStore) {
    startBridgeThread("hopi-bridge-start") {
        try {
            IosAgentBridge.start(store)
        } catch (e: Exception) {
            emitBridgeNotice("agent bridge failed to start", e)
        }
    }
}
