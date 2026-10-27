package dev.koga.hopi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.koga.hopi.core.redact.Redactor
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ui.detail.DetailTags
import dev.koga.hopi.ui.list.ListTags
import kotlin.math.ceil
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@OptIn(ExperimentalTestApi::class)
class HopiScreenUiTest {
    // Unconfined: `submit` runs the store's consumer on the caller, so a write is visible at once.
    private val store = HopiStore(scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @AfterTest
    fun closeStore() = store.close()

    private fun submitAll(vararg transactions: HttpTransaction) {
        for (transaction in transactions) {
            // Ids come from the store, as in the plugin, so that `clear` covers them.
            while (store.nextId() < transaction.id) continue
            assertTrue(store.submit(transaction))
        }
    }

    private fun ComposeUiTest.waitForText(text: String, substring: Boolean = false) {
        waitUntil(timeoutMillis = 5_000) { exists(text, substring) }
    }

    private fun SemanticsNodeInteractionsProvider.exists(text: String, substring: Boolean = false): Boolean =
        onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    /** `GET /r<id>` for each of [ids]. */
    private fun requests(ids: LongRange): Array<HttpTransaction> =
        ids.map { complete(it, url = "https://api.example.com/r$it") }.toTypedArray()

    /** A phone-sized screen, so 20 rows overflow it: a list that fits scrolls back to fill itself and hides nothing. */
    private fun ComposeUiTest.phoneScreen() {
        setContent { Box(Modifier.requiredSize(360.dp, 600.dp)) { HopiScreen(store, onClose = {}) } }
    }

    @Test
    fun listOpensTheDetailAndBackReturnsToTheList() = runComposeUiTest {
        submitAll(
            complete(1, url = "https://api.example.com/users", responseBody = textBody("""{"name":"Ada"}""")),
            complete(2, code = 404, url = "https://api.example.com/missing"),
        )
        var closed = 0
        setContent { HopiScreen(store, onClose = { closed++ }) }

        waitForText("/users")
        onNodeWithText("/missing").assertIsDisplayed()
        onNodeWithContentDescription("Status 404").assertIsDisplayed()
        onNodeWithText("/users").performClick()

        waitForText("GET 200")
        onNodeWithText("200 OK").assertIsDisplayed()
        onNodeWithText("https://api.example.com/users").assertIsDisplayed()

        onNodeWithText("Response").performClick()
        waitForText("\"name\": \"Ada\"", substring = true)
        onNodeWithText("14 B · application/json").assertIsDisplayed()

        onNodeWithContentDescription(DetailTags.BACK).performClick()
        waitForText("/missing")
        assertTrue(!exists("GET 200"))

        onNodeWithContentDescription(ListTags.CLOSE).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun newestFirstAndLiveUpdatesWhileOpen() = runComposeUiTest {
        submitAll(pending(1, url = "https://api.example.com/slow"))
        setContent { HopiScreen(store, onClose = {}) }
        waitForText("pending")
        onNodeWithContentDescription("Pending").assertIsDisplayed()

        // The pending request completes while the screen is open.
        submitAll(complete(1, url = "https://api.example.com/slow"), complete(2, url = "https://api.example.com/fast"))
        waitForText("183 ms")
        waitUntil(timeoutMillis = 5_000) { !exists("pending") }
        val tops = onAllNodes(hasText("/", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
            .filter { it == "/fast" || it == "/slow" }
        assertEquals(listOf("/fast", "/slow"), tops)
    }

    @Test
    fun aListAtItsTopShowsNewRequestsThere() = runComposeUiTest {
        submitAll(*requests(1L..20L))
        phoneScreen()
        waitForText("/r20")
        val top = onNodeWithText("/r20").getBoundsInRoot().top

        // A burst on a list nobody scrolled: the newest takes the top instead of landing above it.
        submitAll(*requests(21L..23L))
        waitForText("23")
        assertEquals(top, onNodeWithText("/r23").getBoundsInRoot().top)
    }

    @Test
    fun aListLeftAtItsTopShowsTheNewestOnTheWayBackFromADetail() = runComposeUiTest {
        submitAll(*requests(1L..20L))
        phoneScreen()
        waitForText("/r20")
        val top = onNodeWithText("/r20").getBoundsInRoot().top
        onNodeWithText("/r20").performClick()
        waitForText(DetailTags.COPY_URL)
        waitForIdle()

        // Requests arrive while the detail covers the list.
        submitAll(*requests(21L..23L))
        onNodeWithContentDescription(DetailTags.BACK).performClick()
        waitForText("23")
        assertEquals(top, onNodeWithText("/r23").getBoundsInRoot().top)
    }

    @Test
    fun aListScrolledAwayFromItsTopKeepsItsRowsInPlace() = runComposeUiTest {
        submitAll(*requests(1L..20L))
        phoneScreen()
        waitForText("/r20")
        onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        val top = onNodeWithText("/r15").getBoundsInRoot().top

        submitAll(*requests(21L..23L))
        waitForText("23")
        assertEquals(top, onNodeWithText("/r15").getBoundsInRoot().top, "the rows moved under the reader")
    }

    @Test
    fun aScrolledListThatWasClearedShowsTheNextRequestsFromTheTop() = runComposeUiTest {
        submitAll(*requests(1L..20L))
        phoneScreen()
        waitForText("/r20")
        val top = onNodeWithText("/r20").getBoundsInRoot().top
        onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        onNodeWithContentDescription(ListTags.CLEAR).performClick()
        waitForText("No requests yet")

        // More rows than fit: they open at the newest, not where the cleared ones were scrolled to.
        submitAll(*requests(21L..45L))
        waitForText("25")
        assertEquals(top, onNodeWithText("/r45").getBoundsInRoot().top, "the newest opened above the list")
    }

    @Test
    fun statusFilterAndSearchNarrowTheList() = runComposeUiTest {
        submitAll(
            complete(1, url = "https://api.example.com/ok"),
            complete(2, code = 404, url = "https://api.example.com/not-there"),
            complete(3, code = 503, url = "https://api.example.com/busy"),
            failed(4, url = "https://down.example.com/health"),
            pending(5, url = "https://api.example.com/waiting"),
        )
        setContent { HopiScreen(store, onClose = {}) }
        waitForText("/ok")

        onNodeWithText("4xx").performClick()
        waitUntil(timeoutMillis = 5_000) { !exists("/ok") }
        onNodeWithText("/not-there").assertIsDisplayed()
        assertTrue(!exists("/busy") && !exists("/health") && !exists("/waiting"))

        onNodeWithText("Failed").performClick()
        waitForText("/health")
        onNodeWithContentDescription("Failed").assertIsDisplayed()
        assertTrue(!exists("/not-there"))

        onNodeWithText("Pending").performClick()
        waitForText("/waiting")

        onNodeWithText("All").performClick()
        waitForText("/ok")
        onNodeWithContentDescription(ListTags.SEARCH).performTextInput("BUSY")
        waitUntil(timeoutMillis = 5_000) { !exists("/ok") }
        onNodeWithText("/busy").assertIsDisplayed()

        onNodeWithText("2xx").performClick()
        waitForText("No matching requests")
    }

    @Test
    fun clearEmptiesTheList() = runComposeUiTest {
        submitAll(complete(1, url = "https://api.example.com/a"))
        setContent { HopiScreen(store, onClose = {}) }
        waitForText("/a")
        onNodeWithContentDescription(ListTags.CLEAR).performClick()
        waitForText("No requests yet")
    }

    @Test
    fun largeTruncatedBodyRendersInPiecesAndSaysSo() = runComposeUiTest {
        val json = buildString {
            append('[')
            for (i in 0 until 6_000) {
                if (i > 0) append(',')
                append("""{"id":$i,"name":"user $i"}""")
            }
            append(']')
        }
        val body = CapturedBody(json.encodeToByteArray(), 512L * 1024, truncated = true, "application/json", null)
        submitAll(complete(1, url = "https://api.example.com/big", responseBody = body))
        setContent { HopiScreen(store, onClose = {}) }
        waitForText("/big")
        onNodeWithText("/big").performClick()
        waitForText("Response")
        onNodeWithText("Response").performClick()
        waitForText("Truncated: showing the first", substring = true)
        waitForText("\"name\": \"user 0\"", substring = true)
    }

    @Test
    fun skippedAndBinaryBodiesAreExplained() = runComposeUiTest {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x01)
        submitAll(
            complete(1, url = "https://cdn.example.com/a.png", responseBody = CapturedBody.skipped("binary content-type", "image/png", 2048)),
            complete(2, url = "https://cdn.example.com/raw", responseBody = CapturedBody(png, 6, false, "application/x-raw", null)),
        )
        setContent { HopiScreen(store, onClose = {}) }
        waitForText("/a.png")
        onNodeWithText("/a.png").performClick()
        waitForText("Response")
        onNodeWithText("Response").performClick()
        waitForText("Not captured: binary content-type")
        onNodeWithContentDescription(DetailTags.BACK).performClick()

        waitForText("/raw")
        onNodeWithText("/raw").performClick()
        waitForText("Response")
        onNodeWithText("Response").performClick()
        waitForText("Binary body", substring = true)
        waitForText("89 50 4e 47", substring = true)
    }

    /** A clipboard that records what it is given, or refuses it as Android does past the binder limit. */
    @Suppress("DEPRECATION")
    private class FakeClipboard(private val refuse: Boolean = false) : ClipboardManager {
        var clip: AnnotatedString? = null

        override fun setText(annotatedString: AnnotatedString) {
            if (refuse) throw RuntimeException("android.os.TransactionTooLargeException: data parcel size 1200000 bytes")
            clip = annotatedString
        }

        override fun getText(): AnnotatedString? = clip
    }

    private fun largeJson(): CapturedBody {
        // Minified 300 KB, far more once pretty-printed: past the 100k-character clip cap.
        val json = buildString {
            append('[')
            for (i in 0 until 10_000) {
                if (i > 0) append(',')
                append("""{"id":$i,"name":"user $i"}""")
            }
            append(']')
        }
        return CapturedBody(json.encodeToByteArray(), null, truncated = false, "application/json", null)
    }

    private fun ComposeUiTest.openResponseOf(path: String) {
        waitForText(path)
        onNodeWithText(path).performClick()
        waitForText("Response")
        onNodeWithText("Response").performClick()
    }

    @Suppress("DEPRECATION")
    @Test
    fun copyingALargeBodyIsCutToTheClipLimitAndSaysSo() = runComposeUiTest {
        submitAll(complete(1, url = "https://api.example.com/big", responseBody = largeJson()))
        val clipboard = FakeClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) { HopiScreen(store, onClose = {}) }
        }
        openResponseOf("/big")
        waitForText("\"name\": \"user 0\"", substring = true)
        onNodeWithText(DetailTags.COPY_BODY).performClick()
        waitForText("Response body copied (first 100k characters)")
        val copied = assertNotNull(clipboard.clip).text
        assertEquals(100_000, copied.length)
        assertTrue(copied.startsWith("[\n  {\n    \"id\": 0,"), copied.take(40))
        // Screen readers announce the confirmation.
        onNode(hasText("Response body copied", substring = true))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Suppress("DEPRECATION")
    @Test
    fun aClipboardThatRefusesTheCopyShowsAnErrorInsteadOfCrashing() = runComposeUiTest {
        submitAll(complete(1, url = "https://api.example.com/a"))
        setContent {
            CompositionLocalProvider(LocalClipboardManager provides FakeClipboard(refuse = true)) { HopiScreen(store, onClose = {}) }
        }
        waitForText("/a")
        onNodeWithText("/a").performClick()
        waitForText(DetailTags.COPY_CURL)
        onNodeWithText(DetailTags.COPY_CURL).performClick()
        waitForText("cURL could not be copied")
        onNodeWithText(DetailTags.COPY_URL).assertIsDisplayed()
    }

    @Test
    fun theHexDumpScrollsSidewaysOnAPhoneWideScreen() = runComposeUiTest {
        val bytes = ByteArray(64) { (it * 7).toByte() }
        submitAll(complete(1, url = "https://cdn.example.com/raw", responseBody = CapturedBody(bytes, 64, false, "application/x-raw", null)))
        setContent { Box(Modifier.width(360.dp)) { HopiScreen(store, onClose = {}) } }
        openResponseOf("/raw")
        waitForText("Binary body", substring = true)
        val hex = onNode(hasText("00000000  00 07 0e", substring = true)).fetchSemanticsNode()
        val range = assertNotNull(hex.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange), "the dump cannot scroll")
        assertTrue(range.maxValue() > 0f, "a 360 dp screen shows every column of the dump")
    }

    @Test
    fun aLargeFontScaleGrowsControlsInsteadOfClippingThem() = runComposeUiTest {
        submitAll(complete(1, url = "https://api.example.com/a"))
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 3f)) {
                HopiScreen(store, onClose = {})
            }
        }
        waitForText("/a")
        val badge = onNodeWithContentDescription("Status 200").getBoundsInRoot()
        assertTrue(badge.right - badge.left > 44.dp && badge.bottom - badge.top > 26.dp, "the badge kept its 44 x 26 dp at 3x: $badge")
        assertNotClipped("Hopi", "2xx", ListTags.SEARCH)
        onNodeWithText("/a").performClick()
        waitForText(DetailTags.COPY_CURL)
        assertNotClipped("GET 200", DetailTags.COPY_CURL, "Overview", "Response")
    }

    private fun SemanticsNodeInteractionsProvider.assertNotClipped(vararg texts: String) {
        for (text in texts) {
            val node = onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().first {
                it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null
            }
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val layout = results.single()
            // A container of fixed height measures its text no taller than itself: the node is then
            // shorter than the lines it holds, and the rest is cut off.
            val needed = ceil(layout.multiParagraph.height).toInt()
            assertTrue(node.size.height >= needed, "\"$text\" is clipped at 3x: ${node.size.height} px tall for $needed px of text")
            assertEquals(1, layout.lineCount, "\"$text\" wrapped at 3x")
        }
    }

    @Test
    fun anOpenDetailFollowsARedactorThatGrew() = runComposeUiTest {
        submitAll(
            pending(1, url = "https://api.example.com/keyed", headers = listOf("X-Api-Key" to "k-123"))
                .completed(200, emptyList(), null, 5, null),
        )
        setContent { HopiScreen(store, onClose = {}) }
        waitForText("/keyed")
        onNodeWithText("/keyed").performClick()
        waitForText("Request")
        onNodeWithText("Request").performClick()
        waitForText("k-123")

        // A second client installs the plugin with X-Api-Key redacted, then makes a request.
        store.includeRedaction(Redactor.create(extraHeaders = listOf("X-Api-Key")))
        submitAll(complete(2, url = "https://api.example.com/other"))
        waitForText(Redactor.REDACTED)
        assertFalse(exists("k-123"), "the open detail still shows a header the store now redacts")
    }

    @Test
    fun anOpenDetailAndTheListFollowARedactorThatGrewBeforeAnyOtherRequest() = runComposeUiTest {
        submitAll(
            pending(1, url = "https://api.example.com/keyed?token=t-123", headers = listOf("X-Api-Key" to "k-123"))
                .completed(200, emptyList(), null, 5, null),
        )
        setContent { HopiScreen(store, onClose = {}) }
        waitForText("/keyed?token=t-123")
        onNodeWithText("/keyed?token=t-123").performClick()
        waitForText("Request")
        onNodeWithText("Request").performClick()
        waitForText("k-123")

        // A second client installs the plugin with more redacted, and no request follows.
        store.includeRedaction(Redactor.create(extraHeaders = listOf("X-Api-Key"), queryParameters = listOf("token")))
        waitUntil(timeoutMillis = 5_000) { !exists("k-123") }
        assertTrue(exists(Redactor.REDACTED, substring = true))

        onNodeWithContentDescription(DetailTags.BACK).performClick()
        waitForText("/keyed?token=${Redactor.REDACTED}")
        assertFalse(exists("t-123", substring = true), "the list still shows a query value the store now redacts")
    }

    @Suppress("DEPRECATION")
    @Test
    fun copyUrlUsesTheRedactorInForceWhenItRuns() = runComposeUiTest {
        submitAll(complete(1, url = "https://api.example.com/items?token=t-123"))
        val clipboard = FakeClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) { HopiScreen(store, onClose = {}) }
        }
        waitForText("/items?token=t-123")
        onNodeWithText("/items?token=t-123").performClick()
        waitForText(DetailTags.COPY_URL)

        // A second client installs the plugin with `token` redacted, and no request follows yet.
        store.includeRedaction(Redactor.create(queryParameters = listOf("token")))
        onNodeWithText(DetailTags.COPY_URL).performClick()
        waitForText("URL copied")
        assertEquals("https://api.example.com/items?token=${Redactor.REDACTED}", assertNotNull(clipboard.clip).text)
    }

    @Test
    fun theLauncherOpensAndClosesTheScreen() = runComposeUiTest {
        submitAll(complete(1, url = "https://api.example.com/launched"))
        // An app's root fills the window; the launcher's button and screen cover what the content covers.
        setContent { HopiLauncher(store = { store }) { Box(Modifier.fillMaxSize()) { BasicText("The app") } } }
        onNodeWithText("The app").assertIsDisplayed()
        onNodeWithContentDescription(LAUNCHER_DESCRIPTION).performClick()
        waitForText("/launched")
        onNodeWithContentDescription(ListTags.CLOSE).performClick()
        waitForText("The app")
        waitUntil(timeoutMillis = 5_000) { !exists("/launched") }
        onNodeWithContentDescription(LAUNCHER_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun theLauncherNeitherRecomposesNorTouchesTheStoreWhenRequestsArrive() = runComposeUiTest {
        var recompositions = 0
        var storeLookups = 0
        var appCompositions = 0
        setContent {
            HopiLauncher(
                store = {
                    storeLookups++
                    store
                },
                recompositionProbe = { recompositions++ },
            ) {
                appCompositions++
                Box(Modifier.fillMaxSize()) { BasicText("The app") }
            }
        }
        waitForIdle()
        val afterFirstFrame = recompositions
        assertTrue(afterFirstFrame > 0, "the probe must run on the first composition")

        repeat(50) { i -> submitAll(pending(i + 1L), complete(i + 1L)) }
        assertEquals(50, store.transactions.value.size)
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()

        assertEquals(afterFirstFrame, recompositions, "the launcher recomposed for store changes")
        assertEquals(1, appCompositions, "the app content recomposed")
        assertEquals(0, storeLookups, "the closed launcher resolved the store")

        onNodeWithContentDescription(LAUNCHER_DESCRIPTION).performClick()
        waitForText("Hopi")
        assertTrue(storeLookups > 0)
    }
}
