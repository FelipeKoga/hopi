package dev.koga.hopi.cli

import dev.koga.hopi.connect.PerfClient
import dev.koga.hopi.connect.usageError
import dev.koga.hopi.core.perf.PerfLimits

/** `perf start`, checked before anything connects: the app checks the same ranges (`LIMIT`). */
internal class PerfStartArgs(val monitors: List<String>, val hangMs: Long?, val intervalMs: Long?, val ttlSec: Long?)

/** The perf commands' own flags (docs/design/perf-monitors.md, "CLI"). */
internal object PerfArgs {
    /** The monitor switches given (`--hangs --cpu`), in display order. */
    fun monitors(args: CliArgs): List<String> = PerfClient.MONITORS.filter { "--$it" in args.switches }

    fun start(args: CliArgs): PerfStartArgs {
        val monitors = monitors(args)
        if (monitors.isEmpty()) throw usageError("perf start needs at least one of --frames, --hangs, --cpu, --memory")
        val hangMs = args.values["--hang-ms"]?.let { value ->
            if ("hangs" !in monitors) throw usageError("--hang-ms goes with --hangs")
            value.toLongOrNull()?.takeIf { it in PerfLimits.MIN_HANG_THRESHOLD_MS..PerfLimits.MAX_HANG_THRESHOLD_MS }
                ?: throw usageError("--hang-ms must be ${PerfLimits.MIN_HANG_THRESHOLD_MS}..${PerfLimits.MAX_HANG_THRESHOLD_MS}")
        }
        val intervalMs = args.values["--interval"]?.let { value ->
            if ("cpu" !in monitors && "memory" !in monitors) throw usageError("--interval goes with --cpu or --memory")
            duration(value, "--interval").takeIf { it in PerfLimits.MIN_SAMPLE_INTERVAL_MS..PerfLimits.MAX_SAMPLE_INTERVAL_MS }
                ?: throw usageError("--interval must be 250ms..10s")
        }
        val noExpiry = "--no-expiry" in args.switches
        val forMs = args.values["--for"]?.let { value ->
            if (noExpiry) throw usageError("--for and --no-expiry exclude each other")
            duration(value, "--for").takeIf { it in 1_000..PerfLimits.MAX_LEASE_SEC * 1_000 } ?: throw usageError("--for must be 1s..60m")
        }
        // Null: the app's default lease (10 minutes). Rounded up, so --for 1500ms still lasts 1.5 s at least.
        val ttlSec = if (noExpiry) 0L else forMs?.let { (it + 999) / 1_000 }
        return PerfStartArgs(monitors, hangMs, intervalMs, ttlSec)
    }

    /** `perf tail --start hangs,frames`. */
    fun tailStart(args: CliArgs): List<String> {
        val raw = args.values["--start"] ?: return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { name ->
            name.takeIf { it in PerfClient.MONITORS } ?: throw usageError("--start: unknown monitor '$name' (frames, hangs, cpu, memory)")
        }.distinct().ifEmpty { throw usageError("--start needs monitors: frames, hangs, cpu, memory") }
    }

    /** `250ms`, `1.5s`, `10m`, `1h` in milliseconds. */
    fun duration(text: String, flag: String): Long {
        val match = DURATION.matchEntire(text.trim()) ?: throw usageError("$flag: '$text' is not a duration (250ms, 2s, 10m, 1h)")
        val number = match.groupValues[1].toDouble()
        val unit = when (match.groupValues[2]) {
            "ms" -> 1.0
            "s" -> 1_000.0
            "m" -> 60_000.0
            else -> 3_600_000.0
        }
        return (number * unit).toLong()
    }

    private val DURATION = Regex("""(\d+(?:\.\d+)?)(ms|s|m|h)""")
}
