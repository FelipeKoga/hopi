package dev.koga.hopi.ui.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.ui.theme.HopiTheme
import dev.koga.hopi.ui.theme.HopiType
import dev.koga.hopi.ui.theme.LocalHopiColors

/** The inspector's monochrome palette, for a host viewer's own chrome around [HopiPanes]. */
@HopiInternalApi
@Immutable
public class HopiDesktopColors internal constructor(
    public val background: Color,
    public val surface: Color,
    public val surfaceStrong: Color,
    public val border: Color,
    public val text: Color,
    public val textMuted: Color,
    public val textFaint: Color,
    /** Text drawn on [text]-colored fills. */
    public val onText: Color,
)

/** The inspector's text styles (platform sans and monospace), for the same chrome. */
@HopiInternalApi
public object HopiDesktopType {
    public val title: TextStyle get() = HopiType.title
    public val body: TextStyle get() = HopiType.body
    public val bodyStrong: TextStyle get() = HopiType.bodyStrong
    public val label: TextStyle get() = HopiType.label
    public val meta: TextStyle get() = HopiType.meta
    public val section: TextStyle get() = HopiType.section
    public val mono: TextStyle get() = HopiType.mono
}

/** Light or dark following the system setting, as inside the app; [HopiPanes] applies it too. */
@HopiInternalApi
@Composable
public fun HopiDesktopTheme(content: @Composable () -> Unit): Unit = HopiTheme(content)

/** The palette of the enclosing [HopiDesktopTheme]. */
@HopiInternalApi
public val hopiDesktopColors: HopiDesktopColors
    @Composable get() {
        val colors = LocalHopiColors.current
        return HopiDesktopColors(
            background = colors.background,
            surface = colors.surface,
            surfaceStrong = colors.surfaceStrong,
            border = colors.border,
            text = colors.text,
            textMuted = colors.textMuted,
            textFaint = colors.textFaint,
            onText = colors.onText,
        )
    }
