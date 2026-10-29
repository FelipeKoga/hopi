package dev.koga.hopi.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.koga.hopi.connect.PerfClient
import dev.koga.hopi.connect.RemoteFrameSecond
import dev.koga.hopi.connect.RemotePerfEvent
import dev.koga.hopi.connect.RemotePerfMonitor
import dev.koga.hopi.connect.RemotePerfSample
import dev.koga.hopi.connect.RemotePerfView
import dev.koga.hopi.connect.RemoteSession
import dev.koga.hopi.core.body.formatBytes
import dev.koga.hopi.ui.desktop.HopiDesktopType
import dev.koga.hopi.ui.desktop.hopiDesktopColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

internal object PerfTags {
    const val PANEL = "perf-panel"
    const val MESSAGE = "perf-message"
    const val FRAMES_CHART = "perf-chart-frames"
    const val CPU_CHART = "perf-chart-cpu"
    const val MEMORY_CHART = "perf-chart-memory"
    const val THREADS = "Per-thread CPU"

    fun toggle(monitor: String) = "Monitor $monitor"

    fun copy(seq: Long) = "Copy hang #$seq"
}

/** The perf panel's state, hoisted so ⌘P and the menu drive it. */
@Stable
internal class PerfPanelState {
    var open: Boolean by mutableStateOf(false)

    fun toggle() {
        open = !open
    }
}

/**
 * The perf panel beside the list (docs/design/perf-monitors.md, "Desktop viewer"): one switch per
 * monitor with who holds it (and a lease's end), three charts drawn with [Canvas] (frames per
 * second with janky bars, CPU %, memory with its events as markers), the per-thread list on
 * request, and the hangs with their stacks (copied as text). It reads only [RemoteSession.perf]:
 * the app is asked for perf data while this panel is composed and never otherwise, and what its
 * switches start runs while it is open (they are held by its stream).
 */
