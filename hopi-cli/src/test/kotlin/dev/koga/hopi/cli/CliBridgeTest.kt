package dev.koga.hopi.cli

import dev.koga.hopi.connect.ExitCode
import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.capture.FullBodies
import dev.koga.hopi.core.curl.toCurl
import dev.koga.hopi.core.json.parseFlatJsonObject
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ktor.Hopi
import dev.koga.hopi.ktor.HopiLogSink
import dev.koga.hopi.start.Hopi as HopiStart
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
 * The CLI against the real JVM bridge, over a store filled by the real Ktor plugin. The JVM entry
 * (`hopi-start`'s `Hopi.start`, first in the companion) registers the plugin's recorder, which
 * writes to the process-wide default store, cleared before each test; every test tags its URLs with
 * its own marker. The bridge is each test's own.
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
    fun listFiltersByUrlMethodAndStatusBeforeTheLimit() {
        val post = postUser()
        val missing = getPath("/missing")
        val failed = getPath("/boom")
        getPath("/a")
        fun ids(vararg filter: String) =
            cli("list", "--bridge-file", bridgeFile, "--json", *filter).lines.map { parse(it)["id"]!!.jsonPrimitive.content.toLong() }
        assertEquals(listOf(missing.id, failed.id), ids("--status", "4xx,failed"))
        assertEquals(listOf(post.id), ids("--method", "post"))
        assertEquals(listOf(missing.id), ids("--url", "/MISSING"))
        assertEquals(listOf(post.id), ids("--status", "201", "--url", "users"))
        // The newest match, not a match among the newest.
        assertEquals(listOf(failed.id), ids("--status", "4xx,failed", "--limit", "1"))
        assertEquals(emptyList<Long>(), ids("--status", "5xx"))
        assertEquals(listOf("no matching transactions"), cli("list", "--bridge-file", bridgeFile, "--status", "5xx").lines)
    }

    @Test
    fun appsFindsTheJvmBridgeAndCommandsUseTheOnlyAppWithoutApp() {
        // A file a killed process left behind: nothing answers on its port.
        val dead = ServerSocket(0).use { it.localPort }
        Files.createDirectories(root.resolve("dead"))
        Files.writeString(root.resolve("dead/bridge.json"), """{"port":$dead,"token":"${"b".repeat(64)}"}""")

        val apps = runCapturing("apps", "--json", jvmBridgeRoot = root)
        assertEquals(ExitCode.OK, apps.exitCode, apps.toString())
        val line = parse(apps.lines.single())
        assertEquals(APP, line["app"]!!.jsonPrimitive.content)
        assertEquals("jvm", line["platform"]!!.jsonPrimitive.content)
        assertEquals(bridgeFile, line["bridgeFile"]!!.jsonPrimitive.content)
        assertTrue(runCapturing("apps", jvmBridgeRoot = root).lines.let { it.size == 2 && it[0].startsWith("APP") && it[1].startsWith(APP) })

        val post = postUser()
        val list = runCapturing("list", "--json", jvmBridgeRoot = root)
        assertEquals(ExitCode.OK, list.exitCode, list.toString())
        assertEquals(post.id, parse(list.lines.single())["id"]!!.jsonPrimitive.content.toLong())

        val narrowed = runCapturing("list", "--json", "--platform", "jvm", "--app", APP, jvmBridgeRoot = root)
        assertEquals(ExitCode.OK, narrowed.exitCode, narrowed.toString())
        assertEquals(ExitCode.NOT_REACHABLE, runCapturing("list", "--platform", "jvm", "--app", "dev.koga.absent", jvmBridgeRoot = root).exitCode)

        val none = runCapturing("list", "--json", jvmBridgeRoot = root.resolve("dead"))
        assertEquals(ExitCode.NOT_REACHABLE, none.exitCode, none.toString())
        assertTrue("no app with a Hopi bridge found" in none.stderr, none.stderr)
        assertEquals(listOf("no app with a Hopi bridge found: run a debug build of the app (hopi devices lists the targets)"), runCapturing("apps", jvmBridgeRoot = root.resolve("dead")).lines)

        val second = JvmAgentBridge.start(HopiStore(), root.resolve("second"), app = "dev.koga.other")
        try {
            val several = runCapturing("list", jvmBridgeRoot = root)
            assertEquals(ExitCode.USAGE, several.exitCode, several.toString())
            assertTrue("several apps have a Hopi bridge, pass one: --app $APP --bridge-file $bridgeFile" in several.stderr, several.stderr)
            assertTrue("--app dev.koga.other --bridge-file" in several.stderr, several.stderr)
        } finally {
            second.close()
        }
    }

    @Test
    fun exportWritesHarAndNdjsonWithHeadersAndBodies() {
        val post = postUser()
        val missing = getPath("/missing")
        val har = cli("export", "--bridge-file", bridgeFile)
        assertEquals(ExitCode.OK, har.exitCode, har.toString())
        assertEquals("", har.stderr)
        val log = parse(har.stdout)["log"]!!.jsonObject
        assertEquals("1.2", log["version"]!!.jsonPrimitive.content)
        val entries = log["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(post.id, missing.id), entries.map { it["_hopiId"]!!.jsonPrimitive.content.toLong() })
        val request = entries[0]["request"]!!.jsonObject
        assertEquals("POST", request["method"]!!.jsonPrimitive.content)
        assertTrue(request["headers"]!!.jsonArray.isNotEmpty())
        assertEquals("""{"id":7,"name":"a'b"}""", entries[0]["response"]!!.jsonObject["content"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        // The app redacted the token; the export shows what the bridge sent.
        assertFalse("secret-value" in har.stdout)

        val filtered = parse(cli("export", "--bridge-file", bridgeFile, "--status", "4xx").stdout)["log"]!!.jsonObject["entries"]!!.jsonArray
        assertEquals(listOf(missing.id), filtered.map { it.jsonObject["_hopiId"]!!.jsonPrimitive.content.toLong() })

        val ndjson = cli("export", "--bridge-file", bridgeFile, "--format", "ndjson", "--limit", "1")
        assertEquals(ExitCode.OK, ndjson.exitCode, ndjson.toString())
        assertEquals(cli("get", missing.id.toString(), "--bridge-file", bridgeFile, "--json").lines, ndjson.lines)
        assertTrue("--format must be har or ndjson" in cli("export", "--bridge-file", bridgeFile, "--format", "csv").stderr)
    }

    @Test
    fun waitBlocksUntilANewMatchingTransactionEnds() {
        getPath("/missing")
        var run: CliRun? = null
        val waiter = thread(name = "test-wait") { run = cli("wait", "--bridge-file", bridgeFile, "--status", "4xx", "--json", "--timeout", "20s") }
        // The older 404 does not count; give wait time to connect, then make a new one.
        Thread.sleep(500)
        assertTrue(waiter.isAlive, "wait returned on an old transaction: $run")
        getPath("/a")
        val fresh = getPath("/missing")
        waiter.join(20_000)
        val done = assertNotNull(run)
        assertEquals(ExitCode.OK, done.exitCode, done.toString())
        assertEquals(fresh.id, parse(done.lines.single())["id"]!!.jsonPrimitive.content.toLong())

        val timeout = cli("wait", "--bridge-file", bridgeFile, "--url", "/never", "--timeout", "300ms")
        assertEquals(ExitCode.NOT_FOUND, timeout.exitCode, timeout.toString())
        assertTrue("no matching transaction ended within 0.3s" in timeout.stderr, timeout.stderr)
        // --since takes what is already there.
        val old = cli("wait", "--bridge-file", bridgeFile, "--status", "404", "--since", "0", "--json", "--timeout", "5s")
        assertEquals(ExitCode.OK, old.exitCode, old.toString())
    }

    @Test
    fun getBodyPrintsTheRawBytes() {
        val post = postUser()
        val response = cli("get", post.id.toString(), "--bridge-file", bridgeFile, "--body", "response")
        assertEquals(ExitCode.OK, response.exitCode, response.toString())
        assertEquals("""{"id":7,"name":"a'b"}""", response.stdout)
        assertEquals("""{"name":"a'b","tags":["x"]}""", cli("get", post.id.toString(), "--bridge-file", bridgeFile, "--body", "request").stdout)
        assertTrue("--body must be" in cli("get", post.id.toString(), "--bridge-file", bridgeFile, "--body", "both").stderr)
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
    fun tailFullBodiesHoldsTheModeWhileItRunsAndPrintsNoAck() {
        val before = postUser()
        assertEquals(0, FullBodies.cap)
        val lines = LinkedBlockingQueue<String>()
        val tail = thread(name = "test-tail") {
            runCli(
                listOf("tail", "--bridge-file", bridgeFile, "--json", "--full-bodies"),
                CliEnvironment(stdout = LineSink(lines), stderr = ByteArrayOutputStream(), shutdownHooks = RecordingShutdownHooks()),
            )
        }
        // The first line is the backlog, not capture.set's answer.
        val backlog = parse(assertNotNull(lines.poll(10, TimeUnit.SECONDS), "no backlog line"))
        assertEquals("transaction", backlog["type"]!!.jsonPrimitive.content)
        assertEquals(before.id, backlog["id"]!!.jsonPrimitive.content.toLong())
        assertTrue(FullBodies.cap > 0, "tail --full-bodies holds the mode")
        bridge.close()
        tail.join(10_000)
        assertFalse(tail.isAlive)
        // The bridge's session thread gives the mode back as it ends.
        val deadline = System.nanoTime() + 10_000_000_000
        while (FullBodies.cap != 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(0, FullBodies.cap, "the mode goes back when tail ends")
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

        init {
            // Before the client: the plugin reads the recorder once, when the client is built.
            HopiStart.start {
                summaryLog = false
                perf = false
            }
        }

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
                // The recorder the JVM start registered, on the default store; the bridge is started
                // by each test, not through the hook (neither the start nor this asks for it).
                logSink = HopiLogSink.Off
            }
        }
    }
}
