package dev.koga.hopi.connect

import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.fake.FakeUsbmuxd
import dev.koga.hopi.fake.FakeXcrun
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * iPhones on USB through [Targets], end to end on the host: usbmuxd is [FakeUsbmuxd] (a Unix socket
 * of the test's own, relaying to host loopback as if it were the phone's), devicectl is
 * [FakeXcrun], and the "phone's" bridge is a real JVM bridge whose `bridge.json` devicectl copies.
 */
class UsbIphoneTargetsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = HopiStore(scope = scope)
    private val root: Path = Files.createTempDirectory("hopi-usb-iphone")
    private val usbmuxd = FakeUsbmuxd()
    private val bridge = JvmAgentBridge.start(store, root.resolve("container/Library/Caches/hopi"), app = APP)
    private val xcrun = FakeXcrun(
        devicectl = FakeXcrun.devicectlList(UDID, "Koga’s iPhone", "26.0"),
        appFile = { device, bundleId, source -> if (device == UDID && bundleId == APP && source == Targets.IOS_BRIDGE_FILE) bridgeJson() else null },
    )
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { it.close() }
        bridge.close()
        usbmuxd.close()
        scope.cancel()
        root.toFile().deleteRecursively()
    }

    private fun bridgeJson(): String = Files.readString(bridge.bridgeFile)

    private fun targets(
        processes: ProcessRunner = xcrun,
        executables: Set<String> = setOf(FakeXcrun.XCRUN),
        env: Map<String, String> = emptyMap(),
        isMac: Boolean = true,
        socket: Path = usbmuxd.socket,
        hooks: ShutdownHooks = NoShutdownHooks,
    ) = Targets(
        HostEnvironment(
            processes = processes,
            env = env,
            userHome = "",
            isMac = isMac,
            isExecutable = { it.toString() in executables },
            shutdownHooks = hooks,
            usbmuxdSocket = socket,
        ),
    )

    private fun record(): HttpTransaction {
        val id = store.nextId()
        val transaction = HttpTransaction.pending(id, 1_700_000_000_000, "GET", "https://api.example.com/users", emptyList(), null)
            .completed(200, listOf("Content-Type" to "application/json"), CapturedBody("{}".encodeToByteArray(), 2, false, "application/json", null), 12, "HTTP/1.1")
        store.submit(transaction)
        runBlocking { withTimeout(10_000) { store.transactions.first { list -> list.any { it.id == id } } } }
        return transaction
    }

    @Test
    fun usbIphonesAreUsbmuxdsUsbEntriesNamedByDevicectlOnce() {
        val targets = targets()
        assertEquals(emptyList(), targets.usbIphones())
        assertEquals(emptyList(), xcrun.devicectlCalls, "devicectl runs only once a device is attached")

        usbmuxd.attach(1, UDID)
        usbmuxd.attach(2, UDID, connection = "Network")
        usbmuxd.attach(4, "-rf", connection = "USB")
        // One per UDID, USB only; a serial that could read as an option never reaches a subprocess.
        assertEquals(listOf("$UDID Koga’s iPhone iOS 26.0"), targets.usbIphones()!!.map(::line))
        assertEquals(1, xcrun.devicectlCalls.size)
        targets.usbIphones()
        assertEquals(1, xcrun.devicectlCalls.size, "a device already described is not described again")

        // One devicectl does not list (not trusted yet, say) is listed unnamed, and asked about again.
        usbmuxd.attach(3, OTHER)
        assertEquals(listOf("$UDID Koga’s iPhone iOS 26.0", "$OTHER null null"), targets.usbIphones()!!.map(::line))
        assertEquals(2, xcrun.devicectlCalls.size)
        xcrun.devicectl = FakeXcrun.devicectlList(OTHER, "Test iPad", "17.5.1")
        assertEquals(listOf("$UDID Koga’s iPhone iOS 26.0", "$OTHER Test iPad iOS 17.5.1"), targets.usbIphones()!!.map(::line))
        targets.usbIphones()
        assertEquals(3, xcrun.devicectlCalls.size, "both named: devicectl is not asked again")

        assertNull(targets(isMac = false).usbIphones(), "not macOS")
        assertNull(targets(executables = emptySet()).usbIphones(), "no Xcode")
        assertNull(targets(socket = Paths.get("/hopi-test/no-usbmuxd")).usbIphones(), "no usbmuxd")
    }

    @Test
    fun aDevicectlThatFailsIsAskedAgainNextTime() {
        val targets = targets()
        usbmuxd.attach(1, UDID)
        xcrun.devicectl = null
        assertEquals(listOf("$UDID null null"), targets.usbIphones()!!.map(::line), "a failing devicectl only leaves the name out")
        xcrun.devicectl = FakeXcrun.devicectlList(UDID, "Koga’s iPhone", "26.0")
        assertEquals(listOf("$UDID Koga’s iPhone iOS 26.0"), targets.usbIphones()!!.map(::line))
        assertEquals(2, xcrun.devicectlCalls.size)
    }

    @Test
    fun openCopiesBridgeJsonWithDevicectlAndRelaysEachConnectionThroughUsbmuxd() {
        usbmuxd.attach(5, UDID)
        val transaction = record()
        val (port, token) = readBridgeFile(bridge.bridgeFile, missing = "no bridge file")
        val endpoint = targets().open(platform = null, app = APP, device = UDID, bridgeFile = null)
        closeables += endpoint
        assertEquals(Platform.Ios, endpoint.platform)
        assertEquals(token, endpoint.token)
        assertNotEquals(port, endpoint.port, "the client connects to the relay on host loopback")
        assertFalse(token in endpoint.toString())

        val copy = xcrun.devicectlCalls.single { it[1] == "device" }
        assertEquals(listOf("devicectl", "device", "copy", "from", "--device", UDID, "--source", Targets.IOS_BRIDGE_FILE, "--destination"), copy.take(9))
        assertEquals(listOf("--domain-type", "appDataContainer", "--domain-identifier", APP), copy.drop(10))
        assertFalse(Files.exists(xcrun.copiedInto.single()), "the copy of bridge.json (it holds the token) outlived open")

        // The token went through and hello came back; this bridge is a JVM one, so the platform check refuses it.
        val refused = assertFailsWith<CliException> { BridgeClient.connect(endpoint, 10_000).use { it.handshake(null) } }
        assertEquals("the bridge reports platform jvm, not ios", refused.message)

        // Two connections at once, as a viewer opens them; each is a Connect of its own.
        val asJvm = BridgeEndpoint(endpoint.port, endpoint.token, Platform.Jvm, APP, "nothing serves $APP")
        BridgeClient.connect(asJvm, 10_000).use { first ->
            BridgeClient.connect(asJvm, 10_000).use { second ->
                first.handshake(BridgeClient.request("list").field("since", 0).field("limit", 10).endObject().toString())
                second.handshake(null)
                assertEquals(transaction.id, first.reply().json.long("id"))
                assertEquals("end", first.reply().type)
                assertEquals(APP, second.hello.json.str("app"))
            }
        }
        assertEquals(List(3) { port }, usbmuxd.connectedPorts, "every connection asked usbmuxd for the bridge's port")

        endpoint.close()
        val after = assertFailsWith<CliException> { BridgeClient.connect(asJvm, 10_000) }
        assertEquals("nothing serves $APP", after.message, "the relay stopped with its endpoint")
    }

    @Test
    fun anAppThatIsNotRunningReadsAsNotRunning() {
        usbmuxd.attach(5, UDID)
        // A bridge.json an app left behind (killed): nothing listens on its port any more.
        val gone = ServerSocket(0, 1, BridgeClient.LOOPBACK).run { localPort.also { close() } }
        xcrun.appFile = { _, _, _ -> """{"port":$gone,"token":"${"ab".repeat(32)}"}""" }
        targets().open(Platform.Ios, APP, UDID, null).use { endpoint ->
            val stopped = assertFailsWith<CliException> { BridgeClient.connect(endpoint, 10_000).use { it.handshake(null) } }
            assertEquals(ExitCode.NOT_REACHABLE, stopped.exitCode)
            assertEquals("app not running or agentBridge = false: no bridge answers for $APP on $UDID (USB)", stopped.message)
        }

        xcrun.appFile = { _, _, _ -> null }
        val missing = assertFailsWith<CliException> { targets().open(Platform.Ios, APP, UDID, null) }
        assertEquals(
            "app not running or agentBridge = false: no bridge answers for $APP on $UDID (USB) " +
                "(devicectl could not copy ${Targets.IOS_BRIDGE_FILE}: ${xcrun.copyError})",
            missing.message,
        )

        xcrun.appFile = { _, _, _ -> "{}" }
        val other = assertFailsWith<CliException> { targets().open(Platform.Ios, APP, UDID, null) }
        assertEquals("${Targets.IOS_BRIDGE_FILE} of $APP on $UDID (USB) is not a Hopi bridge file", other.message)
    }

    @Test
    fun aLinkInTheCopyIsNeitherFollowedNorDeletedThrough() {
        usbmuxd.attach(5, UDID)
        val outside = Files.createDirectories(root.resolve("outside"))
        val kept = Files.writeString(outside.resolve("kept.txt"), "a file of the user's")
        val noFile = "app not running or agentBridge = false: no bridge answers for $APP on $UDID (USB) " +
            "(devicectl could not copy ${Targets.IOS_BRIDGE_FILE}: devicectl reported success but copied no file)"

        // A directory in bridge.json's place that holds a link out of the copy's directory.
        xcrun.copyWith = { destination ->
            Files.createDirectory(destination)
            Files.createSymbolicLink(destination.resolve("x"), outside)
        }
        assertEquals(noFile, assertFailsWith<CliException> { targets().open(Platform.Ios, APP, UDID, null) }.message)
        assertTrue(Files.exists(kept), "the cleanup deleted through the link")
        assertFalse(Files.exists(xcrun.copiedInto.last()))

        // bridge.json itself a link to a host file that holds a valid bridge file: never read.
        val hostFile = Files.writeString(outside.resolve("bridge.json"), bridgeJson())
        xcrun.copyWith = { destination -> Files.createSymbolicLink(destination, hostFile) }
        assertEquals(noFile, assertFailsWith<CliException> { targets().open(Platform.Ios, APP, UDID, null) }.message)
        assertTrue(Files.exists(hostFile))
        assertFalse(Files.exists(xcrun.copiedInto.last()))
    }

    @Test
    fun aShutdownDuringTheCopyStillDeletesIt() {
        usbmuxd.attach(5, UDID)
        val hooks = RecordingHooks()
        xcrun.copyWith = { destination ->
            Files.writeString(destination, bridgeJson())
            // A SIGINT now: the JVM runs its shutdown hooks, never open's finally.
            hooks.active.single().run()
            assertFalse(Files.exists(destination.parent), "the hook left the copy of the token behind")
        }
        assertFailsWith<CliException> { targets(hooks = hooks).open(Platform.Ios, APP, UDID, null) }
        assertEquals(1, hooks.added.size)
        assertEquals(emptyList(), hooks.active, "the hook outlived the copy")
    }

    @Test
    fun deviceSelectsAmongSeveralIphones() {
        usbmuxd.attach(5, UDID)
        usbmuxd.attach(6, OTHER)
        xcrun.appFile = { device, _, _ -> if (device == OTHER) bridgeJson() else null }
        val several = assertFailsWith<CliException> { targets().open(null, APP, null, null) }
        assertEquals(ExitCode.USAGE, several.exitCode)
        assertEquals("several USB iPhones: pass --device <id> ($UDID (USB), $OTHER (USB))", several.message)

        // Another case and no dash still name it; devicectl gets the attached phone's UDID, not that spelling.
        targets().open(null, APP, OTHER.replace("-", "").lowercase(), null).close()
        assertEquals(OTHER, xcrun.devicectlCalls.last { it[1] == "device" }[5])

        val unknown = "00008030-FFFFFFFFFFFFFFFF"
        val named = assertFailsWith<CliException> { targets().open(Platform.Ios, APP, unknown, null) }
        assertEquals("iOS device $unknown not found (available: $UDID (USB), $OTHER (USB))", named.message)
        val inferred = assertFailsWith<CliException> { targets().open(null, APP, unknown, null) }
        assertEquals("device $unknown is neither listed by adb devices nor a booted simulator", inferred.message)
    }

    @Test
    fun anAttachedIphoneChangesNoTargetThatWasFoundBefore() {
        usbmuxd.attach(5, UDID)
        // A booted simulator stays the target of an iOS command that names no device.
        xcrun.simulators = checkNotNull(javaClass.getResource("/fixtures/simctl-booted.json")).readText()
        val simulator = assertFailsWith<CliException> { targets().open(null, APP, null, null) }
        assertEquals("$APP is not installed on simulator iPhone 16 (90E767A1-5F25-4E8E-B1B2-D1A8F53295FA)", simulator.message)
        assertEquals(emptyList(), xcrun.devicectlCalls)
        // ...unless --device names the iPhone.
        targets().open(null, APP, UDID, null).close()
        assertEquals(1, xcrun.devicectlCalls.count { it[1] == "device" })
        // With an iPhone attached, a --device that names neither says so in the iOS words, listing both.
        val unknown = "00008030-FFFFFFFFFFFFFFFF"
        val neither = assertFailsWith<CliException> { targets().open(Platform.Ios, APP, unknown, null) }
        assertEquals("iOS device $unknown not found (available: iPhone 16 (90E767A1-5F25-4E8E-B1B2-D1A8F53295FA), $UDID (USB))", neither.message)

        // A ready adb device stays the target of a command that names no platform.
        val adb = "/sdk/platform-tools/adb"
        val withAdb = FakeXcrun(
            others = { command ->
                if (command.drop(1) == listOf("devices", "-l")) {
                    ProcessResult(0, "List of devices attached\nemulator-5554          device product:sdk model:Pixel_8\n", "")
                } else {
                    ProcessResult(1, "", "error: cannot bind listener: Operation not permitted")
                }
            },
        )
        val android = assertFailsWith<CliException> {
            targets(processes = withAdb, executables = setOf(FakeXcrun.XCRUN, adb), env = mapOf("ANDROID_HOME" to "/sdk")).open(null, APP, null, null)
        }
        assertEquals("adb forward on emulator-5554 failed: error: cannot bind listener: Operation not permitted", android.message)
    }

    @Test
    fun anUnpluggedIphoneIsNamedAsTheReasonTheConnectionEnded() {
        usbmuxd.attach(5, UDID)
        targets().open(Platform.Ios, APP, UDID, null).use { endpoint ->
            assertNull(endpoint.transportLost(), "while it is attached, the app closed the connection")
            val watched = BridgeEndpoint(endpoint.port, endpoint.token, Platform.Jvm, APP, "nothing", transportLost = endpoint.transportLost)
            BridgeClient.connect(watched, 10_000).use { client ->
                client.handshake(null)
                client.waitForever()
                usbmuxd.detach(UDID)
                val lost = assertFailsWith<CliException> { client.reply() }
                assertEquals(
                    "$UDID (USB) is no longer attached (usbmuxd does not list it); the app may still be running: reconnect it and run the command again",
                    lost.message,
                )
            }
        }
        assertTrue(usbmuxd.requests.count { it.messageType == "ListDevices" } >= 3)
    }

    private object NoShutdownHooks : ShutdownHooks {
        override fun add(hook: Thread) = Unit

        override fun remove(hook: Thread) = Unit
    }

    private class RecordingHooks : ShutdownHooks {
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

    private companion object {
        const val APP = "dev.koga.hopi.sample"
        const val UDID = "00008140-001A2B3C4D5E801E"
        const val OTHER = "00008030-000A1B2C3D4E5F60"
    }
}

/** One USB iPhone as `<udid> <name> <os>`. */
private fun line(phone: UsbIphone) = "${phone.udid} ${phone.name} ${phone.os}"
