package dev.koga.hopi.ui.desktop

import dev.koga.hopi.ui.theme.LocalHopiThemeChoice
import dev.koga.hopi.ui.theme.HopiThemeChoice
import androidx.compose.runtime.CompositionLocalProvider
import dev.koga.hopi.ui.component.morphingPill
import dev.koga.hopi.ui.component.pressScale
import dev.koga.hopi.ui.theme.HopiMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.Indication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.ui.component.focusRing
import dev.koga.hopi.ui.theme.HopiIndication
import dev.koga.hopi.ui.theme.HopiTheme
import dev.koga.hopi.ui.theme.HopiTone
import dev.koga.hopi.ui.theme.HopiType
import dev.koga.hopi.ui.theme.LocalHopiColors

/** The inspector's palette (shadcn's zinc neutrals and the status tones), for a host viewer's own chrome around [HopiPanes]. */
@HopiInternalApi
@Immutable
public class HopiDesktopColors internal constructor(
    public val background: Color,
    /** Chrome bands (target bar, status bar, panel headers). */
    public val surface: Color,
    /** Secondary buttons, a selected picker chip. */
    public val muted: Color,
    /** M3's surfaceContainerLow: a pane floating on the background. */
    public val pane: Color,
    /** M3's surfaceContainerHigh: cards, text fields, an unselected chip. */
    public val containerHigh: Color,
    /** M3's secondaryContainer: a selected item, a tonal button. */
    public val secondaryContainer: Color,
    public val onSecondaryContainer: Color,
    public val border: Color,
    /** A focused field's border and its outer ring. */
    public val ring: Color,
    public val text: Color,
    public val textMuted: Color,
    public val textFaint: Color,
    /** Primary buttons, a switch that is on. */
    public val primary: Color,
    public val onPrimary: Color,
    /** The Destructive button's fill. */
    public val destructive: Color,
    public val onDestructive: Color,
    public val success: HopiTone,
    public val warning: HopiTone,
    public val error: HopiTone,
    public val info: HopiTone,
    public val neutral: HopiTone,
    public val violet: HopiTone,
    public val fuchsia: HopiTone,
)

/** The inspector's text styles (Open Runde and the platform monospace), for the same chrome. */
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

/** [choice]'s accent and mode (Greenwave following the system by default); [HopiPanes] inside it keeps the choice. */
@HopiInternalApi
@Composable
public fun HopiDesktopTheme(choice: HopiThemeChoice = HopiThemeChoice(), content: @Composable () -> Unit): Unit =
    CompositionLocalProvider(LocalHopiThemeChoice provides choice) { HopiTheme(content) }

/** The palette of the enclosing [HopiDesktopTheme]. */
@HopiInternalApi
public val hopiDesktopColors: HopiDesktopColors
    @Composable get() {
        val colors = LocalHopiColors.current
        return HopiDesktopColors(
            background = colors.background,
            surface = colors.surface,
            muted = colors.muted,
            pane = colors.pane,
            containerHigh = colors.containerHigh,
            secondaryContainer = colors.secondaryContainer,
            onSecondaryContainer = colors.onSecondaryContainer,
            border = colors.border,
            ring = colors.ring,
            text = colors.text,
            textMuted = colors.textMuted,
            textFaint = colors.textFaint,
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            destructive = colors.destructive,
            onDestructive = colors.onDestructive,
            success = colors.success,
            warning = colors.warning,
            error = colors.error,
            info = colors.info,
            neutral = colors.neutral,
            violet = colors.violet,
            fuchsia = colors.fuchsia,
        )
    }

/** Hover, press and keyboard-focus feedback in [color], twice its alpha while pressed: a filled button passes its label color at 12%. */
@HopiInternalApi
public fun hopiHoverIndication(color: Color): Indication = HopiIndication(color)

/** shadcn's focus ring: 3 dp of [color] at 30% just outside the bounds; nothing when null. Apply before any clip. */
@HopiInternalApi
public fun Modifier.hopiFocusRing(color: Color?): Modifier = focusRing(color)

/** M3 Expressive's motion springs, for a host viewer's own chrome (see HopiMotion). */
@HopiInternalApi
public object HopiDesktopMotion {
    public fun <T> fastSpatial(): FiniteAnimationSpec<T> = HopiMotion.fastSpatial()
    public fun <T> defaultSpatial(): FiniteAnimationSpec<T> = HopiMotion.defaultSpatial()
    public fun <T> fastEffects(): FiniteAnimationSpec<T> = HopiMotion.fastEffects()
    public fun <T> defaultEffects(): FiniteAnimationSpec<T> = HopiMotion.defaultEffects()
}

/** M3 Expressive's press: shrink to [pressedScale] while [source] is pressed. */
@HopiInternalApi
@Composable
public fun Modifier.hopiPressScale(source: InteractionSource, pressedScale: Float = 0.96f): Modifier = pressScale(source, pressedScale)

/** A pill of [height] whose corners squeeze to [pressedRadius] while [source] is pressed. */
@HopiInternalApi
@Composable
public fun hopiMorphingPill(source: InteractionSource, height: Dp, pressedRadius: Dp = 12.dp): Shape = morphingPill(source, height, pressedRadius)
