package dev.koga.hopi.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.koga.hopi.ui.desktop.HopiDesktopMotion
import dev.koga.hopi.ui.desktop.HopiDesktopType
import dev.koga.hopi.ui.desktop.hopiDesktopColors
import dev.koga.hopi.ui.theme.HopiAccent
import dev.koga.hopi.ui.theme.HopiThemeChoice
import dev.koga.hopi.ui.theme.HopiThemeMode
import dev.koga.hopi.ui.theme.swatch
import java.util.prefs.Preferences

/** The viewer's theme choice, kept in [prefs] across launches when given (none in tests). */
internal class ThemeSettings(private val prefs: Preferences? = null) {
    var choice: HopiThemeChoice by mutableStateOf(load())
        private set

    fun update(choice: HopiThemeChoice) {
        this.choice = choice
        prefs?.put(ACCENT, choice.accent.name)
        prefs?.put(MODE, choice.mode.name)
    }

    private fun load(): HopiThemeChoice {
        val p = prefs ?: return HopiThemeChoice()
        val accent = HopiAccent.entries.firstOrNull { it.name == p.get(ACCENT, null) } ?: HopiAccent.Neutral
        val mode = HopiThemeMode.entries.firstOrNull { it.name == p.get(MODE, null) } ?: HopiThemeMode.System
        return HopiThemeChoice(accent, mode)
    }

    companion object {
        private const val ACCENT = "theme.accent"
        private const val MODE = "theme.mode"

        /** The user's stored choice (java.util.prefs, the desktop app's node). */
        fun stored(): ThemeSettings = ThemeSettings(Preferences.userRoot().node("dev/koga/hopi/desktop"))
    }
}

internal object ThemeTags {
    const val BUTTON = "theme-button"
    const val PANEL = "theme-panel"
    fun accent(accent: HopiAccent) = "theme-accent-${accent.name.lowercase()}"
    fun mode(mode: HopiThemeMode) = "theme-mode-${mode.name.lowercase()}"
}

/** The top bar's Theme button and its popover: an accent swatch per scheme and the light, dark or system mode. */
@Composable
internal fun ThemeButton(settings: ThemeSettings) {
    val open = remember { MutableTransitionState(false) }
    Box {
        ToolbarIconButton(TopBarIcons.Theme, "Theme", onClick = { open.targetState = !open.targetState }, modifier = Modifier.testTag(ThemeTags.BUTTON))
        if (open.currentState || open.targetState) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, 48),
                onDismissRequest = { open.targetState = false },
                properties = PopupProperties(focusable = true),
            ) {
                AnimatedVisibility(
                    open,
                    enter = scaleIn(HopiDesktopMotion.fastSpatial(), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)) + fadeIn(HopiDesktopMotion.fastEffects()),
                    exit = scaleOut(HopiDesktopMotion.fastSpatial(), targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)) + fadeOut(HopiDesktopMotion.fastEffects()),
                ) {
                    ThemePanel(settings)
                }
            }
        }
    }
}

@Composable
private fun ThemePanel(settings: ThemeSettings) {
    val colors = hopiDesktopColors
    val choice = settings.choice
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .width(340.dp)
            .shadow(16.dp, shape)
            .clip(shape)
            .background(colors.pane)
            .padding(20.dp)
            .testTag(ThemeTags.PANEL),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText("Accent", style = HopiDesktopType.section.copy(color = colors.primary))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (accent in HopiAccent.entries) {
                val selected = accent == choice.accent
                val ring by animateColorAsState(if (selected) colors.text else colors.pane, HopiDesktopMotion.fastEffects(), label = "swatch")
                Box(
                    Modifier
                        .size(32.dp)
                        .border(2.dp, ring, CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(accent.swatch)
                        // A hairline, so a pale swatch (Neutral's) still reads on a light panel.
                        .border(1.dp, colors.border, CircleShape)
                        .semantics { contentDescription = accent.name; this.selected = selected }
                        .clickable(role = Role.RadioButton) { settings.update(choice.copy(accent = accent)) }
                        .testTag(ThemeTags.accent(accent)),
                )
            }
        }
        BasicText("Mode", style = HopiDesktopType.section.copy(color = colors.primary))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (mode in HopiThemeMode.entries) {
                ChromeChip(
                    mode.name,
                    selected = mode == choice.mode,
                    onClick = { settings.update(choice.copy(mode = mode)) },
                    modifier = Modifier.testTag(ThemeTags.mode(mode)),
                )
            }
        }
    }
}
