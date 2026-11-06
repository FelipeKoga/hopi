@file:OptIn(ExperimentalForeignApi::class)

package dev.koga.hopi.core.bridge

import dev.koga.hopi.core.complete
import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.json.parseFlatJsonObject
import dev.koga.hopi.core.log.emitPlatformLogLine
import dev.koga.hopi.core.pending
import dev.koga.hopi.core.store.HopiStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SO_NOSIGPIPE
import platform.posix.close
import platform.posix.connect
import platform.posix.recv
import platform.posix.send
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.stat
import kotlin.random.Random

class IosAgentBridgeTest {
    /** A blocking loopback client, as the CLI would be. */
    private class Client(port: Int) {
        private val fd = socket(AF_INET, SOCK_STREAM, 0)
        private val pending = StringBuilder()

        init {
            check(fd >= 0) { "socket failed" }
            setIntOption(fd, SO_NOSIGPIPE, 1)
            val result = memScoped {
                val address = loopbackAddress(port)
                connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            }
            check(result == 0) { "connect failed" }
        }

        fun send(line: String) {
            val bytes = (line + "\n").encodeToByteArray()
            val sent = bytes.usePinned { send(fd, it.addressOf(0), bytes.size.convert(), 0) }
            check(sent == bytes.size.toLong()) { "short send" }
        }

        /** The next line, or null when the server closed the connection first. */
        fun readLine(): String? {
            val buffer = ByteArray(4096)
            while (true) {
                val newline = pending.indexOf("\n")
                if (newline >= 0) {
                    val line = pending.substring(0, newline)
                    pending.deleteRange(0, newline + 1)
                    return line
                }
                val count = buffer.usePinned { recv(fd, it.addressOf(0), buffer.size.convert(), 0) }
                if (count <= 0) return null
                pending.append(buffer.decodeToString(0, count.toInt()))
            }
        }

        fun request(line: String): Map<String, Any?> {
            send(line)
            return assertNotNull(parseFlatJsonObject(assertNotNull(readLine()), Int.MAX_VALUE))
        }

        fun close() {
            close(fd)
        }
    }

    private fun mode(path: String): Int = memScoped {
        val info = alloc<stat>()
        check(stat(path, info.ptr) == 0) { "stat $path failed" }
        info.st_mode.toInt() and 0x1FF
    }

    @Test
    fun servesHelloAndListOverLoopbackToAClientWithTheToken() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = HopiStore(scope = scope)
        store.submit(complete(store.nextId()))
        runBlocking { withTimeout(10_000) { store.transactions.first { it.size == 1 } } }
        val bridge = IosAgentBridge.start(store, app = "dev.koga.hopi.test")
        try {
            assertTrue(bridge.bridgeFile.endsWith("/Library/Caches/hopi/bridge.json"), bridge.bridgeFile)
            assertEquals(0x180, mode(bridge.bridgeFile), "bridge.json is 0600")
            assertEquals(0x1C0, mode(bridge.bridgeFile.substringBeforeLast('/')), "its directory is 0700")
            val json = assertNotNull(NSString.stringWithContentsOfFile(bridge.bridgeFile, NSUTF8StringEncoding, null))
            val file = assertNotNull(parseFlatJsonObject(json))
            assertEquals(bridge.port.toLong(), file["port"])
            val token = file["token"] as String
            assertTrue(token.matches(Regex("[0-9a-f]{64}")))

            val client = Client(bridge.port)
            client.send(token)
            assertEquals(
                mapOf("type" to "hello", "protocol" to 1L, "app" to "dev.koga.hopi.test", "platform" to "ios", "count" to 1L, "dropped" to 0L, "rules" to true, "fullBodies" to true, "perf" to false, "perfControl" to false),
                client.request("{\"cmd\":\"hello\"}"),
            )
            assertEquals(1L, client.request("{\"cmd\":\"list\"}")["id"])
            assertEquals("{\"type\":\"end\"}", client.readLine())
            client.close()

            val intruder = Client(bridge.port)
            intruder.send("0".repeat(64))
            intruder.send("{\"cmd\":\"hello\"}")
            assertEquals(null, intruder.readLine(), "a wrong token gets no byte")
            intruder.close()
        } finally {
            bridge.close()
            scope.cancel()
        }
    }

    /**
     * Writes summary lines of several exact sizes, up to about 2 KB, through the os_log shim, to
     * be read back on the host with `log show` (see ARCHITECTURE.md). Each URL is self-describing:
     * after the run marker and the line's own size come 5-char blocks, each the 4-digit index of
     * its block plus `.`, so a cut point reads directly off the output.
     */
    @Test
    fun emitsSummaryLinesForTheOsLogLimitProbe() {
        val marker = "probe-" + Random.nextLong().toULong().toString(16)
        val blocks = (0 until 400).joinToString("") { it.toString().padStart(4, '0') + "." }
        for (size in listOf(1000, 1008, 1012, 1014, 1015, 1016, 1017, 1020, 1024, 4000)) {
            val line = TransactionJson.summaryLogLine(
                pending(1, url = "https://probe.hopi.dev/$marker/$size/$blocks").completed(200, emptyList(), null, 1, null),
                dev.koga.hopi.core.redact.Redactor.Default,
                maxBytes = minOf(size, TransactionJson.MAX_SUMMARY_LINE_BYTES),
            )
            val bytes = line.encodeToByteArray().size
            if (size <= TransactionJson.MAX_SUMMARY_LINE_BYTES) assertEquals(size, bytes) else assertTrue(bytes > 1500)
            emitPlatformLogLine(line)
            println("OSLOG_PROBE marker=$marker size=$size bytes=$bytes")
        }
    }
}
