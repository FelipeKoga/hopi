package dev.koga.hopi.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy

/**
 * The noop twin of `hopi-ui`'s launcher: it renders [content] and nothing else, laid out
 * exactly as the real launcher lays it out, so a release build has the layout tested in debug.
 */
@Composable
public fun HopiLauncher(content: @Composable () -> Unit) {
    Layout(content = content, measurePolicy = ContentMeasurePolicy)
}

/**
 * The real launcher's content layout: every child gets the incoming constraints unchanged and is
 * placed at the top start; the node is as large as its largest child (the minimum constraints
 * with no child). Keep the two in step.
 */
private val ContentMeasurePolicy = MeasurePolicy { measurables, constraints ->
    val placeables = measurables.map { it.measure(constraints) }
    val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
    val height = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
    layout(width, height) { placeables.forEach { it.place(0, 0) } }
}
