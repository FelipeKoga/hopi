package dev.koga.hopi.connect

import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.rules.FailKind
import dev.koga.hopi.core.rules.MatchSpec
import dev.koga.hopi.core.rules.MockSpec
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleResult
import dev.koga.hopi.core.rules.RuleSource
import dev.koga.hopi.core.rules.RuleSpec
import dev.koga.hopi.core.rules.UrlRuleRequest
import dev.koga.hopi.core.store.HopiStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** [RemoteSession]'s rules: listed on connect, re-listed on notices and on ruled records the last list may not count, and commands. */
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

    /** What a recorder does for a ruled request: the engine claims the rule, then the record is written. */
    private fun ruledRequest(url: String = USERS): Long = recorded(url, claim(url))

    private fun claim(url: String = USERS): Long = assertNotNull(RuleEngine.state.firstMatch(RuleSource.KTOR, UrlRuleRequest("GET", url, emptyList())), url).id

    /** The record of a request [ruleId] was applied to: a pending summary, then the terminal one. */
    private fun recorded(url: String, ruleId: Long): Long {
        val id = store.nextId()
        val pending = HttpTransaction.pending(id, 0, "GET", url, emptyList(), null).copy(ruleId = ruleId, ruleApplied = "mock")
        store.submit(pending)
        store.submit(pending.completed(503, emptyList(), textBody("down"), 1, null))
        return id
    }

    /**
     * [session]'s hits of [rule] once its tail brought [id] terminal and every list a summary asked
     * for ran: they are queued on the request thread before this `get`.
     */
    private fun settledHits(session: RemoteSession, rule: Long, id: Long): Long {
        session.store.value.transactions.await { list -> list.any { it.id == id && it.state.isTerminal } }
        session.fetch(id)
        return session.rules.value.rules.single { it.id == rule }.hits
    }

    /** Has the app list its rules again, after whatever the engine counted so far; returns once the session shows it. */
    private fun relistedByANotice(session: RemoteSession) {
        val other = assertIs<RuleResult.Ok>(RuleEngine.add(RuleSpec(MatchSpec(path = "/orders"), fail = FailKind.TIMEOUT))).rule.id
        session.rules.await { rules -> rules.rules.any { it.id == other } }
    }

    @Test
    fun aRuledRequestShowsTheAppsCount() {
        val session = session()
        val rule = session.addRule(mockRule("/users/*")).get(15, TimeUnit.SECONDS)
        session.rules.await { rules -> rules.rules.any { it.id == rule.id } }
        val id = ruledRequest()
        session.rules.await { rules -> rules.rules.single().hits == 1L }
        assertEquals(1L, settledHits(session, rule.id, id))
    }

    @Test
    fun aHitTheListAlreadyCountedIsNotCountedAgainByItsSummary() {
        val session = session()
        val rule = session.addRule(mockRule("/users/*")).get(15, TimeUnit.SECONDS)
        // The claim comes first and a list counts it; only then does the tail bring the record.
        claim()
        relistedByANotice(session)
        assertEquals(1L, session.rules.value.rules.single { it.id == rule.id }.hits)
        val id = recorded(USERS, rule.id)
        assertEquals(1L, settledHits(session, rule.id, id))
    }

    @Test
    fun aMarkedBacklogIsNotCountedTwiceOnConnect() {
        val rule = assertIs<RuleResult.Ok>(RuleEngine.add(RuleSpec(MatchSpec(methods = setOf("GET"), path = "/users/*"), mock = MockSpec(503)))).rule.id
        val ids = List(BACKLOG) { ruledRequest() }
        store.transactions.await { list -> list.count { it.state.isTerminal } == BACKLOG }
        // The first list and the backlog's summaries race; the hits are the app's either way.
        val session = session()
        assertEquals(BACKLOG.toLong(), settledHits(session, rule, ids.last()))
        assertEquals(BACKLOG.toLong(), RuleEngine.get(rule)!!.hits)
    }

    @Test
    fun aMarkThatFirstComesWithTheEndListsAgainThoughTheStartCameBeforeTheList() {
        val session = session()
        val rule = session.addRule(mockRule("/users/*")).get(15, TimeUnit.SECONDS)
        // As URLSession records a mock: the start unmarked, the claim when the task starts loading, the mark at the end.
        val id = store.nextId()
        val started = HttpTransaction.pending(id, 0, "GET", USERS, emptyList(), null)
        store.submit(started)
        session.store.value.transactions.await { list -> list.any { it.id == id } }
        relistedByANotice(session)
        claim()
        store.submit(started.copy(ruleId = rule.id, ruleApplied = "mock").completed(503, emptyList(), textBody("down"), 1, null))
        assertEquals(1L, settledHits(session, rule.id, id))
    }

    @Test
    fun hitsStayExactAcrossABurstWithAListInTheMiddle() {
        val session = session()
        val rule = session.addRule(mockRule("/users/*")).get(15, TimeUnit.SECONDS)
        repeat(5) { claim() }
        relistedByANotice(session)
        // The five records the list already counted, then five more requests.
        val ids = List(5) { recorded(USERS, rule.id) } + List(5) { ruledRequest() }
        assertEquals(10L, settledHits(session, rule.id, ids.last()))
    }

    /** Rule 1 as a scripted app lists it: the app's count [hits], and a [label] naming the list. */
    private fun ruleLine(hits: Long, label: String) =
        """{"type":"rule","id":1,"label":"$label","enabled":true,"hits":$hits,"match":{"shorthand":"GET */users/*"},"mock":{"status":503}}"""

    private fun summary(transaction: HttpTransaction) = TransactionJson.bridgeSummary(transaction, Redactor.Disabled)

    @Test
    fun aHitTheFirstListAfterConnectMissedListsAgain() {
        val script = ScriptedBridge(rules = true)
        val session = RemoteSession({ script.endpoint() }).also { sessions += it }
        script.onRulesList = {
            if (script.lists.get() == 1) {
                // The app writes the first list, then claims a mock and records it: both its
                // summaries reach the viewer before this reply does.
                val tail = script.tails.take()
                val started = HttpTransaction.pending(1, 0, "GET", USERS, emptyList(), null, ruleId = 1, ruleApplied = "mock")
                tail.send(summary(started))
                tail.send(summary(started.completed(503, emptyList(), textBody("down"), 1, null)))
                session.store.value.transactions.await { list -> list.any { it.id == 1L && it.state.isTerminal } }
                listOf(ruleLine(hits = 0, label = "first"))
            } else {
                listOf(ruleLine(hits = 1, label = "again"))
            }
        }
        session.connect()
        assertEquals("again", session.rules.await { it.rules.singleOrNull()?.hits == 1L }.rules.single().label)
        assertEquals(2, script.lists.get())
        script.close()
    }

    @Test
    fun aMarkThatFirstComesWithAGetListsAgain() {
        val script = ScriptedBridge(rules = true)
        val appHits = AtomicLong()
        script.onRulesList = { listOf(ruleLine(appHits.get(), label = "list ${script.lists.get()}")) }
        val session = RemoteSession({ script.endpoint() }).also { sessions += it }
        session.connect()
        val tail = script.tails.take()
        session.rules.await { it.rules.singleOrNull()?.label == "list 1" }
        // As URLSession records a mock: the start unmarked, and a list asked for after it.
        val started = HttpTransaction.pending(1, 0, "GET", USERS, emptyList(), null)
        tail.send(summary(started))
        session.store.value.transactions.await { list -> list.any { it.id == 1L } }
        tail.send("""{"type":"rules","rev":2}""")
        session.rules.await { it.rules.singleOrNull()?.label == "list 2" }

        // The claim when the task starts loading, then the marked end, which a `get` (the row
        // selected, an export) brings before the tail does.
        appHits.set(1)
        val ended = started.copy(ruleId = 1, ruleApplied = "mock").completed(503, emptyList(), textBody("down"), 1, null)
        script.onGet = { TransactionJson.bridgeDetail(ended, Redactor.Disabled) }
        assertNotNull(session.fetch(1))
        assertEquals("list 3", session.rules.await { it.rules.single().hits == 1L }.rules.single().label)

        // The tail's summary of that end, and the same `get` again, list nothing more.
        tail.send(summary(ended))
        tail.send(summary(HttpTransaction.pending(2, 0, "GET", USERS, emptyList(), null)))
        session.store.value.transactions.await { list -> list.any { it.id == 2L } }
        assertNotNull(session.fetch(1))
        // A barrier on the request thread: a list the get above queued would have run before it.
        assertNotNull(session.fetch(1))
        assertEquals(3, script.lists.get())
        script.close()
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
        const val USERS = "https://api.example.com/users/1"

        /** Ruled records already in the app when a viewer connects; its store keeps 100. */
        const val BACKLOG = 60
    }
}
