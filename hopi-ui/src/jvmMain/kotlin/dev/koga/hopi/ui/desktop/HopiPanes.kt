package dev.koga.hopi.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ui.DetailState
import dev.koga.hopi.ui.Toast
import dev.koga.hopi.ui.ToastHost
import dev.koga.hopi.ui.detail.DetailActions
import dev.koga.hopi.ui.detail.DetailFormatter
import dev.koga.hopi.ui.detail.DetailTab
import dev.koga.hopi.ui.detail.TransactionDetailPane
import dev.koga.hopi.ui.list.EmptyState
import dev.koga.hopi.ui.list.RowModelCache
import dev.koga.hopi.ui.list.StatusFilter
import dev.koga.hopi.ui.list.TransactionListPane
import dev.koga.hopi.ui.list.TransactionRow
import dev.koga.hopi.ui.rememberDetailActions
import dev.koga.hopi.ui.rememberDetailState
import dev.koga.hopi.ui.rememberListModel
import dev.koga.hopi.ui.rules.RuleAction
import dev.koga.hopi.ui.rules.RuleActions
import dev.koga.hopi.ui.rules.exactMatchOf
import dev.koga.hopi.ui.rules.mockRefusal
import dev.koga.hopi.ui.rules.replayRefusal
import dev.koga.hopi.ui.theme.HopiTheme
import dev.koga.hopi.ui.theme.LocalHopiColors
import kotlinx.coroutines.Dispatchers

/**
 * The selection of [HopiPanes], hoisted so a desktop window can drive it from the keyboard.
 * Moves follow the rows as shown (newest first, after search and filter).
 */
@HopiInternalApi
@Stable
public class HopiPanesState {
    /** The transaction shown in the detail pane, or null. */
    public var selectedId: Long? by mutableStateOf(null)

    // Written by the panes after each composition; read only by the moves below.
    internal var rows: List<TransactionRow> = emptyList()
    internal val searchFocus: FocusRequester = FocusRequester()

    /** The row below the selected one (older), or the first row when none is selected. */
    public fun selectNext() {
        move(+1)
    }

    /** The row above the selected one (newer), or the first row when none is selected. */
    public fun selectPrevious() {
        move(-1)
    }

    public fun clearSelection() {
        selectedId = null
    }

    /** Moves keyboard focus to the search field; a no-op before the list is first composed. */
    public fun focusSearch() {
        try {
            searchFocus.requestFocus()
        } catch (_: IllegalStateException) {
            // Not attached yet.
        }
    }

    private fun move(step: Int) {
        val shown = rows
        if (shown.isEmpty()) return
        val index = shown.indexOfFirst { it.id == selectedId }
        selectedId = shown[if (index < 0) 0 else (index + step).coerceIn(0, shown.lastIndex)].id
    }
}

@HopiInternalApi
@Composable
public fun rememberHopiPanesState(): HopiPanesState = remember { HopiPanesState() }

/**
 * The detail's rule buttons for a host viewer, each called with the transaction shown: "Mock this
 * response", "Replay" and "Edit and replay…". The viewer sends them over its bridge to the app's
 * rules; nothing here touches this process's own rule engine.
 */
@HopiInternalApi
public class HopiPanesRules(
    public val mockThis: (HttpTransaction) -> Unit,
    public val replay: (HttpTransaction) -> Unit,
    public val editAndReplay: (HttpTransaction) -> Unit,
    /** Why none of them can run now (the app has no rules, say), or null. */
    public val unavailable: String? = null,
)

/**
 * The inspector's list and detail for a host viewer (the desktop app), over any [store]: side by
 * side when at least [sideBySideMinWidth] wide, else one at a time as on a phone. [onSelected] is
 * called with every selection (null included), so the caller can fetch that transaction's body;
 * [onClear] is the trash button (the caller clears the app's store as well as [store]). Unlike
 * [dev.koga.hopi.ui.HopiScreen] it has no close button, back handler or system bar
 * handling: the window owns those. Desktop only (`jvm`), and outside the consumer API, so the
 * noop twin has no counterpart (see the ABI filters in both modules' build files).
 */
