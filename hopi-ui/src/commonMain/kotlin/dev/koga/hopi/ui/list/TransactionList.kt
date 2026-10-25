package dev.koga.hopi.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.koga.hopi.ui.component.FilterChip
import dev.koga.hopi.ui.component.HairlineDivider
import dev.koga.hopi.ui.component.Icon
import dev.koga.hopi.ui.component.IconButton
import dev.koga.hopi.ui.component.Tag
import dev.koga.hopi.ui.theme.HopiColors
import dev.koga.hopi.ui.theme.HopiIcons
import dev.koga.hopi.ui.theme.HopiType
import dev.koga.hopi.ui.theme.LocalHopiColors

/** Accessibility descriptions of the list's controls; the UI tests find controls by them too. */
internal object ListTags {
    const val SEARCH = "Search URL"
    const val CLOSE = "Close Hopi"
    const val CLEAR = "Clear all requests"
    const val RULES = "Rules"
}

@Composable
internal fun TransactionListPane(
    model: ListModel?,
    query: TextFieldState,
    filter: StatusFilter,
    onFilter: (StatusFilter) -> Unit,
    listState: LazyListState,
    onOpen: (Long) -> Unit,
    onClear: () -> Unit,
    /** Null hides the close button (the desktop window closes itself). */
    onClose: (() -> Unit)?,
    /** The row shown beside the list (desktop panes); null in the full-screen list. */
    selectedId: Long? = null,
    searchFocus: FocusRequester? = null,
    /** Opens the rules list (the in-app screen); null hides the button. */
    onRules: (() -> Unit)? = null,
) {
    val colors = LocalHopiColors.current
    Column(Modifier.fillMaxSize()) {
        Row(
            // Minimum sizes, not fixed ones, here and below: a large font scale grows a control
            // instead of clipping its text.
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("Hopi", style = HopiType.title.copy(color = colors.text))
            if (model != null && model.total > 0) {
                Spacer(Modifier.width(8.dp))
                BasicText(model.total.toString(), style = HopiType.meta.copy(color = colors.textFaint))
            }
            Spacer(Modifier.weight(1f))
            if (onRules != null) IconButton(HopiIcons.Rules, ListTags.RULES, onRules)
            IconButton(HopiIcons.Trash, ListTags.CLEAR, onClear, enabled = model != null && model.total > 0)
            if (onClose != null) IconButton(HopiIcons.Close, ListTags.CLOSE, onClose)
        }
        SearchField(query, searchFocus)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 13.dp),
        ) {
            for (option in StatusFilter.entries) {
                FilterChip(
                    label = option.label,
                    count = model?.counts?.get(option.ordinal) ?: 0,
                    selected = option == filter,
                    onClick = { onFilter(option) },
                )
            }
        }
        HairlineDivider()
        when {
            // Rows are still being built off the main thread: show nothing for that one frame.
            model == null -> Unit
            model.rows.isEmpty() -> EmptyState(
                title = if (model.total == 0) "No requests yet" else "No matching requests",
                hint = if (model.total == 0) {
                    "Requests made by a client with the Hopi plugin appear here."
                } else {
                    "Change the search or the status filter."
                },
            )
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(model.rows, key = { it.id }, contentType = { "row" }) { row ->
                    TransactionRowItem(row, colors, onOpen, selected = row.id == selectedId)
                }
            }
        }
    }
}

@Composable
private fun SearchField(state: TextFieldState, focus: FocusRequester?) {
    val colors = LocalHopiColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HopiIcons.Search, colors.textFaint, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (state.text.isEmpty()) {
                BasicText(ListTags.SEARCH, style = HopiType.body.copy(color = colors.textFaint))
            }
            BasicTextField(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                    .semantics { contentDescription = ListTags.SEARCH },
                textStyle = HopiType.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.text),
                lineLimits = TextFieldLineLimits.SingleLine,
            )
        }
        if (state.text.isNotEmpty()) {
            IconButton(HopiIcons.Close, "Clear search", onClick = { state.clearText() })
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun TransactionRowItem(row: TransactionRow, colors: HopiColors, onOpen: (Long) -> Unit, selected: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(colors.surface).semantics { this.selected = true } else Modifier)
            .clickable(onClickLabel = "Open details") { onOpen(row.id) }
            .heightIn(min = 60.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(row, colors)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(row.method, style = HopiType.monoStrong.copy(color = colors.text), maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    for (tag in row.tags) {
                        Tag(tag)
                        Spacer(Modifier.width(6.dp))
                    }
                    BasicText(
                        row.path,
                        modifier = Modifier.weight(1f),
                        style = HopiType.bodyStrong.copy(color = colors.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                BasicText(
                    row.subtitle,
                    style = HopiType.meta.copy(color = colors.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                BasicText(row.duration, style = HopiType.meta.copy(color = colors.text), maxLines = 1)
                if (row.size.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    BasicText(row.size, style = HopiType.meta.copy(color = colors.textFaint), maxLines = 1)
                }
            }
        }
        HairlineDivider(Modifier.padding(start = 72.dp))
    }
}

/**
 * Status in monochrome, by weight: success is a soft fill, redirects quieter still, client errors
 * outlined, server errors and failures solid, pending a dashed outline.
 */
@Composable
private fun StatusBadge(row: TransactionRow, colors: HopiColors) {
    val shape = RoundedCornerShape(6.dp)
    val base = Modifier
        .widthIn(min = 44.dp)
        .heightIn(min = 26.dp)
        .clip(shape)
        .clearAndSetSemantics { contentDescription = row.badgeDescription }
    val (modifier, color) = when (row.status) {
        StatusClass.Success, StatusClass.Informational -> base.background(colors.surfaceStrong) to colors.text
        StatusClass.Redirect -> base.background(colors.surface) to colors.textMuted
        StatusClass.ClientError -> base.border(1.5.dp, colors.text, shape) to colors.text
        StatusClass.ServerError, StatusClass.Failed -> base.background(colors.text) to colors.onText
        StatusClass.Pending -> base.drawBehind {
            val stroke = 1.dp.toPx()
            drawRoundRect(
                color = colors.textFaint,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
        } to colors.textMuted
    }
    Box(modifier.padding(horizontal = 4.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        // Never wrapped: a code that does not fit widens the badge instead of losing its last digit.
        BasicText(
            row.badge,
            style = HopiType.badge.copy(color = color, textAlign = TextAlign.Center),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
internal fun EmptyState(title: String, hint: String) {
    val colors = LocalHopiColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(title, style = HopiType.bodyStrong.copy(color = colors.text, textAlign = TextAlign.Center))
        Spacer(Modifier.height(6.dp))
        BasicText(hint, style = HopiType.meta.copy(color = colors.textMuted, textAlign = TextAlign.Center))
    }
}
