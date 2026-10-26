package dev.koga.hopi.connect

import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.rules.FailKind
import dev.koga.hopi.core.rules.MatchSpec
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleResult
import dev.koga.hopi.core.rules.RuleSpec
import dev.koga.hopi.core.store.HopiStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** [RemoteSession]'s rules: listed on connect, re-listed on notices, hits counted locally, and commands. */
class RemoteSessionRulesTest {
    private val root: Path = Files.createTempDirectory("hopi-remote-rules")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = HopiStore(maxEntries = 100, scope = scope)
    private val bridge = JvmAgentBridge.start(store, root, app = APP)
    private val sessions = mutableListOf<RemoteSession>()

    @BeforeTest
    fun setUp() = RuleEngine.resetForTests()

    @AfterTest
    fun tearDown() {
        sessions.forEach { it.close() }
        RuleEngine.resetForTests()
        bridge.close()
        store.close()
        scope.cancel()
        root.toFile().deleteRecursively()
    }

    private fun session(): RemoteSession {
        val session = RemoteSession({
            val (port, token) = readBridgeFile(root.resolve(JvmAgentBridge.BRIDGE_FILE_NAME), missing = "no bridge file")
            BridgeEndpoint(port, token, Platform.Jvm, APP, unreachableMessage = "nothing serves $APP")
        }).also { sessions += it }
        session.connect()
        assertIs<RemoteState.Connected>(session.state.value)
        return session
    }

    private fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = runBlocking { withTimeout(15_000) { first(predicate) } }

    private fun mockRule(path: String) = { body: (ByteArray) -> String ->
        JsonWriter().beginObject()
            .name("match").beginObject().name("methods").beginArray().value("GET").endArray().field("path", path).endObject()
            .name("mock").beginObject().field("status", 503).name("body").rawValue(body("down".encodeToByteArray())).endObject()
            .endObject().toString()
    }

    @Test
    fun listsOnConnectFollowsNoticesAndCommands() {
        val session = session()
        assertEquals(emptyList(), session.rules.await { it.supported }.rules)

        val added = session.addRule(mockRule("/users/*")).get(15, TimeUnit.SECONDS)
        assertEquals("mock 503 (4 B)", added.action)
        assertEquals(listOf(added.id), session.rules.value.rules.map { it.id })
        assertEquals(503, RuleEngine.get(added.id)!!.spec.mock!!.status)

        // A rule added in the app (the in-process API) arrives through the tail's notice.
        val inApp = assertIs<RuleResult.Ok>(RuleEngine.add(RuleSpec(MatchSpec(path = "/orders"), fail = FailKind.TIMEOUT)))
        session.rules.await { rules -> rules.rules.any { it.id == inApp.rule.id } }

        val disabled = session.updateRule(added.id, enabled = false).get(15, TimeUnit.SECONDS)
        assertFalse(disabled.enabled)
        assertFalse(RuleEngine.get(added.id)!!.spec.enabled)
        session.removeRule(inApp.rule.id).get(15, TimeUnit.SECONDS)
        assertEquals(listOf(added.id), session.rules.value.rules.map { it.id })
        val missing = assertFailsWith<ExecutionException> { session.removeRule(inApp.rule.id).get(15, TimeUnit.SECONDS) }
        assertEquals(ExitCode.NOT_FOUND, (missing.cause as CliException).exitCode)
        // A refusal leaves the connection up.
        val bad = assertFailsWith<ExecutionException> { session.addRule { """{"match":{"path":"/x"},"mock":{"status":42}}""" }.get(15, TimeUnit.SECONDS) }
        assertTrue("mock.status" in bad.cause!!.message.orEmpty(), bad.cause!!.message)
        assertIs<RemoteState.Connected>(session.state.value)

        assertEquals(1L, session.clearRules().get(15, TimeUnit.SECONDS))
        assertEquals(emptyList(), session.rules.await { it.rules.isEmpty() }.rules)
        assertTrue(RuleEngine.list().isEmpty())
    }

    @Test
    fun hitsAreCountedFromTheSummariesOnce() {
        val session = session()
        val rule = session.addRule(mockRule("/users/*")).get(15, TimeUnit.SECONDS)
        session.rules.await { rules -> rules.rules.any { it.id == rule.id } }
        // What a recorder writes for a ruled request: a pending summary, then the terminal one.
        val id = store.nextId()
        val pending = HttpTransaction.pending(id, 0, "GET", "https://api.example.com/users/1", emptyList(), null).copy(ruleId = rule.id, ruleApplied = "mock")
        store.submit(pending)
        store.submit(pending.completed(503, emptyList(), textBody("down"), 1, null))
        session.rules.await { rules -> rules.rules.single().hits == 1L }
        session.store.value.transactions.await { list -> list.any { it.id == id && it.state.isTerminal } }
        assertEquals(1L, session.rules.value.rules.single().hits)
    }

    @Test
    fun anAppWithRulesOffGetsNoRuleCommand() {
        RuleEngine.recorderStarted(rules = false)
        val session = session()
        assertFalse(session.rules.value.supported)
        val refused = assertFailsWith<ExecutionException> { session.clearRules().get(15, TimeUnit.SECONDS) }
        assertEquals(ExitCode.UNSUPPORTED, (refused.cause as CliException).exitCode)
    }

    private companion object {
        const val APP = "dev.koga.sample"
    }
}
