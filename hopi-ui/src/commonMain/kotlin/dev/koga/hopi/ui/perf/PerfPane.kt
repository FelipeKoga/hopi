package dev.koga.hopi.ui.perf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.koga.hopi.core.body.formatBytes
import dev.koga.hopi.core.perf.FrameSecond
import dev.koga.hopi.core.perf.HangEvent
import dev.koga.hopi.core.perf.MemoryEvent
import dev.koga.hopi.core.perf.PerfController
import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfResult
import dev.koga.hopi.core.perf.PerfSample
import dev.koga.hopi.core.perf.PerfStartRequest
import dev.koga.hopi.core.perf.StallEvent
import dev.koga.hopi.core.perf.perfWallClockMs
import dev.koga.hopi.ui.component.ActionButton
import dev.koga.hopi.ui.component.HairlineDivider
import dev.koga.hopi.ui.component.IconButton
import dev.koga.hopi.ui.component.SectionTitle
import dev.koga.hopi.ui.component.Switch
import dev.koga.hopi.ui.platform.formatTimeOfDay
import dev.koga.hopi.ui.theme.HopiIcons
import dev.koga.hopi.ui.theme.HopiType
import dev.koga.hopi.ui.theme.LocalHopiColors
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object PerfTags {
    const val BACK = "Back to requests"
    const val HUD = "Show the HUD"
    const val CADENCE = "Display-link cadence"
    const val THREADS = "Per-thread CPU"
    const val PSS = "Measure PSS"

    fun monitor(name: String) = "Monitor $name"

    fun hang(seq: Long) = "Hang #$seq"
}

/** One monitor's row: its switch, and what it runs with or why it cannot. */
internal class MonitorRow(val monitor: PerfMonitor, val available: Boolean, val running: Boolean, val detail: String)

/** One sparkline: the last points of a series and its latest value, as text. */
internal class Spark(val title: String, val latest: String, val values: FloatArray)

/**
 * One hang's row. Its stacks (up to 16 KB of text) are formatted only when the row is opened, and
 * a row whose event did not change is the previous model's own instance, so a rebuild costs what
 * changed, not the retained history.
 */
internal class HangRow(val seq: Long, val title: String, val subtitle: String, internal val event: HangEvent) {
    private val formatted = lazy { stacksText(event) }
    val stacks: String by formatted

    /** Whether [stacks] was formatted (tests). */
    internal val stacksFormatted: Boolean get() = formatted.isInitialized()
}

/** The Perf tab's content, built off the main thread from the controller and its model. */
internal class PerfPaneModel(
    val platform: String,
    val remoteControl: Boolean,
    val monitors: List<MonitorRow>,
    val notices: List<String>,
    val summary: List<Pair<String, String>>,
    val sparks: List<Spark>,
    val hangs: List<HangRow>,
    val memoryEvents: List<String>,
    /** Null while the thread list is off; empty for a platform without one. */
    val threads: List<String>?,
)

/**
 * Builds the tab's model; [threads] reads the per-thread list (the scan runs while the tab asks).
 * The hang rows of [previous] whose event is unchanged are reused as they are.
 */
