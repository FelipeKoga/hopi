package dev.koga.hopi.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.koga.hopi.connect.RemotePerf
import dev.koga.hopi.connect.RemotePerfEvent
import dev.koga.hopi.connect.RemotePerfSample
import dev.koga.hopi.connect.RemotePerfView
import dev.koga.hopi.connect.RemoteThreadUse
import dev.koga.hopi.core.body.formatBytes
import dev.koga.hopi.core.model.HttpTransaction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/** A stretch of the app's wall clock, in epoch ms. */
internal data class PerfSpan(val fromMs: Long, val toMs: Long) {
    val ms: Long get() = toMs - fromMs

    operator fun contains(atMs: Long): Boolean = atMs in fromMs..toMs

    fun overlaps(startMs: Long, endMs: Long): Boolean = startMs <= toMs && endMs >= fromMs

    /** How far along this span [atMs] is: below 0 before it, above 1 after it. */
    fun fraction(atMs: Long): Float = ((atMs - fromMs).toDouble() / ms).toFloat()

    fun at(fraction: Float): Long = fromMs + (fraction.coerceIn(0f, 1f).toDouble() * ms).roundToLong()

    fun text(): String = "${clock(fromMs)} – ${clock(toMs)} (${seconds(ms)})"
}

/**
 * The Performance screen's state, hoisted so ⌘P and the menu drive it: whether it replaces the
 * Network one, and where its one timeline looks. Every chart reads the same [domain], so one
 * pixel column is one moment in all of them. A selection holds the timeline still: new samples
 * keep arriving, and [live] goes back to them.
 */
@Stable
internal class PerfPanelState {
    var open: Boolean by mutableStateOf(false)

    /** How much the charts show; the app keeps 20 minutes. */
    var rangeMs: Long by mutableStateOf(RANGES_MS[1])
        private set

    /** The selected stretch, summed up beside the charts. */
    var selection: PerfSpan? by mutableStateOf(null)
        private set

    private var zoom: PerfSpan? by mutableStateOf(null)
    private var heldEndMs: Long? by mutableStateOf(null)

    /** The moment under the pointer, on whichever chart. */
    var hoverMs: Long? by mutableStateOf(null)

    val zoomed: Boolean get() = zoom != null
    val held: Boolean get() = heldEndMs != null

    fun toggle() {
        open = !open
    }

    /** What the charts show, with [latestMs] the app's newest sample. */
    fun domain(latestMs: Long): PerfSpan {
        zoom?.let { return it }
        val end = heldEndMs ?: latestMs
        return PerfSpan(end - rangeMs, end)
    }

    fun showRange(ms: Long) {
        rangeMs = ms
        zoom = null
    }

    /** A drag over the charts, held at [endMs]; too short a drag still covers two samples. */
    fun select(span: PerfSpan, endMs: Long) {
        heldEndMs = heldEndMs ?: endMs
        selection = if (span.ms >= MIN_SELECTION_MS) span else PerfSpan(span.fromMs, span.fromMs + MIN_SELECTION_MS)
    }

    /** Selects [span] and frames it, whatever the range: a hang long gone from the last minutes. */
    fun focus(span: PerfSpan, latestMs: Long) {
        select(span, latestMs)
        zoomToSelection()
    }

    fun zoomToSelection() {
        val span = selection ?: return
        val margin = maxOf(ZOOM_MARGIN_MS, span.ms / 4)
        zoom = PerfSpan(span.fromMs - margin, span.toMs + margin)
    }

    fun zoomOut() {
        zoom = null
    }

    fun live() {
        selection = null
        zoom = null
        heldEndMs = null
    }

    companion object {
        val RANGES_MS: List<Long> = listOf(60_000, 300_000, RemotePerf.MAX_POINTS * 1_000L)
        private const val MIN_SELECTION_MS = 2_000L
        private const val ZOOM_MARGIN_MS = 5_000L
    }
}

/** The newest moment the app has reported, or null with nothing to chart. */
internal fun latestMs(view: RemotePerfView): Long? = listOfNotNull(
    view.samples.lastOrNull()?.atMs,
    view.frames.lastOrNull()?.let { (it.second + 1) * 1000 },
).maxOrNull()

/** The sample of the moment [atMs]; none when the nearest is over [NEAREST_SAMPLE_MS] away (a gap in the series). */
internal fun sampleNear(samples: List<RemotePerfSample>, atMs: Long): RemotePerfSample? =
    samples.minByOrNull { abs(it.atMs - atMs) }?.takeIf { abs(it.atMs - atMs) <= NEAREST_SAMPLE_MS }

