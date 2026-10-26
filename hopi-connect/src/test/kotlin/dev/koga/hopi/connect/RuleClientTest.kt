package dev.koga.hopi.connect

import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleSource
import dev.koga.hopi.core.rules.UrlRuleRequest
import dev.koga.hopi.core.store.HopiStore
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The rule commands from the host side, against the real JVM bridge and against scripted peers. */
class RuleClientTest {
    private val directory = Files.createTempDirectory("hopi-rule-client")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = HopiStore(maxEntries = 100, maxTotalBytes = 64L * 1024 * 1024, scope = scope)
    private val bridge = JvmAgentBridge.start(store, directory, app = APP)
    private val servers = mutableListOf<ServerSocket>()

    @BeforeTest
    fun setUp() = RuleEngine.resetForTests()

    @AfterTest
    fun tearDown() {
        RuleEngine.resetForTests()
        bridge.close()
        servers.forEach { it.close() }
        scope.cancel()
        directory.toFile().deleteRecursively()
    }

    private fun connect(): BridgeClient {
        val (port, token) = readBridgeFile(directory.resolve(JvmAgentBridge.BRIDGE_FILE_NAME), missing = "no bridge file")
        return BridgeClient.connect(BridgeEndpoint(port, token, Platform.Jvm, APP, "nothing serves $APP"), 10_000).also { it.handshake(null) }
    }

    @Test
    fun addListUpdateRemoveClear() {
        connect().use { client ->
            val rules = RuleClient(client)
            val added = rules.add("""{"label":"users","match":{"methods":["GET"],"path":"/users/*"},"mock":{"status":500,"body":${rules.body("{}".encodeToByteArray())}}}""")
            val rule = assertNotNull(RemoteRule.decode(added.json))
            assertEquals("GET */users/*", rule.match)
            assertEquals("mock 500 (2 B)", rule.action)
            assertEquals("users", rule.label)
            assertEquals(listOf(rule.id), rules.list().map { it.json.long("id") })
            val disabled = assertNotNull(RemoteRule.decode(rules.update(rule.id, enabled = false, times = 3).json))
            assertEquals(false, disabled.enabled)
            assertEquals(3L, disabled.times)
            assertNull(RemoteRule.decode(rules.update(rule.id, clearTimes = true).json)?.times)
            rules.remove(rule.id)
            assertEquals(ExitCode.NOT_FOUND, assertFailsWith<CliException> { rules.remove(rule.id) }.exitCode)
            rules.add("""{"fail":"reset"}""")
            assertEquals(1, rules.clear())
        }
    }

    @Test
    fun aLargeBodyGoesUpAsABlobInWindows() {
        val body = ByteArray(200_000) { (it * 31).toByte() }
        connect().use { client ->
            val rules = RuleClient(client)
            val bodyJson = rules.body(body)
            assertTrue("\"blob\":1" in bodyJson && "\"size\":200000" in bodyJson, bodyJson)
            val id = rules.add("""{"mock":{"status":200,"body":$bodyJson}}""").json.long("id")
            val mock = RuleEngine.state.firstMatch(RuleSource.OKHTTP, UrlRuleRequest("GET", "https://h/", emptyList()))?.mock
            assertEquals(id, RuleEngine.list().single().id)
            assertTrue(body.contentEquals(mock?.body?.bytes))
            // A second blob gets the next id; a text body that fits stays inline.
            assertTrue("\"blob\":2" in rules.body(ByteArray(5000)))
            assertTrue(rules.body("short".encodeToByteArray()).startsWith("{\"text\":"))
            assertTrue(rules.body(byteArrayOf(-1, 0)).startsWith("{\"base64\":"))
        }
    }

    @Test
    fun twoBodiesThatEachFitInlineShareTheLineAndTheRuleIsAccepted() {
        val request = "q".repeat(1_900).encodeToByteArray()
        val response = "r".repeat(1_900).encodeToByteArray()
        connect().use { client ->
            val rules = RuleClient(client)
            val requestBody = rules.body(request)
            val responseBody = rules.body(response)
            assertTrue(requestBody.startsWith("{\"text\":"), "the first body fits inline")
            assertTrue("\"blob\":1" in responseBody, "the second goes up as a blob: $responseBody")
            rules.add("""{"match":{"methods":["POST"],"path":"/x"},"edit":{"request":{"body":$requestBody},"response":{"body":$responseBody}}}""")
            val rule = RuleEngine.state.firstMatch(RuleSource.OKHTTP, UrlRuleRequest("POST", "https://h/x", emptyList()))
            assertTrue(request.contentEquals(rule?.requestEdit?.body?.bytes))
            assertTrue(response.contentEquals(rule?.responseEdit?.body?.bytes))
            // The budget is per line: the next line's first body is inline again.
            assertTrue(rules.body(request).startsWith("{\"text\":"))
        }
    }

    @Test
    fun validationErrorsAreUsageErrorsAndUnsupportedIsExitFour() {
        connect().use { client ->
            val rules = RuleClient(client)
            val bad = assertFailsWith<CliException> { rules.add("""{"match":{"path":"nope"},"fail":"reset"}""") }
            assertEquals(ExitCode.USAGE, bad.exitCode)
            assertTrue("match.path" in bad.message.orEmpty(), bad.message)
            val missing = assertFailsWith<CliException> { rules.remove(Long.MAX_VALUE) }
            assertEquals(ExitCode.NOT_FOUND, missing.exitCode)
        }
    }

