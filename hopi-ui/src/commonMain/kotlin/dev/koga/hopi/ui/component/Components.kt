package dev.koga.hopi.ui.component

import dev.koga.hopi.ui.theme.HopiMotion
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
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
import dev.koga.hopi.ui.theme.HopiRadius
import dev.koga.hopi.ui.theme.HopiTone
import dev.koga.hopi.ui.theme.HopiType
import dev.koga.hopi.ui.theme.LocalHopiColors

/** Minimum touch target for every control. */
internal val TouchTarget: Dp = 48.dp

/** shadcn's focus ring: 3 dp of [color] at 30% just outside the bounds; nothing when null. Apply before any clip. */
internal fun Modifier.focusRing(color: Color?, radius: Dp = 8.dp): Modifier =
    if (color == null) this else drawBehind {
        val w = 3.dp.toPx()
        drawRoundRect(
            color = color.copy(alpha = 0.3f),
            topLeft = Offset(-w / 2, -w / 2),
            size = Size(size.width + w, size.height + w),
            cornerRadius = CornerRadius(radius.toPx() + w / 2),
            style = Stroke(w),
        )
    }

@Composable
internal fun Icon(vector: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/** An icon-only control (shadcn's: no fill, a square under the pointer): 48 dp target, [description] read by screen readers. */
@Composable
internal fun IconButton(
    vector: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalHopiColors.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(TouchTarget)
            .semantics { contentDescription = description }
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).clip(HopiRadius.md).indication(source, LocalIndication.current), contentAlignment = Alignment.Center) {
            Icon(vector, colors.text, size = 18.dp)
        }
    }
}

/**
 * A selectable filter pill (shadcn's chip), its count in [countColor]; the 48 dp touch target is
 * taller than the pill it draws.
 */
@Composable
internal fun FilterChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit, countColor: Color, modifier: Modifier = Modifier) {
    val colors = LocalHopiColors.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .heightIn(min = TouchTarget)
            .semantics { this.selected = selected }
            .clickable(interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        val fill by animateColorAsState(if (selected) colors.primary else colors.containerHigh, HopiMotion.fastEffects(), label = "chip")
        val ink by animateColorAsState(if (selected) colors.onPrimary else colors.text, HopiMotion.fastEffects(), label = "chipInk")
        Row(
            modifier = Modifier
                .pressScale(source)
                .heightIn(min = 36.dp)
                .clip(morphingPill(source, 36.dp, pressedRadius = 10.dp))
                // M3 Expressive: a tonal pill, filled with primary once selected.
                .background(fill)
                .indication(source, LocalIndication.current)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(label, style = HopiType.label.copy(color = ink))
            BasicText(
                count.toString(),
                style = HopiType.meta.copy(color = if (selected) colors.onPrimary.copy(alpha = 0.7f) else countColor),
            )
        }
    }
}

/**
 * A labelled action with a leading icon (shadcn's secondary button, or tinted in [tone]); the label
 * is what screen readers announce.
 */
@Composable
internal fun ActionButton(
    vector: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: HopiTone? = null,
) {
    val colors = LocalHopiColors.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .heightIn(min = TouchTarget)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 3.dp)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .pressScale(source)
                .heightIn(min = 40.dp)
                .clip(morphingPill(source, 40.dp))
                // M3's tonal button: secondaryContainer, or the tone's container.
                .background(tone?.bg ?: colors.secondaryContainer)
                .then(if (tone != null) Modifier.border(1.dp, tone.border, HopiRadius.full) else Modifier)
                .indication(source, LocalIndication.current)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(vector, tone?.fg ?: colors.onSecondaryContainer, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            BasicText(label, style = HopiType.label.copy(color = tone?.fg ?: colors.onSecondaryContainer), maxLines = 1)
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
        // M3 Expressive's section label: sentence case, in primary.
        BasicText(text.replaceFirstChar { it.uppercase() }, style = HopiType.section.copy(color = colors.primary))
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            BasicText(trailing, style = HopiType.meta.copy(color = colors.textFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A small tinted pill in capitals (`MOCK`, `EDIT`): what a rule did to a request, in [tone]'s hue. */
@Composable
internal fun Tag(text: String, tone: HopiTone, modifier: Modifier = Modifier) {
    BasicText(
        text,
        // A shaped fill, not a clip: a clip is a graphics layer, and every ruled list row has a tag.
        modifier = modifier
            .background(tone.bg, HopiRadius.sm)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = text.lowercase() },
        style = HopiType.tag.copy(color = tone.fg),
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * M3 Expressive's press: the control shrinks to [pressedScale] on a fast spatial spring while
 * [source] is pressed, and springs back. A graphics layer, so it recomposes nothing.
 */
@Composable
internal fun Modifier.pressScale(source: InteractionSource, pressedScale: Float = 0.96f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, HopiMotion.fastSpatial(), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A pill whose corners squeeze to [pressedRadius] while [source] is pressed: M3 Expressive's shape morph. */
@Composable
internal fun morphingPill(source: InteractionSource, height: Dp, pressedRadius: Dp = 12.dp): Shape {
    val pressed by source.collectIsPressedAsState()
    val radius by animateDpAsState(if (pressed) pressedRadius else height / 2, HopiMotion.fastSpatial(), label = "morph")
    return RoundedCornerShape(radius)
}
