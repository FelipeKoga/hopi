package dev.koga.hopi.ui.list

import dev.koga.hopi.ui.component.pressScale
import dev.koga.hopi.ui.theme.HopiMotion
import androidx.compose.foundation.LocalIndication
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import dev.koga.hopi.ui.component.focusRing
import dev.koga.hopi.ui.theme.HopiColors
import dev.koga.hopi.ui.theme.HopiIcons
import dev.koga.hopi.ui.theme.HopiRadius
import dev.koga.hopi.ui.theme.HopiTone
import dev.koga.hopi.ui.theme.HopiType
import dev.koga.hopi.ui.theme.LocalHopiColors

/** Accessibility descriptions of the list's controls; the UI tests find controls by them too. */
internal object ListTags {
    const val SEARCH = "Search URL"
    const val CLEAR = "Clear all requests"
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
    /** The row shown beside the list; null while the panes take turns. */
    selectedId: Long? = null,
    searchFocus: FocusRequester? = null,
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
            IconButton(HopiIcons.Trash, ListTags.CLEAR, onClear, enabled = model != null && model.total > 0)
        }
        SearchField(query, searchFocus)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 13.dp),
        ) {
            for (option in StatusFilter.entries) {
                val count = model?.counts?.get(option.ordinal) ?: 0
                FilterChip(
                    label = option.label,
                    count = count,
                    selected = option == filter,
                    onClick = { onFilter(option) },
                    countColor = option.tone(colors)?.takeIf { count > 0 }?.fg ?: colors.textFaint,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        when {
            // Rows are still being built off the main thread: show nothing for that one frame.
            model == null -> Unit
            model.rows.isEmpty() -> {
                // Emptied (a Clear): the rows that come next start at the top. The state keeps the
                // cleared rows' position while no list is shown, and the next ones would open there.
                if (model.total == 0) SideEffect { listState.requestScrollToItem(0) }
                EmptyState(
                    title = if (model.total == 0) "No requests yet" else "No matching requests",
                    hint = if (model.total == 0) {
                        "Requests made by a client with the Hopi plugin appear here."
                    } else {
                        "Change the search or the status filter."
                    },
                )
            }
            else -> {
                StayOnNewest(listState, newest = model.rows[0].id)
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(model.rows, key = { it.id }, contentType = { "row" }) { row ->
                        // New requests fade and slide in, and the list makes room on a spring.
                        TransactionRowItem(
                            row,
                            colors,
                            onOpen,
                            selected = row.id == selectedId,
                            modifier = Modifier.animateItem(
                                fadeInSpec = HopiMotion.defaultEffects(),
                                placementSpec = HopiMotion.defaultSpatial(),
                                fadeOutSpec = HopiMotion.fastEffects(),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Keeps a list that is at its top there when newer rows arrive. Rows are keyed by id, and a lazy
 * list keeps its first visible row in place when rows are added above it, so new requests would
 * land above the visible area of a list nobody scrolled. A list scrolled away from its top, by hand
 * or by a desktop selection moved from the keyboard, is left where it is.
 */
@Composable
private fun StayOnNewest(listState: LazyListState, newest: Long) {
    // After composition, before the new rows are measured: the position read is the one shown with
    // the previous rows, and the request applies to the measure that brings the new ones. The newest
    // row is compared with the row last shown, not with the last composition's, so a list that comes
    // back (from a detail, say) with rows that arrived while it was gone stays on newest too.
    SideEffect {
        val shownFirst = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
        if (shownFirst != null && shownFirst != newest &&
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        ) {
            listState.requestScrollToItem(0)
        }
    }
}

@Composable
private fun SearchField(state: TextFieldState, focus: FocusRequester?) {
    val colors = LocalHopiColors.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .focusRing(if (focused) colors.ring else null)
            .heightIn(min = 48.dp)
            .clip(HopiRadius.full)
            // M3 Expressive's search bar: a filled pill, outlined in primary while focused.
            .background(colors.containerHigh)
            .then(if (focused) Modifier.border(2.dp, colors.ring, HopiRadius.full) else Modifier)
            .padding(start = 16.dp),
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
                interactionSource = source,
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
private fun TransactionRowItem(row: TransactionRow, colors: HopiColors, onOpen: (Long) -> Unit, selected: Boolean, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val fill by animateColorAsState(if (selected) colors.secondaryContainer else colors.containerHigh, HopiMotion.defaultEffects(), label = "row")
    // M3 Expressive's list: each request a tonal card with large corners and a small gap, the
    // selected one in secondaryContainer.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .pressScale(source, pressedScale = 0.985f)
            .clip(HopiRadius.lg)
            .background(fill)
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
            .clickable(interactionSource = source, indication = LocalIndication.current, onClickLabel = "Open details") { onOpen(row.id) }
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
                        Tag(tag, tagTone(tag, colors))
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
    }
}

/**
 * Status by hue, and by shape and weight for readers who do not see hue: 2xx and 3xx a tinted fill,
 * 4xx outlined, 5xx and failures solid red, 1xx a neutral fill, pending a dashed outline.
 */
@Composable
private fun StatusBadge(row: TransactionRow, colors: HopiColors) {
    val shape = HopiRadius.md
    val base = Modifier
        .widthIn(min = 48.dp)
        .heightIn(min = 30.dp)
        .clip(shape)
        .clearAndSetSemantics { contentDescription = row.badgeDescription }
    val (modifier, color) = when (row.status) {
        StatusClass.Success -> base.background(colors.success.bg) to colors.success.fg
        StatusClass.Redirect -> base.background(colors.info.bg) to colors.info.fg
        StatusClass.ClientError -> base.background(colors.warning.bg).border(1.5.dp, colors.warning.fg, shape) to colors.warning.fg
        StatusClass.ServerError, StatusClass.Failed -> base.background(colors.destructive) to colors.onDestructive
        StatusClass.Informational -> base.background(colors.muted) to colors.text
        StatusClass.Pending -> base.drawBehind {
            val stroke = 1.dp.toPx()
            drawRoundRect(
                color = colors.textFaint,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(12.dp.toPx()),
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

/** A status class's hue: 2xx success, 3xx info, 4xx warning, 5xx and failures error, 1xx and pending neutral. */
internal fun StatusClass.tone(colors: HopiColors): HopiTone = when (this) {
    StatusClass.Success -> colors.success
    StatusClass.Redirect -> colors.info
    StatusClass.ClientError -> colors.warning
    StatusClass.ServerError, StatusClass.Failed -> colors.error
    StatusClass.Informational, StatusClass.Pending -> colors.neutral
}

/** A filter's count hue; null (faint) for All and Pending. */
private fun StatusFilter.tone(colors: HopiColors): HopiTone? = when (this) {
    StatusFilter.All, StatusFilter.Pending -> null
    StatusFilter.Success -> colors.success
    StatusFilter.Redirect -> colors.info
    StatusFilter.ClientError -> colors.warning
    StatusFilter.ServerError, StatusFilter.Failed -> colors.error
}

/** A rule mark's hue: MOCK violet, EDIT fuchsia, FAIL the error red (it made the ERR), DELAY the warning amber. */
private fun tagTone(tag: String, colors: HopiColors): HopiTone = when (tag) {
    "MOCK" -> colors.info
    "EDIT" -> colors.info
    "FAIL" -> colors.error
    "DELAY" -> colors.warning
    else -> colors.neutral
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