    @Test
    fun anAppWithoutRulesIsExitFourBeforeAnyWriteLine() {
        RuleEngine.recorderStarted(rules = false)
        connect().use { client ->
            assertEquals(ExitCode.UNSUPPORTED, assertFailsWith<CliException> { RuleClient(client) }.exitCode)
        }
    }

    /** A peer that answers [hello], then records every line it receives until the client closes. */
    private class Scripted(val port: Int, val received: MutableList<String>, val done: CountDownLatch) {
        /** Every line the peer received, once the client closed its connection. */
        fun lines(): List<String> {
            assertTrue(done.await(10, TimeUnit.SECONDS), "the client did not close")
            return received.toList()
        }
    }

    private fun scripted(hello: String): Scripted {
        val server = ServerSocket(0, 8, BridgeClient.LOOPBACK).also { servers += it }
        val received = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    input.readLine()?.let { received += it }
                    socket.getOutputStream().write("$hello\n".encodeToByteArray())
                    socket.getOutputStream().flush()
                    while (true) received += input.readLine() ?: break
                }
            } catch (_: IOException) {
                // Closed by the client or tearDown.
            } finally {
                done.countDown()
            }
        }
        return Scripted(server.localPort, received, done)
    }

    @Test
    fun aSquatterWithoutTheProofReceivesHelloAndNothingElse() {
        val peer = scripted("""{"type":"hello","protocol":1,"app":"$APP","platform":"android","count":0,"dropped":0,"rules":true}""")
        val endpoint = BridgeEndpoint(peer.port, token = null, Platform.Android, APP, "nothing", proof = ProofCheck({ "the-proof" }, "not the app's bridge"))
        BridgeClient.connect(endpoint, 5_000).use { client ->
            assertEquals(ExitCode.NOT_REACHABLE, assertFailsWith<CliException> { client.handshake(null) }.exitCode)
        }
        assertEquals(listOf("""{"cmd":"hello"}"""), peer.lines())
    }

    @Test
    fun aHelloWithThePerfFlagsIsReadAsBefore() {
        // An app with hopi-perf adds "perf" and "perfControl" (docs/design/perf-monitors.md, "Bridge"):
        // a client that knows nothing of them must read the rest unchanged.
        val peer = scripted("""{"type":"hello","protocol":1,"app":"$APP","platform":"jvm","count":0,"dropped":0,"rules":true,"perf":true,"perfControl":true}""")
        BridgeClient.connect(BridgeEndpoint(peer.port, token = null, Platform.Jvm, APP, "nothing"), 5_000).use { client ->
            client.handshake(null)
            assertEquals(true, client.hello.json.bool("rules"))
            RuleClient(client)
        }
    }

    @Test
    fun anOlderAppWithoutHelloRulesIsUnsupported() {
        val peer = scripted("""{"type":"hello","protocol":1,"app":"$APP","platform":"jvm","count":0,"dropped":0}""")
        BridgeClient.connect(BridgeEndpoint(peer.port, token = null, Platform.Jvm, APP, "nothing"), 5_000).use { client ->
            client.handshake(null)
            assertEquals(ExitCode.UNSUPPORTED, assertFailsWith<CliException> { RuleClient(client) }.exitCode)
        }
        assertEquals(listOf("""{"cmd":"hello"}"""), peer.lines())
    }

    @Test
    fun decoderReadsTheMarksAndAnOlderLineWithoutThem() {
        val marked = completeTx(5).copy(ruleId = 3, ruleApplied = "delay,mock", source = "okhttp")
        val summary = assertNotNull(BridgeDecoder.summary(parse(TransactionJson.bridgeSummary(marked, Redactor.Disabled)), 0))
        assertEquals(3, summary.ruleId)
        assertEquals("delay,mock", summary.ruleApplied)
        val detail = assertNotNull(BridgeDecoder.detail(parse(TransactionJson.bridgeDetail(marked, Redactor.Disabled))))
        assertEquals(marked, detail)
        val old = assertNotNull(BridgeDecoder.summary(parse("""{"type":"transaction","id":1,"method":"GET","url":"https://h/","code":200,"ms":1,"reqBytes":0,"resBytes":0,"state":"complete"}"""), 0))
        assertNull(old.ruleId)
        assertNull(old.ruleApplied)
        // An app from before replay was removed (docs/ROADMAP.md) may still send `replayOf`: ignored.
        val replayed = assertNotNull(BridgeDecoder.summary(parse("""{"type":"transaction","id":2,"method":"GET","url":"https://h/","code":200,"ms":1,"reqBytes":0,"resBytes":0,"state":"complete","replayOf":1}"""), 0))
        assertEquals(2, replayed.id)
    }

    private fun parse(line: String): JsonObject = Json.parseToJsonElement(line) as JsonObject

    private companion object {
        const val APP = "dev.koga.rules.test"
    }
}
