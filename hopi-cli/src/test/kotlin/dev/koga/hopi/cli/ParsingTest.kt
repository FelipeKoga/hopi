package dev.koga.hopi.cli

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsingTest {
    @Test
    fun adbDevicesSkipsChatterAndKeepsStates() {
        val devices = parseAdbDevices(fixture("adb-devices.txt"))
        assertEquals(listOf("emulator-5554", "R58M123ABCD", "192.168.1.20:5555"), devices.map { it.serial })
        assertEquals(listOf("device", "unauthorized", "offline"), devices.map { it.state })
        assertEquals(listOf("sdk_gphone64_arm64", null, "Pixel_8_Pro"), devices.map { it.model })
        assertEquals(listOf(true, false, false), devices.map { it.isReady })
    }

    @Test
    fun adbDevicesEmpty() {
        assertEquals(emptyList(), parseAdbDevices(fixture("adb-devices-empty.txt")))
        assertEquals(emptyList(), parseAdbDevices(""))
    }

    @Test
    fun forwardPortIsDigitsOnly() {
        assertEquals(51234, parseForwardPort("51234\n"))
        assertEquals(1, parseForwardPort("1"))
        assertNull(parseForwardPort(""))
        assertNull(parseForwardPort("0"))
        assertNull(parseForwardPort("65536"))
        assertNull(parseForwardPort("123456"))
        assertNull(parseForwardPort("error: cannot bind"))
        assertNull(parseForwardPort("51234 51235"))
    }

    @Test
    fun forwardListAsSerialAndLocalPairs() {
        // As `adb forward --list` printed it on an API 35 emulator (platform-tools 35.0.2).
        val output = "emulator-5554 tcp:54881 localabstract:hopi.dev.koga.hopi.sample\n" +
            "emulator-5554 tcp:55266 localabstract:hopi.dev.koga.hopi\n\n"
        assertEquals(listOf("emulator-5554" to "tcp:54881", "emulator-5554" to "tcp:55266"), parseForwards(output))
        assertEquals(emptyList(), parseForwards(""))
    }

    @Test
    fun simctlBootedKeepsOnlyBootedDevices() {
        val simulators = parseBootedSimulators(fixture("simctl-booted.json"))!!
        assertEquals(1, simulators.size)
        val simulator = simulators.single()
        assertEquals("90E767A1-5F25-4E8E-B1B2-D1A8F53295FA", simulator.udid)
        assertEquals("iPhone 16", simulator.name)
        assertEquals("iOS 18.2", simulator.os)
        assertEquals(emptyList(), parseBootedSimulators(fixture("simctl-none.json")))
    }

    @Test
    fun simctlRejectsOtherShapes() {
        assertNull(parseBootedSimulators(""))
        assertNull(parseBootedSimulators("not json"))
        assertNull(parseBootedSimulators("[]"))
        assertNull(parseBootedSimulators("""{"runtimes":[]}"""))
    }

    @Test
    fun runtimeNames() {
        assertEquals("iOS 18.2", runtimeName("com.apple.CoreSimulator.SimRuntime.iOS-18-2"))
        assertEquals("watchOS 11.0", runtimeName("com.apple.CoreSimulator.SimRuntime.watchOS-11-0"))
        assertNull(runtimeName("garbage"))
    }

    @Test
    fun bridgeFileNeedsPortAndFullToken() {
        val dir = Files.createTempDirectory("hopi-cli-parse")
        try {
            val file = dir.resolve("bridge.json")
            val token = "0123456789abcdef".repeat(4)
            Files.writeString(file, """{"port":51234,"token":"$token"}""")
            assertEquals(51234 to token, readBridgeFile(file, missing = "missing"))

            for (bad in listOf(
                """{"port":0,"token":"$token"}""",
                """{"port":70000,"token":"$token"}""",
                """{"port":"51234","token":"$token"}""",
                """{"port":51234,"token":"${token.uppercase()}"}""",
                """{"port":51234,"token":"${token.dropLast(1)}"}""",
                """{"port":51234}""",
                "not json",
            )) {
                Files.writeString(file, bad)
                val failure = assertFailsWith<CliException> { readBridgeFile(file, missing = "missing") }
                assertEquals(ExitCode.NOT_REACHABLE, failure.exitCode)
                // The message names the file, never its content.
                assertTrue(token !in failure.message.orEmpty(), failure.message)
            }
            val missing = assertFailsWith<CliException> { readBridgeFile(dir.resolve("absent.json"), missing = "app not running") }
            assertEquals("app not running", missing.message)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun adbIsLocatedInDocumentedOrder() {
        val sdk = Paths.get("/sdk/platform-tools/adb")
        val mac = Paths.get("/Users/dev/Library/Android/sdk/platform-tools/adb")
        val onPath = Paths.get("/opt/bin/adb")
        fun locate(env: Map<String, String>, executables: Set<java.nio.file.Path>) = locateAdb(
            CliEnvironment(
                stdout = java.io.ByteArrayOutputStream(),
                stderr = java.io.ByteArrayOutputStream(),
                env = env,
                userHome = "/Users/dev",
                isExecutable = { it in executables },
            ),
        )
        val env = mapOf("ANDROID_HOME" to "/sdk", "PATH" to "/usr/bin:/opt/bin")
        assertEquals(sdk, locate(env, setOf(sdk, mac, onPath)))
        assertEquals(mac, locate(env, setOf(mac, onPath)))
        assertEquals(onPath, locate(env, setOf(onPath)))
        assertNull(locate(env, emptySet()))
    }
}
