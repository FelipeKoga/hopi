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
 * A shadcn/ui palette (shadcn/ui zinc), with hue for status and only for status: the owner's decision
 * of 2026-10-28, which ended the all-grey palette. Every status still reads without hue, through
 * shape, weight and its text.
 */
@Immutable
internal class HopiColors(
    val background: Color,
    /** Chrome bands (target bar, status bar, panel headers), the selected row, a hang's stack. */
    val surface: Color,
    /** shadcn's muted: secondary buttons, the tabs' track, a selected picker chip. */
    val muted: Color,
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

internal val LightColors = HopiColors(
    background = Color(0xFFFFFFFF), surface = Color(0xFFF4F4F5), muted = Color(0xFFF4F4F5),
    border = Color(0xFFE4E4E7), ring = Color(0xFF71717A),
    text = Color(0xFF09090B), textMuted = Color(0xFF52525B), textFaint = Color(0xFF67676F),
    primary = Color(0xFF18181B), onPrimary = Color(0xFFFAFAFA),
    destructive = Color(0xFFDC2626), onDestructive = Color(0xFFFFFFFF),
    hover = Color(0x0A09090B), scrim = Color(0x14000000),
    success = HopiTone(Color(0xFF047857), Color(0xFFECFDF5), Color(0xFFA7F3D0)),
    warning = HopiTone(Color(0xFFB45309), Color(0xFFFFFBEB), Color(0xFFFDE68A)),
    error = HopiTone(Color(0xFFB91C1C), Color(0xFFFEF2F2), Color(0xFFFECACA)),
    info = HopiTone(Color(0xFF1D4ED8), Color(0xFFEFF6FF), Color(0xFFBFDBFE)),
    neutral = HopiTone(Color(0xFF52525B), Color(0xFFF4F4F5), Color(0xFFE4E4E7)),
    violet = HopiTone(Color(0xFF6D28D9), Color(0xFFF5F3FF), Color(0xFFDDD6FE)),
    fuchsia = HopiTone(Color(0xFFA21CAF), Color(0xFFFDF4FF), Color(0xFFF5D0FE)),
)

internal val DarkColors = HopiColors(
    background = Color(0xFF09090B), surface = Color(0xFF18181B), muted = Color(0xFF27272A),
    border = Color(0xFF27272A), ring = Color(0xFF71717A),
    text = Color(0xFFFAFAFA), textMuted = Color(0xFFA1A1AA), textFaint = Color(0xFF8E8E97),
    primary = Color(0xFFFAFAFA), onPrimary = Color(0xFF18181B),
    destructive = Color(0xFFDC2626), onDestructive = Color(0xFFFFFFFF),
    hover = Color(0x0FFAFAFA), scrim = Color(0x1FFFFFFF),
    success = HopiTone(Color(0xFF34D399), Color(0xFF0A1E19), Color(0xFF0B3E2E)),
    warning = HopiTone(Color(0xFFFBBF24), Color(0xFF251B0B), Color(0xFF50360B)),
    error = HopiTone(Color(0xFFF87171), Color(0xFF251012), Color(0xFF4E1B1C)),
    info = HopiTone(Color(0xFF60A5FA), Color(0xFF0F1827), Color(0xFF182D52)),
    neutral = HopiTone(Color(0xFFA1A1AA), Color(0xFF27272A), Color(0xFF3F3F46)),
    violet = HopiTone(Color(0xFFA78BFA), Color(0xFF191327), Color(0xFF302252)),
    fuchsia = HopiTone(Color(0xFFE879F9), Color(0xFF221026), Color(0xFF471B4F)),
)

internal val LocalHopiColors = staticCompositionLocalOf { LightColors }

/** shadcn's radii: sm badges, md controls and fields, lg the toast, full pills. */
internal object HopiRadius {
    val sm = RoundedCornerShape(6.dp)
    val md = RoundedCornerShape(8.dp)
    val lg = RoundedCornerShape(12.dp)
    val full = RoundedCornerShape(percent = 50)
}

/** Text styles: Open Runde for the sans ([hopiSans], bundled), the platform monospace for code. */
internal object HopiType {
    private val sans: FontFamily = hopiSans()
    val title = TextStyle(fontFamily = sans, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val body = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 19.sp)
    val bodyStrong = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
    val label = TextStyle(fontFamily = sans, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
    val meta = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 16.sp)
    val section = TextStyle(fontFamily = sans, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
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
