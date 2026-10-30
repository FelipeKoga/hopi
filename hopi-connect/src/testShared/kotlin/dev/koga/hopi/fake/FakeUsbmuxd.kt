package dev.koga.hopi.fake

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * usbmuxd on a Unix socket of the test's own ([socket]), speaking the plist protocol (version 1)
 * as far as hopi-connect uses it, so no test needs Apple's daemon or a phone.
 *
 * `ListDevices` answers the [attach]ed devices with the properties Apple's usbmuxd sends (no
 * device: `<array/>`, as Apple's daemon writes it). `Connect` treats each device's loopback as
 * the host's: it decodes `PortNumber` from network order and connects to that port on
 * `127.0.0.1`. When that works it answers 0 and relays raw bytes both ways; nothing listening is
 * 3 (connection refused), an unknown `DeviceID` 2. While [silent], a request gets no answer (a
 * hung usbmuxd); [fragmented] replies arrive in pieces; [wrongTag] and [replyVersion] break the
 * reply's header. [detach] drops a device and cuts its relays, as unplugging it would. Every
 * request is kept in [requests], with the body its header's length took (a test parses it whole).
 *
 * Compiled into the tests of hopi-connect, the CLI and the desktop viewer (their build files add
 * this directory), so it uses only the JDK.
 */
internal class FakeUsbmuxd : AutoCloseable {
    class Request(
        val length: Int,
        val version: Int,
        val type: Int,
        val tag: Int,
        /** The `length - 16` bytes read after the header. */
        val body: ByteArray,
        val messageType: String?,
        val deviceId: Long?,
        val portNumber: Long?,
        val clientVersion: String?,
        val progName: String?,
    )

    private class Device(val id: Long, val udid: String, val connection: String)

    // A short path: macOS limits a Unix socket's path to 104 bytes.
    private val directory: Path = Files.createTempDirectory("hmux")
    val socket: Path = directory.resolve("s")
    private val server: ServerSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
    private val devices = CopyOnWriteArrayList<Device>()
    private val open: MutableSet<AutoCloseable> = ConcurrentHashMap.newKeySet()
    private val relays = ConcurrentHashMap<String, MutableSet<AutoCloseable>>()
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var silent: Boolean = false

    /** Each reply is written in four pieces, with a pause after each: a client then reads part of the header, then part of the body. */
    @Volatile
    var fragmented: Boolean = false

    /** Replies carry their request's tag plus one. */
    @Volatile
    var wrongTag: Boolean = false

    /** The protocol version replies carry; Apple's daemon answers 1. */
    @Volatile
    var replyVersion: Int = 1

    init {
        check(socket.toString().length < 100) { "socket path too long for macOS: $socket" }
        thread(isDaemon = true, name = "fake-usbmuxd") { acceptLoop() }
    }

    fun attach(id: Long, udid: String, connection: String = "USB") {
        devices += Device(id, udid, connection)
    }

    fun detach(udid: String) {
        devices.removeIf { it.udid == udid }
        relays.remove(udid)?.forEach(::closeQuietly)
    }

    /** The ports `Connect` asked for, decoded from network order. */
    val connectedPorts: List<Int> get() = synchronized(requests) { requests.filter { it.messageType == "Connect" }.mapNotNull { it.portNumber?.toInt()?.let(::swap) } }

    private fun acceptLoop() {
        while (true) {
            val channel = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            open += channel
            thread(isDaemon = true, name = "fake-usbmuxd-client") { serve(channel) }
        }
    }

    private fun serve(channel: SocketChannel) {
        var relaying = false
        try {
            val header = readFully(channel, HEADER) ?: return
            header.order(ByteOrder.LITTLE_ENDIAN)
            val length = header.getInt(0)
            val tag = header.getInt(12)
            val body = readFully(channel, length - HEADER)?.array() ?: return
            val xml = body.decodeToString()
            val request = Request(
                length = length,
                version = header.getInt(4),
                type = header.getInt(8),
                tag = tag,
                body = body,
                messageType = string(xml, "MessageType"),
                deviceId = integer(xml, "DeviceID"),
                portNumber = integer(xml, "PortNumber"),
                clientVersion = string(xml, "ClientVersionString"),
                progName = string(xml, "ProgName"),
            )
            requests += request
            if (silent) {
                // Holds the connection without a word until the client gives up and closes it.
                channel.read(ByteBuffer.allocate(1))
                return
            }
            when (request.messageType) {
                "ListDevices" -> reply(channel, tag, deviceList())
                "Connect" -> relaying = connect(channel, tag, request)
                else -> reply(channel, tag, result(1))
            }
        } catch (_: IOException) {
            // The client went away.
        } finally {
            if (!relaying) release(channel)
        }
    }

    /** True when the channel now relays (and is closed by the relay). */
    private fun connect(channel: SocketChannel, tag: Int, request: Request): Boolean {
        val device = devices.firstOrNull { it.id == request.deviceId }
        if (device == null) {
            reply(channel, tag, result(2))
            return false
        }
        val target = try {
            Socket(LOOPBACK, swap(request.portNumber?.toInt() ?: 0))
        } catch (_: IOException) {
            reply(channel, tag, result(3))
            return false
        }
        reply(channel, tag, result(0))
        val pair = listOf(channel, target)
        val cut = AutoCloseable { pair.forEach(::release) }
        relays.computeIfAbsent(device.udid) { ConcurrentHashMap.newKeySet() } += cut
        open += target
        thread(isDaemon = true, name = "fake-usbmuxd-relay") { pump(ChannelIn(channel), target.getOutputStream(), cut) }
        thread(isDaemon = true, name = "fake-usbmuxd-relay") { pump(target.getInputStream(), ChannelOut(channel), cut) }
        return true
    }

