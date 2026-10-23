package dev.koga.hopi.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The sample's own small foundation-only design: neutral greys and one accent, light or dark. */
@Immutable
internal class SampleColors(
    val background: Color,
    val surface: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val error: Color,
)

private val Light = SampleColors(
    background = Color(0xFFF7F7F8),
    surface = Color.White,
    border = Color(0xFFE2E2E6),
    text = Color(0xFF16161A),
    muted = Color(0xFF6B6B76),
    accent = Color(0xFF2F5BEA),
    onAccent = Color.White,
    error = Color(0xFFC62828),
)

private val Dark = SampleColors(
    background = Color(0xFF0F0F12),
    surface = Color(0xFF1A1A1F),
    border = Color(0xFF2C2C33),
    text = Color(0xFFEDEDF0),
    muted = Color(0xFF9A9AA5),
    accent = Color(0xFF7D9BFF),
    onAccent = Color(0xFF0F0F12),
    error = Color(0xFFFF8A80),
)

internal val LocalSampleColors = staticCompositionLocalOf { Light }

@Composable
internal fun SampleTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSampleColors provides if (isSystemInDarkTheme()) Dark else Light, content = content)
}

internal val colors: SampleColors
    @Composable get() = LocalSampleColors.current

@Composable
internal fun Title(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        modifier = modifier.semantics { heading() },
        style = TextStyle(color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
internal fun Body(text: String, modifier: Modifier = Modifier, color: Color = colors.text, maxLines: Int = Int.MAX_VALUE) {
    BasicText(
        text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = 15.sp, lineHeight = 21.sp),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier, color: Color = colors.muted) {
    BasicText(text, modifier = modifier, style = TextStyle(color = color, fontSize = 13.sp, lineHeight = 18.sp))
}

/** A filled button, or an outlined one when [primary] is false; 48 dp tall at least. */
@Composable
internal fun SampleButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true) {
    val shape = RoundedCornerShape(10.dp)
    val base = modifier.heightIn(min = 48.dp).clip(shape)
    val styled = if (primary) {
        base.background(if (enabled) colors.accent else colors.border)
    } else {
        base.border(1.dp, colors.border, shape).background(colors.surface)
    }
    Box(
        modifier = styled.clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = if (primary) colors.onAccent else colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

/** The screen header: a back control when [onBack] is set, the title, then [actions]. */
@Composable
internal fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) {
                BasicText("‹", style = TextStyle(color = colors.text, fontSize = 30.sp))
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Title(title, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
        actions()
    }
}

/** A labelled single- or multi-line text field. */
@Composable
internal fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Caption(label)
        Spacer(Modifier.size(6.dp))
        val shape = RoundedCornerShape(10.dp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = TextStyle(color = colors.text, fontSize = 16.sp, lineHeight = 22.sp),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .border(1.dp, if (error != null) colors.error else colors.border, shape)
                .background(colors.surface, shape)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .semantics { contentDescription = label },
        )
        if (error != null) {
            Spacer(Modifier.size(4.dp))
            Caption(error, color = colors.error)
        }
    }
}