internal fun perfPaneModel(
    controller: PerfController,
    threads: Boolean,
    nowMs: Long = perfWallClockMs(),
    previous: PerfPaneModel? = null,
): PerfPaneModel {
    val data = controller.data
    // A reader closes a second strictly in the past, never the current one.
    data.tick(nowMs / 1000)
    val status = controller.status()
    val summary = data.summary()
    val ios = controller.platform == "ios"
    val monitors = PerfMonitor.entries.map { monitor ->
        val running = status.running.firstOrNull { it.monitor == monitor }
        val detail = when {
            monitor !in controller.available -> "not available on ${controller.platform}"
            running == null -> "off"
            else -> buildList {
                add("on")
                running.details.firstOrNull { it.first == "thresholdMs" }?.second?.let { add("$it ms") }
                running.details.firstOrNull { it.first == "intervalMs" }?.second?.let { add("every $it ms") }
                if (running.details.any { it.first == "attached" && it.second == false }) add("not attached yet")
                add(running.owners.joinToString(", ") { ownerText(it) })
            }.joinToString(" · ")
        }
        MonitorRow(monitor, monitor in controller.available, running != null, detail)
    }
    val rows = buildList {
        // No frames row on a platform without the monitor.
        if (PerfMonitor.FRAMES in controller.available) {
            if (ios && summary.frames == 0L) {
                add("Frames" to "stalls: ${data.stalls.count} (no frame counts without the cadence)")
            } else {
                add(
                    "Frames" to buildString {
                        append(summary.frames).append(" frames · ").append(summary.janky).append(" janky · ").append(summary.frozen).append(" frozen")
                        summary.p90Ms?.let { append(" · p90 ").append(ms(it)) }
                        summary.maxMs?.let { append(" · max ").append(ms(it)) }
                        if (summary.excluded > 0) append(" · ").append(summary.excluded).append(" left out (inspector open, Hopi pauses)")
                    },
                )
            }
        }
        add("Hangs" to "${summary.hangs} · total ${summary.hangTotalMs} ms · worst ${summary.hangWorstMs} ms" + if (summary.debuggerHangs > 0) " · ${summary.debuggerHangs} under a debugger" else "")
        summary.cpuAveragePercent?.let { add("CPU" to "avg ${pct(it)} · max ${pct(summary.cpuMaxPercent ?: it)}" + (summary.cpuLast?.let { c -> " · Hopi ${pct(c.hopiPercent)}" } ?: "")) }
        summary.memoryLast?.residentBytes?.let { last ->
            add("Memory" to "resident ${formatBytes(last)}" + (summary.residentMaxBytes?.let { " · max ${formatBytes(it)}" } ?: "") +
                (summary.memoryLast?.javaHeapBytes?.let { " · java heap ${formatBytes(it)}" } ?: ""))
        }
    }
    val sparks = buildList {
        val frames = data.frames.last(SPARK_POINTS)
        if (frames.size > 0) add(Spark("Frames per second", "${frames.value(frames.size - 1, 0).toInt()} fps", FloatArray(frames.size) { frames.value(it, 0) }))
        val cpu = data.cpu.last(SPARK_POINTS)
        if (cpu.size > 0) add(Spark("CPU", pct(cpu.value(cpu.size - 1, 0).toDouble()), FloatArray(cpu.size) { cpu.value(it, 0) }))
        val memory = data.memory.last(SPARK_POINTS)
        val resident = PerfData.MEMORY_COLUMNS.indexOf("residentKb")
        if (memory.size > 0) {
            val last = memory.value(memory.size - 1, resident)
            add(Spark("Memory", if (last.isNaN()) "-" else formatBytes(last.toLong() * 1024), FloatArray(memory.size) { memory.value(it, resident) }))
        }
    }
    val previousRows = previous?.hangs?.associateBy { it.seq }.orEmpty()
    val hangs = data.hangs.since(0).asReversed().map { hang ->
        previousRows[hang.seq]?.takeIf { it.event === hang } ?: HangRow(
            seq = hang.seq,
            title = "${hang.durationMs} ms${if (hang.ended) "" else " · ongoing"} · ${formatTimeOfDay(hang.atMs, withMillis = false)}",
            subtitle = listOfNotNull(
                hang.screen,
                hang.stacks.firstOrNull()?.firstOrNull(),
                if (hang.debugger) "debugger attached: no stack, not counted" else null,
                if (hang.stackMissed) "no stack survived the capture" else null,
            ).joinToString(" · "),
            event = hang,
        )
    }
    val memoryEvents = data.memoryEvents.since(0).takeLast(MEMORY_EVENTS).asReversed().map(::memoryText) +
        listOfNotNull(data.previousAnrEvent?.let { "previous run's ANR: ${it.description ?: "no description"}" })
    return PerfPaneModel(
        platform = controller.platform,
        remoteControl = controller.remoteControl,
        monitors = monitors,
        notices = status.notices,
        summary = rows,
        sparks = sparks,
        hangs = hangs,
        memoryEvents = memoryEvents,
        threads = if (!threads) null else controller.threads(THREADS).orEmpty().map { t -> "${t.percent?.let(::pct) ?: "-"}  ${t.name}${if (t.hopi) " (Hopi)" else ""}" },
    )
}

private fun monitorTitle(monitor: PerfMonitor): String = when (monitor) {
    PerfMonitor.FRAMES -> "Frames"
    PerfMonitor.HANGS -> "Hangs"
    PerfMonitor.CPU -> "CPU"
    PerfMonitor.MEMORY -> "Memory"
}

private fun ownerText(owner: String): String = when {
    owner == "code" -> "started in the app"
    owner == "lease" -> "remote lease"
    owner == "lease:no-expiry" -> "remote, no expiry"
    owner.startsWith("tail:") -> "a remote stream"
    else -> owner
}

