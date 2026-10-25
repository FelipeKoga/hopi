package dev.koga.hopi.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.rules.FailKind
import dev.koga.hopi.core.rules.MatchSpec
import dev.koga.hopi.core.rules.MockSpec
import dev.koga.hopi.core.rules.ReplayHandoff
import dev.koga.hopi.core.rules.ReplayRequest
import dev.koga.hopi.core.rules.ReplayTarget
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleResult
import dev.koga.hopi.core.rules.RuleSpec
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ui.detail.DetailTags
import dev.koga.hopi.ui.list.ListTags
import dev.koga.hopi.ui.list.ruleTags
import dev.koga.hopi.ui.rules.RulesTags
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The in-app rules list, "Mock this response" and "Replay", over the process-wide rule engine. */
@OptIn(ExperimentalTestApi::class)
class RulesUiTest {
    private val store = HopiStore(scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @BeforeTest
    fun setUp() = RuleEngine.resetForTests()

    @AfterTest
    fun tearDown() {
        RuleEngine.resetForTests()
        store.close()
    }

    private fun submit(transaction: (Long) -> HttpTransaction): HttpTransaction = transaction(store.nextId()).also { assertTrue(store.submit(it)) }

    private fun SemanticsNodeInteractionsProvider.exists(text: String, substring: Boolean = true): Boolean =
        onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.waitForText(text: String) = waitUntil(timeoutMillis = 5_000) { exists(text) }

    private fun add(spec: RuleSpec): Long = assertIs<RuleResult.Ok>(RuleEngine.add(spec)).rule.id

    @Test
    fun theRulesListTogglesRemovesAndRemovesAll() = runComposeUiTest {
        val mock = add(RuleSpec(MatchSpec(methods = setOf("GET"), path = "/users/*"), label = "users", times = 3, mock = MockSpec(500)))
        val fail = add(RuleSpec(MatchSpec(host = "*.example.com"), fail = FailKind.TIMEOUT))
        setContent { HopiScreen(store, onClose = {}) }

        onNodeWithContentDescription(ListTags.RULES).performClick()
        waitForText("#$mock  GET */users/*")
        onNodeWithText("mock 500  ·  0/3 hits  ·  users").assertExists()
        waitForText("#$fail  * *.example.com/**")
        onNodeWithContentDescription(RulesTags.toggle(mock)).assertIsOn().performClick()
        waitUntil(timeoutMillis = 5_000) { RuleEngine.get(mock)?.spec?.enabled == false }
        onNodeWithContentDescription(RulesTags.toggle(mock)).assertIsOff()

        onNodeWithContentDescription(RulesTags.remove(fail)).performClick()
        waitUntil(timeoutMillis = 5_000) { !exists("*.example.com") }
        assertEquals(listOf(mock), RuleEngine.list().map { it.id })

        // A rule added elsewhere (the CLI, a test) appears while the list is open.
        val later = add(RuleSpec(MatchSpec(path = "/orders"), fail = FailKind.RESET))
        waitForText("#$later")

        onNodeWithText(RulesTags.REMOVE_ALL).performClick()
        waitForText("No rules")
        assertTrue(RuleEngine.list().isEmpty())

        onNodeWithContentDescription(RulesTags.BACK).performClick()
        waitForText("No requests yet")
    }

    @Test
    fun mockThisResponseAddsAnExactRuleAndUndoRemovesIt() = runComposeUiTest {
        submit { complete(it, url = "https://api.example.com/users/42?page=2", responseBody = textBody("""{"id":42}""")) }
        setContent { HopiScreen(store, onClose = {}) }
        waitForText("/users/42")
        onNodeWithText("/users/42?page=2").performClick()
        waitForText("GET 200")

        onNodeWithText(DetailTags.MOCK).performClick()
        val rule = RuleEngine.list().single()
        waitForText("Mock rule #${rule.id} added")
        assertEquals("GET https://api.example.com/users/42", rule.spec.match.describe())
        assertEquals(200, rule.spec.mock!!.status)
        assertEquals("""{"id":42}""", rule.spec.mock!!.body!!.bytes.decodeToString())
        assertEquals(null, rule.spec.times)

        onNodeWithText("Undo").performClick()
        assertTrue(RuleEngine.list().isEmpty())
    }

    @Test
    fun aTruncatedResponseCannotBeMockedAndSaysWhy() = runComposeUiTest {
        submit { complete(it, url = "https://api.example.com/big", responseBody = textBody("{", truncated = true, total = 10_000)) }
        setContent { HopiScreen(store, onClose = {}) }
        waitForText("/big")
        onNodeWithText("/big").performClick()
        waitForText("Mock: response body not held in full")
        onNodeWithText(DetailTags.MOCK).assertIsNotEnabled()
        // No replay target: the recorder offers none (a record a test submitted).
        waitForText("Replay: its recorder offers no replay")
    }

    @Test
    fun replayOpensTheNewRecordWhichLinksBack() = runComposeUiTest {
        val target = object : ReplayTarget {
            override val source = "okhttp"

            override fun refusal(): String? = null

            override fun send(request: ReplayRequest, handoff: ReplayHandoff) {
                check(handoff.take())
                store.submit(
                    HttpTransaction.pending(handoff.id, 1_700_000_000_000, request.method, request.url, request.headers, null)
                        .copy(replayOf = handoff.replayOf, source = source)
                        .completed(201, emptyList(), null, 7, "h2"),
                )
                handoff.finish(null)
            }
        }
        val original = submit { complete(it, url = "https://api.example.com/orders").copy(replayTarget = target) }
        setContent { HopiScreen(store, onClose = {}) }
        waitForText("/orders")
        onNodeWithText("/orders").performClick()
        waitForText("GET 200")

        onNodeWithText(DetailTags.REPLAY).performClick()
        waitForText("GET 201")
        onNodeWithText("#${original.id}").assertExists()
        assertEquals(listOf("REPLAY"), ruleTags(store.transactions.value.last()))

        onNodeWithText("#${original.id}").performClick()
        waitForText("GET 200")
    }
}
