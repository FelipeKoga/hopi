package dev.koga.hopi.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.koga.hopi.connect.PerfClient
import dev.koga.hopi.connect.RemotePerf
import dev.koga.hopi.connect.RemoteFrameSecond
import dev.koga.hopi.connect.RemotePerfEvent
import dev.koga.hopi.connect.RemotePerfMonitor
import dev.koga.hopi.connect.RemotePerfSample
import dev.koga.hopi.connect.RemotePerfView
import dev.koga.hopi.connect.RemoteSession
import dev.koga.hopi.core.body.formatBytes
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.ui.desktop.HopiDesktopType
import dev.koga.hopi.ui.desktop.HopiScrollbar
import dev.koga.hopi.ui.desktop.hopiDesktopColors
import kotlin.math.abs
import kotlin.math.roundToLong

internal object PerfTags {
    const val PANEL = "perf-panel"
    const val MESSAGE = "perf-message"
    const val FRAMES_CHART = "perf-chart-frames"
    const val CPU_CHART = "perf-chart-cpu"
    const val MEMORY_CHART = "perf-chart-memory"
    const val REQUESTS_CHART = "perf-chart-requests"
    const val SELECTION = "perf-selection"

    fun toggle(monitor: String) = "Monitor $monitor"

    fun copy(seq: Long) = "Copy hang #$seq"

    fun inspect(seq: Long) = "Inspect hang #$seq"
}

/**
 * The Performance screen, in place of the Network one (docs/design/perf-monitors.md, "Desktop
 * viewer"): one timeline across every chart (frames per second with janky bars, CPU %, memory, the
 * requests), hangs and stalls marked on it, and beside it the monitors, the threads and the hangs.
 * Dragging over a chart selects a stretch; the side then sums up that stretch alone. It reads only
 * [RemoteSession.perf]: the app is asked for perf data while this panel is composed and never
 * otherwise, the per-thread scan included, and what its switches start runs while it is open (they
 * are held by its stream).
 */