private fun stacksText(hang: HangEvent): String = hang.stacks.mapIndexed { i, stack ->
    (if (hang.stacks.size > 1) "stack ${i + 1} of ${hang.stacks.size}\n" else "") + stack.joinToString("\n") { "  at $it" }
}.joinToString("\n\n")

private fun memoryText(event: MemoryEvent): String =
    "${formatTimeOfDay(event.atMs, withMillis = false)} · ${event.event}" + (event.level?.let { " level $it" } ?: "") + (event.detail?.let { " $it" } ?: "")

private fun pct(value: Double): String = "${(value * 10).roundToLong() / 10.0}%"

private fun ms(value: Double): String = "${value.roundToLong()} ms"

/**
 * The tab's model for [controller], rebuilt on [formatDispatcher] while the tab is composed: at
 * once, after each of its own actions ([revision]), and when the model hands its listeners a closed
 * second, a sample, a hang, a stall or a memory event (never a frame or a jank event; a rebuild
 * still running absorbs the ones that arrive meanwhile). No timer: an idle app rebuilds nothing.
 */
@Composable
internal fun rememberPerfPaneModel(
    controller: PerfController,
    threads: Boolean,
    revision: () -> Int,
    formatDispatcher: CoroutineDispatcher,
): State<PerfPaneModel?> = produceState<PerfPaneModel?>(null, controller, threads, formatDispatcher) {
    val changed = Channel<Unit>(Channel.CONFLATED)
    val remove = controller.data.addListener { item ->
        if (item is FrameSecond || item is PerfSample || item is HangEvent || item is StallEvent || item is MemoryEvent) changed.trySend(Unit)
    }
    try {
        launch { snapshotFlow { revision() }.collect { changed.trySend(Unit) } }
        changed.receiveAsFlow().collectLatest {
            val previous = value
            value = withContext(formatDispatcher) { perfPaneModel(controller, threads, previous = previous) }
        }
    } finally {
        remove()
    }
}

/**
 * The in-app Perf tab (docs/design/perf-monitors.md, "In-app"): a switch per monitor (started in
 * code, as `HopiPerf.start` would), the HUD's switch (and, on iOS, the cadence's), summaries,
 * sparklines, the per-thread list (the scan runs while it is shown), "Measure PSS", memory events
 * and the hangs with their stacks.
 */
