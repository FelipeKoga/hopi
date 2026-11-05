package dev.koga.hopi.core.perf

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.json.TransactionJson
import kotlin.math.roundToLong

/**
 * The perf model's JSON: realtime log lines (`{"hopi":1,"perf":"<kind>",…}`, never an `"id"`, so
 * an agent reading transactions skips them) and bridge lines (`{"type":"perf.…",…}`).
 * (docs/design/perf-monitors.md, "Surfaces".)
 */
@HopiInternalApi
public object PerfJson {
    // ---- log lines

    /** The log line for [item], at most [maxBytes] UTF-8 bytes; null for an item that has none (a hang's middle capture). */
    public fun logLine(item: PerfItem, maxBytes: Int): String? = when (item) {
        is HangEvent -> hangLogLine(item, maxBytes)
        is FrameSecond -> if (item.janky > 0) jankLogLine(item) else null
        is MemoryEvent -> logHead("memory").field("seq", item.seq).field("event", item.event)
            .also { w -> item.level?.let { w.field("level", it) } }
            .also { w -> item.detail?.let { w.field("detail", it) } }
            .endObject().toString()
        is PreviousAnrEvent -> logHead("anr").field("seq", item.seq).field("at", item.atMs)
            .also { w -> item.description?.let { w.field("description", it.take(200)) } }
            .endObject().toString()
        is StallEvent -> logHead("stall").field("seq", item.seq).field("ms", item.durationMs)
            .also { w -> item.screen?.let { w.field("screen", it) } }
            .endObject().toString()
        is JankEvent, is PerfSample -> null
    }

    private fun logHead(kind: String): JsonWriter = JsonWriter().beginObject().field("hopi", TransactionJson.SUMMARY_VERSION).field("perf", kind)

    /**
     * One line at detection and one at the end; the 2x and 4x captures are on the bridge only.
     * [named]: the line with the stack again, its names found once the unit ended (iOS).
     */
    internal fun hangLogLine(event: HangEvent, maxBytes: Int, named: Boolean = false): String? {
        if (!named && !event.ended && event.captureUs.size > 1) return null
        val writer = logHead("hang").field("seq", event.seq)
            .field("state", if (event.ended) "ended" else "ongoing")
            .field("ms", event.durationMs)
        if (event.debugger) writer.field("debugger", true)
        if (event.frozenMs > 0) writer.field("frozenMs", event.frozenMs)
        if (event.ended) return writer.endObject().toString()
        event.screen?.let { writer.field("screen", it) }
        if (event.stackMissed) writer.field("stackMissed", true)
        val stack = event.stacks.firstOrNull() ?: return writer.endObject().toString()
        // The top frames that fit the budget, cut at a frame boundary; the full stacks are on the bridge.
        val head = writer.out.toString()
        val out = StringBuilder(head).append(",\"stack\":[")
        var first = true
        for (frame in stack) {
            val candidate = JsonWriter().value(frame).toString()
            val added = (if (first) 0 else 1) + TransactionJson.utf8Length(candidate)
            if (TransactionJson.utf8Length(out) + added + 2 > maxBytes) break
            if (!first) out.append(',')
            out.append(candidate)
            first = false
        }
        out.append("]}")
        return if (TransactionJson.utf8Length(out) <= maxBytes) out.toString() else writer.endObject().toString()
    }

    private fun jankLogLine(second: FrameSecond): String = logHead("jank")
        .field("sec", second.second)
        .field("frames", second.frames)
        .field("slow", second.slow)
        .field("janky", second.janky)
        .field("frozen", second.frozen)
        .name("worstMs").value(round1(second.worstMs))
        .also { w -> if (second.metricsLost > 0) w.field("metricsLost", second.metricsLost) }
        .also { w -> second.screen?.let { w.field("screen", it) } }
        .endObject().toString()

