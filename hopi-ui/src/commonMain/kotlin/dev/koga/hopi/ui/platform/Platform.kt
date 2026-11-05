package dev.koga.hopi.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/** Local wall-clock time of [epochMs]: `14:03:22`, or `14:03:22.481` [withMillis]. */
internal expect fun formatTimeOfDay(epochMs: Long, withMillis: Boolean): String

/** The sans of every HopiType style but the monospace ones: Open Runde (OFL 1.1), bundled on the JVM. */
internal expect fun hopiSans(): FontFamily

/** Hands text to the platform share sheet. */
internal fun interface ShareSheet {
    fun share(subject: String, text: String)
}

/**
 * The platform share sheet, or null where the platform has none (the desktop JVM, this module's
 * only target since the in-app UI went in the runtime-split plan's stage 5), and the detail then
 * offers no share action.
 */
@Composable
internal expect fun rememberShareSheet(): ShareSheet?

/**
 * The most text handed to the clipboard or a share sheet: the limit of an Android clip (a binder
 * transaction of about 1 MB), which the in-app UI had to respect until the runtime-split plan's
 * stage 5 removed it, kept as the desktop's cut.
 */
internal const val MAX_SHARE_CHARS: Int = 100_000

/** The first [max] characters of [text], never ending inside a surrogate pair; [text] when it fits. */
internal fun cutAt(text: String, max: Int = MAX_SHARE_CHARS): String =
    if (text.length <= max) {
        text
    } else {
        text.substring(0, if (text[max - 1].isHighSurrogate()) max - 1 else max)
    }
