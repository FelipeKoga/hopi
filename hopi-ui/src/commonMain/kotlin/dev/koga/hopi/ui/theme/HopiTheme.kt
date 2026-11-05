package dev.koga.hopi.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.ui.platform.hopiSans

/**
 * One hue: [fg] for text, icons and thin marks (4.5:1 on [bg], on the background and on the
 * surface), [bg] a tinted fill, [border] a tinted outline.
 */
@HopiInternalApi
@Immutable
public class HopiTone internal constructor(public val fg: Color, public val bg: Color, public val border: Color)

/**
 * A Material 3 Expressive tonal scheme after "Greenwave" (the owner's decision of 2026-11-05), with
 * hue for status in tonal containers. Every status still reads without hue, through shape, weight
 * and its text.
 */
@Immutable
internal class HopiColors(
    val background: Color,
    /** Chrome bands (target bar, status bar, panel headers), the selected row, a hang's stack. */
    val surface: Color,
    /** shadcn's muted: secondary buttons, the tabs' track, a selected picker chip. */
    val muted: Color,
    /** M3's surfaceContainerLow: a pane floating on the background (list, detail, side panels). */
    val pane: Color,
    /** M3's surfaceContainerHigh: list cards, text fields, an unselected chip. */
    val containerHigh: Color,
    /** M3's secondaryContainer: a selected row, a tonal button, the navigation indicator. */
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val border: Color,
    /** A focused field's border and its outer ring. */
    val ring: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    /** Primary buttons, the selected filter chip, a switch that is on. */
    val primary: Color,
    val onPrimary: Color,
    /** The solid red of the 5xx and ERR badges and of the Destructive button. */
    val destructive: Color,
    val onDestructive: Color,
    /** [HopiIndication]'s hover and keyboard-focus overlay; twice its alpha while pressed. */
    val hover: Color,
    val scrim: Color,
    val success: HopiTone,
    val warning: HopiTone,
    val error: HopiTone,
    val info: HopiTone,
    val neutral: HopiTone,
    /** Rule marks: MOCK; also the memory chart. */
    val violet: HopiTone,
    /** Rule marks: EDIT. */
    val fuchsia: HopiTone,
)

// Material 3 Expressive, after "Greenwave" (https://m3e.c5inco.dev/#greenwave): a tonal scheme from
// the seed #81DFA2. Dark values sampled from its screenshots (surface #0F1511, containers #171D19 /
// #1B211D / #262B27 / #303632, primary #7FD9A3 on #003920); the rest, and the light scheme, are the
// same seed's M3 tonal roles. Status tones are tonal containers of the scheme's own hues: a fill,
// its on-container text, and an outline one step up.
internal val LightColors = HopiColors(
    background = Color(0xFFF5FBF5), surface = Color(0xFFE9EFEA), muted = Color(0xFFDEE4DE),
    pane = Color(0xFFEFF5EF), containerHigh = Color(0xFFE3EAE4), secondaryContainer = Color(0xFFD1E8D8), onSecondaryContainer = Color(0xFF0C1F15),
    border = Color(0xFFBFC9C1), ring = Color(0xFF2C6A47),
    text = Color(0xFF171D19), textMuted = Color(0xFF404943), textFaint = Color(0xFF59625C),
    primary = Color(0xFF2C6A47), onPrimary = Color(0xFFFFFFFF),
    destructive = Color(0xFFBA1A1A), onDestructive = Color(0xFFFFFFFF),
    hover = Color(0x0F171D19), scrim = Color(0x1F000000),
    success = HopiTone(Color(0xFF1F6B40), Color(0xFFD3F5DE), Color(0xFFA8E3BC)),
    warning = HopiTone(Color(0xFF725300), Color(0xFFFFEFC7), Color(0xFFF0D48A)),
    error = HopiTone(Color(0xFFBA1A1A), Color(0xFFFFDAD6), Color(0xFFF5B4AC)),
    info = HopiTone(Color(0xFF2D6475), Color(0xFFD6EEF6), Color(0xFFA9D3E1)),
    neutral = HopiTone(Color(0xFF404943), Color(0xFFE3EAE4), Color(0xFFC9D2CB)),
    violet = HopiTone(Color(0xFF5B4F9A), Color(0xFFE8E2FF), Color(0xFFCEC4F5)),
    fuchsia = HopiTone(Color(0xFF7F3F78), Color(0xFFFADAF2), Color(0xFFEAB6DF)),
)

internal val DarkColors = HopiColors(
    background = Color(0xFF0F1511), surface = Color(0xFF1B211D), muted = Color(0xFF303632),
    pane = Color(0xFF171D19), containerHigh = Color(0xFF262B27), secondaryContainer = Color(0xFF334B40), onSecondaryContainer = Color(0xFFCFE9D9),
    border = Color(0xFF3F4943), ring = Color(0xFF7FD9A3),
    text = Color(0xFFDEE4DE), textMuted = Color(0xFFBFC9C1), textFaint = Color(0xFF8F9A92),
    primary = Color(0xFF81DFA2), onPrimary = Color(0xFF003920),
    destructive = Color(0xFFFFB4AB), onDestructive = Color(0xFF690005),
    hover = Color(0x14DEE4DE), scrim = Color(0x33000000),
    success = HopiTone(Color(0xFF9CEDB8), Color(0xFF15351F), Color(0xFF285A3B)),
    warning = HopiTone(Color(0xFFF2CB78), Color(0xFF382B0E), Color(0xFF5A471F)),
    error = HopiTone(Color(0xFFFFB4AB), Color(0xFF3D1714), Color(0xFF65302A)),
    info = HopiTone(Color(0xFFA6D1E0), Color(0xFF16313A), Color(0xFF2A5060)),
    neutral = HopiTone(Color(0xFFBFC9C1), Color(0xFF262B27), Color(0xFF3F4943)),
    violet = HopiTone(Color(0xFFCCC1F5), Color(0xFF2A2640), Color(0xFF453F62)),
    fuchsia = HopiTone(Color(0xFFEBB8E2), Color(0xFF38233A), Color(0xFF583A55)),
)

internal val LocalHopiColors = staticCompositionLocalOf { LightColors }

/** shadcn's radii: sm badges, md controls and fields, lg the toast, full pills. */
internal object HopiRadius {
    val sm = RoundedCornerShape(8.dp)
    val md = RoundedCornerShape(12.dp)
    val lg = RoundedCornerShape(20.dp)
    val full = RoundedCornerShape(percent = 50)
}

/** Text styles: Open Runde for the sans ([hopiSans], bundled), the platform monospace for code. */
internal object HopiType {
    private val sans: FontFamily = hopiSans()
    val title = TextStyle(fontFamily = sans, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    val body = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 19.sp)
    val bodyStrong = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
    val label = TextStyle(fontFamily = sans, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
    val meta = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 16.sp)
    val section = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp)
    val tag = TextStyle(fontFamily = sans, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    val mono = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
    val monoStrong = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    val badge = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
}

/** Light or dark following the system setting. */
@Composable
internal fun HopiTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    val indication = remember(colors) { HopiIndication(colors.hover) }
    CompositionLocalProvider(
        LocalHopiColors provides colors,
        LocalIndication provides indication,
        LocalTextSelectionColors provides TextSelectionColors(handleColor = colors.text, backgroundColor = colors.text.copy(alpha = 0.2f)),
        content = content,
    )
}
