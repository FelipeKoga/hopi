package dev.koga.hopi.core.bridge

import dev.koga.hopi.core.complete
import dev.koga.hopi.core.json.parseJsonTree
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleLimits
import dev.koga.hopi.core.rules.RuleSource
import dev.koga.hopi.core.rules.RuleState
import dev.koga.hopi.core.rules.UrlRuleRequest
import dev.koga.hopi.core.store.HopiStore
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel

/** The rule commands and blobs over a real [BridgeSession]. */
class RuleBridgeSessionTest {
    private val storeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val storeScope = CoroutineScope(SupervisorJob() + storeExecutor.asCoroutineDispatcher())
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = HopiStore(10_000, 1L shl 30, storeScope)

    @BeforeTest
    fun setUp() = RuleEngine.resetForTests()

    @AfterTest
    fun tearDown() {
        RuleEngine.resetForTests()
        sessionScope.cancel()
        storeScope.cancel()
        storeExecutor.shutdownNow()
    }

    private fun serve(): MemoryConnection {
        val connection = MemoryConnection()
        thread(name = "test-session") { BridgeSession(connection, store, BridgeInfo("dev.koga.sample", "jvm"), null, sessionScope).run() }
        return connection
    }

    /** A session that has said hello. */
    private fun session(): MemoryConnection = serve().also { assertEquals("hello", fields(it.request(HELLO))["type"]) }

    private fun fields(line: String): Map<String, Any?> = assertNotNull(parseJsonTree(line, Int.MAX_VALUE, 8), line)

    private fun MemoryConnection.request(line: String): String {
        send(line)
        return nextLine()
    }

    private fun assertError(code: BridgeError, line: String, containing: String = "") {
        val f = fields(line)
        assertEquals("error", f["type"], line)
        assertEquals(code.name, f["code"], line)
        assertTrue(containing in (f["message"] as String), line)
    }

    private fun addLine(rule: String) = "{\"cmd\":\"rules.add\",\"rule\":$rule}"

    @Test
    fun helloReportsRules() {
        assertEquals(true, fields(serve().request(HELLO))["rules"])
        RuleEngine.recorderStarted(rules = false)
        assertEquals(false, fields(serve().request(HELLO))["rules"])
    }

    @Test
    fun writeCommandsNeedHelloFirstOnTheSameConnection() {
        val client = serve()
        for (line in listOf(addLine("{\"fail\":\"reset\"}"), "{\"cmd\":\"rules.list\"}", "{\"cmd\":\"rules.clear\"}", "{\"cmd\":\"blob.put\",\"blob\":1,\"offset\":0,\"data\":\"\"}")) {
            assertError(BridgeError.BAD_REQUEST, client.request(line), "hello first")
        }
        assertSame(RuleState.EMPTY, RuleEngine.state)
        // Reads still work without hello, as before.
        assertEquals("end", fields(client.request("{\"cmd\":\"list\"}"))["type"])
        client.request(HELLO)
        assertEquals("rule", fields(client.request(addLine("{\"fail\":\"reset\"}")))["type"])
    }

    @Test
    fun rulesOffAnswersUnsupported() {
        RuleEngine.recorderStarted(rules = false)
        assertError(BridgeError.UNSUPPORTED, session().request(addLine("{\"fail\":\"reset\"}")), "rules are off")
    }

