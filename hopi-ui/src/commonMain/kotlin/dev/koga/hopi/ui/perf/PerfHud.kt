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
import dev.koga.hopi.core.perf.PerfMonitor
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
 * sampler, iOS main), so one uncontended lock orders them. Once the controller reports which
 * monitors run ([onRunning]), a stopped monitor's figures go and what it still hands out is
 * ignored; with none running the text says so. Each new text goes to [publish] under that lock,
 * so the texts land in the order they changed (a sample's text racing a stop's never lands after
 * it); [publish] must not call the meter.
 */
internal class HudMeter(private val ios: Boolean, private val publish: (String) -> Unit = {}) {
    private var second: FrameSecond? = null
    private var sample: PerfSample? = null
    private val stallTimes = LongArray(STALLS)
    private val stallMs = LongArray(STALLS)
    private var stallNext = 0
    private var latestMs = 0L
    private var shown: String? = null

    /** The monitors running, as the controller last reported them; null until it does. */
    private var running: Set<PerfMonitor>? = null
    private val lock = PerfLock()

    /** The text for [item], or null when it did not change the text. */
    fun onItem(item: PerfItem): String? = lock.locked { update(item) }

    /**
     * The text once [monitors] are the ones running, or null when it did not change: a stopped
     * monitor's figures are dropped, never left on screen as if live.
     */
    fun onRunning(monitors: Set<PerfMonitor>): String? = lock.locked {
        running = monitors
        if (PerfMonitor.FRAMES !in monitors) {
            second = null
            stallTimes.fill(0)
        }
        if (PerfMonitor.CPU !in monitors && PerfMonitor.MEMORY !in monitors) sample = null
        changed()
    }

    private fun update(item: PerfItem): String? {
        // A stopped monitor still hands out what was under way: a frames second a later tick
        // closes, a sample taken as it stopped.
        when (item) {
            is FrameSecond -> {
                if (!on(PerfMonitor.FRAMES)) return null
                second = item
                latestMs = maxOf(latestMs, item.second * 1000 + 999)
            }
            is PerfSample -> {
                if (!on(PerfMonitor.CPU) && !on(PerfMonitor.MEMORY)) return null
                sample = item
                latestMs = maxOf(latestMs, item.atMs)
            }
            is StallEvent -> {
                if (!on(PerfMonitor.FRAMES)) return null
                stallTimes[stallNext] = item.atMs
                stallMs[stallNext] = item.durationMs
                stallNext = (stallNext + 1) % STALLS
                latestMs = maxOf(latestMs, item.atMs)
            }
            else -> return null
        }
        return changed()
    }

    private fun changed(): String? {
        val next = text()
        if (next == shown) return null
        shown = next
        publish(next)
        return next
    }

    /** Whether [monitor] runs; true until the controller reports the set. */
    private fun on(monitor: PerfMonitor): Boolean = running?.contains(monitor) ?: true

    fun text(): String {
        if (running?.isEmpty() == true) return "perf: off"
        val parts = ArrayList<String>(3)
        frames()?.let(parts::add)
        if (on(PerfMonitor.CPU)) sample?.cpu?.let { parts += "CPU ${it.processPercent.roundToLong()}%" }
        if (on(PerfMonitor.MEMORY)) sample?.memory?.residentBytes?.let { parts += "${(it / (1024 * 1024))} MB" }
        return parts.joinToString(" · ").ifEmpty { "perf: waiting for data" }
    }

    private fun frames(): String? {
        if (!on(PerfMonitor.FRAMES)) return null
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
 * touch). It listens to [controller]'s model and running set only while composed, and recomposes
 * only when the text changes; its own redraws are app frames, at most one per second (and one when
 * a monitor starts or stops) and none while the app is idle. With [PerfHudState.cadence] on iOS it
 * asks for the display-link cadence while composed.
 */
@Composable
internal fun PerfHud(controller: PerfController) {
    val ios = controller.platform == "ios"
    val text by produceState<String?>(null, controller) {
        // Stored under the meter's lock, so a sample's text that raced a stop never lands after it.
        val latest = MutableStateFlow<String?>(null)
        val meter = HudMeter(ios) { latest.value = it }
        latest.value = meter.text()
        val remove = controller.data.addListener { meter.onItem(it) }
        // At once with the monitors running now, then at every start and stop.
        val removeRunning = controller.addRunningListener { meter.onRunning(it) }
        try {
            latest.collect { value = it }
        } finally {
            removeRunning()
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
