package dev.koga.hopi.connect

import dev.koga.hopi.api.HopiInternalApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The perf commands over one [BridgeClient] (docs/design/perf-monitors.md, "Bridge"). Like
 * [RuleClient], it needs a client whose [BridgeClient.handshake] ran with no pipelined request and
 * verified `hello`, and checks `hello.perf` first: no `perf.*` line is sent to an app that did not
 * say it has monitors, nor before its `hello` was verified.
 */
@HopiInternalApi
public class PerfClient(private val client: BridgeClient) {
    init {
        if (client.hello.json.bool("perf") != true) throw CliException(ExitCode.UNSUPPORTED, PERF_UNSUPPORTED)
    }

    /** `hello.perfControl`: the app accepts `perf.start` and `perf.stop` (it refuses them otherwise, `UNSUPPORTED`). */
    public val control: Boolean get() = client.hello.json.bool("perfControl") == true

    /** `perf.start`; [ttlSec] 0 is no expiry. Null settings keep the values in force. Returns the `perf.status` reply. */
    public fun start(monitors: Collection<String>, hangMs: Long? = null, intervalMs: Long? = null, ttlSec: Long? = null): Reply {
        val w = BridgeClient.request("perf.start")
        w.name("monitors").beginArray()
        monitors.forEach { w.value(it) }
        w.endArray()
        hangMs?.let { w.field("hangMs", it) }
        intervalMs?.let { w.field("intervalMs", it) }
        ttlSec?.let { w.field("ttlSec", it) }
        return request(w.endObject().toString(), "perf.status")
    }

    /** `perf.stop`; no monitor named stops all (every owner, code included). */
    public fun stop(monitors: Collection<String> = emptyList()): Reply {
        val w = BridgeClient.request("perf.stop")
        if (monitors.isNotEmpty()) {
            w.name("monitors").beginArray()
            monitors.forEach { w.value(it) }
            w.endArray()
        }
        return request(w.endObject().toString(), "perf.status")
    }

    public fun status(): Reply = request(BridgeClient.request("perf.status").endObject().toString(), "perf.status")

    /** Every line of a `perf.snapshot` up to its `end` (series, the open second, summary, events, threads, PSS, status). */
    public fun snapshot(
        series: List<String> = emptyList(),
        points: Int = DEFAULT_POINTS,
        events: Boolean = true,
        since: Long = 0,
        threads: Boolean = false,
        pss: Boolean = false,
        fullStacks: Boolean = false,
    ): List<Reply> {
        val w = BridgeClient.request("perf.snapshot")
        if (series.isNotEmpty()) {
            w.name("series").beginArray()
            series.forEach { w.value(it) }
            w.endArray()
        }
        w.field("points", points.toLong()).field("events", events).field("since", since)
        if (threads) w.field("threads", true)
        if (pss) w.field("pss", true)
        if (fullStacks) w.field("fullStacks", true)
        client.send(w.endObject().toString())
        val lines = ArrayList<Reply>()
        while (true) {
            val reply = client.reply()
            if (reply.type == "end") return lines
            lines += reply
        }
    }

    /** One event with all its stacks; a missing one is a [CliException] with [ExitCode.NOT_FOUND]. */
    public fun event(seq: Long): Reply = request(BridgeClient.request("perf.event").field("seq", seq).endObject().toString(), "perf.event")

    /**
     * Sends `perf.tail`: the reply to [start] (or the status), the backlog after [since], then live
     * lines until disconnect, read with [BridgeClient.reply]. Nothing else may be sent on this
     * client afterwards. The monitors in [start] are held by this connection until it closes.
     */
    public fun tail(since: Long = 0, samples: Boolean = false, fps: Boolean = false, threads: Boolean = false, start: Collection<String> = emptyList()) {
        client.send(tailRequest(since, samples, fps, threads, start))
        client.waitForever()
    }

    /** The next line of a [tail]; its end fails with [closedMessage]. */
    public fun next(closedMessage: String): Reply = client.reply(closedMessage)

    private fun request(line: String, type: String): Reply {
        client.send(line)
        val reply = client.reply()
        if (reply.type != type) throw notReachable("unexpected reply from the bridge: ${reply.type?.let(::visible)}")
        return reply
    }