    @Test
    fun addListUpdateRemoveClear() {
        val client = session()
        val added = fields(
            client.request(
                addLine(
                    "{\"label\":\"users 500\",\"times\":3,\"match\":{\"methods\":[\"GET\"],\"path\":\"/users/*\",\"headers\":[{\"name\":\"Authorization\",\"value\":\"Bearer s3cret\"}]}," +
                        "\"mock\":{\"status\":500,\"headers\":[[\"Content-Type\",\"application/json\"],[\"X-Api-Key\",\"k\"]],\"body\":{\"text\":\"{}\"}}}",
                ),
            ),
        )
        val id = added["id"] as Long
        assertEquals(3L, added["times"])
        assertEquals(0L, added["hits"])
        assertEquals(mapOf("bytes" to 2L), (added["mock"] as Map<*, *>)["body"])
        assertEquals(true, (added["unsupported"] as List<*>).isEmpty())
        val request = UrlRuleRequest("GET", "https://h/users/1", listOf("Authorization" to "Bearer s3cret"))
        assertEquals(id, RuleEngine.state.firstMatch(RuleSource.OKHTTP, request)?.id)

        client.send("{\"cmd\":\"rules.list\"}")
        val listed = fields(client.nextLine())
        assertEquals(id, listed["id"])
        assertEquals(1L, listed["hits"])
        assertEquals("end", fields(client.nextLine())["type"])

        val updated = fields(client.request("{\"cmd\":\"rules.update\",\"id\":$id,\"enabled\":false,\"priority\":5,\"times\":null}"))
        assertEquals(false, updated["enabled"])
        assertEquals(5L, updated["priority"])
        assertNull(updated["times"])
        assertSame(RuleState.EMPTY, RuleEngine.state)
        assertError(BridgeError.NOT_FOUND, client.request("{\"cmd\":\"rules.update\",\"id\":999,\"enabled\":true}"))

        assertEquals("ok", fields(client.request("{\"cmd\":\"rules.remove\",\"id\":$id}"))["type"])
        assertError(BridgeError.NOT_FOUND, client.request("{\"cmd\":\"rules.remove\",\"id\":$id}"))
        client.request(addLine("{\"fail\":\"reset\"}"))
        client.request(addLine("{\"fail\":\"timeout\"}"))
        assertEquals(mapOf("type" to "ok", "removed" to 2L), fields(client.request("{\"cmd\":\"rules.clear\"}")))
    }

    @Test
    fun repliesRedactHeaderValuesAndNeverEchoBodies() {
        store.includeRedaction(Redactor.create(extraHeaders = listOf("X-Api-Key")))
        val line = session().request(
            addLine(
                "{\"match\":{\"headers\":[{\"name\":\"Authorization\",\"value\":\"Bearer s3cret\"}]}," +
                    "\"mock\":{\"status\":200,\"headers\":[[\"X-Api-Key\",\"k3y\"]],\"body\":{\"text\":\"the-body\"}}}",
            ),
        )
        for (secret in listOf("s3cret", "k3y", "the-body")) assertTrue(secret !in line, "$secret leaked: $line")
    }

    @Test
    fun validationAndLimitErrorsNameTheField() {
        val client = session()
        assertError(BridgeError.BAD_REQUEST, client.request(addLine("{\"match\":{\"path\":\"/" + "a".repeat(300) + "\"},\"fail\":\"reset\"}")), "match.path")
        assertError(BridgeError.BAD_REQUEST, client.request(addLine("{\"mock\":{\"status\":200,\"headers\":[[\"Set-Cookie\",\"a=b\"]]}}")), "mock.headers")
        assertError(BridgeError.BAD_REQUEST, client.request(addLine("{}")), "rule")
        repeat(RuleLimits.MAX_RULES) { client.request(addLine("{\"fail\":\"reset\"}")) }
        assertError(BridgeError.LIMIT, client.request(addLine("{\"fail\":\"reset\"}")), "64")
        assertError(BridgeError.NOT_FOUND, client.request(addLine("{\"mock\":{\"fromTransaction\":77}}")))
    }

    private fun blobPut(blob: Long, offset: Long, bytes: ByteArray) =
        "{\"cmd\":\"blob.put\",\"blob\":$blob,\"offset\":$offset,\"data\":\"${Base64.getEncoder().encodeToString(bytes)}\"}"

    @Test
    fun blobsUploadInOrderAndMoveIntoTheRule() {
        val client = session()
        val body = ByteArray(5000) { (it % 251).toByte() }
        val slices = body.toList().chunked(2304).map { it.toByteArray() }
        var offset = 0L
        // Pipelined in one window, acknowledged in order.
        for (slice in slices) {
            client.send(blobPut(1, offset, slice))
            offset += slice.size
        }
        var size = 0L
        for (slice in slices) {
            size += slice.size
            assertEquals(mapOf("type" to "ok", "blob" to 1L, "size" to size), fields(client.nextLine()))
        }
        val added = fields(client.request(addLine("{\"mock\":{\"status\":200,\"body\":{\"blob\":1,\"size\":5000}}}")))
        val rule = assertNotNull(RuleEngine.get(added["id"] as Long))
        assertTrue(body.contentEquals(rule.spec.mock?.body?.bytes))
        // The blob left the session with the rule.
        assertError(BridgeError.BAD_REQUEST, client.request(addLine("{\"mock\":{\"status\":200,\"body\":{\"blob\":1,\"size\":5000}}}")), "no blob 1")
    }

