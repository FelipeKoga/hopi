package dev.koga.hopi.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** The viewer's keyboard commands; ⌘ on macOS is Ctrl elsewhere. */
internal enum class Shortcut(val keys: String) {
    Next("↓"),
    Previous("↑"),

    /** Selects the first row when none is selected. */
    Open("Enter"),

    /** Clears the selection (and leaves the search field). */
    Back("Esc"),
    Clear("⌘K"),
    Search("⌘F"),
    ExportHar("⌘E"),
    ExportNdjson("⇧⌘E"),
    Reconnect("⌘R"),

    /** Shows or hides the rules panel. */
    Rules("⌘L"),
}

internal val IS_MAC: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

internal fun shortcutOf(event: KeyEvent, isMac: Boolean = IS_MAC): Shortcut? {
    if (event.type != KeyEventType.KeyDown) return null
    val command = if (isMac) event.isMetaPressed && !event.isCtrlPressed else event.isCtrlPressed && !event.isMetaPressed
    val plain = !event.isMetaPressed && !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed
    return when {
        command && !event.isAltPressed -> when (event.key) {
            Key.K -> Shortcut.Clear.takeUnless { event.isShiftPressed }
            Key.F -> Shortcut.Search.takeUnless { event.isShiftPressed }
            Key.E -> if (event.isShiftPressed) Shortcut.ExportNdjson else Shortcut.ExportHar
            Key.R -> Shortcut.Reconnect.takeUnless { event.isShiftPressed }
            Key.L -> Shortcut.Rules.takeUnless { event.isShiftPressed }
            else -> null
        }
        !plain -> null
        else -> when (event.key) {
            Key.DirectionDown -> Shortcut.Next
            Key.DirectionUp -> Shortcut.Previous
            Key.Enter, Key.NumPadEnter -> Shortcut.Open
            Key.Escape -> Shortcut.Back
            else -> null
        }
    }
}
