package dev.koga.hopi.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.koga.hopi.core.perf.PerfHook
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RulePatch
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ui.detail.BodyView
import dev.koga.hopi.ui.detail.DetailActions
import dev.koga.hopi.ui.detail.DetailFormatter
import dev.koga.hopi.ui.detail.DetailModel
import dev.koga.hopi.ui.detail.DetailTab
import dev.koga.hopi.ui.detail.TransactionDetailPane
import dev.koga.hopi.ui.detail.curlBodyWasCut
import dev.koga.hopi.ui.detail.curlOf
import dev.koga.hopi.ui.detail.shareTextOf
import dev.koga.hopi.ui.list.ListModel
import dev.koga.hopi.ui.list.RowModelCache
import dev.koga.hopi.ui.list.StatusFilter
import dev.koga.hopi.ui.list.TransactionListPane
import dev.koga.hopi.ui.list.filterRows
import dev.koga.hopi.ui.perf.PerfPane
import dev.koga.hopi.ui.platform.MAX_SHARE_CHARS
import dev.koga.hopi.ui.platform.SystemBarIcons
import dev.koga.hopi.ui.platform.cutAt
import dev.koga.hopi.ui.platform.rememberShareSheet
import dev.koga.hopi.ui.rules.InAppOutcome
import dev.koga.hopi.ui.rules.RuleActions
import dev.koga.hopi.ui.rules.RulesPane
import dev.koga.hopi.ui.rules.inAppRuleActions
import dev.koga.hopi.ui.rules.rememberRuleRows
import dev.koga.hopi.ui.theme.HopiTheme
import dev.koga.hopi.ui.theme.HopiType
import dev.koga.hopi.ui.theme.LocalHopiColors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The HTTP inspector, full screen: the captured requests, newest first, and the detail of one.
 * The back gesture leaves a detail, then calls [onClose]. It collects the process-wide store only
 * while it is composed.
 */
@Composable
public fun HopiScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    HopiScreen(HopiStore.default, onClose, modifier)
}

/** [HopiScreen] over any [store]; [formatDispatcher] runs row building and detail formatting. */
@Composable
internal fun HopiScreen(
    store: HopiStore,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    formatDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    HopiTheme {
        val colors = LocalHopiColors.current
        // The screen covers the host's bars area: its icons must read against our background.
        SystemBarIcons(lightBackground = colors.background.luminance() > 0.5f)
        var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
        var tab by rememberSaveable { mutableStateOf(DetailTab.Overview) }
        var filter by rememberSaveable { mutableStateOf(StatusFilter.All) }
        val query = rememberTextFieldState()
        val listState = rememberLazyListState()
        val rowCache = remember(store) { RowModelCache() }
        val formatter = remember(store) { DetailFormatter() }
        var toast by remember { mutableStateOf<Toast?>(null) }
        var showRules by rememberSaveable { mutableStateOf(false) }
        var showPerf by rememberSaveable { mutableStateOf(false) }
        // Read once: an app without hopi-perf loads core's PerfHook object and nothing else.
        val perf = remember { PerfHook.controller }
        if (perf != null) {
            // Frames drawn while the inspector covers the app are flagged and left out of the figures.
            DisposableEffect(perf) {
                perf.data.inspectorOpen = true
                onDispose { perf.data.inspectorOpen = false }
            }
        }

        // The store is collected here, while the screen is composed, never before.
        val list by rememberListModel(store, query, { filter }, rowCache, formatDispatcher)
        val detail by rememberDetailState(store, selectedId, formatter, formatDispatcher)
        val model = (detail as? DetailState.Ready)?.model
        val scope = rememberCoroutineScope()
        val rules = remember(store, model) {
            model?.let { m ->
                inAppRuleActions(m.transaction, scope, formatDispatcher) { outcome ->
                    toast = when (outcome) {
                        is InAppOutcome.Mocked -> Toast("Mock rule #${outcome.ruleId} added", action = "Undo") { RuleEngine.remove(outcome.ruleId) }
                        is InAppOutcome.Refused -> Toast(outcome.message.replaceFirstChar { it.uppercase() })
                    }
                }
            }
        }
        val actions = rememberDetailActions(store, model, formatDispatcher, rules) { toast = it }

        // ui-backhandler's BackHandler: the system back gesture on Android, the edge swipe on iOS.
        // Deprecated in favor of navigationevent's handler, which would add a dependency.
        @Suppress("DEPRECATION")
        BackHandler(enabled = true) {
            when {
                selectedId != null -> selectedId = null
                showRules -> showRules = false
                showPerf -> showPerf = false
                else -> onClose()
            }
        }

        Box(
            modifier
                .fillMaxSize()
                .background(colors.background)
                // Touches stop here instead of reaching the app underneath.
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } },
        ) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                AnimatedContent(
                    // The rules list sits above the request list, like a detail; a detail above both.
                    targetState = selectedId ?: if (showRules) RULES_PANE else if (showPerf && perf != null) PERF_PANE else null,
                    transitionSpec = {
                        val forward = targetState != null
                        val enter = fadeIn(tween(160)) + slideInHorizontally(tween(200)) { if (forward) it / 8 else -it / 8 }
                        val exit = fadeOut(tween(120)) + slideOutHorizontally(tween(200)) { if (forward) -it / 8 else it / 8 }
                        enter togetherWith exit
                    },
                    label = "hopi-pane",
                ) { id ->
                    if (id == PERF_PANE && perf != null) {
                        PerfPane(perf, formatDispatcher, onBack = { showPerf = false }, onMessage = { toast = Toast(it) })
                    } else if (id == RULES_PANE) {
                        val rows by rememberRuleRows(store, formatDispatcher)
                        RulesPane(
                            rows = rows,
                            rulesOff = !RuleEngine.rulesAvailable,
                            onBack = { showRules = false },
                            onToggle = { ruleId, enabled -> RuleEngine.update(ruleId, RulePatch(enabled = enabled)) },
                            onRemove = { ruleId -> RuleEngine.remove(ruleId) },
                            onRemoveAll = {
                                val removed = RuleEngine.clear()
                                toast = Toast(if (removed == 1) "1 rule removed" else "$removed rules removed")
                            },
                        )
                    } else if (id == null) {
                        TransactionListPane(
                            model = list,
                            query = query,
                            filter = filter,
                            onFilter = { filter = it },
                            listState = listState,
                            onOpen = {
                                tab = DetailTab.Overview
                                selectedId = it
                            },
                            onClear = { store.clear() },
                            onClose = onClose,
                            onRules = { showRules = true },
                            onPerf = if (perf != null) {
                                { showPerf = true }
                            } else {
                                null
                            },
                        )
                    } else {
                        val shown = detail
                        TransactionDetailPane(
                            model = (shown as? DetailState.Ready)?.model?.takeIf { it.transaction.id == id },
                            missing = shown is DetailState.Missing,
                            tab = tab,
                            onTab = { tab = it },
                            onBack = { selectedId = null },
                            actions = actions,
                        )
                    }
                }
                ToastHost(toast, onShown = { if (toast === it) toast = null })
            }
        }
    }
}