    @Test
    fun aBlobWithAHoleOrBadDataIsDiscarded() {
        val client = session()
        assertError(BridgeError.BAD_REQUEST, client.request(blobPut(1, 5, ByteArray(3))), "expected 0")
        client.request(blobPut(1, 0, ByteArray(3)))
        assertError(BridgeError.BAD_REQUEST, client.request(blobPut(1, 4, ByteArray(3))), "discarded")
        assertError(BridgeError.BAD_REQUEST, client.request(addLine("{\"mock\":{\"status\":200,\"body\":{\"blob\":1,\"size\":3}}}")), "no blob 1")
        client.request(blobPut(2, 0, ByteArray(3)))
        assertError(BridgeError.BAD_REQUEST, client.request("{\"cmd\":\"blob.put\",\"blob\":2,\"offset\":3,\"data\":\"!!!!\"}"), "not base64")
        client.request(blobPut(3, 0, ByteArray(3)))
        assertError(BridgeError.BAD_REQUEST, client.request(addLine("{\"mock\":{\"status\":200,\"body\":{\"blob\":3,\"size\":4}}}")), "mock.body.size")
    }

    @Test
    fun blobsAreLimitedPerSessionAndInSize() {
        val client = session()
        client.request(blobPut(1, 0, ByteArray(1)))
        client.request(blobPut(2, 0, ByteArray(1)))
        assertError(BridgeError.LIMIT, client.request(blobPut(3, 0, ByteArray(1))), "2 blobs")
        // A new session starts with none: blobs die with their session.
        val other = session()
        assertEquals("ok", fields(other.request(blobPut(3, 0, ByteArray(1))))["type"])
        assertError(BridgeError.BAD_REQUEST, other.request(addLine("{\"mock\":{\"status\":200,\"body\":{\"blob\":1,\"size\":1}}}")), "no blob 1")
        val chunk = ByteArray(2304)
        var offset = 0L
        var last = ""
        while (offset <= RuleLimits.MAX_BODY_BYTES) {
            last = other.request(blobPut(4, offset, chunk))
            if (fields(last)["type"] != "ok") break
            offset += chunk.size
        }
        assertError(BridgeError.LIMIT, last, "discarded")
    }

    @Test
    fun tailSendsRulesNoticesOnlyWhenAsked() {
        val plain = session()
        plain.send("{\"cmd\":\"tail\"}")
        val opted = session()
        opted.send("{\"cmd\":\"tail\",\"rules\":true}")
        // A transaction after both tails started tells when each stream is live.
        store.submit(complete(store.nextId()))
        assertEquals("transaction", fields(plain.nextLine())["type"])
        assertEquals("transaction", fields(opted.nextLine())["type"])
        RuleEngine.clear()
        val notice = fields(opted.nextLine())
        assertEquals("rules", notice["type"])
        assertEquals(RuleEngine.revision.value, notice["rev"])
        store.submit(complete(store.nextId()))
        assertEquals("transaction", fields(plain.nextLine())["type"], "no rules line on a tail that did not ask")
    }

    @Test
    fun replayIsAnUnknownCommand() {
        // Removed from the product (docs/ROADMAP.md, "Replay (no priority)"): a newer client that
        // still sends it gets what an older app answers any command it does not know.
        val client = session()
        assertError(BridgeError.BAD_REQUEST, client.request("{\"cmd\":\"replay\",\"id\":1}"), "unknown cmd")
        assertError(BridgeError.BAD_REQUEST, client.request("{\"cmd\":\"replay\",\"id\":1,\"edit\":{\"path\":\"/v2\"}}"), "malformed JSON object")
        assertSame(RuleState.EMPTY, RuleEngine.state)
    }

    private companion object {
        const val HELLO = "{\"cmd\":\"hello\"}"
    }
}
