package dev.koga.hopi.cli

import dev.koga.hopi.connect.ExitCode
import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.curl.toCurl
import dev.koga.hopi.core.json.parseFlatJsonObject
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ktor.Hopi
import dev.koga.hopi.ktor.HopiLogSink
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The CLI against the real JVM bridge, over a store filled by the real Ktor plugin. The plugin's
 * store injection is internal to `hopi-ktor`, so this uses the process-wide default store,
 * cleared before each test; every test tags its URLs with its own marker.
 */
class CliBridgeTest {
    private val store = HopiStore.default
    private val root: Path = Files.createTempDirectory("hopi-cli-test")
    private val bridge = JvmAgentBridge.start(store, root.resolve("bridge"), app = APP)
    private val bridgeFile = bridge.bridgeFile.toString()
    private val token = parseFlatJsonObject(Files.readString(bridge.bridgeFile))!!["token"] as String
    private val marker = UUID.randomUUID().toString()

    init {
        store.clear()
    }

    @AfterTest
    fun tearDown() {
        bridge.close()
        root.toFile().deleteRecursively()
    }

    private fun cli(vararg argv: String): CliRun = runCapturing(*argv).also { run ->
        assertFalse(token in run.stdout || token in run.stderr, "the token leaked")
    }

    private fun postUser(): HttpTransaction = runBlocking {
        val response = client.post("https://api.example.com/users?m=$marker") {
            header(HttpHeaders.Authorization, "Bearer secret-value")
            header("X-Trace", "it's")
            setBody(TextContent("""{"name":"a'b","tags":["x"]}""", ContentType.Application.Json))
        }
        assertEquals("""{"id":7,"name":"a'b"}""", response.bodyAsText())
        awaitTerminal("/users?m=$marker")
    }

    private fun getPath(path: String): HttpTransaction = runBlocking {
        try {
            client.get("https://api.example.com$path?m=$marker").bodyAsText()
        } catch (_: IOException) {
            // The failing route; the plugin records it as Failed.
        }
        awaitTerminal("$path?m=$marker")
    }

    private suspend fun awaitTerminal(urlSuffix: String): HttpTransaction = withTimeout(10_000) {
        store.transactions.first { list -> list.any { it.url.endsWith(urlSuffix) && it.state.isTerminal } }
            .last { it.url.endsWith(urlSuffix) }
    }

    private fun parse(line: String): JsonObject = Json.parseToJsonElement(line).jsonObject

