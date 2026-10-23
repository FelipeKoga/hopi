package dev.koga.hopi.ui.platform

import androidx.compose.runtime.Composable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// SimpleDateFormat is not thread-safe, and rows are built on Dispatchers.Default: one per thread.
private val seconds = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }
private val millis = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

internal actual fun formatTimeOfDay(epochMs: Long, withMillis: Boolean): String =
    (if (withMillis) millis else seconds).get().format(Date(epochMs))

/** Desktop JVMs have no share sheet; copying covers the same need there. */
@Composable
internal actual fun rememberShareSheet(): ShareSheet? = null

/** Desktop windows have no system bars to adapt. */
@Composable
internal actual fun SystemBarIcons(lightBackground: Boolean) {
}
