package dev.koga.hopi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ui.desktop.HopiPanes
import dev.koga.hopi.ui.desktop.HopiPanesRules
import dev.koga.hopi.ui.desktop.HopiPanesState
import dev.koga.hopi.ui.detail.BodyViewerTags
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
class HopiPanesUiTest {
    private val store = HopiStore(scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @AfterTest
    fun closeStore() = store.close()

    private fun submitAll(vararg transactions: HttpTransaction, into: HopiStore = store) {
        for (transaction in transactions) {
            while (into.nextId() < transaction.id) continue
            assertTrue(into.submit(transaction))
        }
    }

    /** What a host viewer holds before `get`: sizes only, as `BridgeDecoder.summary` builds it. */
    private fun summaryOnly(transaction: HttpTransaction) = transaction.copy(
        requestHeaders = emptyList(),
        requestBody = CapturedBody.notFetched(transaction.requestBody?.reportedBytes ?: 0),
        responseHeaders = emptyList(),
        responseBody = transaction.responseBody?.let { CapturedBody.notFetched(it.reportedBytes) },
        protocol = null,
    )

    /** `GET /<path><id>` for each of [ids]. */
    private fun requests(ids: LongRange, path: String = "r"): Array<HttpTransaction> =
        ids.map { complete(it, url = "https://api.example.com/$path$it") }.toTypedArray()

    private fun ComposeUiTest.waitForText(text: String, substring: Boolean = false) {
        waitUntil(timeoutMillis = 5_000) { exists(text, substring) }
    }

    private fun SemanticsNodeInteractionsProvider.exists(text: String, substring: Boolean = false): Boolean =
        onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun SemanticsNodeInteractionsProvider.existsDescription(description: String): Boolean =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(description)))
            .fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.panes(
        width: Int,
        state: HopiPanesState = HopiPanesState(),
        selections: MutableList<Long?> = mutableListOf(),
        onClear: () -> Unit = {},
    ): HopiPanesState {
        setContent {
            Box(Modifier.requiredSize(width.dp, 700.dp)) {
                HopiPanes(store, onSelected = { selections += it }, onClear = onClear, state = state)
            }
        }
        return state
    }

    @Test
    fun wideShowsListAndDetailSideBySideAndReportsTheSelection() = runComposeUiTest {
        submitAll(
            complete(1, url = "https://api.example.com/users"),
            complete(2, code = 404, url = "https://api.example.com/missing"),
        )
        val selections = mutableListOf<Long?>()
        panes(width = 1200, selections = selections)
        waitForText("/users")
        onNodeWithText("No request selected").assertIsDisplayed()

        onNodeWithText("/users").performClick()
        waitForText("GET 200")
        onNodeWithText("/missing").assertIsDisplayed()
        onNodeWithText("/users").assertIsDisplayed()
        // The list stays beside the detail: no back.
        assertFalse(existsDescription(DetailTags.BACK))
        waitUntil(timeoutMillis = 5_000) { selections.lastOrNull() == 1L }
        assertEquals(listOf(null, 1L), selections)

        // The selected row is marked for assistive technology too.
        val selectedRows = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes()
            .filter { node -> node.config.contains(SemanticsActions.OnClick) && node.config.getOrNull(SemanticsProperties.Role) == null }
        assertEquals(1, selectedRows.size)
    }

    @Test
    fun narrowTakesTurnsWithABackButton() = runComposeUiTest {
        submitAll(complete(1, url = "https://api.example.com/users"))
        panes(width = 400)
        waitForText("/users")
        assertFalse(exists("No request selected"))
        onNodeWithText("/users").performClick()
        waitForText("GET 200")
        assertFalse(exists("/users"))
        onNodeWithContentDescription(DetailTags.BACK).performClick()
        waitForText("/users")
    }

    @Test
    fun aSummaryOnlyTransactionSaysLoadingThenShowsTheFetchedBody() = runComposeUiTest {
        val full = complete(1, url = "https://api.example.com/users", responseBody = textBody("""{"name":"Ada"}"""))
        submitAll(summaryOnly(full))
        val state = panes(width = 1200)
        waitForText("/users")
        state.selectedId = 1
        waitForText("GET 200")
        onNodeWithText("Response").performClick()
        waitForText("Loading body…")
        onNodeWithText("Loading headers…").assertIsDisplayed()
        assertFalse(exists("No response body") || exists("Not captured", substring = true))
        // cURL would lack the headers and body still on the app's side.
        onNodeWithContentDescription(DetailTags.COPY_CURL).assertIsNotEnabled()

        // The detail arrives (the request connection's `get`): it replaces the summary in place.
        submitAll(full)
        waitForText("\"name\": \"Ada\"", substring = true)
        assertFalse(exists("Loading body…"))
        onNodeWithText("Content-Type").assertIsDisplayed()
        onNodeWithContentDescription(DetailTags.COPY_CURL).assertIsEnabled()
    }

    @Test
    fun keyboardMovesFollowTheShownRows() = runComposeUiTest {
        submitAll(
            complete(1, url = "https://api.example.com/one"),
            complete(2, url = "https://api.example.com/two"),
            complete(3, url = "https://api.example.com/three"),
        )
        val selections = mutableListOf<Long?>()
        val state = panes(width = 1200, selections = selections)
        waitForText("/three")
        waitForIdle()
        // Newest first: 3, 2, 1.
        state.selectNext()
        assertEquals(3, state.selectedId)
        state.selectNext()
        state.selectNext()
        state.selectNext()
        assertEquals(1, state.selectedId, "stops at the last row")
        state.selectPrevious()
        assertEquals(2, state.selectedId)
        waitUntil(timeoutMillis = 5_000) { selections.lastOrNull() == 2L }
        state.clearSelection()
        waitForText("No request selected")
        waitUntil(timeoutMillis = 5_000) { selections.lastOrNull() == null }
    }

    @Test
    fun aListTheSelectionScrolledKeepsItsRowsInPlace() = runComposeUiTest {
        // More rows than the panes show, newest first: /r20 at the top, /r3 far below it.
        submitAll(*requests(1L..20L))
        val state = panes(width = 1200)
        waitForText("/r20")
        waitForIdle()
        // The keyboard moves the selection down to /r3, out of the first screen: the list scrolls to it.
        repeat(18) { state.selectNext() }
        assertEquals(3, state.selectedId)
        waitForText("/r3")
        val top = onNodeWithText("/r3").getBoundsInRoot().top

        submitAll(*requests(21L..23L))
        waitForText("23")
        assertEquals(top, onNodeWithText("/r3").getBoundsInRoot().top, "the rows moved under the selection")
    }

    @Test
    fun aScrolledListOpensTheStoreOfAnotherAppProcessAtItsNewest() = runComposeUiTest {
        submitAll(*requests(1L..20L))
        var shown by mutableStateOf(store)
        setContent { Box(Modifier.requiredSize(1200.dp, 700.dp)) { HopiPanes(shown, onSelected = {}, onClear = {}) } }
        waitForText("/r20")
        val top = onNodeWithText("/r20").getBoundsInRoot().top
        onNode(hasScrollToIndexAction()).performScrollToIndex(5)

        // The app restarted: the viewer swaps in a store for the new process, whose ids start at 1
        // again and whose catch-up already holds more rows than fit.
        val relaunched = HopiStore(scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        try {
            submitAll(*requests(1L..20L, path = "b"), into = relaunched)
            shown = relaunched
            waitUntil(timeoutMillis = 5_000) { !exists("/r", substring = true) }
            assertEquals(top, onNodeWithText("/b20").getBoundsInRoot().top, "the new process's newest opened above the list")

            // At its top, the list stays on newest from there.
            submitAll(*requests(21L..23L, path = "b"), into = relaunched)
            waitForText("23")
            assertEquals(top, onNodeWithText("/b23").getBoundsInRoot().top)
        } finally {
            relaunched.close()
        }
    }

    @Test
    fun trashCallsOnClearInsteadOfClearingTheStoreItself() = runComposeUiTest {
        submitAll(complete(1, url = "https://api.example.com/a"))
        var cleared = 0
        panes(width = 1200, onClear = { cleared++ })
        waitForText("/a")
        onNodeWithContentDescription(ListTags.CLEAR).performClick()
        assertEquals(1, cleared)
        assertEquals(1, store.since(0).size, "the caller clears the app's store and this one")
    }

    /**
     * The panes one at a time over the whole window, as on a phone: the list, then a detail with a
     * back button. The trash clears [store].
     */
    @Composable
    private fun OnePane(modifier: Modifier = Modifier) {
        HopiPanes(store, onSelected = {}, onClear = { store.clear() }, modifier = modifier, sideBySideMinWidth = Dp.Infinity)
    }

    /** A phone-sized window, so 20 rows overflow it: a list that fits scrolls back to fill itself and hides nothing. */
    private fun ComposeUiTest.phoneScreen() {
        setContent { Box(Modifier.requiredSize(360.dp, 600.dp)) { OnePane() } }
    }

    @Test
    fun listOpensTheDetailAndBackReturnsToTheList() = runComposeUiTest {
        submitAll(
            complete(1, url = "https://api.example.com/users", responseBody = textBody("""{"name":"Ada"}""")),
            complete(2, code = 404, url = "https://api.example.com/missing"),
        )
        setContent { OnePane() }

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
    }

    @Test
    fun newestFirstAndLiveUpdatesWhileOpen() = runComposeUiTest {
        submitAll(pending(1, url = "https://api.example.com/slow"))
        setContent { OnePane() }
        waitForText("pending")
        onNodeWithContentDescription("Pending").assertIsDisplayed()

        // The pending request completes while the list is shown.
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
        waitUntil(timeoutMillis = 5_000) { existsDescription(DetailTags.COPY_URL) }
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
        setContent { OnePane() }
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
        setContent { OnePane() }
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
        setContent { OnePane() }
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
        setContent { OnePane() }
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
            CompositionLocalProvider(LocalClipboardManager provides clipboard) { OnePane() }
        }
        openResponseOf("/big")
        waitForText("\"name\": \"user 0\"", substring = true)
        onNodeWithContentDescription(DetailTags.COPY_BODY).performClick()
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
            CompositionLocalProvider(LocalClipboardManager provides FakeClipboard(refuse = true)) { OnePane() }
        }
        waitForText("/a")
        onNodeWithText("/a").performClick()
        waitUntil(timeoutMillis = 5_000) { existsDescription(DetailTags.COPY_CURL) }
        onNodeWithContentDescription(DetailTags.COPY_CURL).performClick()
        waitForText("cURL could not be copied")
        onNodeWithContentDescription(DetailTags.COPY_URL).assertIsDisplayed()
    }

    @Test
    fun theHexDumpScrollsSidewaysOnAPhoneWideScreen() = runComposeUiTest {
        val bytes = ByteArray(64) { (it * 7).toByte() }
        submitAll(complete(1, url = "https://cdn.example.com/raw", responseBody = CapturedBody(bytes, 64, false, "application/x-raw", null)))
        setContent { Box(Modifier.width(360.dp)) { OnePane() } }
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
                OnePane()
            }
        }
        waitForText("/a")
        val badge = onNodeWithContentDescription("Status 200").getBoundsInRoot()
        assertTrue(badge.right - badge.left > 44.dp && badge.bottom - badge.top > 26.dp, "the badge kept its 44 x 26 dp at 3x: $badge")
        assertNotClipped("Hopi", "2xx", ListTags.SEARCH)
        onNodeWithText("/a").performClick()
        waitUntil(timeoutMillis = 5_000) { existsDescription(DetailTags.COPY_CURL) }
        assertNotClipped("GET 200", "cURL", "Overview", "Response")
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
            pending(1, url = "https://api.example.com/keyed", headers = listOf("X-Tenant-Key" to "k-123"))
                .completed(200, emptyList(), null, 5, null),
        )
        setContent { OnePane() }
        waitForText("/keyed")
        onNodeWithText("/keyed").performClick()
        waitForText("Request")
        onNodeWithText("Request").performClick()
        waitForText("k-123")

        // A second client installs the plugin with X-Tenant-Key redacted, then makes a request.
        store.includeRedaction(Redactor.create(extraHeaders = listOf("X-Tenant-Key")))
        submitAll(complete(2, url = "https://api.example.com/other"))
        waitForText(Redactor.REDACTED)
        assertFalse(exists("k-123"), "the open detail still shows a header the store now redacts")
    }

    @Test
    fun anOpenDetailAndTheListFollowARedactorThatGrewBeforeAnyOtherRequest() = runComposeUiTest {
        submitAll(
            pending(1, url = "https://api.example.com/keyed?otp=t-123", headers = listOf("X-Tenant-Key" to "k-123"))
                .completed(200, emptyList(), null, 5, null),
        )
        setContent { OnePane() }
        waitForText("/keyed?otp=t-123")
        onNodeWithText("/keyed?otp=t-123").performClick()
        waitForText("Request")
        onNodeWithText("Request").performClick()
        waitForText("k-123")

        // A second client installs the plugin with more redacted, and no request follows.
        store.includeRedaction(Redactor.create(extraHeaders = listOf("X-Tenant-Key"), queryParameters = listOf("otp")))
        waitUntil(timeoutMillis = 5_000) { !exists("k-123") }
        assertTrue(exists(Redactor.REDACTED, substring = true))

        onNodeWithContentDescription(DetailTags.BACK).performClick()
        waitForText("/keyed?otp=${Redactor.REDACTED}")
        assertFalse(exists("t-123", substring = true), "the list still shows a query value the store now redacts")
    }

    @Suppress("DEPRECATION")
    @Test
    fun copyUrlUsesTheRedactorInForceWhenItRuns() = runComposeUiTest {
        submitAll(complete(1, url = "https://api.example.com/items?otp=t-123"))
        val clipboard = FakeClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) { OnePane() }
        }
        waitForText("/items?otp=t-123")
        onNodeWithText("/items?otp=t-123").performClick()
        waitUntil(timeoutMillis = 5_000) { existsDescription(DetailTags.COPY_URL) }

        // A second client installs the plugin with `otp` redacted, and no request follows yet.
        store.includeRedaction(Redactor.create(queryParameters = listOf("otp")))
        onNodeWithContentDescription(DetailTags.COPY_URL).performClick()
        waitForText("URL copied")
        assertEquals("https://api.example.com/items?otp=${Redactor.REDACTED}", assertNotNull(clipboard.clip).text)
    }

    @Test
    fun aTruncatedResponseCannotBeMockedAndSaysWhy() = runComposeUiTest {
        submitAll(complete(1, url = "https://api.example.com/big", responseBody = textBody("{", truncated = true, total = 10_000)))
        setContent { HopiPanes(store, onSelected = {}, onClear = {}, sideBySideMinWidth = Dp.Infinity, rules = HopiPanesRules(mockThis = {})) }
        waitForText("/big")
        onNodeWithText("/big").performClick()
        waitForText("Mock: response body not held in full")
        onNodeWithText(DetailTags.MOCK).assertIsNotEnabled()
    }

    @Test
    fun anExpandedBodyFindsEveryMatchAndStepsThroughThem() = runComposeUiTest {
        val records = (0 until 600).joinToString(",", "[", "]") { i -> """{"id":$i,"name":"${if (i % 200 == 7) "needle" else "hay"}-$i","ok":true}""" }
        submitAll(complete(1, url = "https://api.example.com/big", responseBody = textBody(records)))
        setContent { OnePane() }
        waitForText("/big")
        onNodeWithText("/big").performClick()
        waitForText("Response")
        onNodeWithText("Response").performClick()
        waitUntil(timeoutMillis = 5_000) { existsDescription(BodyViewerTags.EXPAND) }
        onNodeWithContentDescription(BodyViewerTags.EXPAND).performClick()
        waitUntil(timeoutMillis = 5_000) { existsDescription(BodyViewerTags.SEARCH) }
        onNodeWithContentDescription(BodyViewerTags.SEARCH).performTextInput("NEEDLE")
        waitForText("1 of 3")
        onNodeWithContentDescription(BodyViewerTags.NEXT).performClick()
        waitForText("2 of 3")
        onNodeWithContentDescription(BodyViewerTags.PREVIOUS).performClick()
        onNodeWithContentDescription(BodyViewerTags.PREVIOUS).performClick()
        waitForText("3 of 3")
        // The last match, far down a 600-record body, was scrolled into view.
        waitForText("needle-407", substring = true)
        onNodeWithContentDescription(BodyViewerTags.CLOSE).performClick()
        waitUntil(timeoutMillis = 5_000) { !existsDescription(BodyViewerTags.SEARCH) }
    }
}
