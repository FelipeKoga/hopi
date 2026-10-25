package dev.koga.hopi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.koga.hopi.ui.desktop.HopiDesktopType
import dev.koga.hopi.ui.desktop.hopiDesktopColors

private val Shape = RoundedCornerShape(6.dp)

/** A text button: filled when [primary], outlined otherwise. */
@Composable
internal fun ChromeButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false) {
    val colors = hopiDesktopColors
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(if (primary) colors.text else colors.background, Shape)
            .border(1.dp, if (primary) colors.text else colors.border, Shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = HopiDesktopType.label.copy(color = if (primary) colors.onText else colors.text))
    }
}

/** A selectable chip; [detail] (a device state) is shown after [text] in a fainter color. */
@Composable
internal fun ChromeChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, detail: String? = null) {
    val colors = hopiDesktopColors
    Box(
        modifier
            .background(if (selected) colors.surfaceStrong else colors.background, Shape)
            .border(1.dp, if (selected) colors.textMuted else colors.border, Shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        val label = if (detail != null) "$text · $detail" else text
        BasicText(label, style = HopiDesktopType.label.copy(color = if (selected) colors.text else colors.textMuted), maxLines = 1)
    }
}

/** A text field with [placeholder] shown while empty; one line unless [multiline] (a body). */
@Composable
internal fun ChromeTextField(state: TextFieldState, placeholder: String, modifier: Modifier = Modifier, multiline: Boolean = false) {
    val colors = hopiDesktopColors
    BasicTextField(
        state = state,
        modifier = if (multiline) modifier.heightIn(min = 72.dp) else modifier,
        textStyle = HopiDesktopType.mono.copy(color = colors.text),
        cursorBrush = SolidColor(colors.text),
        lineLimits = if (multiline) TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 10) else TextFieldLineLimits.SingleLine,
        decorator = { field ->
            Box(
                Modifier.background(colors.surface, Shape).border(1.dp, colors.border, Shape).padding(horizontal = 10.dp, vertical = 7.dp),
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
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 30.dp, height = 16.dp)
                .background(if (checked) colors.text else colors.background, track)
                .border(1.dp, if (checked) colors.text else colors.textFaint, track)
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(12.dp).background(if (checked) colors.onText else colors.textFaint, CircleShape))
        }
    }
}
