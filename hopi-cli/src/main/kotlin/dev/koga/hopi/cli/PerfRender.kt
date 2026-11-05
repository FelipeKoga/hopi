package dev.koga.hopi.cli

import dev.koga.hopi.connect.RemoteFrameSecond
import dev.koga.hopi.connect.RemotePerfEvent
import dev.koga.hopi.connect.RemotePerfMonitor
import dev.koga.hopi.connect.RemotePerfSample
import dev.koga.hopi.connect.RemotePerfStatus
import dev.koga.hopi.connect.RemotePerfSummary
import dev.koga.hopi.connect.RemotePerfThread
import dev.koga.hopi.connect.visible
import dev.koga.hopi.core.body.formatBytes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/** Human output of the perf commands. `--json` prints the bridge's lines as received instead. */
internal object PerfRender {
    /** When an Android frames monitor started with no resumed activity attaches (docs/design/perf-monitors.md, "Frames"). */
    const val FRAMES_HINT: String = "frames attach when an activity next starts"

    /** Which setting turns remote control off, on either platform. */
    private const val REMOTE_CONTROL_OFF: String = "perf remoteControl, or perfRemoteControl on iOS, is false"

    /** `perf status`, `start`, `stop`: one line per monitor, then the app's notices. [summary] adds the counts. */
    fun status(status: RemotePerfStatus, summary: RemotePerfSummary?, nowMs: Long): List<String> {
        val lines = ArrayList<String>()
        lines += "${visible(status.platform)} · remote control ${if (status.remoteControl) "on" else "off ($REMOTE_CONTROL_OFF)"}"
        val rows = listOf("frames", "hangs", "cpu", "memory").map { name ->
            val monitor = status.monitor(name)
            when {
                monitor != null -> listOf(name, "on", setting(monitor), owners(monitor, nowMs), counts(name, summary, status.platform))
                name !in status.available -> listOf(name, "unavailable", "", "", "")
                else -> listOf(name, "off", "", "", "")
            }
        }
        lines += columns(rows)
        if (status.monitor("frames")?.unattached == true) lines += "note: $FRAMES_HINT"
        status.notices.forEach { lines += "notice: ${visible(it)}" }
        return lines
    }