@Composable
internal fun PerfPanel(session: RemoteSession?, state: PerfPanelState, modifier: Modifier = Modifier) {
    val colors = hopiDesktopColors
    val perf = session?.perf
    DisposableEffect(perf) {
        val watch = perf?.watch()
        onDispose { watch?.close() }
    }
    val view = perf?.view?.collectAsState()?.value ?: RemotePerfView.NONE
    var threads by remember(perf) { mutableStateOf(false) }

    Column(modifier.fillMaxHeight().background(colors.background).testTag(PerfTags.PANEL)) {
        Row(
            Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText("Perf", style = HopiDesktopType.bodyStrong.copy(color = colors.text))
            view.status?.let { BasicText(it.platform, style = HopiDesktopType.meta.copy(color = colors.textFaint)) }
            Spacer(Modifier.weight(1f))
            ChromeButton(
                "Stop all",
                enabled = view.control && !view.status?.running.isNullOrEmpty(),
                onClick = { perf?.stopAll() },
            )
            ChromeButton("Close", onClick = { state.open = false })
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        view.message?.let {
            BasicText(
                it,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag(PerfTags.MESSAGE),
                style = HopiDesktopType.meta.copy(color = colors.text),
            )
        }
        when {
            session == null -> PerfNote("Not connected.")
            !view.supported -> PerfNote("This app's Hopi has no perf monitors (not connected yet, no hopi-perf in this build, perf left out of Hopi's start, or an older Hopi).")
            else -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Monitors(view) { monitor, on -> perf?.setMonitor(monitor, on) }
                Section("Frames per second", latest = view.frames.lastOrNull()?.let(::framesText) ?: idle(view, "frames"))
                FramesChart(view.frames, Modifier.testTag(PerfTags.FRAMES_CHART))
                Section("CPU", latest = view.samples.lastOrNull { it.cpu != null }?.let(::cpuText) ?: idle(view, "cpu"))
                LineChart(listOf(view.samples.mapNotNull { s -> s.cpu?.let { s.atMs to it } }), markers = emptyList(), floor = 100.0, Modifier.testTag(PerfTags.CPU_CHART))
                Section("Memory", latest = view.samples.lastOrNull { it.residentBytes != null }?.let(::memoryText) ?: idle(view, "memory"))
                LineChart(
                    listOf(
                        view.samples.mapNotNull { s -> s.residentBytes?.let { s.atMs to it.toDouble() } },
                        view.samples.mapNotNull { s -> s.javaHeapBytes?.let { s.atMs to it.toDouble() } },
                    ),
                    markers = view.memoryEvents.filter { it.kind == "memory" }.map { it.atMs },
                    floor = 0.0,
                    Modifier.testTag(PerfTags.MEMORY_CHART),
                )
                if (view.memoryEvents.isNotEmpty()) {
                    for (event in view.memoryEvents.takeLast(5).asReversed()) PerfLine(eventText(event))
                }
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("THREADS", Modifier.weight(1f), style = HopiDesktopType.section.copy(color = colors.textFaint))
                    ChromeSwitch(threads, PerfTags.THREADS, onChange = {
                        threads = it
                        perf?.watchThreads(it)
                    })
                }
                if (threads) {
                    val list = view.threads
                    if (list == null) {
                        PerfLine("Waiting for the next sample (CPU must be on; not available on the JVM).")
                    } else {
                        for (thread in list) PerfLine("${pct(thread.percent)}  ${thread.name}${if (thread.hopi) "  (Hopi)" else ""}")
                    }
                }
                Section("Hangs", latest = if (view.hangs.isEmpty()) null else "${view.hangs.size} · janky frames ${view.janks} · stalls ${view.stalls}")
                if (view.hangs.isEmpty()) {
                    PerfLine("No hang so far.")
                } else {
                    for (hang in view.hangs.asReversed()) HangRow(hang)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Monitors(view: RemotePerfView, onToggle: (String, Boolean) -> Unit) {
    val colors = hopiDesktopColors
    val status = view.status
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        for (name in PerfClient.MONITORS) {
            val running = status?.monitor(name)
            val available = status == null || name in status.available
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(name, style = HopiDesktopType.bodyStrong.copy(color = if (available) colors.text else colors.textFaint))
                    BasicText(
                        when {
                            !available -> "not available on ${status?.platform}"
                            running != null -> monitorText(running)
                            else -> "off"
                        },
                        style = HopiDesktopType.meta.copy(color = colors.textMuted),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (available && view.control) ChromeSwitch(running != null, PerfTags.toggle(name), onChange = { onToggle(name, it) })
            }
        }
        val notes = buildList {
            if (!view.control) add("This app refuses remote starts and stops: remote control is off in its Hopi configuration (perf remoteControl, or perfRemoteControl on iOS, is false).")
            if (status?.monitor("frames")?.unattached == true) add("Frames attach when an activity next starts.")
            addAll(status?.notices.orEmpty())
            add("A monitor switched on here runs while this panel is open; off stops it for every client.")
        }
        for (note in notes) PerfLine(note)
    }
}

/** What an empty chart says: its monitor is off, or on with nothing drawn or sampled yet. */
private fun idle(view: RemotePerfView, monitor: String): String = when {
    view.status?.monitor(monitor) == null -> "off"
    monitor == "frames" && view.status?.platform == "ios" -> "stalls only (no frame counts without the cadence)"
    monitor == "frames" -> "nothing drawn yet"
    else -> "waiting for a sample"
}

/** `on · 250 ms · this panel`, `on · code`, `on · lease until 14:32:05`. */
private fun monitorText(monitor: RemotePerfMonitor): String = buildList {
    add("on")
    monitor.thresholdMs?.let { add("$it ms") }
    monitor.intervalMs?.let { add("every $it ms") }
    for (owner in monitor.owners) {
        add(
            when {
                owner == "code" -> "started in the app"
                owner == "lease" -> monitor.expiresAtMs?.let { "lease until ${clock(it)}" } ?: "lease"
                owner == "lease:no-expiry" -> "lease, no expiry"
                owner.startsWith("tail:") -> "a stream (this panel or an agent)"
                else -> owner
            },
        )
    }
}.joinToString(" · ")

@Composable
private fun HangRow(hang: RemotePerfEvent) {
    val colors = hopiDesktopColors
    @Suppress("DEPRECATION") // The text-only clipboard API, as the rest of the viewer.
    val clipboard = LocalClipboardManager.current
    var open by remember(hang.seq) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    "#${hang.seq}  ${hang.ms?.roundToLong() ?: "?"} ms${if (hang.ongoing) "  ongoing" else ""}  ${clock(hang.atMs)}",
                    style = HopiDesktopType.mono.copy(color = colors.text),
                )
                BasicText(
                    listOfNotNull(
                        hang.screen,
                        hang.stacks.firstOrNull()?.firstOrNull(),
                        if (hang.debugger) "debugger attached: no stack, not counted" else null,
                        if (hang.stackMissed) "no stack survived the capture" else null,
                    ).joinToString("  ·  "),
                    style = HopiDesktopType.meta.copy(color = colors.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hang.stacks.isNotEmpty()) {
                ChromeButton(
                    "Copy",
                    onClick = { clipboard.setText(AnnotatedString(hang.stacksText())) },
                    modifier = Modifier.semantics { contentDescription = PerfTags.copy(hang.seq) },
                )
            }
        }
        if (open) {
            BasicText(
                hang.stacksText().ifEmpty { "no stack" },
                modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 12.dp, vertical = 6.dp),
                style = HopiDesktopType.mono.copy(color = colors.textMuted),
            )
        }
        Spacer(Modifier.fillMaxWidth().padding(start = 12.dp).height(1.dp).background(colors.border))
    }
}

