package dev.koga.hopi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.koga.hopi.core.rules.FailKind
import dev.koga.hopi.core.rules.MatchSpec
import dev.koga.hopi.core.rules.MockSpec
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleResult
import dev.koga.hopi.core.rules.RuleSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The rules panel and the detail's rule buttons against a real JVM bridge. The app's side runs in
 * this process, so the tests read [RuleEngine] to see what the app received; the panel itself
 * only ever reads the session.
 */
@OptIn(ExperimentalTestApi::class)
class RulesPanelUiTest {
    private val root: Path = Files.createTempDirectory("hopi-rules-ui")
    private val closeables = mutableListOf<AutoCloseable>()

    @BeforeTest
    fun setUp() = RuleEngine.resetForTests()

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { it.close() }
        RuleEngine.resetForTests()
        root.toFile().deleteRecursively()
    }

    private fun SemanticsNodeInteractionsProvider.exists(text: String): Boolean =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.waitForText(text: String) = waitUntil(timeoutMillis = 15_000) { exists(text) }

    /** Connects a viewer to [app]; [bodyFile] is what "Choose file…" picks. */
    private fun ComposeUiTest.connected(app: FakeApp, bodyFile: BodyFile? = null): RulesPanelState {
        val viewer = Viewer(jvmOnlyTargets(), jvmBridgeRoot = root).also { closeables += it }
        viewer.start()
        lateinit var rules: RulesPanelState
        setContent {
            rules = remember { RulesPanelState() }
            Box(Modifier.fillMaxSize()) {
                ViewerContent(viewer, chooseExportFile = { _, _ -> root.resolve("x.har") }, rules = rules, chooseBodyFile = { bodyFile })
            }
        }
        waitForText("JVM ${app.bridgeFile.parent.fileName}")
        onNodeWithTag(ViewerTags.CONNECT).performClick()
        waitForText("Connected to $APP")
        return rules
    }

    private fun ComposeUiTest.openPanel() {
        onNodeWithTag(ViewerTags.ROOT).performKeyInput { withKeyDown(if (IS_MAC) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.L) } }
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasTestTag(RulesTags.PANEL)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun added(spec: RuleSpec): Long = assertIs<RuleResult.Ok>(RuleEngine.add(spec)).rule.id

    @Test
    fun listsTogglesAddsRemovesAndClears() = runComposeUiTest {
        val app = FakeApp(root.resolve("1")).also { closeables += it }
        val existing = added(RuleSpec(MatchSpec(host = "*.example.com"), label = "slow", fail = FailKind.TIMEOUT))
        connected(app)
        openPanel()
        waitForText("* *.example.com/**")
        waitForText("fail timeout  ·  0 hits  ·  slow")

        onNodeWithContentDescription(RulesTags.toggle(existing)).performClick()
        waitUntil(timeoutMillis = 15_000) { RuleEngine.get(existing)?.spec?.enabled == false }
        onNodeWithContentDescription(RulesTags.toggle(existing)).assertIsOff()

        // A mock with a header from the table and a body from the text area.
        onNodeWithText("Mock").performClick()
        onNodeWithTag(RulesTags.MATCH).performTextInput("GET */users/*")
        onNodeWithTag(RulesTags.STATUS).performTextInput("503")
        onNodeWithText("Add header").performClick()
        onNodeWithTag(RulesTags.header("Headers", 0, value = false)).performTextInput("Content-Type")
        onNodeWithTag(RulesTags.header("Headers", 0, value = true)).performTextInput("application/json")
        onNodeWithTag(RulesTags.BODY).performTextInput("""{"error":"down"}""")
        onNodeWithTag(RulesTags.SAVE).performClick()
        waitUntil(timeoutMillis = 15_000) { RuleEngine.list().size == 2 }
        val mock = RuleEngine.list().last()
        assertEquals("GET */users/*", mock.spec.match.describe())
        assertEquals(503, mock.spec.mock!!.status)
        assertEquals(listOf("Content-Type" to "application/json"), mock.spec.mock!!.headers)
        assertEquals("""{"error":"down"}""", mock.spec.mock!!.body!!.bytes.decodeToString())
        waitForText("Rule #${mock.id} added")
        waitForText("mock 503 (16 B)")
        screenshot("viewer-rules.png")

        // A form the host can check is refused before anything is sent.
        onNodeWithText("Delay").performClick()
        onNodeWithTag(RulesTags.MATCH).performTextInput("/slow")
        onNodeWithTag(RulesTags.SAVE).performClick()
        waitForText("delay: give a delay before the request or before the response")
        onNodeWithText("Cancel").performClick()

        // Rows in the order the app added them: the existing rule first.
        onAllNodes(hasText("Remove", substring = false)).onFirst().performClick()
        waitForText("Rule #$existing removed")
        assertEquals(listOf(mock.id), RuleEngine.list().map { it.id })

        onNodeWithText("Clear").performClick()
        waitForText("1 rule removed")
        assertTrue(RuleEngine.list().isEmpty())
        waitForText("No rules.")
    }

    @Test
    fun hitsAreCountedFromTheTailAndAnAppRuleAppearsByItsNotice() = runComposeUiTest {
        val app = FakeApp(root.resolve("2")).also { closeables += it }
        connected(app)
        openPanel()
        waitForText("No rules.")
        // Added in the app (HopiRules, the CLI): the tail's notice re-lists.
        val rule = added(RuleSpec(MatchSpec(path = "/users/*"), times = 2, mock = MockSpec(500)))
        waitForText("0/2 hits")
        app.add { completeTx(it, url = "https://api.example.com/users/1").copy(ruleId = rule, ruleApplied = "mock") }
        waitForText("1/2 hits")
        // The row's tag in the list.
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasContentDescription("mock")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun mockThisResponseCopiesTheRecordInTheApp() = runComposeUiTest {
        val app = FakeApp(root.resolve("3")).also { closeables += it }
        val recorded = app.add { completeTx(it, url = "https://api.example.com/users/42?page=2", responseBody = textBody("""{"id":42}""")) }
        connected(app)
        waitForText("/users/42")
        onNodeWithText("/users/42?page=2").performClick()
        waitForText("GET 200")
        onNodeWithText("Mock this response").performScrollTo().performClick()
        waitForText("Mock this response (#${recorded.id})")
        screenshot("viewer-mock-this.png")
        onNodeWithTag(RulesTags.SAVE).performClick()
        waitUntil(timeoutMillis = 15_000) { RuleEngine.list().size == 1 }
        val rule = RuleEngine.list().single()
        assertEquals("GET https://api.example.com/users/42", rule.spec.match.describe())
        assertEquals(200, rule.spec.mock!!.status)
        // Copied from the app's own record, unredacted, never through the bridge.
        assertEquals("""{"id":42}""", rule.spec.mock!!.body!!.bytes.decodeToString())
        assertTrue(rule.spec.mock!!.headers.none { it.first.equals("Set-Cookie", ignoreCase = true) })
    }

    @Test
    fun aLargeBodyFromAFileGoesUpAsABlob() = runComposeUiTest {
        val app = FakeApp(root.resolve("5")).also { closeables += it }
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        connected(app, bodyFile = BodyFile("big.bin", bytes))
        openPanel()
        onNodeWithText("Mock").performClick()
        onNodeWithTag(RulesTags.MATCH).performTextInput("/big")
        onNodeWithTag(RulesTags.STATUS).performTextInput("200")
        onNodeWithText("Choose file…").performClick()
        waitForText("big.bin (")
        onNodeWithTag(RulesTags.SAVE).performClick()
        waitUntil(timeoutMillis = 15_000) { RuleEngine.list().size == 1 }
        assertTrue(RuleEngine.list().single().spec.mock!!.body!!.bytes.contentEquals(bytes))
    }

    @Test
    fun anAppWithoutRulesDisablesTheButtonsAndSaysWhy() = runComposeUiTest {
        RuleEngine.recorderStarted(rules = false)
        val app = FakeApp(root.resolve("6")).also { closeables += it }
        app.add { completeTx(it, url = "https://api.example.com/plain") }
        connected(app)
        waitForText("/plain")
        onNodeWithText("/plain").performClick()
        waitForText("Mock: this app's Hopi does not support rules")
        onNodeWithText("Mock this response").assertIsNotEnabled()
        openPanel()
        waitForText("does not support rules (not connected yet, an older Hopi, or rules = false)")
    }

    /** Writes the composed window content where the build's test task says (build/reports/screenshots). */
    private fun ComposeUiTest.screenshot(name: String) {
        val directory = System.getProperty("hopi.desktop.screenshots") ?: return
        val image = onRoot().captureToImage().toAwtImage()
        Files.createDirectories(Paths.get(directory))
        ImageIO.write(image, "png", Paths.get(directory, name).toFile())
    }
}
