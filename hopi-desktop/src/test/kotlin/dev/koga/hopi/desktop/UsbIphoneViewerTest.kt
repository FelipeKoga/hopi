package dev.koga.hopi.desktop

import dev.koga.hopi.connect.HostEnvironment
import dev.koga.hopi.connect.Targets
import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.fake.FakeUsbmuxd
import dev.koga.hopi.fake.FakeXcrun
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** USB iPhones in the picker and a connect to one, through a fake usbmuxd (a Unix socket of the test's own) and a fake devicectl. */
class UsbIphoneViewerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = HopiStore(scope = scope)
    private val root: Path = Files.createTempDirectory("hopi-desktop-iphone")
    private val usbmuxd = FakeUsbmuxd()
    private val bridge = JvmAgentBridge.start(store, root.resolve("phone"), app = APP)
    private val xcrun = FakeXcrun(
        devicectl = FakeXcrun.devicectlList(UDID, "Koga’s iPhone", "26.0"),
        appFile = { device, bundleId, _ -> if (device == UDID && bundleId == APP) Files.readString(bridge.bridgeFile) else null },
    )
    private val targets = Targets(
        HostEnvironment(
            processes = xcrun,
            env = emptyMap(),
            userHome = "",
            isMac = true,
            isExecutable = { it.toString() == FakeXcrun.XCRUN },
            shutdownHooks = NoHooks,
            streams = { throw IOException("no long-running subprocess in this test") },
            usbmuxdSocket = usbmuxd.socket,
        ),
    )

    @AfterTest
    fun tearDown() {
        bridge.close()
        usbmuxd.close()
        scope.cancel()
        root.toFile().deleteRecursively()
    }

    @Test
    fun thePickerListsUsbIphonesNamedByDevicectl() {
        val noAdb = listOf("adb not found: set ANDROID_HOME, or put adb on PATH")
        val empty = discover(targets, jvmRoot = null)
        assertEquals(emptyList(), empty.devices)
        assertEquals(noAdb, empty.notes)

        usbmuxd.attach(5, UDID)
        val discovery = discover(targets, jvmRoot = null)
        assertEquals(listOf(UsbIphoneDevice(UDID, "Koga’s iPhone", "iOS 26.0")), discovery.devices)
        assertEquals(noAdb, discovery.notes)
        assertEquals("Koga’s iPhone (USB), iOS 26.0", discovery.devices.single().label)
        assertEquals("$UDID (USB)", UsbIphoneDevice(UDID, name = null, os = null).label)
    }

    @Test
    fun aUsbIphoneIsReachedThroughUsbmuxdAndReconnectedByHand() {
        usbmuxd.attach(5, UDID)
        val phone = UsbIphoneDevice(UDID, "Koga’s iPhone", "iOS 26.0")
        Viewer(targets, jvmBridgeRoot = null).use { viewer ->
            viewer.start()
            viewer.connect(Target(phone, APP))
            // The token was accepted through usbmuxd and hello came back; a JVM bridge fails the platform check.
            val refused = viewer.status.await { it is ViewerStatus.Waiting } as ViewerStatus.Waiting
            assertEquals(WaitKind.Failed, refused.kind)
            assertEquals("the bridge reports platform jvm, not ios", refused.message)
            assertEquals(listOf(bridge.port), usbmuxd.connectedPorts)

            // No bridge.json on the phone: the app is stopped, and nothing on the host says when it starts.
            // devicectl's reason is shown: it tells a stopped app from one not installed or a locked phone.
            xcrun.appFile = { _, _, _ -> null }
            viewer.reconnect()
            val stopped = viewer.status.await { it is ViewerStatus.Waiting && it.kind == WaitKind.AppStopped } as ViewerStatus.Waiting
            assertEquals(
                "App stopped: Reconnect (⌘R) once $APP runs on the iPhone (devicectl could not copy ${Targets.IOS_BRIDGE_FILE}: ${xcrun.copyError})",
                statusText(stopped),
            )
            // A bridge.json left behind, with nothing listening on its port: no devicectl reason to show.
            val leftBehind = ViewerStatus.Waiting(Target(phone, APP), WaitKind.AppStopped, "app not running or agentBridge = false: no bridge answers for $APP on $UDID (USB)")
            assertEquals("App stopped: Reconnect (⌘R) once $APP runs on the iPhone", statusText(leftBehind))

            // Unplugged: the phone is named, never a simulator's "not found".
            usbmuxd.detach(UDID)
            viewer.reconnect()
            val unplugged = viewer.status.await { it is ViewerStatus.Waiting && it.kind == WaitKind.Failed } as ViewerStatus.Waiting
            assertEquals("$UDID (USB) is not attached (usbmuxd does not list it): plug it in, then reconnect", unplugged.message)
        }
    }

    private companion object {
        const val APP = "dev.koga.hopi.sample"
        const val UDID = "00008140-001A2B3C4D5E801E"
    }
}
