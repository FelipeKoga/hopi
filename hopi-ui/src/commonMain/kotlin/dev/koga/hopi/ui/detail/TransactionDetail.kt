package dev.koga.hopi.ui.detail

import dev.koga.hopi.ui.component.GroupPosition
import dev.koga.hopi.ui.component.GroupedAction
import dev.koga.hopi.ui.component.pressScale
import dev.koga.hopi.ui.theme.HopiMotion
import androidx.compose.runtime.getValue
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.ui.component.ActionButton
import dev.koga.hopi.ui.component.HairlineDivider
import dev.koga.hopi.ui.component.IconButton
import dev.koga.hopi.ui.component.SectionTitle
import dev.koga.hopi.ui.list.EmptyState
import dev.koga.hopi.ui.list.statusOf
import dev.koga.hopi.ui.list.tone
import dev.koga.hopi.ui.rules.RuleActions
import dev.koga.hopi.ui.theme.HopiColors
import dev.koga.hopi.ui.theme.HopiIcons
import dev.koga.hopi.ui.theme.HopiRadius
import dev.koga.hopi.ui.theme.HopiType
import dev.koga.hopi.ui.theme.LocalHopiColors

internal enum class DetailTab(val label: String) { Overview("Overview"), Request("Request"), Response("Response") }

internal object DetailTags {
    const val BACK = "Back to requests"
    const val COPY_CURL = "Copy cURL"
    const val COPY_URL = "Copy URL"
    const val COPY_BODY = "Copy body"
    const val SHARE = "Share"
    const val MOCK = "Mock this response"
}

/** What the detail's action row does; the screen wires them to the clipboard and share sheet. */
internal class DetailActions(
    val copyCurl: () -> Unit,
    val copyUrl: () -> Unit,
    val copyBody: () -> Unit,
    /** Null where the platform has no share sheet. */
    val share: (() -> Unit)?,
    /** "Mock this response"; null where nobody wired it (a host viewer without a bridge to the app's rules). */
    val rules: RuleActions? = null,
)