    /** `{"hopi":1,"perf":"sample","cpu":12.5,"javaHeapMb":42.1,…}`. */
    public fun sampleLogLine(cpu: CpuSample?, memory: MemorySample?): String {
        val writer = logHead("sample")
        cpu?.let {
            writer.name("cpu").value(round1(it.processPercent))
            writer.name("hopiCpu").value(round1(it.hopiPercent))
        }
        memory?.let { m ->
            m.javaHeapBytes?.let { writer.name("javaHeapMb").value(mb(it)) }
            m.nativeHeapBytes?.let { writer.name("nativeHeapMb").value(mb(it)) }
            m.residentBytes?.let { writer.name("rssMb").value(mb(it)) }
            m.availableBytes?.let { writer.name("availableMb").value(mb(it)) }
        }
        return writer.endObject().toString()
    }

    public fun suppressedNotice(suppressed: Long): String = logHead("notice").field("suppressed", suppressed).endObject().toString()

    public fun noticeLine(message: String): String = logHead("notice").field("message", message).endObject().toString()

    // ---- bridge lines

    public fun statusLine(status: PerfStatus): String {
        val w = JsonWriter().beginObject()
            .field("type", "perf.status")
            .field("platform", status.platform)
        w.name("available").beginArray()
        status.available.forEach { w.value(it.wireName) }
        w.endArray()
        w.field("remoteControl", status.remoteControl)
        w.name("running").beginObject()
        for (monitor in status.running) {
            w.name(monitor.monitor.wireName).beginObject()
            w.field("since", monitor.sinceMs)
            w.name("owners").beginArray()
            monitor.owners.forEach { w.value(it) }
            w.endArray()
            w.field("expiresAt", monitor.expiresAtMs)
            for ((name, value) in monitor.details) w.name(name).anyValue(value)
            w.endObject()
        }
        w.endObject()
        if (status.notices.isNotEmpty()) {
            w.name("notices").beginArray()
            status.notices.forEach { w.value(it) }
            w.endArray()
        }
        return w.endObject().toString()
    }

    /** One event; stacks cut to [stackFrames] frames each (Int.MAX_VALUE: all). */
    public fun eventLine(event: PerfEvent, stackFrames: Int = Int.MAX_VALUE): String {
        val w = JsonWriter().beginObject()
            .field("type", "perf.event")
            .field("kind", event.kind)
            .field("seq", event.seq)
            .field("at", event.atMs)
        when (event) {
            is HangEvent -> {
                w.field("state", if (event.ended) "ended" else "ongoing")
                    .field("ms", event.durationMs)
                    .field("thresholdMs", event.thresholdMs)
                    .field("screen", event.screen)
                w.name("stacks").beginArray()
                for (stack in event.stacks) {
                    w.beginArray()
                    stack.take(stackFrames).forEach { w.value(it) }
                    w.endArray()
                }
                w.endArray()
                if (stackFrames != Int.MAX_VALUE && event.stacks.any { it.size > stackFrames }) w.field("stacksCut", true)
                w.name("captureUs").beginArray()
                event.captureUs.forEach { w.value(it) }
                w.endArray()
                if (event.stackMissed) w.field("stackMissed", true)
                if (event.debugger) w.field("debugger", true)
                if (event.frozenMs > 0) w.field("frozenMs", event.frozenMs)
                if (event.heartbeat) w.field("heartbeat", true)
            }
            is JankEvent -> {
                w.name("ms").value(round1(event.durationMs))
                w.name("deadlineMs").value(round1(event.deadlineMs))
                if (event.frozen) w.field("frozen", true)
                w.field("screen", event.screen)
                event.stagesNs?.let { stages ->
                    w.name("stagesMs").beginObject()
                    FrameStages.NAMES.forEachIndexed { i, name ->
                        val ns = stages.getOrNull(i) ?: -1
                        if (ns >= 0) w.name(name).value(round1(ns / 1_000_000.0))
                    }
                    w.endObject()
                }
            }
            is StallEvent -> w.field("ms", event.durationMs).field("screen", event.screen)
            is MemoryEvent -> {
                w.field("event", event.event).field("level", event.level)
                event.detail?.let { w.field("detail", it) }
            }
            is PreviousAnrEvent -> w.field("description", event.description).field("trace", event.mainThreadTrace)
        }
        return w.endObject().toString()
    }

