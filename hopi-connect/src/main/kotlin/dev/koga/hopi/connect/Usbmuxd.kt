package dev.koga.hopi.connect

import dev.koga.hopi.api.HopiInternalApi
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLResolver
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader
import kotlin.concurrent.thread

/**
 * An iPhone (or iPad) attached over USB, as usbmuxd lists it. [name] and [os] (e.g. `iOS 26.0`)
 * come from devicectl, null when it does not know the device.
 */
@HopiInternalApi
public class UsbIphone(public val udid: String, public val name: String?, public val os: String?)

/**
 * One `ListDevices` entry: [deviceId] is what `Connect` takes, [udid] the `SerialNumber` as a UDID
 * ([canonicalUdid]), [connection] `USB` or `Network`.
 */
internal class UsbmuxDevice(val deviceId: Long, val udid: String, val connection: String?)

/** usbmuxd answered `Connect` with 3: nothing listens on that port of the device. */
internal class UsbmuxRefused(message: String) : CliException(ExitCode.NOT_REACHABLE, message)

/** The same device, whichever way its UDID is written (case, the dash of the 25-character form). */
internal fun sameUdid(a: String, b: String): Boolean = a.replace("-", "").equals(b.replace("-", ""), ignoreCase = true)

/**
 * [udid] as devicectl and Xcode write it: a UDID of the 25-character kind
 * (`00008140-001A2B3C4D5E801E`) given without its dash, as the 24 characters of a USB serial
 * number, gets the dash back after the 8th; any other is returned unchanged.
 */
internal fun canonicalUdid(udid: String): String =
    if (DASHLESS_UDID.matches(udid)) udid.substring(0, 8) + "-" + udid.substring(8) else udid

private val DASHLESS_UDID = Regex("[0-9A-Fa-f]{24}")

/**
 * A client of usbmuxd, Apple's USB multiplexer, over its Unix socket [socket]. It speaks the plist
 * protocol, version 1: every message is a 16-byte little-endian header (the length including the
 * header, version 1, type 8 = plist, a tag the reply repeats) followed by an XML plist. Only
 * `ListDevices` and `Connect` are used (no `Listen`), each on a socket of its own; after a
 * `Connect` answered 0 that socket is a raw byte stream to that TCP port on the device's
 * loopback. Each reply is awaited at most [timeoutMs].
 */
internal class Usbmuxd(private val socket: Path, private val timeoutMs: Long = TIMEOUT_MS) {
    /** Every device usbmuxd lists, USB and network. */
    fun devices(): List<UsbmuxDevice> {
        val channel = open()
        try {
            val reply = exchange(channel, message("ListDevices"))
            val list = reply["DeviceList"] as? List<*> ?: throw notReachable("usbmuxd answered ListDevices without a DeviceList")
            return list.mapNotNull(::device)
        } finally {
            closeQuietly(channel)
        }
    }

    /**
     * A blocking channel to [port] on device [deviceId]'s loopback. Throws [UsbmuxRefused] when
     * nothing listens there, a [CliException] when the device is gone or usbmuxd fails.
     */
    fun connect(deviceId: Long, port: Int): SocketChannel {
        val channel = open()
        try {
            // PortNumber is the port in network byte order, read by usbmuxd as a host-order integer.
            val reply = exchange(channel, message("Connect", "DeviceID" to deviceId, "PortNumber" to networkOrder(port).toLong()))
            val number = reply["Number"] as? Long
            if (number == RESULT_OK) return channel
            throw when (number) {
                RESULT_REFUSED -> UsbmuxRefused("nothing listens on port $port of the device (usbmuxd: connection refused)")
                RESULT_BAD_DEVICE -> notReachable("the device is no longer attached (usbmuxd knows no device $deviceId)")
                else -> notReachable("usbmuxd refused the connection to port $port of the device (result ${number ?: "missing"})")
            }
        } catch (e: Throwable) {
            closeQuietly(channel)
            throw e
        }
    }