internal sealed interface DetailState {
    data object Missing : DetailState

    class Ready(val model: DetailModel) : DetailState
}

/**
 * The list rows for [store], filtered by [query] and [filter]. The store is collected only while
 * the caller is composed; rows are built off the main thread, and only for snapshots the cache
 * has not seen.
 */
@Composable
internal fun rememberListModel(
    store: HopiStore,
    query: TextFieldState,
    /** Read inside a snapshot flow: a state read, so a new filter re-filters at once. */
    filter: () -> StatusFilter,
    rowCache: RowModelCache,
    formatDispatcher: CoroutineDispatcher,
): State<ListModel?> =
    produceState<ListModel?>(null, store, formatDispatcher) {
        // The list on screen is a viewer: requests in flight show as pending the moment they start.
        val detachViewer = store.attachViewer()
        try {
            // A redactor that grew (another client installed the plugin) rebuilds the rows at once.
            val rows = combine(store.transactions, store.redactors) { transactions, _ -> transactions }
                .map { rowCache.rowsFor(it, store.redactor) }
                .conflate()
                .flowOn(formatDispatcher)
            val criteria = snapshotFlow { query.text.toString() to filter() }.distinctUntilChanged()
            combine(rows, criteria) { all, (text, status) -> filterRows(all, text, status) }
                .conflate()
                .flowOn(formatDispatcher)
                .collect { value = it }
        } finally {
            detachViewer()
        }
    }

/** The detail of [selectedId] in [store], formatted off the main thread; null while formatting. */
@Composable
internal fun rememberDetailState(
    store: HopiStore,
    selectedId: Long?,
    formatter: DetailFormatter,
    formatDispatcher: CoroutineDispatcher,
): State<DetailState?> = produceState<DetailState?>(null, store, selectedId, formatDispatcher) {
    // A new selection never shows the previous one's state, not even for a frame.
    value = null
    val id = selectedId ?: return@produceState
    // The store's redactor grows when another client installs the plugin: a new redactor is a
    // change too, at once, so headers it now redacts do not stay readable on an open detail.
    combine(store.transactions, store.redactors) { _, _ -> store.get(id) to store.redactor }
        .distinctUntilChanged { old, new -> old.first === new.first && old.second === new.second }
        .collectLatest { (transaction, redactor) ->
            value = if (transaction == null) {
                DetailState.Missing
            } else {
                DetailState.Ready(withContext(formatDispatcher) { formatter.model(transaction, redactor) })
            }
        }
}

