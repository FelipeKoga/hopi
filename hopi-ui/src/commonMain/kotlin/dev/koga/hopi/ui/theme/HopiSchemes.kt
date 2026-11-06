package dev.koga.hopi.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** The viewer's accent: the seed of its M3 tonal scheme. [Green] is Greenwave's, the default. */
public enum class HopiAccent(
    internal val darkPrimary: Color,
    internal val darkOnPrimary: Color,
    internal val lightPrimary: Color,
    internal val lightOnPrimary: Color,
) {
    Neutral(Color(0xFFE2E2E2), Color(0xFF1B1B1B), Color(0xFF3B3B3B), Color(0xFFFFFFFF)),
    Green(Color(0xFF81DFA2), Color(0xFF003920), Color(0xFF2C6A47), Color(0xFFFFFFFF)),
    Blue(Color(0xFFA8C8FF), Color(0xFF05305F), Color(0xFF3A5F94), Color(0xFFFFFFFF)),
    Teal(Color(0xFF82D3E0), Color(0xFF00363E), Color(0xFF006874), Color(0xFFFFFFFF)),
    Violet(Color(0xFFCDBDFF), Color(0xFF33275E), Color(0xFF65558F), Color(0xFFFFFFFF)),
    Orange(Color(0xFFFFB68A), Color(0xFF522300), Color(0xFF8F4C1F), Color(0xFFFFFFFF)),
    Rose(Color(0xFFFFB1C3), Color(0xFF5E1130), Color(0xFF8E4459), Color(0xFFFFFFFF)),
}

/** Light, dark, or the system's setting. */
public enum class HopiThemeMode { System, Light, Dark }

/** The viewer's theme choice; none (the default) is Greenwave following the system. */
@Immutable
public data class HopiThemeChoice(val accent: HopiAccent = HopiAccent.Neutral, val mode: HopiThemeMode = HopiThemeMode.System)

internal val LocalHopiThemeChoice = staticCompositionLocalOf { HopiThemeChoice() }

/**
 * [accent]'s scheme: Greenwave's own for [HopiAccent.Green]; for another accent, the same roles with
 * its primary, its containers mixed from the primary, and the neutrals tinted a few percent toward
 * it (as M3's tonal palettes tint their neutrals). Status tones are the mode's, shared by every accent.
 */
internal fun schemeOf(accent: HopiAccent, dark: Boolean): HopiColors {
    val base = if (dark) DarkColors else LightColors
    if (accent == HopiAccent.Green) return base
    val primary = if (dark) accent.darkPrimary else accent.lightPrimary
    fun tint(neutral: Color, amount: Float) = lerp(neutral, primary, amount)
    return if (dark) {
        HopiColors(
            background = tint(Color(0xFF0E0E0E), 0.03f), surface = tint(Color(0xFF1A1A1A), 0.05f), muted = tint(Color(0xFF2E2E2E), 0.05f),
            pane = tint(Color(0xFF151515), 0.04f), containerHigh = tint(Color(0xFF242424), 0.05f),
            secondaryContainer = lerp(Color(0xFF111111), primary, 0.25f), onSecondaryContainer = lerp(primary, Color.White, 0.55f),
            border = tint(Color(0xFF3E3E3E), 0.06f), ring = primary,
            text = tint(Color(0xFFE2E2E2), 0.03f), textMuted = tint(Color(0xFFC2C2C2), 0.04f), textFaint = tint(Color(0xFF8F8F8F), 0.04f),
            primary = primary, onPrimary = accent.darkOnPrimary,
            destructive = base.destructive, onDestructive = base.onDestructive, hover = base.hover, scrim = base.scrim,
            success = base.success, warning = base.warning, error = base.error, info = base.info, neutral = base.neutral,
            violet = base.violet, fuchsia = base.fuchsia,
        )
    } else {
        HopiColors(
            background = tint(Color(0xFFFAFAFA), 0.03f), surface = tint(Color(0xFFECECEC), 0.06f), muted = tint(Color(0xFFE0E0E0), 0.06f),
            pane = tint(Color(0xFFF2F2F2), 0.05f), containerHigh = tint(Color(0xFFE6E6E6), 0.06f),
            secondaryContainer = lerp(Color.White, primary, 0.2f), onSecondaryContainer = lerp(primary, Color.Black, 0.6f),
            border = tint(Color(0xFFC4C4C4), 0.06f), ring = primary,
            text = tint(Color(0xFF1A1A1A), 0.02f), textMuted = tint(Color(0xFF444444), 0.03f), textFaint = tint(Color(0xFF5E5E5E), 0.03f),
            primary = primary, onPrimary = accent.lightOnPrimary,
            destructive = base.destructive, onDestructive = base.onDestructive, hover = base.hover, scrim = base.scrim,
            success = base.success, warning = base.warning, error = base.error, info = base.info, neutral = base.neutral,
            violet = base.violet, fuchsia = base.fuchsia,
        )
    }
}

/** [accent]'s swatch color in either mode: its dark primary. */
public val HopiAccent.swatch: Color get() = darkPrimary
