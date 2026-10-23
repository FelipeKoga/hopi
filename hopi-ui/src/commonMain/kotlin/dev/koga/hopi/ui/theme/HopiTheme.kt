package dev.koga.hopi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Monochrome palette: every color is a grey, so status reads through shape and weight, not hue. */
@Immutable
internal class HopiColors(
    val background: Color,
    val surface: Color,
    val surfaceStrong: Color,
    val border: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    /** Text drawn on [text]-colored fills (filled badges, the launcher button). */
    val onText: Color,
    val scrim: Color,
)

internal val LightColors = HopiColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF4F4F5),
    surfaceStrong = Color(0xFFE4E4E7),
    border = Color(0xFFE4E4E7),
    text = Color(0xFF09090B),
    textMuted = Color(0xFF52525B),
    textFaint = Color(0xFF71717A),
    onText = Color(0xFFFFFFFF),
    scrim = Color(0x14000000),
)

internal val DarkColors = HopiColors(
    background = Color(0xFF09090B),
    surface = Color(0xFF18181B),
    surfaceStrong = Color(0xFF27272A),
    border = Color(0xFF27272A),
    text = Color(0xFFFAFAFA),
    textMuted = Color(0xFFA1A1AA),
    textFaint = Color(0xFF8B8B94),
    onText = Color(0xFF09090B),
    scrim = Color(0x1FFFFFFF),
)

internal val LocalHopiColors = staticCompositionLocalOf { LightColors }

/** Text styles on the platform's default sans and monospace families; nothing is bundled. */
internal object HopiType {
    val title = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
    val bodyStrong = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
    val label = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
    val meta = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val section = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
    val mono = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
    val monoStrong = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    val badge = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
}

/** Light or dark following the system setting. */
@Composable
internal fun HopiTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    CompositionLocalProvider(
        LocalHopiColors provides colors,
        LocalTextSelectionColors provides TextSelectionColors(handleColor = colors.text, backgroundColor = colors.surfaceStrong),
        content = content,
    )
}
