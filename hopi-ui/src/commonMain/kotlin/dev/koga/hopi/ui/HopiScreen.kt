package dev.koga.hopi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ui.detail.BodyView
import dev.koga.hopi.ui.detail.DetailActions
import dev.koga.hopi.ui.detail.DetailFormatter
import dev.koga.hopi.ui.detail.DetailModel
import dev.koga.hopi.ui.detail.curlBodyWasCut
import dev.koga.hopi.ui.detail.curlOf
import dev.koga.hopi.ui.detail.shareTextOf
import dev.koga.hopi.ui.list.ListModel
import dev.koga.hopi.ui.list.RowModelCache
import dev.koga.hopi.ui.list.StatusFilter
import dev.koga.hopi.ui.list.filterRows
import dev.koga.hopi.ui.platform.MAX_SHARE_CHARS
import dev.koga.hopi.ui.platform.cutAt
import dev.koga.hopi.ui.platform.rememberShareSheet
import dev.koga.hopi.ui.rules.RuleActions
import dev.koga.hopi.ui.theme.HopiType
import dev.koga.hopi.ui.theme.LocalHopiColors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    @Suppress("DEPRECATION") // Compose's text clipboard: the panes copy plain text only.
    val clipboard = LocalClipboardManager.current
    val shareSheet = rememberShareSheet()
    return remember(model, clipboard, shareSheet, rules) {
        // The clip is cut at MAX_SHARE_CHARS (kept from the removed in-app UI's Android limit), and
        // a clipboard that throws shows a toast instead of failing the copy.
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

/** A short confirmation; each instance is shown once. */
internal class Toast(val text: String)

@Composable
internal fun ToastHost(toast: Toast?, onShown: (Toast) -> Unit) {
    val colors = LocalHopiColors.current
    // Keeps the last text while the toast fades out; plain memory, not state, so it never recomposes.
    val last = remember { arrayOfNulls<String>(1) }
    if (toast != null) last[0] = toast.text
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(TOAST_MS)
            onShown(toast)
        }
    }
    Box(Modifier.fillMaxSize().padding(bottom = 24.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = toast != null, enter = fadeIn(tween(120)), exit = fadeOut(tween(160))) {
            BasicText(
                last[0] ?: "",
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.text)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    // Announced by screen readers: focus stays on the button that copied.
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = HopiType.label.copy(color = colors.onText),
            )
        }
    }
}

private const val TOAST_MS = 1_600L