@HopiInternalApi
@Composable
public fun HopiPanes(
    store: HopiStore,
    onSelected: (Long?) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    state: HopiPanesState = rememberHopiPanesState(),
    sideBySideMinWidth: Dp = SIDE_BY_SIDE_MIN_WIDTH,
    rules: HopiPanesRules? = null,
) {
    HopiTheme {
        val colors = LocalHopiColors.current
        val formatDispatcher = Dispatchers.Default
        var tab by remember { mutableStateOf(DetailTab.Overview) }
        var filter by remember { mutableStateOf(StatusFilter.All) }
        val query = rememberTextFieldState()
        val listState = rememberLazyListState()
        val rowCache = remember(store) { RowModelCache() }
        val formatter = remember(store) { DetailFormatter() }
        var toast by remember { mutableStateOf<Toast?>(null) }

        val list by rememberListModel(store, query, { filter }, rowCache, formatDispatcher)
        val detail by rememberDetailState(store, state.selectedId, formatter, formatDispatcher)
        val model = (detail as? DetailState.Ready)?.model
        val ruleActions = remember(model, rules) { rules?.let { r -> model?.let { hostRuleActions(r, it.transaction) } } }
        val actions = rememberDetailActions(store, model, formatDispatcher, ruleActions) { toast = it }

        val shownRows = list?.rows ?: emptyList()
        SideEffect { state.rows = shownRows }
        val currentOnSelected by rememberUpdatedState(onSelected)
        LaunchedEffect(state) {
            snapshotFlow { state.selectedId }.collect { currentOnSelected(it) }
        }
        // A selection moved from the keyboard stays in view.
        LaunchedEffect(state.selectedId) { scrollIntoView(listState, state.rows, state.selectedId) }

        BoxWithConstraints(modifier.fillMaxSize().background(colors.background)) {
            val wide = maxWidth >= sideBySideMinWidth
            val listPane: @Composable () -> Unit = {
                TransactionListPane(
                    model = list,
                    query = query,
                    filter = filter,
                    onFilter = { filter = it },
                    listState = listState,
                    onOpen = { state.selectedId = it },
                    onClear = onClear,
                    onClose = null,
                    selectedId = if (wide) state.selectedId else null,
                    searchFocus = state.searchFocus,
                )
            }
            val selected = state.selectedId
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(LIST_WEIGHT).fillMaxHeight()) { listPane() }
                    Box(Modifier.fillMaxHeight().width(1.dp).background(colors.border))
                    Box(Modifier.weight(1f - LIST_WEIGHT).fillMaxHeight()) {
                        if (selected == null) {
                            EmptyState("No request selected", "Select a request to see its headers and bodies.")
                        } else {
                            DetailPane(detail, selected, tab, { tab = it }, onBack = null, actions) { state.selectedId = it }
                        }
                    }
                }
            } else if (selected == null) {
                listPane()
            } else {
                DetailPane(detail, selected, tab, { tab = it }, onBack = { state.selectedId = null }, actions) { state.selectedId = it }
            }
            ToastHost(toast, onShown = { if (toast === it) toast = null })
        }
    }
}

@Composable
private fun DetailPane(
    detail: DetailState?,
    id: Long,
    tab: DetailTab,
    onTab: (DetailTab) -> Unit,
    onBack: (() -> Unit)?,
    actions: DetailActions,
    onOpen: (Long) -> Unit,
) {
    TransactionDetailPane(
        model = (detail as? DetailState.Ready)?.model?.takeIf { it.transaction.id == id },
        missing = detail is DetailState.Missing,
        tab = tab,
        onTab = onTab,
        onBack = onBack,
        actions = actions,
        onOpen = onOpen,
    )
}

/** What a viewer can tell from its copy; the app refuses the rest itself, and the viewer shows why. */
private fun hostRuleActions(rules: HopiPanesRules, transaction: HttpTransaction): RuleActions {
    val off = rules.unavailable
    val replayRefusal = off ?: replayRefusal(transaction)
    return RuleActions(
        mock = RuleAction({ rules.mockThis(transaction) }, off ?: mockRefusal(transaction) ?: if (exactMatchOf(transaction) == null) "URL cannot be matched" else null),
        replay = RuleAction({ rules.replay(transaction) }, replayRefusal),
        editAndReplay = RuleAction({ rules.editAndReplay(transaction) }, replayRefusal),
    )
}

private suspend fun scrollIntoView(listState: LazyListState, rows: List<TransactionRow>, id: Long?) {
    val index = rows.indexOfFirst { it.id == id }
    if (index < 0) return
    val visible = listState.layoutInfo.visibleItemsInfo
    val first = visible.firstOrNull()?.index ?: return
    val last = visible.last().index
    // The last visible row may be cut by the bottom edge: treat it as out of view.
    if (index < first || index >= last) listState.scrollToItem(if (index < first) index else maxOf(0, index - (last - first) + 1))
}

/** Below this the panes take turns, as on a phone. */
private val SIDE_BY_SIDE_MIN_WIDTH = 720.dp
private const val LIST_WEIGHT = 0.42f