/** Copy and share for the detail of [model], plus [rules] when given; [onToast] shows the confirmation. */
@Composable
internal fun rememberDetailActions(
    store: HopiStore,
    model: DetailModel?,
    formatDispatcher: CoroutineDispatcher,
    rules: RuleActions? = null,
    onToast: (Toast) -> Unit,
): DetailActions {
    val scope = rememberCoroutineScope()
    val toast = rememberUpdatedState(onToast)
    @Suppress("DEPRECATION") // The text-only clipboard API is the one common to every target.
    val clipboard = LocalClipboardManager.current
    val shareSheet = rememberShareSheet()
    return remember(model, clipboard, shareSheet, rules) {
        // The clip crosses a binder transaction on Android: it is cut like the share text, and a
        // clipboard that still refuses it shows a toast instead of crashing the app.
        fun copy(label: String, cutInside: (String) -> Boolean = { false }, text: suspend () -> String) {
            scope.launch {
                val (full, clip) = withContext(formatDispatcher) { text().let { it to cutAt(it) } }
                toast.value(
                    try {
                        clipboard.setText(AnnotatedString(clip))
                        Toast(
                            when {
                                clip !== full -> "$label copied (first ${MAX_SHARE_CHARS / 1_000}k characters)"
                                cutInside(full) -> "$label copied (request body cut to fit ${MAX_SHARE_CHARS / 1_000}k characters)"
                                else -> "$label copied"
                            },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: RuntimeException) {
                        Toast("$label could not be copied")
                    },
                )
            }
        }
        DetailActions(
            copyCurl = { model?.let { m -> copy("cURL", ::curlBodyWasCut) { curlOf(m.transaction, store.redactor) } } },
            // The redactor in force now, not the one the model was built with: it grows when another
            // client installs the plugin, and the clip never waits for the open detail to follow it.
            copyUrl = { model?.let { m -> copy("URL") { store.redactor.url(m.transaction.url) } } },
            copyBody = {
                (model?.response?.body as? BodyView.Text)?.let { body ->
                    // One character more than the cap, so the copy knows it was cut.
                    copy("Response body") { body.pieces.join(maxChars = MAX_SHARE_CHARS + 1) }
                }
            },
            share = shareSheet?.let { sheet ->
                {
                    model?.let { m ->
                        scope.launch {
                            val text = withContext(formatDispatcher) { shareTextOf(m, store.redactor) }
                            sheet.share(m.title, text)
                        }
                    }
                }
            },
            rules = rules,
        )
    }
}

/** A short confirmation; each instance is shown once. [action] (Undo) runs [onAction] and dismisses it. */
internal class Toast(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

/** The [AnimatedContent] keys of the rules list and the Perf tab; transaction ids start at 1. */
private const val RULES_PANE = -1L
private const val PERF_PANE = -2L

@Composable
internal fun ToastHost(toast: Toast?, onShown: (Toast) -> Unit) {
    val colors = LocalHopiColors.current
    // Keeps the last text while the toast fades out; plain memory, not state, so it never recomposes.
    val last = remember { arrayOfNulls<String>(1) }
    if (toast != null) last[0] = toast.text
    val action = remember { arrayOfNulls<Toast>(1) }
    if (toast != null) action[0] = toast
    LaunchedEffect(toast) {
        if (toast != null) {
            // Long enough to reach an Undo.
            delay(if (toast.action != null) ACTION_TOAST_MS else TOAST_MS)
            onShown(toast)
        }
    }
    Box(Modifier.fillMaxSize().padding(bottom = 24.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = toast != null, enter = fadeIn(tween(120)), exit = fadeOut(tween(160))) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(colors.text).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    last[0] ?: "",
                    modifier = Modifier
                        .padding(end = 10.dp, top = 10.dp, bottom = 10.dp)
                        // Announced by TalkBack and VoiceOver: focus stays on the button that copied.
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = HopiType.label.copy(color = colors.onText),
                )
                val shown = action[0]
                if (shown?.action != null) {
                    BasicText(
                        shown.action,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(role = Role.Button) {
                                shown.onAction?.invoke()
                                onShown(shown)
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        style = HopiType.label.copy(color = colors.onText, textDecoration = TextDecoration.Underline),
                    )
                }
            }
        }
    }
}

private const val TOAST_MS = 1_600L
private const val ACTION_TOAST_MS = 4_000L