    public companion object {
        public const val DEFAULT_POINTS: Int = 120
        public const val MAX_POINTS: Int = 1_200
        public const val PERF_UNSUPPORTED: String =
            "this app's Hopi has no perf monitors (no hopi-perf in this build, perf left out of Hopi's start, or an older Hopi)"

        /** The four monitors by their wire names, in display order. */
        public val MONITORS: List<String> = listOf("frames", "hangs", "cpu", "memory")

        public fun tailRequest(since: Long, samples: Boolean, fps: Boolean, threads: Boolean, start: Collection<String>): String {
            val w = BridgeClient.request("perf.tail").field("since", since)
            if (samples) w.field("samples", true)
            if (fps) w.field("fps", true)
            if (threads) w.field("threads", true)
            if (start.isNotEmpty()) {
                w.name("start").beginArray()
                start.forEach { w.value(it) }
                w.endArray()
            }
            return w.endObject().toString()
        }
    }
}

/** One running monitor of a `perf.status` line. */
@HopiInternalApi
public class RemotePerfMonitor(
    public val name: String,
    /** Epoch ms. */
    public val sinceMs: Long?,
    /** `code`, `lease`, `lease:no-expiry`, `tail:N`. */
    public val owners: List<String>,
    /** Epoch ms; null without a lease or with one that does not expire. */
    public val expiresAtMs: Long?,
    /** The monitor's own facts (`thresholdMs`, `attached`, `tier`, …) as the app sent them. */
    public val details: JsonObject,
) {
    public val thresholdMs: Long? get() = details.long("thresholdMs")
    public val intervalMs: Long? get() = details.long("intervalMs")

    /**
     * Android frames that no window reports yet (`"attached":false`) and that waits for no attach
     * already posted to the app's main thread (`"attaching":true`, absent from an older app).
     */
    public val unattached: Boolean get() = name == "frames" && details.bool("attached") == false && details.bool("attaching") != true
}

/** A `{"type":"perf.status",…}` line. */
@HopiInternalApi
public class RemotePerfStatus(
    public val platform: String,
    public val available: List<String>,
    public val remoteControl: Boolean,
    public val running: List<RemotePerfMonitor>,
    public val notices: List<String>,
) {
    public fun monitor(name: String): RemotePerfMonitor? = running.firstOrNull { it.name == name }

    public companion object {
        public fun decode(json: JsonObject): RemotePerfStatus? {
            if (json.str("type") != "perf.status") return null
            val running = json["running"] as? JsonObject ?: return null
            return RemotePerfStatus(
                platform = json.str("platform") ?: return null,
                available = strings(json["available"]),
                remoteControl = json.bool("remoteControl") == true,
                running = running.mapNotNull { (name, value) ->
                    val monitor = value as? JsonObject ?: return@mapNotNull null
                    RemotePerfMonitor(
                        name = name,
                        sinceMs = monitor.long("since"),
                        owners = strings(monitor["owners"]),
                        expiresAtMs = monitor.long("expiresAt"),
                        details = JsonObject(monitor.filterKeys { it !in STATUS_KEYS }),
                    )
                },
                notices = strings(json["notices"]),
            )
        }

        private val STATUS_KEYS = setOf("since", "owners", "expiresAt")
    }
}