@Composable
internal fun TransactionDetailPane(
    model: DetailModel?,
    missing: Boolean,
    tab: DetailTab,
    onTab: (DetailTab) -> Unit,
    /** Null hides the back button (the desktop panes show the list beside the detail). */
    onBack: (() -> Unit)?,
    actions: DetailActions,
) {
    val colors = LocalHopiColors.current
    val palette = remember(colors) { JsonPalette.of(colors) }
    // The body open full size, with its title; a new transaction closes it.
    var expanded by remember(model?.transaction?.id) { mutableStateOf<Pair<String, BodyView.Text>?>(null) }
    expanded?.let { (title, body) -> BodyViewer(title, body, onClose = { expanded = null }) }
    Column(Modifier.fillMaxSize()) {
        Row(
            // A minimum, not a height: a large font scale grows the bar instead of clipping the title.
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(HopiIcons.Back, DetailTags.BACK, onBack)
                Spacer(Modifier.width(4.dp))
            } else {
                Spacer(Modifier.width(12.dp))
            }
            BasicText(
                model?.title ?: "",
                style = HopiType.title.copy(color = colors.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            missing -> EmptyState("This request is gone", "It was cleared or evicted from the store.")
            // Formatting off the main thread: an empty frame, then the content.
            model == null -> Unit
            else -> {
                val responseText = model.response.body as? BodyView.Text
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // M3 Expressive's connected button group: the three copies as one control, each
                    // segment named in full for screen readers and tests ("Copy cURL").
                    Row(Modifier.padding(horizontal = 3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        // A summary has no headers or body (yet, or ever): its cURL would be wrong, not just short.
                        GroupedAction(HopiIcons.Terminal, "cURL", DetailTags.COPY_CURL, actions.copyCurl, GroupPosition.First, enabled = !model.loading && !model.gone)
                        GroupedAction(HopiIcons.Link, "URL", DetailTags.COPY_URL, actions.copyUrl, GroupPosition.Middle)
                        GroupedAction(HopiIcons.Copy, "Body", DetailTags.COPY_BODY, actions.copyBody, GroupPosition.Last, enabled = responseText != null)
                    }
                    Spacer(Modifier.width(6.dp))
                    actions.share?.let { ActionButton(HopiIcons.Share, DetailTags.SHARE, it) }
                    actions.rules?.let { rules ->
                        rules.mock?.let { ActionButton(HopiIcons.Mock, DetailTags.MOCK, it.run, enabled = it.refusal == null) }
                    }
                }
                // A disabled rule button says why.
                val refusals = actions.rules?.let { rules -> listOfNotNull(rules.mock?.refusal?.let { "Mock: $it" }) }.orEmpty()
                if (refusals.isNotEmpty()) {
                    BasicText(
                        refusals.joinToString("  ·  "),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        style = HopiType.meta.copy(color = colors.textMuted),
                    )
                }
                Tabs(tab, onTab, colors)
                Spacer(Modifier.height(4.dp))
                // The tab's content slides toward the tab picked, and fades, on M3 Expressive springs.
                AnimatedContent(
                    targetState = tab,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val shift = { width: Int -> (if (forward) width else -width) / 8 }
                        (slideInHorizontally(HopiMotion.defaultSpatial(), shift) + fadeIn(HopiMotion.defaultEffects()))
                            .togetherWith(slideOutHorizontally(HopiMotion.defaultSpatial()) { -shift(it) } + fadeOut(HopiMotion.fastEffects()))
                    },
                    label = "tab",
                ) { shown ->
                    // A fresh scroll position per tab and per transaction.
                    val listState = remember(shown, model.transaction.id) { LazyListState() }
                    SelectionContainer(Modifier.fillMaxSize()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            when (shown) {
                                DetailTab.Overview -> overview(model, colors)
                                DetailTab.Request -> exchange(model.request, colors, palette) { expanded = "Request body" to it }
                                DetailTab.Response -> exchange(model.response, colors, palette) { expanded = "Response body" to it }
                            }
                            item(key = "end", contentType = "end") { Spacer(Modifier.height(24.dp)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * M3 Expressive's connected button group: one pill per tab with a 2 dp gap, the outer corners round,
 * the inner ones small, and the selected one filled with primary and fully round; 48 dp targets.
 */
@Composable
private fun Tabs(selected: DetailTab, onTab: (DetailTab) -> Unit, colors: HopiColors) {
    val tabs = DetailTab.entries
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((index, tab) in tabs.withIndex()) {
            val isSelected = tab == selected
            val source = remember { MutableInteractionSource() }
            val outer = 20.dp
            // The selected segment's inner corners round out as it fills: M3 Expressive's shape morph.
            val inner by animateDpAsState(if (isSelected) 20.dp else 6.dp, HopiMotion.fastSpatial(), label = "segment")
            val fill by animateColorAsState(if (isSelected) colors.primary else colors.containerHigh, HopiMotion.fastEffects(), label = "segmentFill")
            val ink by animateColorAsState(if (isSelected) colors.onPrimary else colors.text, HopiMotion.fastEffects(), label = "segmentInk")
            val shape = RoundedCornerShape(
                topStart = if (index == 0) outer else inner,
                bottomStart = if (index == 0) outer else inner,
                topEnd = if (index == tabs.lastIndex) outer else inner,
                bottomEnd = if (index == tabs.lastIndex) outer else inner,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .pressScale(source)
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .background(fill)
                    .semantics { this.selected = isSelected }
                    .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Tab) { onTab(tab) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(tab.label, modifier = Modifier.padding(vertical = 12.dp), style = HopiType.label.copy(color = ink))
            }
        }
    }
}

private fun LazyListScope.overview(model: DetailModel, colors: HopiColors) {
    item(key = "top", contentType = "space") { Spacer(Modifier.height(8.dp)) }
    // The status in its class's hue (as its badge), the error in red; every other value plain.
    val statusColor = statusOf(model.transaction).tone(colors).fg
    for (field in model.overview) {
        item(key = "field-${field.label}", contentType = "field") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                BasicText(field.label, style = HopiType.meta.copy(color = colors.textFaint))
                Spacer(Modifier.height(2.dp))
                BasicText(
                    field.value,
                    style = HopiType.mono.copy(
                        color = when (field.label) {
                            "Status" -> statusColor
                            "Error" -> colors.error.fg
                            else -> colors.text
                        },
                    ),
                )
            }
        }
    }
}

private fun LazyListScope.exchange(exchange: ExchangeView, colors: HopiColors, palette: JsonPalette, onExpand: (BodyView.Text) -> Unit) {
    item(key = "headers-title", contentType = "title") {
        SectionTitle("Headers", trailing = exchange.headers.size.toString())
    }
    if (exchange.headers.isEmpty()) {
        item(key = "headers-none", contentType = "message") {
            Message(
                when {
                    exchange.loading -> "Loading headers…"
                    exchange.gone -> "Headers gone from the app"
                    else -> "No headers"
                },
                colors,
            )
        }
    } else {
        itemsIndexed(exchange.headers, key = { index, _ -> "header-$index" }, contentType = { _, _ -> "header" }) { _, (name, value) ->
            HeaderRow(name, value, colors)
        }
    }
    val body = exchange.body
    item(key = "body-title", contentType = "title") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Body", Modifier.weight(1f), trailing = body.summary)
            if (body is BodyView.Text) IconButton(HopiIcons.Expand, BodyViewerTags.EXPAND, { onExpand(body) }, Modifier.padding(end = 4.dp))
        }
    }
    body.notice?.let { notice ->
        item(key = "body-notice", contentType = "notice") {
            // A warning callout: the body was cut, or not captured at all.
            BasicText(
                notice,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(HopiRadius.md)
                    .background(colors.warning.bg)
                    .border(1.dp, colors.warning.border, HopiRadius.md)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = HopiType.meta.copy(color = colors.warning.fg),
            )
        }
    }
    when (body) {
        is BodyView.None -> item(key = "body-none", contentType = "message") { Message(body.message, colors) }
        is BodyView.Skipped -> Unit
        is BodyView.Text -> itemsIndexed(body.chunks, key = { index, _ -> "chunk-$index" }, contentType = { _, _ -> "chunk" }) { index, chunk ->
            BodyPiece(chunk, body.pieceStates?.get(index)?.toInt(), palette, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        is BodyView.Binary -> {
            item(key = "body-binary", contentType = "message") {
                Message("Binary body. The first ${body.previewBytes} bytes:", colors)
            }
            item(key = "body-hex", contentType = "hex") {
                // A dump line is wider than a phone in portrait: it scrolls sideways, never clips.
                BasicText(
                    body.hexPreview,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    style = HopiType.mono.copy(color = colors.text),
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun HeaderRow(name: String, value: String, colors: HopiColors) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        BasicText(
            name,
            modifier = Modifier.weight(0.4f),
            style = HopiType.mono.copy(color = colors.textMuted),
        )
        Spacer(Modifier.width(12.dp))
        val redacted = value == Redactor.REDACTED
        BasicText(
            value,
            modifier = Modifier.weight(0.6f),
            style = HopiType.mono.copy(
                color = if (redacted) colors.textFaint else colors.text,
                fontStyle = if (redacted) FontStyle.Italic else FontStyle.Normal,
            ),
        )
    }
}

@Composable
private fun Message(text: String, colors: HopiColors) {
    BasicText(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        style = HopiType.meta.copy(color = colors.textMuted, textAlign = TextAlign.Start),
    )
}
