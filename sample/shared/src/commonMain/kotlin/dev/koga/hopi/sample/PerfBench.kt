package dev.koga.hopi.sample

import dev.koga.hopi.perf.HopiMonitor
import dev.koga.hopi.perf.HopiPerf
import kotlinx.coroutines.delay

/** One timed chain of main-thread units: the gap between consecutive unit starts, and the main thread's CPU over it. */
internal class BenchChain(val gapsNs: LongArray, val mainCpuNs: Long)

/**
 * The main-thread unit kinds this platform's bench posts: Android `empty` (one reused `Runnable`),
 * `lambda` (a new lambda per message, as `View.post` callers do) and `coroutine` (a `yield()` on
 * `Dispatchers.Main`, whose message the Looper printer formats with the continuation's `toString`);
 * iOS `mainQueue` (`dispatch_async` to the main queue) and `coroutine`.
 */
internal expect val benchKinds: List<String>

/** Posts [count] units of [kind] to the main thread one after the other, each posting the next. */
internal expect suspend fun benchChain(kind: String, count: Int): BenchChain

/** The runtime's GC count, -1 where the platform has none to read. */
internal expect fun benchGcCount(): Long

/**
 * The overhead A/B workload (docs/design/perf-monitors.md, "Performance plan"): Android
 * `--es perfBench <spec>`, iOS `-perfBench <spec>`, e.g. `monitors=hangs;n=10000;runs=3`.
 *
 * - `monitors`: `none` (the default: the real module with nothing started, or the noop build),
 *   `all`, or a comma list of `frames,hangs,cpu,memory`, started with [HopiPerf.start] before the
 *   run (a noop build starts nothing and says `"running":""`); `heartbeat=1` runs the Android hang
 *   monitor on its heartbeat instead of the Looper printer ([dev.koga.hopi.perf.HopiPerfConfig.hangHeartbeat]);
 * - `mode=messages` (the default): per kind and run, `n` main-thread units after `warmup`
 *   uncounted ones, one `{"perfBench":"messages",…}` line with the median, p90, p99 and mean gap
 *   between unit starts (what one unit of the chain costs the main thread, the monitors' work
 *   included), the main thread's CPU per unit and the GC count over the run;
 * - `mode=idle`: starts the monitors and does nothing else; the host measures every thread's CPU
 *   and wakes from outside (benchmark/perf/idle.py).
 */
public suspend fun runPerfBench(spec: String): String {
    val fields = spec.split(';').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
    val monitors = parseMonitors(fields["monitors"])
    val mode = fields["mode"] ?: "messages"
    val count = fields["n"]?.toIntOrNull()?.coerceIn(100, 1_000_000) ?: 10_000
    val warmup = fields["warmup"]?.toIntOrNull()?.coerceIn(0, 1_000_000) ?: 2_000
    val runs = fields["runs"]?.toIntOrNull()?.coerceIn(1, 50) ?: 3
    val heartbeat = fields["heartbeat"] == "1"
    val kinds = fields["kinds"]?.split(',')?.map { it.trim() }?.filter { it in benchKinds }?.ifEmpty { null } ?: benchKinds
    if (monitors.isNotEmpty()) {
        HopiPerf.start {
            frames = HopiMonitor.Frames in monitors
            hangs = HopiMonitor.Hangs in monitors
            cpu = HopiMonitor.Cpu in monitors
            memory = HopiMonitor.Memory in monitors
            hangHeartbeat = heartbeat
        }
    }
    // Lets the launch's own work (first frames, the monitors' attach message, JIT) drain first.
    delay(SETTLE_MS)
    val head = "\"build\":\"${if (HOPI_ENABLED) "real" else "noop"}\",\"monitors\":\"${monitors.joinToString(",") { it.name.lowercase() }}\"," +
        "\"heartbeat\":$heartbeat,\"running\":\"${HopiPerf.running.joinToString(",") { it.name.lowercase() }}\""
    if (mode == "idle") {
        val ready = "{\"perfBench\":\"idle\",$head,\"at\":${labWallMs()}}"
        sampleLog(ready)
        return ready
    }
    val lines = ArrayList<String>()
    repeat(runs) { run ->
        for (kind in kinds) {
            if (warmup > 0) benchChain(kind, warmup)
            val gcBefore = benchGcCount()
            val chain = benchChain(kind, count)
            val gc = if (gcBefore < 0) -1 else benchGcCount() - gcBefore
            val sorted = chain.gapsNs.sortedArray()
            val n = sorted.size
            val line = "{\"perfBench\":\"messages\",$head,\"kind\":\"$kind\",\"run\":${run + 1},\"n\":$n," +
                "\"medianNs\":${sorted[n / 2]},\"p90Ns\":${sorted[n * 9 / 10]},\"p99Ns\":${sorted[n * 99 / 100]},\"meanNs\":${sorted.sum() / n}," +
                "\"mainCpuNsPerUnit\":${chain.mainCpuNs / n},\"gc\":$gc}"
            sampleLog(line)
            lines += line
        }
    }
    val done = "{\"perfBench\":\"done\",$head}"
    sampleLog(done)
    lines += done
    return lines.joinToString("\n")
}

private const val SETTLE_MS = 3_000L
