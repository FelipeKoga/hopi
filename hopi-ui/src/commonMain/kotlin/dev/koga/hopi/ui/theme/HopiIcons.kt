package dev.koga.hopi.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Stroke icons on a 24-unit grid (Lucide geometry), drawn in black and tinted at the call site.
 * Built once, on first use.
 */
internal object HopiIcons {
    val Close by lazy { icon("Close", "M18 6 6 18", "M6 6l12 12") }
    val Back by lazy { icon("Back", "M12 19l-7-7 7-7", "M19 12H5") }
    val Search by lazy { icon("Search", "M11 3a8 8 0 1 0 0 16a8 8 0 1 0 0-16z", "M21 21l-4.3-4.3") }
    val Trash by lazy {
        icon(
            "Trash",
            "M3 6h18",
            "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6",
            "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2",
        )
    }
    val Copy by lazy {
        icon(
            "Copy",
            "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z",
            "M4 16a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2",
        )
    }
    val Link by lazy {
        icon(
            "Link",
            "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
            "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
        )
    }
    val Terminal by lazy { icon("Terminal", "M4 17l6-6-6-6", "M12 19h8") }
    val Share by lazy { icon("Share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "M16 6l-4-4-4 4", "M12 2v13") }

    /** Horizontal sliders: the rules list. */
    val Rules by lazy { icon("Rules", "M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3", "M14 2v4", "M8 10v4", "M16 18v4") }

    /** A wand: a made-up response from this one. */
    val Mock by lazy { icon("Mock", "M15 4V2", "M15 16v-2", "M8 9h2", "M20 9h2", "M17.8 11.8L19 13", "M17.8 6.2L19 5", "M3 21l9-9", "M12.2 6.2L11 5") }

    /** A pulse: the performance monitors. */
    val Perf by lazy { icon("Perf", "M22 12h-4l-3 9L9 3l-3 9H2") }

    /** Up and down arrows: traffic in both directions, the launcher's glyph. */
    val Traffic by lazy { icon("Traffic", "M7 3v18", "M3 7l4-4 4 4", "M17 21V3", "M21 17l-4 4-4-4") }

    private fun icon(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        for (path in paths) {
            builder.addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