@Composable
internal fun PerfPanel(session: RemoteSession?, state: PerfPanelState, modifier: Modifier = Modifier) {
    val colors = hopiDesktopColors
    val perf = session?.perf
    DisposableEffect(perf) {
        val watch = perf?.watch(threads = true)
        onDispose { watch?.close() }
    }
    val view = perf?.view?.collectAsState()?.value ?: RemotePerfView.NONE
    val transactions = session?.store?.collectAsState()?.value?.transactions?.collectAsState()?.value.orEmpty()
    val latest = latestMs(view)
    val domain = state.domain(latest ?: 0)

    Column(modifier.fillMaxHeight().testTag(PerfTags.PANEL)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText("Perf", style = HopiDesktopType.title.copy(color = colors.text))
            view.status?.let { BasicText(it.platform, style = HopiDesktopType.meta.copy(color = colors.textFaint)) }
            Spacer(Modifier.width(10.dp))
            for ((range, label) in PerfPanelState.RANGES_MS.zip(RANGE_LABELS)) {
                ChromeButton(
                    label,
                    onClick = { state.showRange(range) },
                    variant = if (range == state.rangeMs && !state.zoomed) ButtonVariant.Secondary else ButtonVariant.Ghost,
                )
            }
            if (state.selection != null) {
                if (state.zoomed) ChromeButton("Zoom out", onClick = state::zoomOut) else ChromeButton("Zoom", onClick = state::zoomToSelection)
            }
            if (state.held) ChromeButton("Live", onClick = state::live)
            Spacer(Modifier.weight(1f))
            ChromeButton(
                "Stop all",
                enabled = view.control && !view.status?.running.isNullOrEmpty(),
                onClick = { perf?.stopAll() },
            )
        }
        view.message?.let {
            // Only failures come here: a red callout.
            BasicText(
                it,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.error.bg)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag(PerfTags.MESSAGE),
                style = HopiDesktopType.meta.copy(color = colors.error.fg),
            )
        }
        when {
            session == null -> PerfNote("Not connected.")
            !view.supported -> PerfNote("This app's Hopi has no perf monitors (not connected yet, no hopi-perf in this build, perf left out of Hopi's start, or an older Hopi).")
            else -> Row(Modifier.fillMaxSize()) {
                val hover = state.hoverMs?.let { sampleNear(view.samples, it) }
                val hoverFrame = state.hoverMs?.let { at -> view.frames.firstOrNull { it.second == at / 1000 } }
                val cpuSample = hover?.takeIf { it.cpu != null } ?: view.samples.lastOrNull { it.cpu != null }
                val memorySample = hover?.takeIf { it.residentBytes != null } ?: view.samples.lastOrNull { it.residentBytes != null }
                val marks = marks(view, colors.error.fg, colors.warning.fg)
                val time = TimeAxis(domain, state, marks)
                ScrollPane(Modifier.weight(1f).fillMaxHeight()) {
                    TimeLabels(domain, state.hoverMs)
                    Section("Frames per second", latest = (hoverFrame ?: view.frames.lastOrNull())?.let(::framesText) ?: idle(view, "frames"))
                    FramesChart(view.frames, time, Modifier.testTag(PerfTags.FRAMES_CHART))
                    Section("CPU", latest = cpuSample?.let(::cpuText) ?: idle(view, "cpu"))
                    LineChart(
                        listOf(view.samples.mapNotNull { s -> s.cpu?.let { s.atMs to it } }),
                        markers = emptyList(),
                        floor = 100.0,
                        color = colors.info.fg,
                        time = time,
                        modifier = Modifier.testTag(PerfTags.CPU_CHART),
                    )
                    Section("Memory", latest = memorySample?.let(::memoryText) ?: idle(view, "memory"))
                    LineChart(
                        listOf(
                            view.samples.mapNotNull { s -> s.residentBytes?.let { s.atMs to it.toDouble() } },
                            view.samples.mapNotNull { s -> s.javaHeapBytes?.let { s.atMs to it.toDouble() } },
                        ),
                        markers = view.memoryEvents.filter { it.kind == "memory" }.map { it.atMs },
                        floor = 0.0,
                        color = colors.violet.fg,
                        time = time,
                        modifier = Modifier.testTag(PerfTags.MEMORY_CHART),
                    )
                    Section("Requests", latest = transactions.count { domain.overlaps(it.startedAtEpochMs, requestEnd(it, latest ?: 0)) }.let { "$it in view" })
                    RequestsChart(transactions, latest ?: 0, time)
                    if (view.memoryEvents.isNotEmpty()) {
                        // Memory events in the amber of their ticks; a previous run's ANR in red.
                        for (event in view.memoryEvents.takeLast(5).asReversed()) {
                            PerfLine(eventText(event), color = if (event.kind == "anr") colors.error.fg else colors.warning.fg)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                ScrollPane(Modifier.width(PERF_SIDE_WIDTH).fillMaxHeight()) {
                    val selection = state.selection
                    val inspect = { hang: RemotePerfEvent ->
                        val around = PerfSpan(hang.atMs - HANG_MARGIN_MS, hang.atMs + (hang.ms?.toLong() ?: 0) + HANG_MARGIN_MS)
                        state.focus(around, latest ?: around.toMs)
                    }
                    if (selection != null) {
                        SelectionSummary(perfCut(view, transactions, selection), inspect)
                        Monitors(view) { monitor, on -> perf?.setMonitor(monitor, on) }
                    } else {
                        Monitors(view) { monitor, on -> perf?.setMonitor(monitor, on) }
                        Section("Threads", latest = "last ${RECENT_THREADS_MS / 1000} s")
                        val recent = latest?.let { RemotePerf.threadsBetween(view.threadTicks, it - RECENT_THREADS_MS, it) }.orEmpty()
                        if (recent.isEmpty()) {
                            PerfLine(if (view.threads == null) "Waiting for the next sample (CPU must be on; not available on the JVM)." else "No thread used the CPU.")
                        }
                        for (thread in recent.take(MAX_THREAD_ROWS)) PerfLine(threadText(thread))
                        Section("Hangs", latest = if (view.hangs.isEmpty()) null else "${view.hangs.size} · janky frames ${view.janks} · stalls ${view.stalls}")
                        if (view.hangs.isEmpty()) {
                            PerfLine("No hang so far.")
                        } else {
                            for (hang in view.hangs.asReversed()) HangRow(hang, inspect)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** The selected stretch alone: its figures, who used the CPU in it, its hangs and its requests. */
@Composable
private fun SelectionSummary(cut: PerfCut, inspect: (RemotePerfEvent) -> Unit) {
    val colors = hopiDesktopColors
    @Suppress("DEPRECATION") // The text-only clipboard API, as the rest of the viewer.
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().testTag(PerfTags.SELECTION)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Selection", Modifier.weight(1f), style = HopiDesktopType.section.copy(color = colors.primary))
            ChromeButton("Copy", onClick = { clipboard.setText(AnnotatedString(cut.text())) })
        }
        PerfLine(cut.span.text(), color = colors.text)
        for (line in cut.summary()) PerfLine(line, color = colors.text)
        Section("Threads", latest = null)
        if (cut.threads.isEmpty()) {
            PerfLine("No per-thread data here: this screen was closed, or CPU was off.")
        }
        for (thread in cut.threads.take(MAX_THREAD_ROWS)) PerfLine(threadText(thread))
        if (cut.hangs.isNotEmpty()) {
            Section("Hangs", latest = null)
            for (hang in cut.hangs.asReversed()) HangRow(hang, inspect)
        }
        Section("Requests", latest = cut.requests.size.toString())
        if (cut.requests.isEmpty()) PerfLine("No request here.")
        for (request in cut.requests.take(MAX_REQUEST_ROWS)) {
            BasicText(
                requestText(request),
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                style = HopiDesktopType.meta.copy(color = if (failed(request)) colors.error.fg else colors.textMuted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (cut.requests.size > MAX_REQUEST_ROWS) PerfLine("and ${cut.requests.size - MAX_REQUEST_ROWS} more (Copy has them all).")
    }
}

/** One side of the screen: a column that scrolls on its own, with its scrollbar. */
@Composable
private fun ScrollPane(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll), content = content)
        HopiScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun Monitors(view: RemotePerfView, onToggle: (String, Boolean) -> Unit) {
    val colors = hopiDesktopColors
    val status = view.status
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val monitors = PerfClient.MONITORS
        for ((index, name) in monitors.withIndex()) {
            val running = status?.monitor(name)
            val available = status == null || name in status.available
            // M3 Expressive's grouped list: tonal cards 2 dp apart, the group's outer corners large.
            val big = 20.dp
            val small = 6.dp
            val shape = RoundedCornerShape(
                topStart = if (index == 0) big else small,
                topEnd = if (index == 0) big else small,
                bottomStart = if (index == monitors.lastIndex) big else small,
                bottomEnd = if (index == monitors.lastIndex) big else small,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 1.dp)
                    .clip(shape)
                    .background(colors.containerHigh)
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
private fun HangRow(hang: RemotePerfEvent, inspect: (RemotePerfEvent) -> Unit) {
    val colors = hopiDesktopColors
    @Suppress("DEPRECATION") // The text-only clipboard API, as the rest of the viewer.
    val clipboard = LocalClipboardManager.current
    var open by remember(hang.seq) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Red from a frozen frame's length (or while it lasts), amber below; grey when it says nothing (a debugger, no duration).
            val ms = hang.ms
            val severity = when {
                hang.debugger || ms == null -> colors.neutral
                hang.ongoing || ms >= SEVERE_HANG_MS -> colors.error
                else -> colors.warning
            }
            Box(Modifier.padding(end = 8.dp).size(8.dp).background(severity.fg, CircleShape))
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
            ChromeButton("Inspect", onClick = { inspect(hang) }, variant = ButtonVariant.Ghost, modifier = Modifier.semantics { contentDescription = PerfTags.inspect(hang.seq) })
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
    }
}

@Composable
private fun Section(title: String, latest: String?) {
    val colors = hopiDesktopColors
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // M3 Expressive's section label: sentence case, in primary.
        BasicText(title, style = HopiDesktopType.section.copy(color = colors.primary))
        Spacer(Modifier.weight(1f))
        latest?.let { BasicText(it, style = HopiDesktopType.meta.copy(color = colors.text), maxLines = 1) }
    }
}

@Composable
private fun PerfLine(text: String, color: Color = hopiDesktopColors.textMuted) {
    BasicText(text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), style = HopiDesktopType.meta.copy(color = color))
}

@Composable
private fun PerfNote(text: String) {
    BasicText(text, Modifier.padding(12.dp), style = HopiDesktopType.body.copy(color = hopiDesktopColors.textMuted))
}

/** A moment or stretch marked across the charts. */
private class PerfMark(val fromMs: Long, val toMs: Long, val color: Color)

private fun marks(view: RemotePerfView, hang: Color, stall: Color): List<PerfMark> =
    view.stallEvents.map { PerfMark(it.atMs, it.atMs + (it.ms?.toLong() ?: 0), stall) } +
        view.hangs.map { PerfMark(it.atMs, it.atMs + (it.ms?.toLong() ?: 0), hang) }

/** The one time scale of every chart, with what each draws over itself. */
@Stable
private class TimeAxis(val span: PerfSpan, val state: PerfPanelState, val marks: List<PerfMark>) {
    fun x(atMs: Long, width: Float): Float = span.fraction(atMs) * width

    fun DrawScope.overlay(selection: Color, hover: Color) {
        for (mark in marks) {
            if (!span.overlaps(mark.fromMs, mark.toMs)) continue
            val left = x(mark.fromMs, size.width)
            val width = maxOf(1.5f, x(mark.toMs, size.width) - left)
            drawRect(mark.color.copy(alpha = 0.75f), Offset(left, 0f), Size(width, size.height))
        }
        state.selection?.let { cut ->
            val left = x(cut.fromMs, size.width)
            val right = x(cut.toMs, size.width)
            drawRect(selection.copy(alpha = 0.16f), Offset(left, 0f), Size(right - left, size.height))
            drawLine(selection, Offset(left, 0f), Offset(left, size.height), strokeWidth = 1f)
            drawLine(selection, Offset(right, 0f), Offset(right, size.height), strokeWidth = 1f)
        }
        state.hoverMs?.let { at ->
            val px = x(at, size.width)
            drawLine(hover, Offset(px, 0f), Offset(px, size.height), strokeWidth = 1f)
        }
    }
}

/**
 * Hovering reads a moment on every chart; dragging selects a stretch, on the timeline as it stood
 * when the button went down; a click clears the selection.
 */
@Composable
private fun Modifier.timeGestures(time: TimeAxis): Modifier {
    val current by rememberUpdatedState(time)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            var pressed: PerfSpan? = null
            var startX = 0f
            var dragging = false
            while (true) {
                val event = awaitPointerEvent()
                val x = event.changes.firstOrNull()?.position?.x ?: continue
                val fraction = x / size.width
                when (event.type) {
                    PointerEventType.Press -> {
                        pressed = current.span
                        startX = x
                        dragging = false
                    }
                    PointerEventType.Move -> {
                        val held = pressed
                        current.state.hoverMs = (held ?: current.span).at(fraction)
                        if (held != null && (dragging || abs(x - startX) >= DRAG_SLOP_PX)) {
                            dragging = true
                            val a = held.at(startX / size.width)
                            val b = held.at(fraction)
                            current.state.select(PerfSpan(minOf(a, b), maxOf(a, b)), held.toMs)
                        }
                    }
                    PointerEventType.Release -> {
                        if (pressed != null && !dragging) current.state.live()
                        pressed = null
                        dragging = false
                    }
                    PointerEventType.Exit -> current.state.hoverMs = null
                }
            }
        }
    }
}

/** The clock over the charts: the timeline's ends and quarters, or the moment under the pointer. */
@Composable
private fun TimeLabels(span: PerfSpan, hoverMs: Long?) {
    val colors = hopiDesktopColors
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        if (hoverMs != null) {
            BasicText(clock(hoverMs), style = HopiDesktopType.mono.copy(color = colors.text))
        } else {
            for (quarter in 0..4) BasicText(clock(span.at(quarter / 4f)), style = HopiDesktopType.meta.copy(color = colors.textFaint))
        }
    }
}

/**
 * Frames per second as bars in the success green, whatever the accent (a neutral accent would make
 * them grey), the janky ones of each second drawn over them in the error red.
 */
@Composable
private fun FramesChart(frames: List<RemoteFrameSecond>, time: TimeAxis, modifier: Modifier = Modifier) {
    val colors = hopiDesktopColors
    val shown = frames.filter { (it.second + 1) * 1000 >= time.span.fromMs && it.second * 1000 <= time.span.toMs }
    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT).padding(horizontal = 12.dp).clipToBounds().timeGestures(time)) {
        frame(colors.border)
        if (shown.isNotEmpty()) {
            val top = maxOf(60.0, shown.maxOf { it.frames }.toDouble())
            val slot = size.width * 1000f / time.span.ms
            for (second in shown) {
                val x = time.x(second.second * 1000, size.width)
                val h = (second.frames / top * size.height).toFloat()
                drawRect(colors.success.fg, Offset(x, size.height - h), Size(maxOf(1f, slot - 1f), h))
                if (second.janky > 0) {
                    val j = maxOf(2f, (second.janky / top * size.height).toFloat())
                    drawRect(colors.error.fg, Offset(x, size.height - j), Size(maxOf(1f, slot - 1f), j))
                }
            }
        }
        with(time) { overlay(colors.primary, colors.text) }
    }
}

/**
 * Lines over time in [color] (the first solid over a faint fill, the next dashed), 2 dp wide,
 * scaled to what is in view or [floor]; [markers] as amber vertical ticks. Each chart has a hue of
 * its own (CPU info blue, memory violet), never the accent's.
 */
@Composable
private fun LineChart(lines: List<List<Pair<Long, Double>>>, markers: List<Long>, floor: Double, color: Color, time: TimeAxis, modifier: Modifier = Modifier) {
    val colors = hopiDesktopColors
    val visible = lines.map { points ->
        // One point past each edge, so a line leaves the chart instead of stopping short of it.
        val first = points.indexOfFirst { it.first >= time.span.fromMs }
        val last = points.indexOfLast { it.first <= time.span.toMs }
        if (first < 0 || last < first) emptyList() else points.subList((first - 1).coerceAtLeast(0), (last + 2).coerceAtMost(points.size))
    }
    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT).padding(horizontal = 12.dp).clipToBounds().timeGestures(time)) {
        frame(colors.border)
        val all = visible.flatten()
        if (all.isNotEmpty()) {
            val top = maxOf(floor, all.maxOf { it.second }) * 1.05
            fun x(t: Long) = time.x(t, size.width)
            fun y(v: Double) = (size.height - v / top * size.height).toFloat()
            for (t in markers) {
                if (t !in time.span) continue
                drawLine(colors.warning.fg, Offset(x(t), 0f), Offset(x(t), size.height), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)))
            }
            visible.forEachIndexed { index, points ->
                if (points.isEmpty()) return@forEachIndexed
                val path = Path()
                points.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
                if (index == 0 && points.size > 1) {
                    val area = Path().apply {
                        addPath(path)
                        lineTo(x(points.last().first), size.height)
                        lineTo(x(points.first().first), size.height)
                        close()
                    }
                    drawPath(area, color.copy(alpha = 0.14f))
                }
                drawPath(
                    path,
                    color,
                    style = Stroke(width = 2.dp.toPx(), pathEffect = if (index == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
                )
            }
        }
        with(time) { overlay(colors.primary, colors.text) }
    }
}

