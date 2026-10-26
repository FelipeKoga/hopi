package dev.koga.hopi.core.bridge

import dev.koga.hopi.core.complete
import dev.koga.hopi.core.json.parseFlatJsonObject
import dev.koga.hopi.core.store.HopiStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class JvmAgentBridgeTest {
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = HopiStore(scope = storeScope)
    private val root: Path = Files.createTempDirectory("hopi-bridge-test")
    private val bridges = mutableListOf<JvmAgentBridge>()
    private val sockets = mutableListOf<Socket>()

    @AfterTest
    fun tearDown() {
        sockets.forEach { it.close() }
        bridges.forEach { it.close() }
        storeScope.cancel()
        root.toFile().deleteRecursively()
    }

    private fun start(directory: Path = root.resolve("app").resolve("1234")) =
        JvmAgentBridge.start(store, directory, app = "dev.koga.sample").also { bridges += it }

    private fun bridgeFile(bridge: JvmAgentBridge): Map<String, Any?> =
        assertNotNull(parseFlatJsonObject(Files.readString(bridge.bridgeFile)))

    private inner class Client(bridge: JvmAgentBridge, token: String? = bridgeFile(bridge)["token"] as String) {
        val socket = Socket(JvmAgentBridge.LOOPBACK, bridge.port).also {
            sockets += it
            it.soTimeout = 10_000
        }
        private val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

        init {
            if (token != null) send(token)
        }

        fun send(line: String) {
            socket.getOutputStream().write((line + "\n").encodeToByteArray())
            socket.getOutputStream().flush()
        }

        fun readLine(): String? = input.readLine()

        fun request(line: String): Map<String, Any?> {
            send(line)
            return assertNotNull(parseFlatJsonObject(assertNotNull(readLine()), Int.MAX_VALUE))
        }

        /**
         * -1 when the server closed the connection without sending anything. Closing with unread
         * input makes TCP answer with a reset, which carries no data either.
         */
        fun firstByte(): Int = try {
            socket.getInputStream().read()
        } catch (e: SocketException) {
            if (e.message?.contains("reset") == true) -1 else throw e
        }
    }

    @Test
    fun bindsLoopbackOnlyAndAdvertisesPortAndTokenInAnOwnerOnlyFile() {
        val bridge = start()
        assertEquals("127.0.0.1", bridge.address.hostAddress)
        assertTrue(bridge.address.isLoopbackAddress)

        val dir = bridge.bridgeFile.parent
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(bridge.bridgeFile)))
        assertEquals(listOf(JvmAgentBridge.BRIDGE_FILE_NAME), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
        val file = bridgeFile(bridge)
        assertEquals(setOf("port", "token"), file.keys)
        assertEquals(bridge.port.toLong(), file["port"])
        assertTrue((file["token"] as String).matches(Regex("[0-9a-f]{64}")))

        // Every non-loopback IPv4 address of this machine refuses the connection.
        val external = NetworkInterface.networkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
        for (address in external) {
            assertFailsWith<ConnectException>(address.hostAddress) {
                Socket().use { it.connect(InetSocketAddress(address, bridge.port), 2_000) }
            }
        }
    }

    @Test
    fun aRestartReplacesTheBridgeFileAtomically() {
        val directory = root.resolve("same")
        val first = start(directory)
        val firstToken = bridgeFile(first)["token"]
        first.close()
        assertFalse(Files.exists(first.bridgeFile))
        val second = start(directory)
        assertEquals(second.port.toLong(), bridgeFile(second)["port"])
        assertTrue(firstToken != bridgeFile(second)["token"])
        // An existing directory with wider permissions is tightened.
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"))
        second.close()
        start(directory)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)))
    }

    @Test
    fun servesSeveralClientsAtOnce() {
        store.submit(complete(store.nextId()))
        runBlocking { withTimeout(10_000) { store.transactions.first { it.size == 1 } } }
        val bridge = start()
        val tailer = Client(bridge)
        tailer.send("{\"cmd\":\"tail\"}")
        assertEquals(1L, parseFlatJsonObject(assertNotNull(tailer.readLine()))?.get("id"))
        val clients = List(3) { Client(bridge) }
        for (client in clients) {
            val hello = client.request("{\"cmd\":\"hello\"}")
            assertEquals(mapOf("type" to "hello", "protocol" to 1L, "app" to "dev.koga.sample", "platform" to "jvm", "count" to 1L, "dropped" to 0L, "rules" to true, "perf" to false, "perfControl" to false), hello)
        }
        for (client in clients) {
            client.send("{\"cmd\":\"list\"}")
            assertEquals(1L, parseFlatJsonObject(assertNotNull(client.readLine()))?.get("id"))
            assertEquals("{\"type\":\"end\"}", client.readLine())
        }
        store.submit(complete(store.nextId()))
        assertEquals(2L, parseFlatJsonObject(assertNotNull(tailer.readLine()))?.get("id"))
    }

    @Test
    fun aWrongOrMissingTokenGetsNoByteBeforeTheClose() {
        val bridge = start()
        val wrong = Client(bridge, token = "0".repeat(64))
        wrong.send("{\"cmd\":\"hello\"}")
        assertEquals(-1, wrong.firstByte())

        val missing = Client(bridge, token = null)
        missing.send("{\"cmd\":\"hello\"}")
        assertEquals(-1, missing.firstByte())

        val silent = Client(bridge, token = null)
        silent.socket.shutdownOutput()
        assertEquals(-1, silent.firstByte())

        // The bridge keeps serving authorized clients.
        assertEquals("hello", Client(bridge).request("{\"cmd\":\"hello\"}")["type"])
    }

    @Test
    fun connectionsBeyondTheCapAreClosedAtOnce() {
        val bridge = start()
        // Silent, unauthenticated connections hold their slots.
        val held = List(AgentBridgeServer.MAX_CLIENTS) { Client(bridge, token = null) }
        val rejected = Client(bridge)
        assertEquals(-1, rejected.firstByte())
        assertEquals(AgentBridgeServer.MAX_CLIENTS, held.size)
    }

    @Test
    fun closeDisconnectsClientsAndStopsListening() {
        val bridge = start()
        val client = Client(bridge)
        assertEquals("hello", client.request("{\"cmd\":\"hello\"}")["type"])
        bridge.close()
        assertNull(client.readLine())
        assertFailsWith<ConnectException> { Socket(JvmAgentBridge.LOOPBACK, bridge.port) }
        assertFalse(Files.exists(bridge.bridgeFile))
    }

    @Test
    fun theProcessBridgeRemovesItsFileAndDirectoryWhenTheJvmExits() {
        val directory = root.resolve("hopi").resolve("4242")
        val hooks = mutableListOf<Thread>()
        val bridge = startProcessBridge(store, directory, ownsDirectory = true) { hooks += it }
        bridges += bridge
        assertTrue(Files.exists(bridge.bridgeFile))
        assertEquals(1, hooks.size, "no shutdown hook registered")

        // What the JVM does on a normal exit or SIGTERM.
        hooks.single().run()
        assertFalse(Files.exists(bridge.bridgeFile))
        assertFalse(Files.exists(directory))
        assertFailsWith<ConnectException> { Socket(JvmAgentBridge.LOOPBACK, bridge.port) }
    }

    @Test
    fun aDirectoryNamedByTheUserIsKeptWhenTheJvmExits() {
        val directory = root.resolve("chosen")
        val hooks = mutableListOf<Thread>()
        bridges += startProcessBridge(store, directory, ownsDirectory = false) { hooks += it }
        hooks.single().run()
        assertTrue(Files.isDirectory(directory))
        assertFalse(Files.exists(directory.resolve(JvmAgentBridge.BRIDGE_FILE_NAME)))
    }
}
