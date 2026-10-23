package dev.koga.hopi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ui.desktop.HopiPanes
import dev.koga.hopi.ui.desktop.HopiPanesState
import dev.koga.hopi.ui.detail.DetailTags
import dev.koga.hopi.ui.list.ListTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@OptIn(ExperimentalTestApi::class)
class HopiPanesUiTest {
    private val store = HopiStore(scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @AfterTest
    fun closeStore() = store.close()

    private fun submitAll(vararg transactions: HttpTransaction) {
        for (transaction in transactions) {
            while (store.nextId() < transaction.id) continue
            assertTrue(store.submit(transaction))
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
        // The window closes itself and the list stays beside the detail: no close, no back.
        assertFalse(existsDescription(ListTags.CLOSE))

        onNodeWithText("/users").performClick()
        waitForText("GET 200")
        onNodeWithText("/missing").assertIsDisplayed()
        onNodeWithText("/users").assertIsDisplayed()
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
        onNodeWithText(DetailTags.COPY_CURL).assertIsNotEnabled()

        // The detail arrives (the request connection's `get`): it replaces the summary in place.
        submitAll(full)
        waitForText("\"name\": \"Ada\"", substring = true)
        assertFalse(exists("Loading body…"))
        onNodeWithText("Content-Type").assertIsDisplayed()
        onNodeWithText(DetailTags.COPY_CURL).assertIsEnabled()
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
    fun trashCallsOnClearInsteadOfClearingTheStoreItself() = runComposeUiTest {
        submitAll(complete(1, url = "https://api.example.com/a"))
        var cleared = 0
        panes(width = 1200, onClear = { cleared++ })
        waitForText("/a")
        onNodeWithContentDescription(ListTags.CLEAR).performClick()
        assertEquals(1, cleared)
        assertEquals(1, store.since(0).size, "the caller clears the app's store and this one")
    }
}