/** A `{"type":"perf.event",…}` line: a hang, jank, stall, memory event or the previous ANR. */
@HopiInternalApi
public class RemotePerfEvent(
    public val kind: String,
    public val seq: Long,
    /** Epoch ms. */
    public val atMs: Long,
    /** Hangs and stalls: whole milliseconds; janks: the frame's duration. */
    public val ms: Double?,
    public val screen: String?,
    /** Hangs: still running. */
    public val ongoing: Boolean,
    /** Hangs: the main thread's stacks, innermost frame first. */
    public val stacks: List<List<String>>,
    /** Hangs: the snapshot cut each stack ([PerfClient.snapshot] without `fullStacks`). */
    public val stacksCut: Boolean,
    public val debugger: Boolean,
    public val stackMissed: Boolean,
    public val frozenMs: Long?,
    /** Memory: `trim`, `low`, `warning`, `pressure`; with [level] or [detail]. */
    public val event: String?,
    public val level: Long?,
    public val detail: String?,
    /** The previous ANR: the OS's description and the main thread's trace. */
    public val description: String?,
    public val trace: String?,
) {
    /** The stacks as copyable text: one block per capture. */
    public fun stacksText(): String = stacks.mapIndexed { i, stack ->
        buildString {
            append(if (stacks.size > 1) "stack ${i + 1} of ${stacks.size}\n" else "")
            stack.forEach { append("  at ").append(it).append('\n') }
        }
    }.joinToString("\n")

    public companion object {
        public fun decode(json: JsonObject): RemotePerfEvent? {
            if (json.str("type") != "perf.event") return null
            return RemotePerfEvent(
                kind = json.str("kind") ?: return null,
                seq = json.long("seq") ?: return null,
                atMs = json.long("at") ?: 0,
                ms = json.number("ms"),
                screen = json.str("screen"),
                ongoing = json.str("state") == "ongoing",
                stacks = (json["stacks"] as? JsonArray).orEmpty().map(::strings),
                stacksCut = json.bool("stacksCut") == true,
                debugger = json.bool("debugger") == true,
                stackMissed = json.bool("stackMissed") == true,
                frozenMs = json.long("frozenMs"),
                event = json.str("event"),
                level = json.long("level"),
                detail = json.str("detail"),
                description = json.str("description"),
                trace = json.str("trace"),
            )
        }
    }
}

/** A `{"type":"perf.frames",…}` line: one second of frames ([provisional] for the second still open). */
@HopiInternalApi
public class RemoteFrameSecond(
    /** Epoch seconds. */
    public val second: Long,
    public val frames: Long,
    public val slow: Long,
    public val janky: Long,
    public val frozen: Long,
    public val worstMs: Double,
    public val provisional: Boolean,
) {
    public companion object {
        public fun decode(json: JsonObject): RemoteFrameSecond? {
            if (json.str("type") != "perf.frames") return null
            return RemoteFrameSecond(
                second = json.long("sec") ?: return null,
                frames = json.long("frames") ?: 0,
                slow = json.long("slow") ?: 0,
                janky = json.long("janky") ?: 0,
                frozen = json.long("frozen") ?: 0,
                worstMs = json.number("worstMs") ?: 0.0,
                provisional = json.bool("provisional") == true,
            )
        }
    }
}

/** A `{"type":"perf.sample",…}` line; null fields for a monitor that is off or a value the platform lacks. */
@HopiInternalApi
public class RemotePerfSample(
    /** Epoch ms. */
    public val atMs: Long,
    /** Percent of one core. */
    public val cpu: Double?,
    public val hopiCpu: Double?,
    public val residentBytes: Long?,
    public val javaHeapBytes: Long?,
    public val nativeHeapBytes: Long?,
    public val availableBytes: Long?,
) {
    public companion object {
        public fun decode(json: JsonObject): RemotePerfSample? {
            if (json.str("type") != "perf.sample") return null
            val cpu = json["cpu"] as? JsonObject
            val memory = json["memory"] as? JsonObject
            return RemotePerfSample(
                atMs = json.long("at") ?: return null,
                cpu = cpu?.number("process"),
                hopiCpu = cpu?.number("hopi"),
                residentBytes = memory?.long("resident"),
                javaHeapBytes = memory?.long("javaHeap"),
                nativeHeapBytes = memory?.long("nativeHeap"),
                availableBytes = memory?.long("available"),
            )
        }
    }
}

/**
 * A `{"type":"perf.series",…}` line: [times] in epoch ms and one value per column per point,
 * `NaN` where the app sent `null`. Memory columns are KiB, as the app keeps them.
 */
@HopiInternalApi
public class RemotePerfSeries(public val name: String, public val columns: List<String>, public val times: LongArray, public val values: List<DoubleArray>) {
    public val size: Int get() = times.size

    /** Column [column] of every point; NaN for a column the line lacks. */
    public fun column(column: String): DoubleArray {
        val index = columns.indexOf(column)
        return DoubleArray(size) { i -> if (index < 0) Double.NaN else values[i].getOrElse(index) { Double.NaN } }
    }

