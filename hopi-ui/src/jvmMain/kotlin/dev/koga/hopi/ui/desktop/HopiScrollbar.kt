package dev.koga.hopi.ui.desktop

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.ui.theme.LocalHopiColors

/** The scrollbar of a lazy list; lay it over the list's end edge. */
@HopiInternalApi
@Composable
public fun HopiScrollbar(state: LazyListState, modifier: Modifier = Modifier): Unit =
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier, style = style())

/** The scrollbar of a scrolling column; lay it over the column's end edge. */
@HopiInternalApi
@Composable
public fun HopiScrollbar(state: ScrollState, modifier: Modifier = Modifier): Unit =
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier, style = style())

@Composable
private fun style(): ScrollbarStyle {
    val text = LocalHopiColors.current.text
    return ScrollbarStyle(
        minimalHeight = 32.dp,
        thickness = 6.dp,
        shape = RoundedCornerShape(3.dp),
        hoverDurationMillis = 150,
        unhoverColor = text.copy(alpha = 0.18f),
        hoverColor = text.copy(alpha = 0.42f),
    )
}