    /** A closed (or, with [provisional], the open) second of frames. */
    public fun framesLine(second: FrameSecond, provisional: Boolean = false): String {
        val w = JsonWriter().beginObject()
            .field("type", "perf.frames")
            .field("sec", second.second)
            .field("frames", second.frames)
            .field("slow", second.slow)
            .field("janky", second.janky)
            .field("frozen", second.frozen)
        w.name("worstMs").value(round1(second.worstMs))
        w.field("missedVsyncs", second.missedVsyncs)
        if (second.metricsLost > 0) w.field("metricsLost", second.metricsLost)
        if (second.firstFrames > 0) w.field("firstFrames", second.firstFrames)
        if (second.excluded > 0) w.field("excluded", second.excluded)
        w.field("screen", second.screen)
        if (provisional) w.field("provisional", true)
        return w.endObject().toString()
    }

    public fun sampleLine(sample: PerfSample): String {
        val w = JsonWriter().beginObject().field("type", "perf.sample").field("at", sample.atMs)
        sample.cpu?.let { writeCpu(w, it) }
        sample.memory?.let { writeMemory(w, it) }
        return w.endObject().toString()
    }

    private fun writeCpu(w: JsonWriter, cpu: CpuSample) {
        w.name("cpu").beginObject()
        w.name("process").value(round1(cpu.processPercent))
        w.name("hopi").value(round1(cpu.hopiPercent))
        w.field("cores", cpu.cores)
        w.endObject()
    }

    private fun writeMemory(w: JsonWriter, m: MemorySample) {
        w.name("memory").beginObject()
        w.field("javaHeap", m.javaHeapBytes)
        w.field("javaMax", m.javaMaxBytes)
        w.field("nativeHeap", m.nativeHeapBytes)
        w.field("resident", m.residentBytes)
        w.field("available", m.availableBytes)
        w.field("gcCount", m.gcCount)
        w.field("gcBlockingMs", m.gcBlockingMs)
        if (m.perturbed) w.field("perturbed", true)
        w.endObject()
    }

    /** `{"type":"perf.series","name":…,"columns":[…],"points":[[t,v…],…]}`; NaN values are `null`. */
    public fun seriesLine(name: String, columns: List<String>, points: SeriesPoints): String {
        val w = JsonWriter().beginObject().field("type", "perf.series").field("name", name)
        w.name("columns").beginArray()
        w.value("t")
        columns.forEach { w.value(it) }
        w.endArray()
        w.name("points").beginArray()
        for (i in 0 until points.size) {
            w.beginArray()
            w.value(points.times[i])
            for (c in 0 until points.columns) w.value(round1(points.value(i, c).toDouble()))
            w.endArray()
        }
        w.endArray()
        return w.endObject().toString()
    }

    public fun summaryLine(summary: PerfSummary): String {
        val w = JsonWriter().beginObject().field("type", "perf.summary")
        w.name("frames").beginObject()
            .field("count", summary.frames).field("slow", summary.slow).field("janky", summary.janky).field("frozen", summary.frozen)
            .field("excluded", summary.excluded).field("firstFrames", summary.firstFrames).field("metricsLost", summary.metricsLost)
        w.name("p50Ms").value(summary.p50Ms?.let(::round1))
        w.name("p90Ms").value(summary.p90Ms?.let(::round1))
        w.name("p99Ms").value(summary.p99Ms?.let(::round1))
        w.name("maxMs").value(summary.maxMs?.let(::round1))
        w.endObject()
        w.name("hangs").beginObject()
            .field("count", summary.hangs).field("totalMs", summary.hangTotalMs).field("worstMs", summary.hangWorstMs)
            .field("debugger", summary.debuggerHangs)
            .endObject()
        w.name("cpu").beginObject()
        w.name("averagePercent").value(summary.cpuAveragePercent?.let(::round1))
        w.name("maxPercent").value(summary.cpuMaxPercent?.let(::round1))
        w.endObject()
        w.name("memory").beginObject()
            .field("residentMin", summary.residentMinBytes).field("residentMax", summary.residentMaxBytes)
            .field("residentLast", summary.memoryLast?.residentBytes)
            .endObject()
        return w.endObject().toString()
    }

