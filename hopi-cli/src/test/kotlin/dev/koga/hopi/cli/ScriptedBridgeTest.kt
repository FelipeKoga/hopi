package dev.koga.hopi.cli

import dev.koga.hopi.connect.BridgeClient
import dev.koga.hopi.connect.ExitCode
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The CLI against a bridge that answers with scripted lines, for replies the real bridge sends
 * only under load (`SLOW_CLIENT`, drops) or that a hostile peer could send.
 */
class ScriptedBridgeTest {
    private val root: Path = Files.createTempDirectory("hopi-cli-scripted")
    private val servers = mutableListOf<ServerSocket>()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.close() }
        root.toFile().deleteRecursively()
    }

    /**
     * Accepts one connection, reads the token and [requests] lines (hello plus the command's own),
     * writes [replies] and closes. Returns the bridge file to pass as `--bridge-file`.
     */
    private fun bridge(vararg replies: String, requests: Int = 2): String {
        val server = ServerSocket(0, 8, BridgeClient.LOOPBACK).also { servers += it }
        thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    repeat(1 + requests) { input.readLine() }
                    socket.getOutputStream().write(replies.joinToString("") { "$it\n" }.encodeToByteArray())
                    socket.getOutputStream().flush()
                }
            } catch (_: IOException) {
                // Closed by tearDown.
            }
        }
        val file = root.resolve("bridge-${server.localPort}.json")
        Files.writeString(file, """{"port":${server.localPort},"token":"${"a".repeat(64)}"}""")
        return file.toString()
    }

    private fun hello(dropped: Long = 0, app: String = APP) =
        """{"type":"hello","protocol":1,"app":"$app","platform":"jvm","count":0,"dropped":$dropped}"""

    @Test
    fun slowClientNamesTheIdToTailFromAgain() {
        val file = bridge(
            hello(),
            """{"type":"transaction","id":42,"method":"GET","url":"https://a.example/x","code":null,"ms":null,"reqBytes":0,"resBytes":0,"state":"pending"}""",
            """{"type":"error","code":"SLOW_CLIENT","message":"client fell behind","since":41}""",
        )
        val run = runCapturing("tail", "--bridge-file", file, "--json")
        assertEquals(ExitCode.NOT_REACHABLE, run.exitCode, run.toString())
        assertEquals(1, run.lines.size, run.toString())
        assertEquals(
            "hopi: the bridge disconnected this client for reading too slowly (SLOW_CLIENT): " +
                "run hopi tail --since 41 to resume without losing a transaction\n",
            run.stderr,
        )
    }

    @Test
    fun listReportsDropsMadeBeforeItConnected() {
        val json = runCapturing("list", "--bridge-file", bridge(hello(dropped = 3), """{"type":"end"}"""), "--json")
        assertEquals(ExitCode.OK, json.exitCode, json.toString())
        assertEquals(listOf("""{"type":"notice","dropped":3}"""), json.lines)

        val human = runCapturing("list", "--bridge-file", bridge(hello(dropped = 3), """{"type":"end"}"""))
        assertEquals(ExitCode.OK, human.exitCode, human.toString())
        assertEquals("no transactions", human.lines.first())
        assertTrue(human.lines.last().startsWith("! the app's store has dropped 3 transactions"), human.stdout)

        val none = runCapturing("list", "--bridge-file", bridge(hello(), """{"type":"end"}"""), "--json")
        assertEquals("", none.stdout)
    }

    @Test
    fun controlCharactersFromThePeerNeverReachTheTerminalInErrors() {
        val hostile = "\\u001b]52;c;ZWNobyBvd25lZA==\\u0007\\u001b[2K"
        val run = runCapturing("list", "--bridge-file", bridge(hello(app = hostile)), "--app", APP)
        assertEquals(ExitCode.NOT_REACHABLE, run.exitCode, run.toString())
        assertFalse(run.stderr.any { it < ' ' && it != '\n' }, run.stderr)
        assertTrue("the bridge serves \\u001b]52;c;ZWNobyBvd25lZA==\\u0007\\u001b[2K, not $APP" in run.stderr, run.stderr)
    }

    private companion object {
        const val APP = "dev.koga.sample"
    }
}
