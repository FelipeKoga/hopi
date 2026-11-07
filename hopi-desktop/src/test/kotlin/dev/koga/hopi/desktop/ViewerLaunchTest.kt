package dev.koga.hopi.desktop

import dev.koga.hopi.connect.Platform
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewerLaunchTest {
    private val simulator = SimulatorDevice("UDID-1", "iPhone 16", "iOS 18.2")
    private val emulator = AndroidDevice("emulator-5554", "device", null, emptyList())
    private val offline = AndroidDevice("emulator-5556", "offline", null, emptyList())
    private val jvm = JvmBridge(Paths.get("/tmp/hopi/1/bridge.json"))

    @Test
    fun parsesWhatTheCliPasses() {
        assertEquals(
            ViewerLaunch("dev.koga.grifo", Platform.Ios, "UDID-1"),
            ViewerLaunch.parse(listOf("--app", "dev.koga.grifo", "--platform", "ios", "--device", "UDID-1")),
        )
    }

    @Test
    fun noUsableValueIsNoLaunch() {
        assertNull(ViewerLaunch.parse(emptyList()))
        assertNull(ViewerLaunch.parse(listOf("--app")))
        assertNull(ViewerLaunch.parse(listOf("--app", "--device", "--platform", "tv")))
    }

    @Test
    fun picksTheOnlyReadyDevice() {
        assertEquals(simulator, ViewerLaunch("a", null, null).pick(listOf(simulator, offline, jvm)))
    }

    @Test
    fun severalDevicesAreLeftToThePickerUnlessNamed() {
        val devices = listOf(simulator, emulator)
        assertNull(ViewerLaunch("a", null, null).pick(devices))
        assertEquals(emulator, ViewerLaunch("a", Platform.Android, null).pick(devices))
        assertEquals(simulator, ViewerLaunch("a", null, "UDID-1").pick(devices))
    }

    @Test
    fun aDeviceThatIsNotThereIsNotPicked() {
        assertNull(ViewerLaunch("a", null, "UDID-2").pick(listOf(simulator)))
    }
}
