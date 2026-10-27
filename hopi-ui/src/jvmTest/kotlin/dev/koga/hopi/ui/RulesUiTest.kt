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
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionSource
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.rules.FailKind
import dev.koga.hopi.core.rules.MatchSpec
import dev.koga.hopi.core.rules.MockSpec
import dev.koga.hopi.core.rules.Predicate
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleResult
import dev.koga.hopi.core.rules.RuleSpec
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ui.detail.DetailTags
import dev.koga.hopi.ui.list.ListTags
import dev.koga.hopi.ui.rules.RulesTags
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher

/** The in-app rules list and "Mock this response", over the process-wide rule engine. */
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
    fun theRulesListFollowsARedactorThatGrewBeforeAnyOtherChange() = runComposeUiTest {
        val keyed = add(RuleSpec(MatchSpec(headers = listOf(Predicate("X-Api-Key", "k-123"))), fail = FailKind.RESET))
        setContent { HopiScreen(store, onClose = {}) }
        onNodeWithContentDescription(ListTags.RULES).performClick()
        waitForText("#$keyed")
        assertTrue(exists("X-Api-Key=k-123"))

        // A second client installs the plugin with X-Api-Key redacted, and no request or rule change follows.
        store.includeRedaction(Redactor.create(extraHeaders = listOf("X-Api-Key")))
        waitUntil(timeoutMillis = 5_000) { !exists("k-123") }
        assertTrue(exists("X-Api-Key=${Redactor.REDACTED}"))
    }

    @Test
    fun mockThisResponseAddsAnExactRuleAndUndoRemovesIt() = runComposeUiTest {
        submit { complete(it, url = "https://api.example.com/users/42?page=2", responseBody = textBody("""{"id":42}""")) }
        setContent { HopiScreen(store, onClose = {}) }
        waitForText("/users/42")
        onNodeWithText("/users/42?page=2").performClick()
        waitForText("GET 200")

        onNodeWithText(DetailTags.MOCK).performClick()
        // Built off the main thread: the rule may not exist yet when the tap returns.
        waitUntil(timeoutMillis = 5_000) { RuleEngine.list().isNotEmpty() }
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
    fun aBodyHeldGzippedIsMockedDecodedOnTheFormatDispatcher() {
        // An OkHttp record of a request that named its own Accept-Encoding: the server's gzip bytes.
        val json = """{"id":42}"""
        val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(json.encodeToByteArray()) } }.toByteArray()
        submit {
            complete(
                it,
                url = "https://api.example.com/users/42",
                responseBody = CapturedBody(gzipped, gzipped.size.toLong(), truncated = false, "application/json", null),
                responseHeaders = listOf("Content-Type" to "application/json", "Content-Encoding" to "gzip"),
            ).copy(source = TransactionSource.OKHTTP)
        }
        val formatThread = AtomicReference<Thread>()
        val format = Executors.newSingleThreadExecutor { Thread(it, "hopi-format").also(formatThread::set) }.asCoroutineDispatcher()
        // The engine calls its listeners on the thread that added the rule, right after the mock was built.
        val addedOn = ConcurrentLinkedQueue<Thread>()
        val removeListener = RuleEngine.addListener { addedOn += Thread.currentThread() }
        try {
            runComposeUiTest {
                setContent { HopiScreen(store, onClose = {}, formatDispatcher = format) }
                waitForText("/users/42")
                onNodeWithText("/users/42").performClick()
                waitForText("GET 200")
                onNodeWithText(DetailTags.MOCK).performClick()
                waitUntil(timeoutMillis = 5_000) { RuleEngine.list().isNotEmpty() }
                val rule = RuleEngine.list().single()
                waitForText("Mock rule #${rule.id} added")
                assertEquals(json, rule.spec.mock!!.body!!.bytes.decodeToString())
                assertEquals(listOf(formatThread.get()), addedOn.toList())
            }
        } finally {
            removeListener()
            format.close()
        }
    }

    @Test
    fun aTruncatedResponseCannotBeMockedAndSaysWhy() = runComposeUiTest {
        submit { complete(it, url = "https://api.example.com/big", responseBody = textBody("{", truncated = true, total = 10_000)) }
        setContent { HopiScreen(store, onClose = {}) }
        waitForText("/big")
        onNodeWithText("/big").performClick()
        waitForText("Mock: response body not held in full")
        onNodeWithText(DetailTags.MOCK).assertIsNotEnabled()
    }
}
