package dev.koga.hopi.ui.perf

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.koga.hopi.core.perf.FrameSecond
import dev.koga.hopi.core.perf.PerfController
import dev.koga.hopi.core.perf.PerfItem
import dev.koga.hopi.core.perf.PerfLock
import dev.koga.hopi.core.perf.PerfSample
import dev.koga.hopi.core.perf.StallEvent
import dev.koga.hopi.ui.theme.DarkColors
import dev.koga.hopi.ui.theme.HopiType
import dev.koga.hopi.ui.theme.LightColors
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The HUD's two switches, shared by the launcher (a long press on its button) and the Perf tab.
 * In memory only: an app starts with the HUD hidden.
 */
internal object PerfHudState {
    var shown: Boolean by mutableStateOf(false)

    /** iOS frames tier 2: the display-link cadence, asked for only while the HUD shows it. */
    var cadence: Boolean by mutableStateOf(false)
}

/**
 * The HUD's text (docs/design/perf-monitors.md, "In-app"), computed on the writer's thread from
 * what the model hands its listeners: closed seconds of frames, samples and iOS stalls (never a
 * frame, never a jank event). [onItem] returns the new text only when it changed, so an idle app
 * or a steady one makes the HUD stop drawing. Items come from several monitor threads (frames,
 * sampler, iOS main), so one uncontended lock orders them.
 */
internal class HudMeter(private val ios: Boolean) {
    private var second: FrameSecond? = null
    private var sample: PerfSample? = null
    private val stallTimes = LongArray(STALLS)
    private val stallMs = LongArray(STALLS)
    private var stallNext = 0
    private var latestMs = 0L
    private var shown: String? = null
    private val lock = PerfLock()

    /** The text for [item], or null when it did not change the text. */
    fun onItem(item: PerfItem): String? = lock.locked { update(item) }

    private fun update(item: PerfItem): String? {
        when (item) {
            is FrameSecond -> {
                second = item
                latestMs = maxOf(latestMs, item.second * 1000 + 999)
            }
            is PerfSample -> {
                sample = item
                latestMs = maxOf(latestMs, item.atMs)
            }
            is StallEvent -> {
                stallTimes[stallNext] = item.atMs
                stallMs[stallNext] = item.durationMs
                stallNext = (stallNext + 1) % STALLS
                latestMs = maxOf(latestMs, item.atMs)
            }
            else -> return null
        }
        val next = text()
        if (next == shown) return null
        shown = next
        return next
    }

    fun text(): String {
        val parts = ArrayList<String>(3)
        frames()?.let(parts::add)
        sample?.cpu?.let { parts += "CPU ${it.processPercent.roundToLong()}%" }
        sample?.memory?.residentBytes?.let { parts += "${(it / (1024 * 1024))} MB" }
        return parts.joinToString(" · ").ifEmpty { "perf: waiting for data" }
    }

    private fun frames(): String? {
        val sec = second
        val sampling = sample
        if (ios) {
            // Tier 2's seconds are display-link callbacks, never frames the app drew.
            val cadence = sec?.takeIf { latestMs - it.second * 1000 < 2_000 }?.let { "cadence ${it.frames}/s" }
            val since = latestMs - STALL_WINDOW_MS
            var count = 0
            var worst = 0L
            for (i in 0 until STALLS) {
                if (stallTimes[i] != 0L && stallTimes[i] > since) {
                    count++
                    if (stallMs[i] > worst) worst = stallMs[i]
                }
            }
            val stalls = if (count == 0) "no stall/10 s" else "worst stall $worst ms · $count stall${if (count == 1) "" else "s"}/10 s"
            return listOfNotNull(cadence, stalls).joinToString(" · ")
        }
        sec ?: return null
        // A sampler tick in a later second with no frame between: nothing drew.
        if (sampling != null && sampling.atMs / 1000 > sec.second + 1) return "idle"
        if (sec.frames <= IDLE_FRAMES) return "idle"
        val fps = if (sampling == null) "${sec.frames} fps while drawing" else "${sec.frames} fps"
        return "$fps · ${sec.janky} jank"
    }

    private companion object {
        /** A second with this many frames or fewer reads "idle" (a cursor blink, a ripple's end). */
        const val IDLE_FRAMES = 2
        const val STALLS = 32
        const val STALL_WINDOW_MS = 10_000L
    }
}

internal const val HUD_DESCRIPTION: String = "Hopi performance HUD"

/**
 * The HUD over the app: one line of text, top center, touching nothing (the app keeps every
 * touch). It listens to [controller]'s model only while composed, and recomposes only when the
 * text changes; its own redraws are app frames, at most one per second and none while the app is
 * idle. With [PerfHudState.cadence] on iOS it asks for the display-link cadence while composed.
 */
@Composable
internal fun PerfHud(controller: PerfController) {
    val ios = controller.platform == "ios"
    val text by produceState<String?>(null, controller) {
        val meter = HudMeter(ios)
        val latest = MutableStateFlow(meter.text())
        val remove = controller.data.addListener { item -> meter.onItem(item)?.let { latest.value = it } }
        try {
            latest.collect { value = it }
        } finally {
            remove()
        }
    }
    if (ios && PerfHudState.cadence) {
        DisposableEffect(controller) {
            controller.watchCadence(true)
            onDispose { controller.watchCadence(false) }
        }
    }
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
        BasicText(
            text ?: "",
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(colors.text.copy(alpha = 0.82f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .semantics { contentDescription = HUD_DESCRIPTION },
            style = HopiType.meta.copy(color = colors.onText),
            maxLines = 1,
        )
    }
}
