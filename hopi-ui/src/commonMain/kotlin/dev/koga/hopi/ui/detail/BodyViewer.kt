package dev.koga.hopi.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.koga.hopi.ui.component.Icon
import dev.koga.hopi.ui.component.IconButton
import dev.koga.hopi.ui.theme.HopiColors
import dev.koga.hopi.ui.theme.HopiIcons
import dev.koga.hopi.ui.theme.HopiRadius
import dev.koga.hopi.ui.theme.HopiType
import dev.koga.hopi.ui.theme.LocalHopiColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

internal object BodyViewerTags {
    const val EXPAND = "Expand body"
    const val CLOSE = "Close body"
    const val SEARCH = "Search in body"
    const val NEXT = "Next match"
    const val PREVIOUS = "Previous match"
}

/** One piece of a body in the detail or the viewer: JSON in colors, the search's matches marked. */
@Composable
internal fun BodyPiece(
    piece: String,
    state: Int?,
    palette: JsonPalette,
    modifier: Modifier = Modifier,
    matches: List<IntRange> = emptyList(),
    current: IntRange? = null,
) {
    val colors = LocalHopiColors.current
    // At most a piece's 4096 chars, and only when its piece, the search or the theme changed.
    val text = remember(piece, state, palette, matches, current) { highlightPiece(piece, state, palette, matches, current) }
    BasicText(text, modifier = modifier, style = HopiType.mono.copy(color = colors.text))
}

/**
 * [body] over the whole window: the same lazy pieces with room to read, and a search that finds
 * every occurrence (ignoring case) and steps through them (Enter, Shift+Enter, or the arrows).
 * Escape or the close button dismiss it.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun BodyViewer(title: String, body: BodyView.Text, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val colors = LocalHopiColors.current
        val palette = remember(colors) { JsonPalette.of(colors) }
        val query = rememberTextFieldState()
        val listState = rememberLazyListState()
        var matches by remember { mutableStateOf(emptyList<BodyMatch>()) }
        var current by remember { mutableIntStateOf(0) }
        val focus = remember { FocusRequester() }
        // Found off the main thread: a 16 MB body is searched in tens of milliseconds.
        LaunchedEffect(body) {
            snapshotFlow { query.text.toString() }.debounce(120).collectLatest { text ->
                matches = withContext(Dispatchers.Default) { findInBody(body.chunks, text) }
                current = 0
            }
        }
        LaunchedEffect(matches, current) {
            matches.getOrNull(current)?.let { listState.animateScrollToItem(it.piece) }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
        val byPiece = remember(matches) { matches.groupBy({ it.piece }, { it.range }) }
        val step = { delta: Int -> if (matches.isNotEmpty()) current = (current + delta).mod(matches.size) }
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp)
                .clip(HopiRadius.lg)
                .background(colors.pane)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            step(if (event.isShiftPressed) -1 else 1)
                            true
                        }
                        Key.Escape -> {
                            onClose()
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText(title, style = HopiType.title.copy(color = colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(body.summary, style = HopiType.meta.copy(color = colors.textFaint), maxLines = 1)
                }
                SearchBox(query, focus, colors, Modifier.width(360.dp))
                Spacer(Modifier.width(8.dp))
                val count = when {
                    query.text.isEmpty() -> ""
                    matches.isEmpty() -> "No matches"
                    matches.size >= MAX_BODY_MATCHES -> "${current + 1} of ${MAX_BODY_MATCHES}+"
                    else -> "${current + 1} of ${matches.size}"
                }
                BasicText(count, modifier = Modifier.width(96.dp), style = HopiType.meta.copy(color = colors.textMuted), maxLines = 1)
                IconButton(HopiIcons.ChevronUp, BodyViewerTags.PREVIOUS, { step(-1) }, enabled = matches.isNotEmpty())
                IconButton(HopiIcons.ChevronDown, BodyViewerTags.NEXT, { step(1) }, enabled = matches.isNotEmpty())
                IconButton(HopiIcons.Close, BodyViewerTags.CLOSE, onClose)
            }
            body.notice?.let { notice ->
                BasicText(
                    notice,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(HopiRadius.md)
                        .background(colors.warning.bg)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = HopiType.meta.copy(color = colors.warning.fg),
                )
            }
            val currentMatch = matches.getOrNull(current)
            SelectionContainer(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(bottom = 16.dp)) {
                    itemsIndexed(body.chunks, key = { index, _ -> index }, contentType = { _, _ -> "chunk" }) { index, chunk ->
                        BodyPiece(
                            chunk,
                            body.pieceStates?.get(index)?.toInt(),
                            palette,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            matches = byPiece[index].orEmpty(),
                            current = currentMatch?.takeIf { it.piece == index }?.range,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBox(state: androidx.compose.foundation.text.input.TextFieldState, focus: FocusRequester, colors: HopiColors, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(HopiRadius.full)
            .background(colors.containerHigh)
            .then(if (focused) Modifier.border(2.dp, colors.ring, HopiRadius.full) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HopiIcons.Search, colors.textFaint, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (state.text.isEmpty()) BasicText(BodyViewerTags.SEARCH, style = HopiType.body.copy(color = colors.textFaint))
            BasicTextField(
                state = state,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = BodyViewerTags.SEARCH },
                textStyle = HopiType.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.text),
                lineLimits = TextFieldLineLimits.SingleLine,
                interactionSource = source,
            )
        }
    }
}
