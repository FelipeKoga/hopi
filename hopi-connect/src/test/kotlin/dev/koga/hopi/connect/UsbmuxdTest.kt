package dev.koga.hopi.connect

import dev.koga.hopi.fake.FakeUsbmuxd
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Paths
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** usbmuxd's plist protocol against [FakeUsbmuxd] (a Unix socket of the test's own), and devicectl's JSON. */
class UsbmuxdTest {
    private val usbmuxd = FakeUsbmuxd()
    private val servers = mutableListOf<ServerSocket>()

    @AfterTest
    fun tearDown() {
        usbmuxd.close()
        servers.forEach { it.close() }
    }

    /** A loopback server that echoes every byte, standing in for a port on the device. */
    private fun echo(): Int {
        val server = ServerSocket(0, 8, BridgeClient.LOOPBACK).also { servers += it }
        thread(isDaemon = true) {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: java.io.IOException) {
                    return@thread
                }
                thread(isDaemon = true) { socket.use { it.getInputStream().copyTo(it.getOutputStream()) } }
            }
        }
        return server.localPort
    }

    @Test
    fun listDevicesSpeaksPlistVersionOne() {
        usbmuxd.attach(3, UDID)
        usbmuxd.attach(4, UDID, connection = "Network")
        usbmuxd.attach(7, OTHER)
        val devices = Usbmuxd(usbmuxd.socket).devices()
        assertEquals(listOf("3 $UDID USB", "4 $UDID Network", "7 $OTHER USB"), devices.map { "${it.deviceId} ${it.udid} ${it.connection}" })

        val request = usbmuxd.requests.single()
        assertEquals("ListDevices", request.messageType)
        assertEquals(1, request.version, "plist protocol version")
        assertEquals(8, request.type, "message type plist")
        assertEquals("hopi-connect", request.clientVersion)
        assertEquals("hopi", request.progName)
        // The header's length took the whole plist: what it covers parses to its end.
        assertEquals(
            mapOf("ClientVersionString" to "hopi-connect", "MessageType" to "ListDevices", "ProgName" to "hopi", "kLibUSBMuxVersion" to 3L),
            parsePlist(request.body),
        )

        val frame = Usbmuxd.frame("<plist/>", 42).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16 + 8, frame.remaining())
        assertEquals(frame.remaining(), frame.getInt(0), "the length counts the header")
        assertEquals(listOf(1, 8, 42), listOf(frame.getInt(4), frame.getInt(8), frame.getInt(12)), "version, type, tag")
    }

    @Test
    fun noDeviceIsTheEmptyArrayAppleWrites() {
        assertEquals(emptyList(), Usbmuxd(usbmuxd.socket).devices())
        // Apple's daemon's reply to ListDevices with no phone attached, byte for byte (2026-10-30).
        val apple = FakeUsbmuxd.PLIST_HEAD + "<dict>\n\t<key>DeviceList</key>\n\t<array/>\n</dict>\n</plist>\n"
        assertEquals(237, 16 + apple.encodeToByteArray().size, "the length Apple's daemon sent")
        assertEquals(mapOf("DeviceList" to emptyList<Any?>()), parsePlist(apple.encodeToByteArray()))
    }

    @Test
    fun repliesThatArriveInPiecesAreReadWhole() {
        usbmuxd.fragmented = true
        assertEquals(emptyList(), Usbmuxd(usbmuxd.socket).devices())
        usbmuxd.attach(3, UDID)
        assertEquals(listOf(UDID), Usbmuxd(usbmuxd.socket).devices().map { it.udid })
        Usbmuxd(usbmuxd.socket).connect(3, echo()).close()
    }

    @Test
    fun aReplyWithAnotherTagOrVersionIsRefused() {
        usbmuxd.wrongTag = true
        val tag = assertFailsWith<CliException> { Usbmuxd(usbmuxd.socket).devices() }
        assertEquals(ExitCode.NOT_REACHABLE, tag.exitCode)
        val (got, sent) = Regex("usbmuxd answered tag (\\d+) to request (\\d+)").matchEntire(tag.message!!)!!.destructured
        assertEquals(sent.toInt() + 1, got.toInt())

        usbmuxd.wrongTag = false
        usbmuxd.replyVersion = 0
        val version = assertFailsWith<CliException> { Usbmuxd(usbmuxd.socket).devices() }
        assertEquals("usbmuxd answered with protocol version 0, not 1", version.message)
    }

    @Test
    fun connectSendsThePortInNetworkOrderThenTheSocketIsRawBytes() {
        assertEquals(0x3412, Usbmuxd.networkOrder(0x1234))
        assertEquals(32498, Usbmuxd.networkOrder(62078), "lockdownd's port as libusbmuxd sends it")
        usbmuxd.attach(3, UDID)
        val port = echo()
        Usbmuxd(usbmuxd.socket).connect(3, port).use { channel ->
            channel.write(ByteBuffer.wrap("ping\n".encodeToByteArray()))
            val back = ByteBuffer.allocate(5)
            while (back.hasRemaining()) check(channel.read(back) >= 0)
            assertEquals("ping\n", back.array().decodeToString())
        }
        val connect = usbmuxd.requests.single { it.messageType == "Connect" }
        assertEquals(3, connect.deviceId)
        assertEquals(Usbmuxd.networkOrder(port).toLong(), connect.portNumber)
        assertEquals(listOf(port), usbmuxd.connectedPorts)
    }

    @Test
    fun connectFailuresSayWhy() {
        usbmuxd.attach(3, UDID)
        val closed = ServerSocket(0, 1, BridgeClient.LOOPBACK).run { localPort.also { close() } }
        val refused = assertFailsWith<UsbmuxRefused> { Usbmuxd(usbmuxd.socket).connect(3, closed) }
        assertEquals("nothing listens on port $closed of the device (usbmuxd: connection refused)", refused.message)

        val gone = assertFailsWith<CliException> { Usbmuxd(usbmuxd.socket).connect(99, echo()) }
        assertEquals(ExitCode.NOT_REACHABLE, gone.exitCode)
        assertEquals("the device is no longer attached (usbmuxd knows no device 99)", gone.message)
    }

    @Test
    fun noSocketAndAUsbmuxdThatNeverAnswers() {
        val missing = Paths.get("/hopi-test/no-usbmuxd")
        val none = assertFailsWith<CliException> { Usbmuxd(missing).devices() }
        assertEquals("usbmuxd is not running: no socket at $missing", none.message)

        usbmuxd.silent = true
        val started = System.nanoTime()
        val late = assertFailsWith<CliException> { Usbmuxd(usbmuxd.socket, timeoutMs = 300).devices() }
        assertEquals("usbmuxd did not answer within 300 ms", late.message)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "the deadline fired late")
    }

    @Test
    fun plistsAreReadWithoutLoadingTheirDtd() {
        // Apple's DOCTYPE names a URL; reading it must not fetch it (nor fail offline).
        val plist = FakeUsbmuxd.PLIST_HEAD + """
            <dict>
            	<key>DeviceList</key>
            	<array>
            		<dict>
            			<key>DeviceID</key>
            			<integer>12</integer>
            			<key>Properties</key>
            			<dict>
            				<key>SerialNumber</key>
            				<string>a &amp; b &lt;c&gt;</string>
            				<key>NetworkAddress</key>
            				<data>
            				AQID
            				</data>
            				<key>Big</key>
            				<integer>18446744073709551615</integer>
            				<key>Speed</key>
            				<real>1.5</real>
            				<key>Yes</key>
            				<true/>
            				<key>No</key>
            				<false/>
            				<key>Empty</key>
            				<string></string>
            			</dict>
            		</dict>
            	</array>
            </dict>
            </plist>
        """.trimIndent()
        val root = parsePlist(plist.encodeToByteArray()) as Map<*, *>
        val device = (root["DeviceList"] as List<*>).single() as Map<*, *>
        assertEquals(12L, device["DeviceID"])
        val properties = device["Properties"] as Map<*, *>
        assertEquals("a & b <c>", properties["SerialNumber"])
        assertContentEquals(byteArrayOf(1, 2, 3), properties["NetworkAddress"] as ByteArray)
        assertEquals("18446744073709551615", properties["Big"], "beyond a Long, kept as its text")
        assertEquals(1.5, properties["Speed"])
        assertEquals(true, properties["Yes"])
        assertEquals(false, properties["No"])
        assertEquals("", properties["Empty"])

        val written = Usbmuxd.message("Connect", "DeviceID" to 3L, "PortNumber" to 32498L)
        val read = parsePlist(written.encodeToByteArray()) as Map<*, *>
        assertEquals(
            mapOf(
                "ClientVersionString" to "hopi-connect",
                "MessageType" to "Connect",
                "ProgName" to "hopi",
                "kLibUSBMuxVersion" to 3L,
                "DeviceID" to 3L,
                "PortNumber" to 32498L,
            ),
            read,
        )
        assertNull(parsePlist("not xml".encodeToByteArray()))
        assertNull(parsePlist("<?xml version=\"1.0\"?><html/>".encodeToByteArray()))
        // An external entity a DOCTYPE declares is never read.
        val entity = parsePlist("<?xml version=\"1.0\"?><!DOCTYPE plist [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><plist><string>&x;</string></plist>".encodeToByteArray())
        assertTrue("root" !in entity?.toString().orEmpty(), "the entity was expanded: $entity")
    }

    @Test
    fun devicectlListsPhysicalDevicesOnly() {
        val devices = parseDevicectlDevices(checkNotNull(javaClass.getResource("/fixtures/devicectl-devices.json")).readText())!!
        // The simulator goes; the second device has only the deprecated dictionaries (older Xcodes).
        assertEquals(
            listOf("00008140-001A2B3C4D5E801E Koga’s iPhone iOS 26.0", "00008027-000C1D2E3F40002E Test iPad iOS 17.5.1"),
            devices.map { "${it.udid} ${it.name} ${it.os}" },
        )
        assertNull(parseDevicectlDevices("""{"devices":[]}"""))
        assertNull(parseDevicectlDevices("ERROR: no JSON"))
        assertEquals("a\\u001b[2Jb", parseDevicectlDevices("""{"result":{"devices":[{"properties":{"hardware":{"udid":"U","reality":"physical"},"state":{"name":"a\u001b[2Jb"}}}]}}""")!!.single().name)
    }

    @Test
    fun udidsMatchWhateverTheirCaseAndDash() {
        assertTrue(sameUdid("00008140-001A2B3C4D5E801E", "00008140001a2b3c4d5e801e"))
        assertTrue(!sameUdid("00008140-001A2B3C4D5E801E", "00008140-001A2B3C4D5E801F"))

        // A 25-character UDID that comes as its 24-character USB serial gets its dash back.
        assertEquals(UDID, canonicalUdid("00008140001A2B3C4D5E801E"))
        assertEquals(UDID, canonicalUdid(UDID))
        val older = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(older, canonicalUdid(older), "a 40-character UDID has no dash")
        usbmuxd.attach(3, UDID.replace("-", ""))
        assertEquals(listOf(UDID), Usbmuxd(usbmuxd.socket).devices().map { it.udid }, "devicectl gets the UDID as it writes it")
    }

    private companion object {
        const val UDID = "00008140-001A2B3C4D5E801E"
        const val OTHER = "00008030-000A1B2C3D4E5F60"
    }
}