/** What happened in one stretch: everything the app reported about it, side by side. */
internal class PerfCut(
    val span: PerfSpan,
    val cpuPeak: RemotePerfSample?,
    val cpuAverage: Double?,
    val hopiAverage: Double?,
    val memoryFirstBytes: Long?,
    val memoryLastBytes: Long?,
    val memoryPeakBytes: Long?,
    val fps: Double?,
    val janky: Long,
    val hangs: List<RemotePerfEvent>,
    val stalls: List<RemotePerfEvent>,
    val requests: List<HttpTransaction>,
    val threads: List<RemoteThreadUse>,
) {
    /** The figures, one line each; a series the app did not sample here says nothing. */
    fun summary(): List<String> = buildList {
        cpuPeak?.cpu?.let { peak ->
            val hopi = hopiAverage?.let { " (Hopi ${percent(it)})" }.orEmpty()
            add("CPU peak ${percent(peak)} at ${clock(cpuPeak.atMs)}, average ${percent(cpuAverage ?: peak)}$hopi")
        }
        if (memoryFirstBytes != null && memoryLastBytes != null) {
            add("Memory ${formatBytes(memoryFirstBytes)} to ${formatBytes(memoryLastBytes)}, peak ${formatBytes(memoryPeakBytes ?: memoryLastBytes)}")
        }
        fps?.let { add("Frames ${it.roundToLong()} fps, $janky janky") }
        add("Hangs ${hangs.size}, stalls ${stalls.size}" + (stalls.mapNotNull { it.ms }.maxOrNull()?.let { ", worst stall ${it.roundToLong()} ms" }.orEmpty()))
    }

    /** Everything as text, for a note or an agent. */
    fun text(): String = buildString {
        append("Selection ").append(span.text()).append('\n')
        summary().forEach { append(it).append('\n') }
        if (threads.isNotEmpty()) {
            append("Threads\n")
            threads.forEach { append("  ").append(threadText(it)).append('\n') }
        }
        if (hangs.isNotEmpty()) {
            append("Hangs\n")
            for (hang in hangs) {
                append("  ").append(clock(hang.atMs)).append(' ').append(hang.ms?.roundToLong() ?: "?").append(" ms\n")
                append(hang.stacksText().prependIndent("  "))
            }
        }
        if (requests.isNotEmpty()) {
            append("Requests\n")
            requests.forEach { append("  ").append(requestText(it)).append('\n') }
        }
    }.trimEnd()
}

internal fun perfCut(view: RemotePerfView, transactions: List<HttpTransaction>, span: PerfSpan): PerfCut {
    val samples = view.samples.filter { it.atMs in span }
    val cpu = samples.filter { it.cpu != null }
    val memory = samples.mapNotNull { it.residentBytes }
    val frames = view.frames.filter { it.second * 1000 in span }
    return PerfCut(
        span = span,
        cpuPeak = cpu.maxByOrNull { it.cpu ?: 0.0 },
        cpuAverage = cpu.mapNotNull { it.cpu }.takeIf { it.isNotEmpty() }?.average(),
        hopiAverage = cpu.mapNotNull { it.hopiCpu }.takeIf { it.isNotEmpty() }?.average(),
        memoryFirstBytes = memory.firstOrNull(),
        memoryLastBytes = memory.lastOrNull(),
        memoryPeakBytes = memory.maxOrNull(),
        fps = frames.takeIf { it.isNotEmpty() }?.map { it.frames }?.average(),
        janky = frames.sumOf { it.janky },
        hangs = view.hangs.filter { span.overlaps(it.atMs, it.atMs + (it.ms?.toLong() ?: 0)) },
        stalls = view.stallEvents.filter { it.atMs in span },
        requests = transactions.filter { span.overlaps(it.startedAtEpochMs, it.startedAtEpochMs + (it.durationMs ?: Long.MAX_VALUE / 2)) },
        threads = RemotePerf.threadsBetween(view.threadTicks, span.fromMs, span.toMs),
    )
}

internal fun threadText(thread: RemoteThreadUse): String =
    "${percent(thread.percent)}  ${thread.name.ifEmpty { "thread ${thread.tid}" }}${if (thread.hopi) "  (Hopi)" else ""}"

internal fun requestText(request: HttpTransaction): String = listOfNotNull(
    request.method,
    request.url,
    request.responseCode?.toString() ?: request.error?.let { "failed" },
    request.durationMs?.let { "$it ms" } ?: "pending",
).joinToString(" ")

internal fun percent(value: Double?): String = value?.let { "${(it * 10).roundToLong() / 10.0}%" } ?: "-"

internal fun percent(sample: RemotePerfSample): String = percent(sample.cpu)

internal fun clock(epochMs: Long): String = CLOCK.format(Instant.ofEpochMilli(epochMs))

private fun seconds(ms: Long): String = if (ms < 120_000) "${(ms / 100.0).roundToLong() / 10.0} s" else "${ms / 60_000} min"

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** At 1 Hz the nearest sample of a moment is half a second away; two seconds means a gap. */
private const val NEAREST_SAMPLE_MS = 2_000L