@Composable
private fun Section(title: String, latest: String?) {
    val colors = hopiDesktopColors
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title.uppercase(), style = HopiDesktopType.section.copy(color = colors.textFaint))
        Spacer(Modifier.weight(1f))
        latest?.let { BasicText(it, style = HopiDesktopType.meta.copy(color = colors.text), maxLines = 1) }
    }
}

@Composable
private fun PerfLine(text: String) {
    BasicText(text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), style = HopiDesktopType.meta.copy(color = hopiDesktopColors.textMuted))
}

@Composable
private fun PerfNote(text: String) {
    BasicText(text, Modifier.padding(12.dp), style = HopiDesktopType.body.copy(color = hopiDesktopColors.textMuted))
}

/** Frames per second as bars, the janky ones of each second drawn over them in the strong color. */
@Composable
private fun FramesChart(frames: List<RemoteFrameSecond>, modifier: Modifier = Modifier) {
    val colors = hopiDesktopColors
    val shown = frames.takeLast(CHART_SECONDS)
    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT).padding(horizontal = 12.dp)) {
        frame(colors.border)
        if (shown.isEmpty()) return@Canvas
        val end = shown.last().second
        val top = maxOf(60.0, shown.maxOf { it.frames }.toDouble())
        val slot = size.width / CHART_SECONDS
        for (second in shown) {
            val x = size.width - (end - second.second + 1) * slot
            if (x < 0) continue
            val h = (second.frames / top * size.height).toFloat()
            drawRect(colors.textFaint, Offset(x, size.height - h), Size(maxOf(1f, slot - 1f), h))
            if (second.janky > 0) {
                val j = maxOf(2f, (second.janky / top * size.height).toFloat())
                drawRect(colors.text, Offset(x, size.height - j), Size(maxOf(1f, slot - 1f), j))
            }
        }
    }
}

/** Lines over time (the first solid, the next dashed), scaled to their maximum or [floor]; [markers] as vertical ticks. */
@Composable
private fun LineChart(lines: List<List<Pair<Long, Double>>>, markers: List<Long>, floor: Double, modifier: Modifier = Modifier) {
    val colors = hopiDesktopColors
    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT).padding(horizontal = 12.dp)) {
        frame(colors.border)
        val all = lines.flatten()
        if (all.isEmpty()) return@Canvas
        val end = all.maxOf { it.first }
        val start = end - CHART_SECONDS * 1000L
        val top = maxOf(floor, all.maxOf { it.second }) * 1.05
        fun x(t: Long) = ((t - start).toDouble() / (end - start) * size.width).toFloat()
        fun y(v: Double) = (size.height - v / top * size.height).toFloat()
        for (t in markers) {
            if (t < start) continue
            drawLine(colors.textMuted, Offset(x(t), 0f), Offset(x(t), size.height), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)))
        }
        lines.forEachIndexed { index, points ->
            val visible = points.filter { it.first >= start }
            if (visible.isEmpty()) return@forEachIndexed
            val path = Path()
            visible.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
            drawPath(
                path,
                if (index == 0) colors.text else colors.textMuted,
                style = Stroke(width = 1.5f, pathEffect = if (index == 0) null else PathEffect.dashPathEffect(floatArrayOf(4f, 3f))),
            )
        }
    }
}

private fun DrawScope.frame(color: Color) {
    drawLine(color, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
}

internal fun framesText(second: RemoteFrameSecond): String = "${second.frames} fps · ${second.janky} janky · worst ${second.worstMs.roundToLong()} ms"

internal fun cpuText(sample: RemotePerfSample): String = "${pct(sample.cpu)}" + (sample.hopiCpu?.let { " (Hopi ${pct(it)})" } ?: "")

internal fun memoryText(sample: RemotePerfSample): String = listOfNotNull(
    sample.residentBytes?.let { "resident ${formatBytes(it)}" },
    sample.javaHeapBytes?.let { "java heap ${formatBytes(it)} (dashed)" },
).joinToString(" · ")

private fun eventText(event: RemotePerfEvent): String = when (event.kind) {
    "anr" -> "${clock(event.atMs)}  previous run's ANR: ${event.description ?: "no description"}"
    else -> "${clock(event.atMs)}  memory ${event.event}${event.level?.let { " level $it" } ?: ""}${event.detail?.let { " $it" } ?: ""}"
}

private fun pct(value: Double?): String = value?.let { "${(it * 10).roundToLong() / 10.0}%" } ?: "-"

private fun clock(epochMs: Long): String = CLOCK.format(Instant.ofEpochMilli(epochMs))

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** The charts show the last five minutes. */
private const val CHART_SECONDS = 300
private val CHART_HEIGHT = 72.dp
