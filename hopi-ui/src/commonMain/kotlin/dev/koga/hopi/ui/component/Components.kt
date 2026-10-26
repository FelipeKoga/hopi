package dev.koga.hopi.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.koga.hopi.ui.theme.HopiType
import dev.koga.hopi.ui.theme.LocalHopiColors

/** Minimum touch target for every control. */
internal val TouchTarget: Dp = 48.dp

@Composable
internal fun Icon(vector: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/** An icon-only control: 48 dp target, [description] read by screen readers. */
@Composable
internal fun IconButton(
    vector: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalHopiColors.current
    Box(
        modifier = modifier
            .size(TouchTarget)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(vector, colors.text)
    }
}

/** A selectable filter pill; the 48 dp touch target is taller than the pill it draws. */
@Composable
internal fun FilterChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalHopiColors.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .heightIn(min = TouchTarget)
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .clip(shape)
                .then(if (selected) Modifier.background(colors.text) else Modifier.border(1.dp, colors.border, shape))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(label, style = HopiType.label.copy(color = if (selected) colors.onText else colors.text))
            BasicText(
                count.toString(),
                style = HopiType.meta.copy(color = if (selected) colors.onText.copy(alpha = 0.7f) else colors.textFaint),
            )
        }
    }
}

/** A labelled action with a leading icon; the label is what screen readers announce. */
@Composable
internal fun ActionButton(
    vector: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalHopiColors.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .heightIn(min = TouchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 3.dp)
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .clip(shape)
                .background(colors.surface)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(vector, colors.text, size = 16.dp)
            Spacer(Modifier.width(8.dp))
            BasicText(label, style = HopiType.label.copy(color = colors.text), maxLines = 1)
        }
    }
}

@Composable
internal fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalHopiColors.current.border))
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val colors = LocalHopiColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text.uppercase(), style = HopiType.section.copy(color = colors.textFaint))
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            BasicText(trailing, style = HopiType.meta.copy(color = colors.textFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * An on/off switch in monochrome: a filled track with the knob at the end when on, an outlined one
 * at the start when off. [description] names what it switches, for screen readers and tests.
 */
@Composable
internal fun Switch(checked: Boolean, description: String, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalHopiColors.current
    val track = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .size(TouchTarget)
            .semantics { contentDescription = description }
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 20.dp)
                .clip(track)
                .then(if (checked) Modifier.background(colors.text) else Modifier.border(1.5.dp, colors.textFaint, track))
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(if (checked) colors.onText else colors.textFaint))
        }
    }
}

/** A small outlined label in capitals (`MOCK`, `EDIT`): what a rule did to a request. */
@Composable
internal fun Tag(text: String, modifier: Modifier = Modifier) {
    val colors = LocalHopiColors.current
    val shape = RoundedCornerShape(4.dp)
    BasicText(
        text,
        modifier = modifier
            .border(1.dp, colors.textFaint, shape)
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .clearAndSetSemantics { contentDescription = text.lowercase() },
        style = HopiType.section.copy(color = colors.textMuted),
        maxLines = 1,
        softWrap = false,
    )
}
