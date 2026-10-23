package dev.koga.hopi.ui.platform

import androidx.compose.runtime.Composable

/** Local wall-clock time of [epochMs]: `14:03:22`, or `14:03:22.481` [withMillis]. */
internal expect fun formatTimeOfDay(epochMs: Long, withMillis: Boolean): String

/** Hands text to the platform share sheet. */
internal fun interface ShareSheet {
    fun share(subject: String, text: String)
}

/**
 * The platform share sheet: `Intent.ACTION_SEND` on Android, `UIActivityViewController` on iOS.
 * Null where the platform has none (the JVM), and the screen then offers no share action.
 */
@Composable
internal expect fun rememberShareSheet(): ShareSheet?

/**
 * While composed, makes the system bars' icons readable over the screen's own background: dark
 * icons when [lightBackground], light ones otherwise, whatever the host app chose. The host's
 * choice is restored when it leaves the composition. Android only; elsewhere it does nothing.
 */
@Composable
internal expect fun SystemBarIcons(lightBackground: Boolean)

/**
 * The most text handed to a share sheet or the clipboard. An Android intent and a clip both travel
 * through a binder transaction of about 1 MB, which a UTF-16 string of this length stays well
 * below; past it, `setPrimaryClip` throws in the app.
 */
internal const val MAX_SHARE_CHARS: Int = 100_000

/** The first [max] characters of [text], never ending inside a surrogate pair; [text] when it fits. */
internal fun cutAt(text: String, max: Int = MAX_SHARE_CHARS): String =
    if (text.length <= max) {
        text
    } else {
        text.substring(0, if (text[max - 1].isHighSurrogate()) max - 1 else max)
    }

internal fun capForShare(text: String): String {
    val cut = cutAt(text)
    return if (cut === text) text else cut + "\n… (cut at $MAX_SHARE_CHARS characters)"
}
