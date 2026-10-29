package dev.koga.hopi.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
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

/** Where Open Runde and its license sit on the classpath (src/jvmMain/resources). */
internal const val HOPI_FONT_DIR: String = "dev/koga/hopi/ui/font"

internal val HOPI_FONT_FILES: List<Pair<String, FontWeight>> = listOf(
    "open_runde_regular.otf" to FontWeight.Normal,
    "open_runde_medium.otf" to FontWeight.Medium,
    "open_runde_semibold.otf" to FontWeight.SemiBold,
    "open_runde_bold.otf" to FontWeight.Bold,
)

// Compose Desktop's resource fonts, read on first use: no plugin, no dependency. A missing file
// falls back to the platform default instead of failing text layout; HopiFontsTest fails the build.
internal actual fun hopiSans(): FontFamily {
    val loader = Thread.currentThread().contextClassLoader ?: return FontFamily.Default
    if (HOPI_FONT_FILES.any { (file, _) -> loader.getResource("$HOPI_FONT_DIR/$file") == null }) return FontFamily.Default
    // Positional: (resource: String, weight: FontWeight); the identity overloads need bytes second.
    return FontFamily(HOPI_FONT_FILES.map { (file, weight) -> Font("$HOPI_FONT_DIR/$file", weight) })
}