@Composable
internal fun PerfPane(
    controller: PerfController,
    formatDispatcher: CoroutineDispatcher,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val colors = LocalHopiColors.current
    val scope = rememberCoroutineScope()
    var threads by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var openHang by rememberSaveable { mutableStateOf<Long?>(null) }
    val model by rememberPerfPaneModel(controller, threads, { revision }, formatDispatcher)
    if (threads) {
        DisposableEffect(controller) {
            controller.watchThreads(true)
            onDispose { controller.watchThreads(false) }
        }
    }

    fun toggle(monitor: PerfMonitor, on: Boolean) {
        scope.launch {
            val refused = withContext(formatDispatcher) {
                if (on) {
                    (controller.start(PerfStartRequest(setOf(monitor)), PerfOwner.Code) as? PerfResult.Failed)?.message
                } else {
                    controller.stop(setOf(monitor))
                    null
                }
            }
            refused?.let(onMessage)
            revision++
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(HopiIcons.Back, PerfTags.BACK, onBack)
            Spacer(Modifier.width(4.dp))
            BasicText("Perf", style = HopiType.title.copy(color = colors.text))
            model?.let {
                Spacer(Modifier.width(8.dp))
                BasicText(it.platform, style = HopiType.meta.copy(color = colors.textFaint))
            }
        }
        HairlineDivider()
        val shown = model ?: return@Column
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown.monitors, key = { it.monitor.wireName }, contentType = { "monitor" }) { row ->
                SwitchRow(monitorTitle(row.monitor), row.detail, row.running, PerfTags.monitor(row.monitor.wireName), enabled = row.available) {
                    toggle(row.monitor, it)
                }
            }
            item(key = "hud", contentType = "switch") {
                SwitchRow("HUD", "One line over the app; a long press on the Hopi button toggles it too", PerfHudState.shown, PerfTags.HUD) { PerfHudState.shown = it }
            }
            if (shown.platform == "ios") {
                item(key = "cadence", contentType = "switch") {
                    SwitchRow(
                        "Cadence in the HUD",
                        "Display-link callbacks per second: 60 or 120 main-thread wakes per second while the HUD shows, idle included",
                        PerfHudState.cadence,
                        PerfTags.CADENCE,
                    ) { PerfHudState.cadence = it }
                }
            }
            item(key = "notes", contentType = "text") {
                val notes = buildList {
                    addAll(shown.notices)
                    add("Frames drawn while this screen is open are left out. The HUD redraws at most once a second, and not at all while the app is idle; the desktop viewer and the CLI measure without either.")
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    for (note in notes) BasicText(note, style = HopiType.meta.copy(color = colors.textMuted))
                }
            }
            if (shown.summary.isNotEmpty()) {
                item(key = "summary-title", contentType = "title") { SectionTitle("Since start") }
                items(shown.summary, key = { "summary-" + it.first }, contentType = { "summary" }) { (label, text) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
                        BasicText(label, Modifier.width(72.dp), style = HopiType.label.copy(color = colors.textMuted))
                        BasicText(text, style = HopiType.meta.copy(color = colors.text))
                    }
                }
            }
            items(shown.sparks, key = { "spark-" + it.title }, contentType = { "spark" }) { spark -> SparkRow(spark) }
            item(key = "threads", contentType = "switch") {
                SwitchRow("Threads", "The top threads by CPU; the scan runs only while this list is shown", threads, PerfTags.THREADS) { threads = it }
            }
            shown.threads?.let { list ->
                item(key = "threads-list", contentType = "text") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        if (list.isEmpty()) BasicText("No per-thread list on this platform, or no sample yet.", style = HopiType.meta.copy(color = colors.textMuted))
                        for (line in list) BasicText(line, style = HopiType.mono.copy(color = colors.text))
                    }
                }
            }
            item(key = "pss", contentType = "action") {
                Row(Modifier.padding(horizontal = 13.dp, vertical = 4.dp)) {
                    ActionButton(HopiIcons.Perf, PerfTags.PSS, onClick = {
                        scope.launch {
                            val pss = withContext(formatDispatcher) { controller.measurePss() }
                            onMessage(pss.kb?.let { "PSS ${formatBytes(it * 1024)} (${pss.ms} ms)" } ?: "PSS refused: ${pss.refused}")
                        }
                    })
                }
            }
            if (shown.memoryEvents.isNotEmpty()) {
                item(key = "memory-title", contentType = "title") { SectionTitle("Memory events") }
                items(shown.memoryEvents, contentType = { "text" }) { line ->
                    BasicText(line, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = HopiType.meta.copy(color = colors.text))
                }
            }
            item(key = "hangs-title", contentType = "title") { SectionTitle("Hangs", trailing = if (shown.hangs.isEmpty()) "none so far" else shown.hangs.size.toString()) }
            items(shown.hangs, key = { "hang-" + it.seq }, contentType = { "hang" }) { hang ->
                Column(Modifier.fillMaxWidth().clickable { openHang = if (openHang == hang.seq) null else hang.seq }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    BasicText("#${hang.seq}  ${hang.title}", style = HopiType.monoStrong.copy(color = colors.text))
                    if (hang.subtitle.isNotEmpty()) {
                        BasicText(hang.subtitle, style = HopiType.meta.copy(color = colors.textMuted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (openHang == hang.seq) {
                        Spacer(Modifier.height(4.dp))
                        BasicText(hang.stacks.ifEmpty { "no stack" }, style = HopiType.mono.copy(color = colors.textMuted))
                    }
                }
                HairlineDivider(Modifier.padding(start = 16.dp))
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, description: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = LocalHopiColors.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = HopiType.bodyStrong.copy(color = if (enabled) colors.text else colors.textFaint))
            BasicText(detail, style = HopiType.meta.copy(color = colors.textMuted), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (enabled) Switch(checked, description, onChange)
    }
}

@Composable
private fun SparkRow(spark: Spark) {
    val colors = LocalHopiColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(spark.title.uppercase(), Modifier.weight(1f), style = HopiType.section.copy(color = colors.textFaint))
            BasicText(spark.latest, style = HopiType.meta.copy(color = colors.text))
        }
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(28.dp).background(colors.surface)) {
            val values = spark.values.filter { !it.isNaN() }
            if (values.size < 2) return@Canvas
            val top = maxOf(1f, values.max()) * 1.1f
            val step = size.width / (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val point = Offset(i * step, size.height - v / top * size.height)
                if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            drawPath(path, colors.text, style = Stroke(width = 1.5f))
        }
    }
}

private const val SPARK_POINTS = 60
private const val MEMORY_EVENTS = 20
private const val THREADS = 10