/** Each request as a bar from its start to its end, overlapping ones on separate rows; failures in the error red. */
@Composable
private fun RequestsChart(transactions: List<HttpTransaction>, latestMs: Long, time: TimeAxis) {
    val colors = hopiDesktopColors
    val shown = transactions.filter { time.span.overlaps(it.startedAtEpochMs, requestEnd(it, latestMs)) }.sortedBy { it.startedAtEpochMs }
    Canvas(Modifier.fillMaxWidth().height(REQUESTS_HEIGHT).padding(horizontal = 12.dp).clipToBounds().timeGestures(time).testTag(PerfTags.REQUESTS_CHART)) {
        frame(colors.border)
        val rows = LongArray(REQUEST_ROWS)
        val height = size.height / REQUEST_ROWS
        for (request in shown) {
            val end = requestEnd(request, latestMs)
            val row = rows.indexOfFirst { it <= request.startedAtEpochMs }.takeIf { it >= 0 } ?: rows.indices.minBy { rows[it] }
            rows[row] = end
            val left = time.x(request.startedAtEpochMs, size.width)
            val width = maxOf(2f, time.x(end, size.width) - left)
            val fill = if (failed(request)) colors.error.fg else colors.info.fg
            drawRect(fill.copy(alpha = 0.8f), Offset(left, row * height + 1f), Size(width, maxOf(1f, height - 2f)))
        }
        with(time) { overlay(colors.primary, colors.text) }
    }
}