    public fun threadsLine(threads: List<PerfThread>?): String {
        val w = JsonWriter().beginObject().field("type", "perf.threads")
        if (threads == null) return w.field("available", false).endObject().toString()
        w.name("threads").beginArray()
        for (thread in threads) {
            w.beginObject().field("tid", thread.tid).field("name", thread.name).field("cpuMs", thread.cpuMs)
            w.name("percent").value(thread.percent?.let(::round1))
            if (thread.hopi) w.field("hopi", true)
            w.endObject()
        }
        w.endArray()
        return w.endObject().toString()
    }

    public fun pssLine(pss: PerfPss): String = JsonWriter().beginObject()
        .field("type", "perf.pss").field("kb", pss.kb).field("ms", pss.ms)
        .also { w -> pss.refused?.let { w.field("refused", it) } }
        .endObject().toString()

    private fun JsonWriter.anyValue(value: Any?): JsonWriter = when (value) {
        null -> nullValue()
        is String -> value(value)
        is Boolean -> value(value)
        is Int -> value(value)
        is Long -> value(value)
        is Double -> value(round1(value))
        is Float -> value(round1(value.toDouble()))
        is List<*> -> {
            beginArray()
            value.forEach { anyValue(it) }
            endArray()
        }
        else -> value(value.toString())
    }

    private fun JsonWriter.value(value: Double?): JsonWriter = if (value == null) nullValue() else value(value)

    private fun round1(value: Double): Double = if (value.isFinite()) (value * 10).roundToLong() / 10.0 else value

    private fun mb(bytes: Long): Double = round1(bytes / (1024.0 * 1024.0))
}

/**
 * Writes the perf log lines, rate-limited (docs/design/perf-monitors.md, "Log lines"): a jank
 * line at most once per second and only for a second with a janky frame; hangs and memory events
 * always, within a hard cap of [maxLinesPerSecond] perf lines per second (the excess is counted in
 * a `notice`); a `sample` line every [sampleEveryMs] while CPU or memory is on. Called on the
 * writer's thread; one log call per line, nothing else.
 */
@HopiInternalApi
public class PerfLogSink(
    private val emit: (String) -> Unit = ::emitPerfLogLine,
    private val maxBytes: Int = dev.koga.hopi.core.log.platformLogLineMaxBytes,
    private val clockMs: () -> Long = ::perfWallClockMs,
    private val maxLinesPerSecond: Int = 5,
    private val sampleEveryMs: Long = 10_000,
) {
    private val lock = PerfLock()
    private var windowSecond = -1L
    private var linesInWindow = 0
    private var suppressed = 0L
    private var lastSampleLine = Long.MIN_VALUE

    public fun onItem(item: PerfItem) {
        if (item is PerfSample) {
            val now = clockMs()
            val due = lock.locked {
                if (lastSampleLine == Long.MIN_VALUE || now - lastSampleLine >= sampleEveryMs) {
                    lastSampleLine = now
                    true
                } else {
                    false
                }
            }
            if (due) write(PerfJson.sampleLogLine(item.cpu, item.memory))
            return
        }
        val line = PerfJson.logLine(item, maxBytes) ?: return
        write(line)
    }

    /**
     * The ongoing line of a hang again, with its stack named after the unit ended (iOS names raw
     * addresses only then), within the same cap; just before the hang's ended line.
     */
    public fun namedHang(event: HangEvent) {
        PerfJson.hangLogLine(event.copy(ended = false), maxBytes, named = true)?.let(::write)
    }

    /** A notice line (`{"hopi":1,"perf":"notice","message":…}`), within the same cap. */
    public fun notice(message: String) {
        write(PerfJson.noticeLine(message))
    }

    private fun write(line: String) {
        val now = clockMs()
        var owed = 0L
        val allowed = lock.locked {
            val second = now / 1000
            if (second != windowSecond) {
                windowSecond = second
                linesInWindow = 0
            }
            if (linesInWindow >= maxLinesPerSecond) {
                suppressed++
                false
            } else {
                linesInWindow++
                if (suppressed > 0 && linesInWindow < maxLinesPerSecond) {
                    owed = suppressed
                    suppressed = 0
                    linesInWindow++
                }
                true
            }
        }
        if (!allowed) return
        if (owed > 0) emit(PerfJson.suppressedNotice(owed))
        emit(line)
    }
}
