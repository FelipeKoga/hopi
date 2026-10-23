package dev.koga.hopi.ui.detail

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import dev.koga.hopi.ui.theme.HopiColors
import dev.koga.hopi.ui.theme.HopiIcons
import dev.koga.hopi.ui.theme.HopiType
import dev.koga.hopi.ui.theme.LocalHopiColors

internal enum class DetailTab(val label: String) { Overview("Overview"), Request("Request"), Response("Response") }

internal object DetailTags {
    const val BACK = "Back to requests"
    const val COPY_CURL = "Copy cURL"
    const val COPY_URL = "Copy URL"
    const val COPY_BODY = "Copy body"
    const val SHARE = "Share"
}

/** What the detail's action row does; the screen wires them to the clipboard and share sheet. */
internal class DetailActions(
    val copyCurl: () -> Unit,
    val copyUrl: () -> Unit,
    val copyBody: () -> Unit,
    /** Null where the platform has no share sheet. */
    val share: (() -> Unit)?,
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
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 13.dp)) {
                    // A summary has no headers or body (yet, or ever): its cURL would be wrong, not just short.
                    ActionButton(HopiIcons.Terminal, DetailTags.COPY_CURL, actions.copyCurl, enabled = !model.loading && !model.gone)
                    ActionButton(HopiIcons.Link, DetailTags.COPY_URL, actions.copyUrl)
                    ActionButton(HopiIcons.Copy, DetailTags.COPY_BODY, actions.copyBody, enabled = responseText != null)
                    actions.share?.let { ActionButton(HopiIcons.Share, DetailTags.SHARE, it) }
                }
                Tabs(tab, onTab, colors)
                HairlineDivider()
                // A fresh scroll position per tab and per transaction.
                val listState = remember(tab, model.transaction.id) { LazyListState() }
                SelectionContainer(Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        when (tab) {
                            DetailTab.Overview -> overview(model, colors)
                            DetailTab.Request -> exchange(model.request, colors)
                            DetailTab.Response -> exchange(model.response, colors)
                        }
                        item(key = "end", contentType = "end") { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tabs(selected: DetailTab, onTab: (DetailTab) -> Unit, colors: HopiColors) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        for (tab in DetailTab.entries) {
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics { this.selected = isSelected }
                    .clickable(role = Role.Tab) { onTab(tab) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    tab.label,
                    modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
                    style = HopiType.label.copy(color = if (isSelected) colors.text else colors.textFaint),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .width(32.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (isSelected) colors.text else colors.background),
                )
            }
        }
    }
}

private fun LazyListScope.overview(model: DetailModel, colors: HopiColors) {
    item(key = "top", contentType = "space") { Spacer(Modifier.height(8.dp)) }
    for (field in model.overview) {
        item(key = "field-${field.label}", contentType = "field") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                BasicText(field.label, style = HopiType.meta.copy(color = colors.textFaint))
                Spacer(Modifier.height(2.dp))
                BasicText(field.value, style = HopiType.mono.copy(color = colors.text))
            }
        }
    }
}

private fun LazyListScope.exchange(exchange: ExchangeView, colors: HopiColors) {
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
    item(key = "body-title", contentType = "title") { SectionTitle("Body", trailing = body.summary) }
    body.notice?.let { notice ->
        item(key = "body-notice", contentType = "notice") {
            BasicText(
                notice,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceStrong)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = HopiType.meta.copy(color = colors.text),
            )
        }
    }
    when (body) {
        is BodyView.None -> item(key = "body-none", contentType = "message") { Message(body.message, colors) }
        is BodyView.Skipped -> Unit
        is BodyView.Text -> itemsIndexed(body.chunks, key = { index, _ -> "chunk-$index" }, contentType = { _, _ -> "chunk" }) { _, chunk ->
            BasicText(
                chunk,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                style = HopiType.mono.copy(color = colors.text),
            )
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
