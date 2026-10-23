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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
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
import dev.koga.hopi.ui.platform.MAX_SHARE_CHARS
import dev.koga.hopi.ui.platform.SystemBarIcons
import dev.koga.hopi.ui.platform.cutAt
import dev.koga.hopi.ui.platform.rememberShareSheet
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
        val scope = rememberCoroutineScope()
        var toast by remember { mutableStateOf<Toast?>(null) }

        // The store is collected here, while the screen is composed, never before. Rows are built
        // off the main thread, and only for snapshots the cache has not seen.
        val list by produceState<ListModel?>(null, store, formatDispatcher) {
            val rows = store.transactions
                .map { rowCache.rowsFor(it, store.redactor) }
                .conflate()
                .flowOn(formatDispatcher)
            val criteria = snapshotFlow { query.text.toString() to filter }.distinctUntilChanged()
            combine(rows, criteria) { all, (text, status) -> filterRows(all, text, status) }
                .conflate()
                .flowOn(formatDispatcher)
                .collect { value = it }
        }

        val detail by produceState<DetailState?>(null, store, selectedId, formatDispatcher) {
            // A new selection never shows the previous one's state, not even for a frame.
            value = null
            val id = selectedId ?: return@produceState
            // The store's redactor grows when another client installs the plugin: a new redactor is a
            // change too, so headers it now redacts do not stay readable on an open detail.
            store.transactions
                .map { store.get(id) to store.redactor }
                .distinctUntilChanged { old, new -> old.first === new.first && old.second === new.second }
                .collectLatest { (transaction, redactor) ->
                    value = if (transaction == null) {
                        DetailState.Missing
                    } else {
                        DetailState.Ready(withContext(formatDispatcher) { formatter.model(transaction, redactor) })
                    }
                }
        }

        @Suppress("DEPRECATION") // The text-only clipboard API is the one common to every target.
        val clipboard = LocalClipboardManager.current
        val shareSheet = rememberShareSheet()
        val model = (detail as? DetailState.Ready)?.model
        val actions = remember(model, clipboard, shareSheet) {
            // The clip crosses a binder transaction on Android: it is cut like the share text, and a
            // clipboard that still refuses it shows a toast instead of crashing the app.
            fun copy(label: String, cutInside: (String) -> Boolean = { false }, text: suspend () -> String) {
                scope.launch {
                    val (full, clip) = withContext(formatDispatcher) { text().let { it to cutAt(it) } }
                    toast = try {
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
                    }
                }
            }
            DetailActions(
                copyCurl = { model?.let { m -> copy("cURL", ::curlBodyWasCut) { curlOf(m.transaction, store.redactor) } } },
                copyUrl = { model?.let { m -> copy("URL") { m.url } } },
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
            )
        }

        // ui-backhandler's BackHandler: the system back gesture on Android, the edge swipe on iOS.
        // Deprecated in favor of navigationevent's handler, which would add a dependency.
        @Suppress("DEPRECATION")
        BackHandler(enabled = true) {
            if (selectedId != null) selectedId = null else onClose()
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
                    targetState = selectedId,
                    transitionSpec = {
                        val forward = targetState != null
                        val enter = fadeIn(tween(160)) + slideInHorizontally(tween(200)) { if (forward) it / 8 else -it / 8 }
                        val exit = fadeOut(tween(120)) + slideOutHorizontally(tween(200)) { if (forward) -it / 8 else it / 8 }
                        enter togetherWith exit
                    },
                    label = "hopi-pane",
                ) { id ->
                    if (id == null) {
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

private sealed interface DetailState {
    data object Missing : DetailState

    class Ready(val model: DetailModel) : DetailState
}

/** A short confirmation; each instance is shown once. */
private class Toast(val text: String)

@Composable
private fun ToastHost(toast: Toast?, onShown: (Toast) -> Unit) {
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
                    // Announced by TalkBack and VoiceOver: focus stays on the button that copied.
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = HopiType.label.copy(color = colors.onText),
            )
        }
    }
}

private const val TOAST_MS = 1_600L