    @Test
    fun listJsonIsTheBridgeSummaryLines() {
        val post = postUser()
        val missing = getPath("/missing")
        val run = cli("list", "--bridge-file", bridgeFile, "--json")
        assertEquals(ExitCode.OK, run.exitCode, run.toString())
        assertEquals("", run.stderr)
        assertEquals(2, run.lines.size, run.toString())
        val first = parse(run.lines[0])
        assertEquals(
            listOf("type", "id", "method", "url", "code", "ms", "reqBytes", "resBytes", "state", "source"),
            first.keys.toList(),
        )
        assertEquals("transaction", first["type"]!!.jsonPrimitive.content)
        assertEquals(post.id, first["id"]!!.jsonPrimitive.content.toLong())
        assertEquals("POST", first["method"]!!.jsonPrimitive.content)
        assertEquals("201", first["code"]!!.jsonPrimitive.content)
        assertEquals("complete", first["state"]!!.jsonPrimitive.content)
        assertEquals(missing.id, parse(run.lines[1])["id"]!!.jsonPrimitive.content.toLong())
        assertEquals("404", parse(run.lines[1])["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun listLimitKeepsTheNewestAndSinceSkipsOlder() {
        val first = getPath("/a")
        val second = getPath("/b")
        val third = getPath("/c")
        val newest = cli("list", "--bridge-file", bridgeFile, "--json", "--limit", "2")
        assertEquals(listOf(second.id, third.id), newest.lines.map { parse(it)["id"]!!.jsonPrimitive.content.toLong() })
        val since = cli("list", "--bridge-file", bridgeFile, "--json", "--since", first.id.toString())
        assertEquals(listOf(second.id, third.id), since.lines.map { parse(it)["id"]!!.jsonPrimitive.content.toLong() })
    }

    @Test
    fun listHumanIsAlignedColumns() {
        postUser()
        getPath("/boom")
        val run = cli("list", "--bridge-file", bridgeFile)
        assertEquals(ExitCode.OK, run.exitCode, run.toString())
        val (header, post, failed) = run.lines
        assertTrue(header.startsWith("ID"), header)
        val urlColumn = header.indexOf("URL")
        assertEquals(urlColumn, post.indexOf("https://"), run.stdout)
        assertEquals(urlColumn, failed.indexOf("https://"), run.stdout)
        assertTrue(post.contains("POST") && post.contains("201"), post)
        assertTrue(failed.contains("ERR") && failed.contains("(IOException: connection reset"), failed)
    }

    @Test
    fun emptyList() {
        assertEquals(listOf("no transactions"), cli("list", "--bridge-file", bridgeFile).lines)
        val json = cli("list", "--bridge-file", bridgeFile, "--json")
        assertEquals(ExitCode.OK, json.exitCode)
        assertEquals("", json.stdout)
    }

    @Test
    fun getJsonIsTheFullRedactedDetail() {
        val post = postUser()
        val run = cli("get", post.id.toString(), "--bridge-file", bridgeFile, "--json", "--app", APP)
        assertEquals(ExitCode.OK, run.exitCode, run.toString())
        assertEquals(1, run.lines.size)
        val detail = parse(run.lines.single())
        assertEquals(
            listOf("type", "id", "startedAt", "method", "url", "state", "source", "code", "ms", "protocol", "error", "request", "response"),
            detail.keys.toList(),
        )
        val request = detail["request"]!!.jsonObject
        val authorization = request["headers"]!!.jsonArray.map { it.jsonArray }
            .single { it[0].jsonPrimitive.content == HttpHeaders.Authorization }
        assertEquals("<redacted>", authorization[1].jsonPrimitive.content)
        val body = request["body"]!!.jsonObject
        assertEquals("utf8", body["encoding"]!!.jsonPrimitive.content)
        assertEquals("""{"name":"a'b","tags":["x"]}""", body["text"]!!.jsonPrimitive.content)
        val responseBody = detail["response"]!!.jsonObject["body"]!!.jsonObject
        assertEquals("""{"id":7,"name":"a'b"}""", responseBody["text"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("encoding", "text", "truncated", "capturedBytes", "totalBytes", "contentType", "skippedReason"),
            responseBody.keys.toList(),
        )
        assertFalse("secret-value" in run.stdout)
    }

    @Test
    fun getHumanPrettyPrintsJsonBodies() {
        val post = postUser()
        val run = cli("get", post.id.toString(), "--bridge-file", bridgeFile)
        assertEquals(ExitCode.OK, run.exitCode, run.toString())
        val out = run.stdout
        assertTrue(out.startsWith("POST https://api.example.com/users?m=$marker\n"), out)
        assertTrue("  Code      201\n" in out, out)
        assertTrue("  Authorization: <redacted>\n" in out, out)
        assertTrue("Request body (application/json" in out, out)
        assertTrue("{\n  \"name\": \"a'b\",\n  \"tags\": [\n    \"x\"\n  ]\n}" in out, out)
        assertTrue("{\n  \"id\": 7,\n  \"name\": \"a'b\"\n}" in out, out)
        assertFalse("secret-value" in out)
    }

    @Test
    fun curlIsCoresExportOfTheRedactedRequest() {
        val post = postUser()
        val run = cli("curl", post.id.toString(), "--bridge-file", bridgeFile)
        assertEquals(ExitCode.OK, run.exitCode, run.toString())
        assertEquals(toCurl(post, store.redactor) + "\n", run.stdout)
        assertTrue(run.stdout.startsWith("curl -X POST 'https://api.example.com/users?m=$marker'"), run.stdout)
        assertTrue("-H 'Authorization: <redacted>'" in run.stdout, run.stdout)
        assertTrue("-H 'X-Trace: it'\\''s'" in run.stdout, run.stdout)
        assertTrue("--data-raw '{\"name\":\"a'\\''b\",\"tags\":[\"x\"]}'" in run.stdout, run.stdout)
    }

    @Test
    fun notFoundExitsThree() {
        postUser()
        for (command in listOf("get", "curl")) {
            val run = cli(command, "999999999", "--bridge-file", bridgeFile)
            assertEquals(ExitCode.NOT_FOUND, run.exitCode, run.toString())
            assertEquals("", run.stdout)
            assertEquals(1, run.stderr.lines().filter { it.isNotEmpty() }.size, run.stderr)
            assertTrue(run.stderr.startsWith("hopi: transaction 999999999 not found"), run.stderr)
        }
    }

    @Test
    fun clearEmptiesTheTimeline() {
        postUser()
        val clear = cli("clear", "--bridge-file", bridgeFile, "--json")
        assertEquals(ExitCode.OK, clear.exitCode, clear.toString())
        assertTrue(Regex("""\{"type":"ok","through":[1-9][0-9]*\}""").matches(clear.lines.single()), clear.toString())
        assertEquals("", cli("list", "--bridge-file", bridgeFile, "--json").stdout)
        assertEquals(listOf("cleared"), cli("clear", "--bridge-file", bridgeFile).lines)
    }

    @Test
    fun tailStreamsBacklogThenLiveTransactions() {
        val before = postUser()
        val lines = LinkedBlockingQueue<String>()
        val stderr = ByteArrayOutputStream()
        var exit = -1
        val tail = thread(name = "test-tail") {
            exit = runCli(
                listOf("tail", "--bridge-file", bridgeFile, "--json"),
                CliEnvironment(stdout = LineSink(lines), stderr = stderr, shutdownHooks = RecordingShutdownHooks()),
            )
        }
        val backlog = parse(assertNotNull(lines.poll(10, TimeUnit.SECONDS), "no backlog line"))
        assertEquals(before.id, backlog["id"]!!.jsonPrimitive.content.toLong())

        val live = getPath("/live")
        var sawComplete = false
        while (!sawComplete) {
            val line = parse(assertNotNull(lines.poll(10, TimeUnit.SECONDS), "no live line"))
            assertEquals("transaction", line["type"]!!.jsonPrimitive.content)
            assertEquals(live.id, line["id"]!!.jsonPrimitive.content.toLong())
            sawComplete = line["state"]!!.jsonPrimitive.content == "complete"
        }

        bridge.close()
        tail.join(10_000)
        assertFalse(tail.isAlive)
        assertEquals(ExitCode.NOT_REACHABLE, exit)
        assertTrue(stderr.toString().startsWith("hopi: the app closed the bridge connection"), stderr.toString())
    }

    @Test
    fun tailHumanPrintsAHeaderThenRows() {
        postUser()
        val lines = LinkedBlockingQueue<String>()
        val tail = thread(name = "test-tail") {
            runCli(
                listOf("tail", "--bridge-file", bridgeFile),
                CliEnvironment(stdout = LineSink(lines), stderr = ByteArrayOutputStream(), shutdownHooks = RecordingShutdownHooks()),
            )
        }
        val header = assertNotNull(lines.poll(10, TimeUnit.SECONDS))
        val row = assertNotNull(lines.poll(10, TimeUnit.SECONDS))
        assertTrue(header.trimStart().startsWith("ID"), header)
        assertEquals(header.indexOf("URL"), row.indexOf("https://"), "$header\n$row")
        bridge.close()
        tail.join(10_000)
        assertFalse(tail.isAlive)
    }

    @Test
    fun unreachableBridgesExitTwo() {
        val missing = cli("list", "--bridge-file", root.resolve("absent.json").toString())
        assertEquals(ExitCode.NOT_REACHABLE, missing.exitCode)
        assertTrue("not found: process not running or agentBridge = false" in missing.stderr, missing.stderr)

        val closedPort = ServerSocket(0).use { it.localPort }
        val stale = root.resolve("stale.json").also { Files.writeString(it, """{"port":$closedPort,"token":"$token"}""") }
        val staleRun = cli("list", "--bridge-file", stale.toString())
        assertEquals(ExitCode.NOT_REACHABLE, staleRun.exitCode)
        assertTrue("process not running or agentBridge = false" in staleRun.stderr, staleRun.stderr)

        // A wrong token is closed by the bridge before any byte, which reads exactly like no bridge.
        val forged = root.resolve("forged.json").also { Files.writeString(it, """{"port":${bridge.port},"token":"${"0".repeat(64)}"}""") }
        val forgedRun = cli("list", "--bridge-file", forged.toString())
        assertEquals(ExitCode.NOT_REACHABLE, forgedRun.exitCode)
        assertTrue("process not running or agentBridge = false" in forgedRun.stderr, forgedRun.stderr)
        assertEquals("", forgedRun.stdout)
    }

    @Test
    fun anotherAppsBridgeIsRefusedBeforeClearing() {
        val post = postUser()
        val run = cli("clear", "--bridge-file", bridgeFile, "--app", "com.other.app")
        assertEquals(ExitCode.NOT_REACHABLE, run.exitCode)
        assertTrue("the bridge serves $APP, not com.other.app" in run.stderr, run.stderr)
        assertNotNull(store.get(post.id), "clear reached the bridge")
    }

    @Test
    fun usageErrorsExitOneWithOneLine() {
        val run = cli("list", "--bridge-file", bridgeFile, "--limit", "0")
        assertEquals(ExitCode.USAGE, run.exitCode)
        assertEquals("", run.stdout)
        assertEquals(listOf("hopi: --limit must be 1..10000 (see hopi --help)"), run.stderr.lines().filter { it.isNotEmpty() })
    }

    @Test
    fun aClosedStdoutEndsQuietly() {
        postUser()
        val stderr = ByteArrayOutputStream()
        val closed = object : OutputStream() {
            override fun write(b: Int) = throw IOException("Broken pipe")

            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("Broken pipe")
        }
        val code = runCli(
            listOf("list", "--bridge-file", bridgeFile),
            CliEnvironment(stdout = closed, stderr = stderr, shutdownHooks = RecordingShutdownHooks()),
        )
        assertEquals(ExitCode.OK, code)
        assertEquals("", stderr.toString())
    }

    private fun assertNoControlCharacters(text: String) =
        assertFalse(text.any { (it < ' ' && it != '\n') || it in '\u007f'..'\u009f' }, text)

    @Test
    fun humanOutputEscapesControlCharactersFromTheServer() {
        val body = getPath("/hostile")
        val failed = getPath("/hostile-boom")
        val shown = "\\u001b]52;c;ZWNobyBvd25lZA==\\u0007\\u001b[2K\\u001b[1Aforged"

        val get = cli("get", body.id.toString(), "--bridge-file", bridgeFile)
        assertEquals(ExitCode.OK, get.exitCode, get.toString())
        assertNoControlCharacters(get.stdout)
        assertTrue(shown in get.stdout, get.stdout)

        val list = cli("list", "--bridge-file", bridgeFile)
        assertNoControlCharacters(list.stdout)
        assertTrue("(IOException: reset $shown)" in list.stdout, list.stdout)
        val detail = cli("get", failed.id.toString(), "--bridge-file", bridgeFile)
        assertNoControlCharacters(detail.stdout)

        // --json keeps the bridge's own JSON escapes, which are not control characters either.
        val json = cli("get", body.id.toString(), "--bridge-file", bridgeFile, "--json")
        assertNoControlCharacters(json.stdout)
        assertEquals(HOSTILE, parse(json.lines.single())["response"]!!.jsonObject["body"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun curlShowsControlCharactersAsEscapesWithAWarning() {
        val sent = runBlocking {
            client.post("https://api.example.com/echo?m=$marker") { setBody(TextContent("a${HOSTILE}b", ContentType.Text.Plain)) }
            awaitTerminal("/echo?m=$marker")
        }
        val run = cli("curl", sent.id.toString(), "--bridge-file", bridgeFile)
        assertEquals(ExitCode.OK, run.exitCode, run.toString())
        assertNoControlCharacters(run.stdout)
        assertEquals(CONTROL_CHARACTERS_WARNING, run.lines.first())
        assertTrue("--data-raw 'a\\u001b]52;c;" in run.stdout, run.stdout)
    }

    /** Collects stdout line by line as the CLI flushes it. */
    private class LineSink(private val lines: LinkedBlockingQueue<String>) : OutputStream() {
        private val pending = ByteArrayOutputStream()

        override fun write(b: Int) {
            if (b == '\n'.code) {
                lines.put(pending.toString(Charsets.UTF_8))
                pending.reset()
            } else {
                pending.write(b)
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) write(b[i].toInt())
        }
    }

    companion object {
        const val APP = "dev.koga.sample"

        /** An OSC 52 clipboard write, a bell, and an erase-line plus cursor-up, as a server could send them. */
        const val HOSTILE = "\u001b]52;c;ZWNobyBvd25lZA==\u0007\u001b[2K\u001b[1Aforged"

        val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/users" -> respond(
                        """{"id":7,"name":"a'b"}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                    "/boom" -> throw IOException("connection reset")
                    "/hostile" -> respond(HOSTILE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
                    "/hostile-boom" -> throw IOException("reset $HOSTILE")
                    "/missing" -> respond("nope", HttpStatusCode.NotFound)
                    else -> respond("ok", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
                }
            },
        ) {
            install(Hopi) {
                // The default store; the bridge is started by each test, not through the hook.
                logSink = HopiLogSink.Off
            }
        }
    }
}