    private fun pump(from: InputStream, to: OutputStream, cut: AutoCloseable) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
            }
        } catch (_: IOException) {
            // Cut.
        }
        cut.close()
    }

    private fun deviceList(): String = buildString {
        val attached = devices.toList()
        append(PLIST_HEAD).append("<dict>\n\t<key>DeviceList</key>\n")
        // Apple's daemon writes an empty list as `<array/>` (seen on 2026-10-30, no phone attached).
        append(if (attached.isEmpty()) "\t<array/>\n" else "\t<array>\n")
        for (device in attached) {
            append("\t\t<dict>\n")
            append("\t\t\t<key>DeviceID</key>\n\t\t\t<integer>${device.id}</integer>\n")
            append("\t\t\t<key>MessageType</key>\n\t\t\t<string>Attached</string>\n")
            append("\t\t\t<key>Properties</key>\n\t\t\t<dict>\n")
            if (device.connection == "USB") {
                append("\t\t\t\t<key>ConnectionSpeed</key>\n\t\t\t\t<integer>480000000</integer>\n")
                append("\t\t\t\t<key>ConnectionType</key>\n\t\t\t\t<string>USB</string>\n")
                append("\t\t\t\t<key>DeviceID</key>\n\t\t\t\t<integer>${device.id}</integer>\n")
                append("\t\t\t\t<key>LocationID</key>\n\t\t\t\t<integer>336592896</integer>\n")
                append("\t\t\t\t<key>ProductID</key>\n\t\t\t\t<integer>4776</integer>\n")
                append("\t\t\t\t<key>SerialNumber</key>\n\t\t\t\t<string>${device.udid}</string>\n")
                append("\t\t\t\t<key>USBSerialNumber</key>\n\t\t\t\t<string>${device.udid.replace("-", "")}</string>\n")
            } else {
                append("\t\t\t\t<key>ConnectionType</key>\n\t\t\t\t<string>${device.connection}</string>\n")
                append("\t\t\t\t<key>DeviceID</key>\n\t\t\t\t<integer>${device.id}</integer>\n")
                append("\t\t\t\t<key>EscapedFullServiceName</key>\n\t\t\t\t<string>a1:b2:c3:d4:e5:f6@fe80::1._apple-mobdev2._tcp.local.</string>\n")
                append("\t\t\t\t<key>InterfaceIndex</key>\n\t\t\t\t<integer>15</integer>\n")
                append("\t\t\t\t<key>NetworkAddress</key>\n\t\t\t\t<data>\n\t\t\t\tHB4AAAAAAAD+gAAAAAAAAAAAAAAAAAABDwAAAAAAAAA=\n\t\t\t\t</data>\n")
                append("\t\t\t\t<key>SerialNumber</key>\n\t\t\t\t<string>${device.udid}</string>\n")
            }
            append("\t\t\t</dict>\n\t\t</dict>\n")
        }
        if (attached.isNotEmpty()) append("\t</array>\n")
        append("</dict>\n</plist>\n")
    }

    private fun result(number: Int): String =
        "$PLIST_HEAD<dict>\n\t<key>MessageType</key>\n\t<string>Result</string>\n\t<key>Number</key>\n\t<integer>$number</integer>\n</dict>\n</plist>\n"

    private fun reply(channel: SocketChannel, tag: Int, plist: String) {
        val body = plist.encodeToByteArray()
        val size = HEADER + body.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(size).putInt(replyVersion).putInt(8).putInt(if (wrongTag) tag + 1 else tag).put(body).flip()
        val ends = if (fragmented) listOf(5, HEADER, HEADER + body.size / 2, size) else listOf(size)
        for (end in ends) {
            buffer.limit(end)
            while (buffer.hasRemaining()) channel.write(buffer)
            if (end < size) Thread.sleep(PAUSE_MS)
        }
    }

    private fun release(closeable: AutoCloseable) {
        open -= closeable
        closeQuietly(closeable)
    }

    override fun close() {
        closeQuietly(server)
        open.toList().forEach(::release)
        directory.toFile().deleteRecursively()
    }

    private class ChannelIn(private val channel: SocketChannel) : InputStream() {
        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int = channel.read(ByteBuffer.wrap(b, off, len))
    }

    private class ChannelOut(private val channel: SocketChannel) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val buffer = ByteBuffer.wrap(b, off, len)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }

    companion object {
        private const val HEADER = 16
        private const val PAUSE_MS = 30L
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** What Apple's usbmuxd puts before every plist. */
        const val PLIST_HEAD: String = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
            "<plist version=\"1.0\">\n"

        /** Network order to host order and back: the two bytes swapped. */
        fun swap(port: Int): Int = ((port and 0xff) shl 8) or ((port ushr 8) and 0xff)

        private fun string(xml: String, key: String): String? =
            Regex("<key>$key</key>\\s*<string>([^<]*)</string>").find(xml)?.groupValues?.get(1)

        private fun integer(xml: String, key: String): Long? =
            Regex("<key>$key</key>\\s*<integer>(-?\\d+)</integer>").find(xml)?.groupValues?.get(1)?.toLong()

        private fun readFully(channel: SocketChannel, count: Int): ByteBuffer? {
            if (count < 0) return null
            val buffer = ByteBuffer.allocate(count)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) return null
            }
            return buffer
        }

        private fun closeQuietly(closeable: AutoCloseable) {
            try {
                closeable.close()
            } catch (_: Exception) {
                // Already closed.
            }
        }
    }
}