private fun requestEnd(request: HttpTransaction, latestMs: Long): Long = request.durationMs?.let { request.startedAtEpochMs + it } ?: maxOf(latestMs, request.startedAtEpochMs)

private fun failed(request: HttpTransaction): Boolean = request.error != null || (request.responseCode ?: 0) >= 400

private fun DrawScope.frame(color: Color) {
    drawLine(color, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
}

internal fun framesText(second: RemoteFrameSecond): String = "${second.frames} fps · ${second.janky} janky · worst ${second.worstMs.roundToLong()} ms"

internal fun cpuText(sample: RemotePerfSample): String = percent(sample) + (sample.hopiCpu?.let { " (Hopi ${percent(it)})" } ?: "")

internal fun memoryText(sample: RemotePerfSample): String = listOfNotNull(
    sample.residentBytes?.let { "resident ${formatBytes(it)}" },
    sample.javaHeapBytes?.let { "java heap ${formatBytes(it)} (dashed)" },
).joinToString(" · ")

private fun eventText(event: RemotePerfEvent): String = when (event.kind) {
    "anr" -> "${clock(event.atMs)}  previous run's ANR: ${event.description ?: "no description"}"
    else -> "${clock(event.atMs)}  memory ${event.event}${event.level?.let { " level $it" } ?: ""}${event.detail?.let { " $it" } ?: ""}"
}

private val CHART_HEIGHT = 120.dp
private val REQUESTS_HEIGHT = 44.dp

/** Rows of the requests chart: enough to see a burst, and it stays one strip. */
private const val REQUEST_ROWS = 5

private val RANGE_LABELS = listOf("1 min", "5 min", "20 min")

/** Pixels the pointer moves before a press is a drag. */
private const val DRAG_SLOP_PX = 4f

/** What Inspect shows either side of a hang: what led to it and what came after. */
private const val HANG_MARGIN_MS = 5_000L

/** With no selection, the threads list covers this much: long enough to read, short enough to be now. */
private const val RECENT_THREADS_MS = 10_000L
private const val MAX_THREAD_ROWS = 10
private const val MAX_REQUEST_ROWS = 8

/** The monitors, threads and hangs beside the charts. */
private val PERF_SIDE_WIDTH = 380.dp

/** Android vitals' frozen frame: a hang this long or longer reads red. */
private const val SEVERE_HANG_MS = 700.0