    private fun open(): SocketChannel {
        if (!Files.exists(socket)) throw notReachable("usbmuxd is not running: no socket at $socket")
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            channel.connect(UnixDomainSocketAddress.of(socket))
        } catch (e: IOException) {
            closeQuietly(channel)
            throw notReachable("cannot connect to usbmuxd at $socket: ${e.message}")
        }
        return channel
    }

    /** Sends [plist] and reads the reply, closing the channel if none arrives in time (a blocked read then fails). */
    private fun exchange(channel: SocketChannel, plist: String): Map<*, *> {
        val state = AtomicInteger(RUNNING)
        val watchdog = WATCHDOG.schedule({ if (state.compareAndSet(RUNNING, FIRED)) closeQuietly(channel) }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            val tag = TAGS.incrementAndGet()
            write(channel, frame(plist, tag))
            val reply = read(channel, tag)
            // A deadline that fired just now closed the channel under the reply.
            if (!state.compareAndSet(RUNNING, DONE)) throw timedOut()
            return reply
        } catch (e: IOException) {
            if (state.get() == FIRED) throw timedOut()
            throw notReachable("usbmuxd closed the connection: ${e.message}")
        } finally {
            watchdog.cancel(false)
        }
    }

    private fun timedOut() = notReachable("usbmuxd did not answer within $timeoutMs ms")

    /** The reply to the request tagged [tag]: a plist message of protocol version 1 that carries the same tag. */
    private fun read(channel: SocketChannel, tag: Int): Map<*, *> {
        val header = readFully(channel, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val length = header.getInt(0)
        val version = header.getInt(4)
        val type = header.getInt(8)
        val replyTag = header.getInt(12)
        if (length < HEADER_BYTES || length > MAX_MESSAGE_BYTES) throw notReachable("usbmuxd sent a message of $length bytes")
        if (version != VERSION_PLIST) throw notReachable("usbmuxd answered with protocol version $version, not $VERSION_PLIST")
        if (type != TYPE_PLIST) throw notReachable("usbmuxd answered with message type $type, not a plist")
        // One request per socket, so a reply cannot belong to another: this guards the protocol.
        if (replyTag != tag) throw notReachable("usbmuxd answered tag $replyTag to request $tag")
        val body = readFully(channel, length - HEADER_BYTES)
        return parsePlist(body.array()) as? Map<*, *> ?: throw notReachable("usbmuxd sent a reply that is not a plist dictionary")
    }

    private fun device(entry: Any?): UsbmuxDevice? {
        val map = entry as? Map<*, *> ?: return null
        val properties = map["Properties"] as? Map<*, *> ?: return null
        val id = (map["DeviceID"] ?: properties["DeviceID"]) as? Long ?: return null
        val udid = properties["SerialNumber"] as? String ?: return null
        return UsbmuxDevice(id, canonicalUdid(udid), properties["ConnectionType"] as? String)
    }

    companion object {
        const val TIMEOUT_MS: Long = 5_000
        const val RESULT_OK: Long = 0
        const val RESULT_BAD_DEVICE: Long = 2
        const val RESULT_REFUSED: Long = 3

        private const val HEADER_BYTES = 16
        private const val VERSION_PLIST = 1
        private const val TYPE_PLIST = 8
        private const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024
        private const val RUNNING = 0
        private const val DONE = 1
        private const val FIRED = 2
        private val TAGS = AtomicInteger(0)

        /** Closes channels whose reply is late. Its one thread ends when idle for a second. */
        private val WATCHDOG = ScheduledThreadPoolExecutor(1) { Thread(it, "hopi-usbmuxd-deadline").apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(1, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }

        /** `htons(port)`: the two bytes of [port] swapped. */
        fun networkOrder(port: Int): Int = ((port and 0xff) shl 8) or ((port ushr 8) and 0xff)

        /** A request as libusbmuxd words it: its client strings and protocol version, then [fields]. */
        fun message(type: String, vararg fields: Pair<String, Any>): String = plistDict(
            listOf<Pair<String, Any>>(
                "ClientVersionString" to "hopi-connect",
                "MessageType" to type,
                "ProgName" to "hopi",
                "kLibUSBMuxVersion" to 3L,
            ) + fields,
        )

        fun frame(plist: String, tag: Int): ByteBuffer {
            val body = plist.encodeToByteArray()
            return ByteBuffer.allocate(HEADER_BYTES + body.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(HEADER_BYTES + body.size).putInt(VERSION_PLIST).putInt(TYPE_PLIST).putInt(tag).put(body)
                .flip()
        }

        private fun write(channel: SocketChannel, buffer: ByteBuffer) {
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        private fun readFully(channel: SocketChannel, count: Int): ByteBuffer {
            val buffer = ByteBuffer.allocate(count)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw IOException("end of stream after ${buffer.position()} of $count bytes")
            }
            return buffer
        }
    }
}

/**
 * A host loopback port whose every connection is relayed to [port] on usbmuxd device [deviceId],
 * so the bridge client reaches a device's loopback bridge exactly as it reaches a simulator's
 * (on `127.0.0.1`). Each accepted connection gets its own `Connect`; one usbmuxd refuses (nothing
 * listens: the app is not running) is closed before any byte, which the client reads as "app not
 * running", as it does an adb forward nothing answers. The relay only moves bytes: the token is
 * still the first line the client sends, and the bridge still checks it. [close] stops accepting
 * and closes every relayed pair.
 */
internal class UsbmuxRelay(private val usbmuxd: Usbmuxd, private val deviceId: Long, private val port: Int) : AutoCloseable {
    private val server = ServerSocket(0, BACKLOG, BridgeClient.LOOPBACK)
    private val open: MutableSet<Closeable> = ConcurrentHashMap.newKeySet()
    private val closed = AtomicBoolean(false)

    /** The host loopback port to connect to. */
    val localPort: Int = server.localPort

    init {
        thread(isDaemon = true, name = "hopi-usbmux-accept") { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            thread(isDaemon = true, name = "hopi-usbmux-relay") { relay(socket) }
        }
    }

    private fun relay(socket: Socket) {
        if (!track(socket)) return
        val channel = try {
            usbmuxd.connect(deviceId, port)
        } catch (_: Exception) {
            // Refused (the app is not running), the device gone, usbmuxd failing: no byte reaches the client.
            release(socket)
            return
        }
        if (!track(channel)) {
            release(socket)
            return
        }
        try {
            socket.tcpNoDelay = true
        } catch (_: IOException) {
            // Closed already: the pumps find out.
        }
        val link = Link(socket, channel)
        thread(isDaemon = true, name = "hopi-usbmux-relay") { link.hostToDevice() }
        link.deviceToHost()
    }

    /** False when the relay closed meanwhile; [closeable] is then closed. */
    private fun track(closeable: Closeable): Boolean {
        open += closeable
        if (!closed.get()) return true
        release(closeable)
        return false
    }

    private fun release(closeable: Closeable) {
        open -= closeable
        closeQuietly(closeable)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeQuietly(server)
        open.toList().forEach(::release)
    }

    /** One relayed pair. An end of input is passed on as a half close; a failure closes both at once. */
    private inner class Link(private val socket: Socket, private val channel: SocketChannel) {
        private val ended = AtomicInteger(0)

        fun hostToDevice() = pump({ socket.getInputStream() }, { ChannelOutput(channel) }) { channel.shutdownOutput() }

        fun deviceToHost() = pump({ ChannelInput(channel) }, { socket.getOutputStream() }) { socket.shutdownOutput() }

        private fun pump(source: () -> InputStream, sink: () -> OutputStream, halfClose: () -> Unit) {
            try {
                val from = source()
                val to = sink()
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = from.read(buffer)
                    if (n < 0) break
                    to.write(buffer, 0, n)
                }
                halfClose()
            } catch (_: IOException) {
                end()
                return
            }
            if (ended.incrementAndGet() == 2) end()
        }

        private fun end() {
            release(socket)
            release(channel)
        }
    }

    private companion object {
        const val BACKLOG = 8
        const val BUFFER_BYTES = 64 * 1024
    }
}

/** A blocking channel's reads; it never returns 0 for a non-empty request. */
private class ChannelInput(private val channel: SocketChannel) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else channel.read(ByteBuffer.wrap(b, off, len))
}