    private fun setting(monitor: RemotePerfMonitor): String = when (monitor.name) {
        "hangs" -> monitor.thresholdMs?.let { "$it ms" }.orEmpty()
        "cpu", "memory" -> monitor.intervalMs?.let { "every ${duration(it)}" }.orEmpty()
        "frames" -> monitor.details.let { d ->
            listOfNotNull(
                (d["tier"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { visible(it) },
                if (monitor.unattached) "not attached" else null,
            ).joinToString(", ")
        }
        else -> ""
    }

    private fun owners(monitor: RemotePerfMonitor, nowMs: Long): String = monitor.owners.joinToString(", ") { owner ->
        if (owner == "lease") {
            monitor.expiresAtMs?.let { "lease, expires in ${duration((it - nowMs).coerceAtLeast(0))}" } ?: "lease"
        } else {
            visible(owner)
        }
    }

    private fun counts(monitor: String, summary: RemotePerfSummary?, platform: String): String {
        summary ?: return ""
        return when (monitor) {
            "hangs" -> plural(summary.hangs, "hang") + (if (summary.hangs > 0) ", worst ${summary.hangWorstMs} ms" else "") +
                (if (summary.debuggerHangs > 0) " (+${summary.debuggerHangs} under a debugger)" else "")
            "frames" -> if (summary.frames == 0L && platform == "ios") "stalls only (no frame counts without the cadence)" else framesCount(summary)
            "cpu" -> summary.cpuAveragePercent?.let { "avg ${pct(it)}, max ${pct(summary.cpuMaxPercent ?: it)}" }.orEmpty()
            "memory" -> summary.residentLastBytes?.let { "resident ${formatBytes(it)}" }.orEmpty()
            else -> ""
        }
    }

    private fun framesCount(summary: RemotePerfSummary): String =
        "${plural(summary.frames, "frame")}, ${summary.janky} janky" + (if (summary.frames > 0) " (${pct(100.0 * summary.janky / summary.frames)})" else "")

    /** `perf snapshot`: the summaries, the newest events, then the top threads and PSS. */
    fun snapshot(
        status: RemotePerfStatus?,
        summary: RemotePerfSummary?,
        events: List<RemotePerfEvent>,
        threads: List<RemotePerfThread>?,
        threadsAsked: Boolean,
        pss: Pair<Long?, String?>?,
        lastSample: RemotePerfSample?,
    ): List<String> {
        val lines = ArrayList<String>()
        status?.let { lines += "${visible(it.platform)} · running: ${it.running.joinToString(", ") { m -> m.name }.ifEmpty { "nothing" }}" }
        summary?.let { s ->
            val ios = status?.platform == "ios"
            val frames = if (s.frames == 0L && ios) {
                "no frame counts on iOS without the cadence; ${plural(events.count { it.kind == "stall" }.toLong(), "stall")} listed below"
            } else {
                framesCount(s) + ", ${s.frozen} frozen" +
                    listOfNotNull(s.p50Ms?.let { "p50 ${ms(it)}" }, s.p90Ms?.let { "p90 ${ms(it)}" }, s.p99Ms?.let { "p99 ${ms(it)}" }, s.maxMs?.let { "max ${ms(it)}" })
                        .joinToString("  ").let { if (it.isEmpty()) "" else "   $it" }
            }
            val rows = mutableListOf(
                listOf("frames", frames),
                listOf("hangs", plural(s.hangs, "hang") + if (s.hangs > 0) ", total ${s.hangTotalMs} ms, worst ${s.hangWorstMs} ms" else ""),
                listOf(
                    "cpu",
                    s.cpuAveragePercent?.let { avg ->
                        "avg ${pct(avg)}, max ${pct(s.cpuMaxPercent ?: avg)}" +
                            (lastSample?.cpu?.let { now -> "   now ${pct(now)}" + (lastSample.hopiCpu?.let { h -> " (Hopi ${pct(h)})" } ?: "") } ?: "")
                    } ?: "no samples",
                ),
                listOf(
                    "memory",
                    s.residentLastBytes?.let { last ->
                        "resident ${formatBytes(last)}" + (if (s.residentMinBytes != null && s.residentMaxBytes != null) " (min ${formatBytes(s.residentMinBytes!!)}, max ${formatBytes(s.residentMaxBytes!!)})" else "") +
                            (lastSample?.javaHeapBytes?.let { "   java heap ${formatBytes(it)}" } ?: "") +
                            (lastSample?.nativeHeapBytes?.let { "   native heap ${formatBytes(it)}" } ?: "")
                    } ?: "no samples",
                ),
            )
            lines += columns(rows)
        }
        val shown = events.takeLast(MAX_SNAPSHOT_EVENTS)
        if (shown.isNotEmpty()) {
            lines += ""
            if (events.size > shown.size) lines += "events (newest ${shown.size} of ${events.size}):" else lines += "events:"
            shown.forEach { lines += "  " + event(it) }
        }
        if (threadsAsked) {
            lines += ""
            if (threads == null) {
                lines += "threads: no per-thread list on this platform"
            } else {
                lines += columns(listOf(listOf("TID", "CPU%", "CPU ms", "NAME")) + threads.map { t ->
                    listOf(t.tid.toString(), t.percent?.let(::pct) ?: "-", t.cpuMs.toString(), visible(t.name) + if (t.hopi) "  (Hopi)" else "")
                })
            }
        }
        pss?.let { (kb, refused) -> lines += if (kb != null) "pss ${formatBytes(kb * 1024)}" else "pss refused: ${visible(refused.orEmpty())}" }
        return lines
    }

    /** One event on one line (`tail`, `snapshot`): the hang's top frame only; `hangs` prints the stacks. */
    fun event(event: RemotePerfEvent): String = buildString {
        append('#').append(event.seq).append("  ").append(time(event.atMs)).append("  ")
        when (event.kind) {
            "hang" -> {
                append("hang ").append(if (event.ongoing) "ongoing " else "").append(event.ms?.let(::ms) ?: "?")
                if (event.debugger) append("  (debugger attached: no stack, not counted)")
                event.frozenMs?.takeIf { it > 0 }?.let { append("  (process frozen ").append(it).append(" ms, not counted)") }
                event.screen?.let { append("  ").append(visible(it)) }
                event.stacks.firstOrNull()?.firstOrNull()?.let { append("  at ").append(visible(it)) }
            }
            "jank" -> {
                append("jank ").append(event.ms?.let(::ms) ?: "?")
                event.screen?.let { append("  ").append(visible(it)) }
            }
            "stall" -> {
                append("stall ").append(event.ms?.let(::ms) ?: "?")
                event.screen?.let { append("  ").append(visible(it)) }
            }
            "memory" -> {
                append("memory ").append(visible(event.event ?: "?"))
                event.level?.let { append(" level ").append(it) }
                event.detail?.let { append(' ').append(visible(it)) }
            }
            "anr" -> append("previous run's ANR  ").append(visible(event.description ?: "no description"))
            else -> append(visible(event.kind))
        }
    }

    /** `hangs`: each hang's duration, screen and every stack it holds. */
    fun hang(event: RemotePerfEvent): List<String> {
        val lines = ArrayList<String>()
        lines += event(event)
        if (event.stackMissed) lines += "  (no stack: the main thread moved on during the capture)"
        event.stacks.forEachIndexed { i, stack ->
            if (event.stacks.size > 1) lines += "  stack ${i + 1} of ${event.stacks.size}${STACK_TIMES.getOrNull(i)?.let { " ($it the threshold)" }.orEmpty()}"
            stack.forEach { lines += "    at ${visible(it)}" }
        }
        return lines
    }

    fun frames(second: RemoteFrameSecond): String =
        "${time(second.second * 1000)}  frames ${second.frames}/s  ${second.janky} janky  ${second.slow} slow  worst ${ms(second.worstMs)}"

    fun sample(sample: RemotePerfSample): String = buildString {
        append(time(sample.atMs))
        sample.cpu?.let { append("  cpu ").append(pct(it)) }
        sample.hopiCpu?.let { append(" (Hopi ").append(pct(it)).append(')') }
        sample.residentBytes?.let { append("  resident ").append(formatBytes(it)) }
        sample.javaHeapBytes?.let { append("  java heap ").append(formatBytes(it)) }
        sample.nativeHeapBytes?.let { append("  native heap ").append(formatBytes(it)) }
        sample.availableBytes?.let { append("  available ").append(formatBytes(it)) }
    }

    fun threads(threads: List<RemotePerfThread>): String =
        "threads  " + threads.take(5).joinToString("  ") { "${visible(it.name)} ${it.percent?.let(::pct) ?: "-"}" }

    private fun columns(rows: List<List<String>>): List<String> {
        if (rows.isEmpty()) return emptyList()
        val widths = IntArray(rows.maxOf { it.size }) { c -> rows.maxOf { it.getOrNull(c)?.length ?: 0 } }
        return rows.map { cells ->
            buildString {
                cells.forEachIndexed { c, cell ->
                    append(cell)
                    if (c < cells.lastIndex) repeat(widths[c] - cell.length + 2) { append(' ') }
                }
            }.trimEnd()
        }
    }

    private fun plural(n: Long, word: String) = if (n == 1L) "1 $word" else "$n ${word}s"

    private fun pct(value: Double): String = "${(value * 10).roundToLong() / 10.0}%"

    private fun ms(value: Double): String = if (value >= 100 || value == value.roundToLong().toDouble()) "${value.roundToLong()} ms" else "${(value * 10).roundToLong() / 10.0} ms"

    /** `9m12s`, `45s`, `1h0m`. */
    fun duration(ms: Long): String {
        val seconds = (ms + 999) / 1000
        return when {
            ms < 1000 -> "$ms ms"
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m${(seconds % 60).toString().padStart(2, '0')}s"
            else -> "${seconds / 3600}h${(seconds % 3600 / 60).toString().padStart(2, '0')}m"
        }
    }

    private fun time(epochMs: Long): String = TIME.format(Instant.ofEpochMilli(epochMs))

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    /** The captures of one hang are taken at 1x, 2x and 4x its threshold. */
    private val STACK_TIMES = listOf("1x", "2x", "4x")

    private const val MAX_SNAPSHOT_EVENTS = 20
}
