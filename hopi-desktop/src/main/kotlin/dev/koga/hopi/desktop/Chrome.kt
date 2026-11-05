package dev.koga.hopi.desktop

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.koga.hopi.ui.desktop.HopiDesktopType
import dev.koga.hopi.ui.desktop.hopiDesktopColors
import dev.koga.hopi.ui.desktop.hopiFocusRing
import dev.koga.hopi.ui.desktop.hopiHoverIndication

private val Shape = RoundedCornerShape(8.dp)
private val Pill = RoundedCornerShape(50)

/** shadcn's button variants. */
internal enum class ButtonVariant { Primary, Secondary, Outline, Ghost, Destructive }

/** A text button in shadcn's [variant]s, [ButtonVariant.Outline] unless said otherwise. */
@Composable
internal fun ChromeButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, variant: ButtonVariant = ButtonVariant.Outline) {
    val colors = hopiDesktopColors
    val (fill, label, edge) = when (variant) {
        ButtonVariant.Primary -> Triple(colors.primary, colors.onPrimary, colors.primary)
        ButtonVariant.Secondary -> Triple(colors.muted, colors.text, colors.muted)
        ButtonVariant.Outline -> Triple(colors.background, colors.text, colors.border)
        ButtonVariant.Ghost -> Triple(Color.Transparent, colors.text, Color.Transparent)
        ButtonVariant.Destructive -> Triple(colors.destructive, colors.onDestructive, colors.destructive)
    }
    val filled = variant == ButtonVariant.Primary || variant == ButtonVariant.Destructive
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(Shape)
            .background(fill)
            .border(1.dp, edge, Shape)
            .clickable(
                interactionSource = null,
                // The theme's overlay does not show on a dark or red fill: the label's color at 12%.
                indication = if (filled) hopiHoverIndication(label.copy(alpha = 0.12f)) else LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = HopiDesktopType.label.copy(color = label))
    }
}

/**
 * A selectable chip (a device, a bridge, a fail kind); [detail] (a device state) follows [text] in
 * the warning amber. Softer than the list's filter chips: the target bar has a primary button already.
 */
@Composable
internal fun ChromeChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, detail: String? = null) {
    val colors = hopiDesktopColors
    Box(
        modifier
            .clip(Pill)
            .background(if (selected) colors.muted else colors.background)
            .border(1.dp, if (selected) colors.ring else colors.border, Pill)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        // The same text as "$text · $detail", the state in its hue.
        val label = buildAnnotatedString {
            append(text)
            if (detail != null) {
                append(" · ")
                withStyle(SpanStyle(color = colors.warning.fg)) { append(detail) }
            }
        }
        BasicText(label, style = HopiDesktopType.label.copy(color = if (selected) colors.text else colors.textMuted), maxLines = 1)
    }
}

/** A text field with [placeholder] shown while empty; one line unless [multiline] (a body). shadcn's input: a ring while focused. */
@Composable
internal fun ChromeTextField(state: TextFieldState, placeholder: String, modifier: Modifier = Modifier, multiline: Boolean = false) {
    val colors = hopiDesktopColors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    BasicTextField(
        state = state,
        modifier = if (multiline) modifier.heightIn(min = 72.dp) else modifier,
        textStyle = HopiDesktopType.mono.copy(color = colors.text),
        cursorBrush = SolidColor(colors.text),
        lineLimits = if (multiline) TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 10) else TextFieldLineLimits.SingleLine,
        interactionSource = source,
        decorator = { field ->
            Box(
                Modifier
                    .hopiFocusRing(if (focused) colors.ring else null)
                    .background(colors.background, Shape)
                    .border(1.dp, if (focused) colors.ring else colors.border, Shape)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (state.text.isEmpty()) BasicText(placeholder, style = HopiDesktopType.mono.copy(color = colors.textFaint), maxLines = 1)
                field()
            }
        },
    )
}

/** An on/off switch: a filled track with the knob at the end when on; [description] names it for tests and screen readers. */
@Composable
internal fun ChromeSwitch(checked: Boolean, description: String, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = hopiDesktopColors
    val track = RoundedCornerShape(50)
    Box(
        modifier
            .semantics { contentDescription = description }
            // No overlay: a rectangle around a pill looks wrong, and the knob already moves.
            .toggleable(value = checked, interactionSource = null, indication = null, role = Role.Switch, onValueChange = onChange)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 30.dp, height = 16.dp)
                .background(if (checked) colors.primary else colors.background, track)
                .border(1.dp, if (checked) colors.primary else colors.textFaint, track)
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(12.dp).background(if (checked) colors.onPrimary else colors.textFaint, CircleShape))
        }
    }
}