/** A blocking channel's writes, each written whole. */
private class ChannelOutput(private val channel: SocketChannel) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        val buffer = ByteBuffer.wrap(b, off, len)
        while (buffer.hasRemaining()) channel.write(buffer)
    }
}

private fun closeQuietly(closeable: Closeable) {
    try {
        closeable.close()
    } catch (_: IOException) {
        // Already closed.
    }
}

private const val PLIST_HEADER = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
"""

/** An XML plist holding one dictionary of strings and integers, in [fields]' order. */
internal fun plistDict(fields: List<Pair<String, Any>>): String = buildString {
    append(PLIST_HEADER).append("<dict>\n")
    for ((key, value) in fields) {
        append("\t<key>").append(xmlEscape(key)).append("</key>\n")
        when (value) {
            is String -> append("\t<string>").append(xmlEscape(value)).append("</string>\n")
            is Long, is Int -> append("\t<integer>").append(value).append("</integer>\n")
            else -> throw IllegalArgumentException("a plist field is a string or an integer, not ${value::class.simpleName}")
        }
    }
    append("</dict>\n</plist>\n")
}

private fun xmlEscape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * The value of an XML plist: a dictionary is a `Map<String, Any?>`, an array a `List`, an
 * integer a `Long`, `real` a `Double`, `data` a `ByteArray`, `true`/`false` a `Boolean`, a
 * string or a date a `String`. Null when [bytes] is not an XML plist. The DOCTYPE usbmuxd sends
 * is never loaded: DTDs are off and any entity resolves to nothing, so nothing is fetched.
 */
internal fun parsePlist(bytes: ByteArray): Any? {
    val factory = XMLInputFactory.newDefaultFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setXMLResolver(XMLResolver { _, _, _, _ -> ByteArrayInputStream(ByteArray(0)) })
    }
    val reader = try {
        factory.createXMLStreamReader(ByteArrayInputStream(bytes))
    } catch (_: XMLStreamException) {
        return null
    }
    return try {
        var root: Any? = null
        while (reader.hasNext()) {
            if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
            if (reader.localName != "plist") break
            if (reader.nextTag() == XMLStreamConstants.START_ELEMENT) root = reader.plistValue()
            break
        }
        root
    } catch (_: XMLStreamException) {
        null
    } finally {
        try {
            reader.close()
        } catch (_: XMLStreamException) {
            // Nothing held.
        }
    }
}

/** Reads the element the reader is on, leaving it on that element's end. */
private fun XMLStreamReader.plistValue(): Any? = when (localName) {
    "dict" -> {
        val map = LinkedHashMap<String, Any?>()
        while (nextTag() == XMLStreamConstants.START_ELEMENT) {
            if (localName != "key") {
                skipElement()
                continue
            }
            val key = elementText
            if (nextTag() != XMLStreamConstants.START_ELEMENT) break
            map[key] = plistValue()
        }
        map
    }
    "array" -> {
        val list = ArrayList<Any?>()
        while (nextTag() == XMLStreamConstants.START_ELEMENT) list += plistValue()
        list
    }
    "string", "date" -> elementText
    "integer" -> elementText.trim().let { it.toLongOrNull() ?: it }
    "real" -> elementText.trim().toDoubleOrNull()
    "true" -> true.also { skipElement() }
    "false" -> false.also { skipElement() }
    "data" -> try {
        Base64.getMimeDecoder().decode(elementText)
    } catch (_: IllegalArgumentException) {
        null
    }
    else -> null.also { skipElement() }
}

/** From an element's start to its end, whatever it holds. */
private fun XMLStreamReader.skipElement() {
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XMLStreamConstants.START_ELEMENT -> depth++
            XMLStreamConstants.END_ELEMENT -> depth--
        }
    }
}
