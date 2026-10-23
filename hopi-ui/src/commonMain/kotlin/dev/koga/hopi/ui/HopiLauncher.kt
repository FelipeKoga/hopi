package dev.koga.hopi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ui.component.Icon
import dev.koga.hopi.ui.theme.DarkColors
import dev.koga.hopi.ui.theme.HopiIcons
import dev.koga.hopi.ui.theme.LightColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Wraps the app's [content] and floats a small draggable button over it that opens
 * [HopiScreen] over the content. The button is static: it never observes the store, and it
 * recomposes only when tapped (dragging moves it in the layout phase, without recomposition).
 *
 * [content] is laid out exactly as by the noop twin: it gets the launcher's own constraints
 * unchanged, every root child is placed at the top start, and the launcher is as large as its
 * largest child. The button and the screen cover that area.
 */
@Composable
public fun HopiLauncher(content: @Composable () -> Unit) {
    HopiLauncher(store = { HopiStore.default }, content = content)
}

/** Description of the floating button, for screen readers and tests. */
internal const val LAUNCHER_DESCRIPTION: String = "Open Hopi"

/**
 * [HopiLauncher] with the store resolved only when the screen opens ([store] is not called
 * before), and a [recompositionProbe] that tests use to count the launcher's recompositions.
 */
@Composable
internal fun HopiLauncher(
    store: () -> HopiStore,
    recompositionProbe: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    // Where the button rests, as fractions of the free area, so it stays put across rotations.
    val x = rememberSaveable { mutableStateOf(1f) }
    val y = rememberSaveable { mutableStateOf(0.72f) }
    // The screen's transition. Its state is read only in the draw phase below, so the end of the
    // transition redraws and never recomposes.
    val screen = remember { MutableTransitionState(open) }
    val focusManager = LocalFocusManager.current
    if (recompositionProbe != null) SideEffect(recompositionProbe)
    Layout(
        contents = listOf(
            {
                Layout(
                    content = content,
                    modifier = Modifier
                        // Once the opaque screen fully covers the app, the app is not drawn at all:
                        // no full-window layer under every inspector frame. It stays composed, so
                        // its state survives, and it draws again as soon as the screen starts to leave.
                        .drawWithContent { if (!(screen.isIdle && screen.currentState)) drawContent() }
                        .then(
                            if (open) {
                                // Screen readers do not walk the hidden app, and focus cannot move into it.
                                Modifier
                                    .clearAndSetSemantics {}
                                    .focusProperties { onEnter = { cancelFocusChange() } }
                                    .focusGroup()
                            } else {
                                Modifier
                            },
                        ),
                    measurePolicy = ContentMeasurePolicy,
                )
            },
            {
                if (!open) {
                    LauncherButton(
                        x,
                        y,
                        onOpen = {
                            // A focused field of the app would keep the keyboard and its input.
                            focusManager.clearFocus(force = true)
                            open = true
                            screen.targetState = true
                        },
                        recompositionProbe,
                    )
                }
                AnimatedVisibility(
                    visibleState = screen,
                    enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 16 },
                    exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 16 },
                ) {
                    HopiScreen(
                        store(),
                        onClose = {
                            open = false
                            screen.targetState = false
                        },
                    )
                }
            },
        ),
        measurePolicy = LauncherMeasurePolicy,
    )
}

/**
 * The content's layout, the same as the noop twin's launcher: every child gets the incoming
 * constraints unchanged and is placed at the top start; the node is as large as its largest
 * child (the minimum constraints with no child). Keep the two in step.
 */
private val ContentMeasurePolicy = MeasurePolicy { measurables, constraints ->
    val placeables = measurables.map { it.measure(constraints) }
    val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
    val height = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
    layout(width, height) { placeables.forEach { it.place(0, 0) } }
}

/** The content as [ContentMeasurePolicy] lays it out, then the button and screen over exactly its area. */
private val LauncherMeasurePolicy = MultiContentMeasurePolicy { (content, overlay), constraints ->
    val placeables = content.map { it.measure(constraints) }
    val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
    val height = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
    val cover = Constraints.fixed(width, height)
    val overlays = overlay.map { it.measure(cover) }
    layout(width, height) {
        placeables.forEach { it.place(0, 0) }
        overlays.forEach { it.place(0, 0) }
    }
}

@Composable
private fun LauncherButton(
    x: MutableState<Float>,
    y: MutableState<Float>,
    onOpen: () -> Unit,
    recompositionProbe: (() -> Unit)?,
) {
    if (recompositionProbe != null) SideEffect(recompositionProbe)
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    val scope = rememberCoroutineScope()
    // The free area, written on size changes and read only in the layout phase and the gesture.
    val area = remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(8.dp)
            .onSizeChanged { area.value = it },
    ) {
        Box(
            modifier = Modifier
                .offset {
                    val button = BUTTON_SIZE.roundToPx()
                    val (width, height) = area.value
                    IntOffset(
                        ((width - button).coerceAtLeast(0) * x.value).roundToInt(),
                        ((height - button).coerceAtLeast(0) * y.value).roundToInt(),
                    )
                }
                .size(BUTTON_SIZE)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(colors.text)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            // Settle on the nearer side edge, briefly.
                            val target = if (x.value < 0.5f) 0f else 1f
                            scope.launch { animate(x.value, target, animationSpec = tween(180)) { value, _ -> x.value = value } }
                        },
                    ) { change, drag ->
                        change.consume()
                        val button = BUTTON_SIZE.toPx()
                        val (width, height) = area.value
                        val freeWidth = (width - button).coerceAtLeast(1f)
                        val freeHeight = (height - button).coerceAtLeast(1f)
                        x.value = (x.value + drag.x / freeWidth).coerceIn(0f, 1f)
                        y.value = (y.value + drag.y / freeHeight).coerceIn(0f, 1f)
                    }
                }
                .semantics { contentDescription = LAUNCHER_DESCRIPTION }
                .clickable(role = Role.Button, onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(HopiIcons.Traffic, colors.onText, size = 22.dp)
        }
    }
}

/** 48 dp: the minimum touch target, and small enough to stay out of the way. */
private val BUTTON_SIZE = 48.dp