    public companion object {
        public fun decode(json: JsonObject): RemotePerfSeries? {
            if (json.str("type") != "perf.series") return null
            val columns = strings(json["columns"]).drop(1) // "t" first
            val points = json["points"] as? JsonArray ?: return null
            val times = LongArray(points.size)
            val values = ArrayList<DoubleArray>(points.size)
            points.forEachIndexed { i, element ->
                val point = element as? JsonArray ?: return null
                times[i] = (point.getOrNull(0) as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
                values += DoubleArray(columns.size) { c -> (point.getOrNull(c + 1) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull ?: Double.NaN }
            }
            return RemotePerfSeries(json.str("name") ?: return null, columns, times, values)
        }
    }
}

/** A `{"type":"perf.summary",…}` line: the figures since the monitors started. */
@HopiInternalApi
public class RemotePerfSummary(
    public val frames: Long,
    public val janky: Long,
    public val frozen: Long,
    public val p50Ms: Double?,
    public val p90Ms: Double?,
    public val p99Ms: Double?,
    public val maxMs: Double?,
    public val hangs: Long,
    public val hangTotalMs: Long,
    public val hangWorstMs: Long,
    public val debuggerHangs: Long,
    public val cpuAveragePercent: Double?,
    public val cpuMaxPercent: Double?,
    public val residentMinBytes: Long?,
    public val residentMaxBytes: Long?,
    public val residentLastBytes: Long?,
) {
    public companion object {
        public fun decode(json: JsonObject): RemotePerfSummary? {
            if (json.str("type") != "perf.summary") return null
            val frames = json["frames"] as? JsonObject ?: JsonObject(emptyMap())
            val hangs = json["hangs"] as? JsonObject ?: JsonObject(emptyMap())
            val cpu = json["cpu"] as? JsonObject ?: JsonObject(emptyMap())
            val memory = json["memory"] as? JsonObject ?: JsonObject(emptyMap())
            return RemotePerfSummary(
                frames = frames.long("count") ?: 0,
                janky = frames.long("janky") ?: 0,
                frozen = frames.long("frozen") ?: 0,
                p50Ms = frames.number("p50Ms"),
                p90Ms = frames.number("p90Ms"),
                p99Ms = frames.number("p99Ms"),
                maxMs = frames.number("maxMs"),
                hangs = hangs.long("count") ?: 0,
                hangTotalMs = hangs.long("totalMs") ?: 0,
                hangWorstMs = hangs.long("worstMs") ?: 0,
                debuggerHangs = hangs.long("debugger") ?: 0,
                cpuAveragePercent = cpu.number("averagePercent"),
                cpuMaxPercent = cpu.number("maxPercent"),
                residentMinBytes = memory.long("residentMin"),
                residentMaxBytes = memory.long("residentMax"),
                residentLastBytes = memory.long("residentLast"),
            )
        }
    }
}

/** One thread of a `{"type":"perf.threads",…}` line. */
@HopiInternalApi
public class RemotePerfThread(public val tid: Long, public val name: String, public val cpuMs: Long, public val percent: Double?, public val hopi: Boolean) {
    public companion object {
        /** Null for a platform without a per-thread list (`"available":false`) or a malformed line. */
        public fun decodeAll(json: JsonObject): List<RemotePerfThread>? {
            if (json.str("type") != "perf.threads") return null
            val threads = json["threads"] as? JsonArray ?: return null
            return threads.mapNotNull { element ->
                val thread = element as? JsonObject ?: return@mapNotNull null
                RemotePerfThread(
                    tid = thread.long("tid") ?: return@mapNotNull null,
                    name = thread.str("name").orEmpty(),
                    cpuMs = thread.long("cpuMs") ?: 0,
                    percent = thread.number("percent"),
                    hopi = thread.bool("hopi") == true,
                )
            }
        }
    }
}

/** The numeric field [name] (integer or fractional); null when absent, `null` or a string. */
@HopiInternalApi
public fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private fun strings(element: kotlinx.serialization.json.JsonElement?): List<String> =
    (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
