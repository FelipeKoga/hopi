package dev.koga.hopi.core.bridge

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.json.parseFlatJsonObject
import dev.koga.hopi.core.json.parseJsonTree
import dev.koga.hopi.core.perf.PerfMonitor

/** A client-to-app bridge request. Unknown fields are ignored. */
@HopiInternalApi
public sealed interface BridgeCommand {
    /**
     * True for a command that changes the app's behavior or reads its rules (rules, blobs):
     * accepted only after `hello` on the same connection, and sent by the host only after it
     * verified `hello`, so a squatter holding the socket name never receives one.
     */
    public val isWrite: Boolean get() = false

    public data object Hello : BridgeCommand

    public data class List(public val since: Long, public val limit: Int) : BridgeCommand

    /** [trim]: the client keeps the body (full bodies), so the app cuts its own copy back afterwards. */
    public data class Get(public val id: Long, public val trim: Boolean = false) : BridgeCommand

    /** [rules]: also send `{"type":"rules","rev":N}` whenever the rule set changes. */
    public data class Tail(public val since: Long, public val rules: Boolean = false) : BridgeCommand

    /**
     * Full bodies on or off for as long as this connection lasts ([dev.koga.hopi.core.capture.FullBodies]):
     * a host viewer that fetches every body at once holds it on its tail connection.
     */
    public data class CaptureSet(public val fullBodies: Boolean) : BridgeCommand {
        override val isWrite: Boolean get() = true
    }

    /** Drops every record: a write, so only after `hello` on the same connection. */
    public data object Clear : BridgeCommand {
        override val isWrite: Boolean get() = true
    }

    /** [rule] is the rule object, parsed by `RuleJson.parseRule` against the session's blobs. */
    public data class RulesAdd(public val rule: Map<String, Any?>) : BridgeCommand {
        override val isWrite: Boolean get() = true
    }

    public data object RulesList : BridgeCommand {
        override val isWrite: Boolean get() = true
    }

    /** [fields] is the whole request: `enabled`, `priority`, `times` (null clears it), `resetHits`. */
    public data class RulesUpdate(public val id: Long, public val fields: Map<String, Any?>) : BridgeCommand {
        override val isWrite: Boolean get() = true
    }

    public data class RulesRemove(public val id: Long) : BridgeCommand {
        override val isWrite: Boolean get() = true
    }

    public data object RulesClear : BridgeCommand {
        override val isWrite: Boolean get() = true
    }

    /** One slice of a blob: [data] is base64 of at most [MAX_BLOB_CHUNK_CHARS] chars, [offset] the bytes received so far. */
    public data class BlobPut(public val blob: Long, public val offset: Long, public val data: String) : BridgeCommand {
        override val isWrite: Boolean get() = true
    }

    /**
     * The perf commands (docs/design/perf-monitors.md, "Bridge"): all accepted only after `hello`,
     * like the rule commands. Values are kept as sent; the session checks their ranges (`LIMIT`).
     */
    public sealed interface Perf : BridgeCommand {
        override val isWrite: Boolean get() = true
    }

    /** [ttlSec]: the lease; 0 is no expiry. Null settings keep the values in force. */
    public data class PerfStart(
        public val monitors: Set<PerfMonitor>,
        public val hangMs: Long?,
        public val intervalMs: Long?,
        public val ttlSec: Long,
    ) : Perf

    /** No monitor named: all. */
    public data class PerfStop(public val monitors: Set<PerfMonitor>) : Perf

    public data object PerfStatus : Perf

    public data class PerfSnapshot(
        /** Series by name (`frames`, `cpu`, `memory`); empty: all. */
        public val series: kotlin.collections.List<String>,
        public val points: Long,
        public val events: Boolean,
        /** Events after this sequence number only. */
        public val since: Long,
        public val threads: Boolean,
        public val pss: Boolean,
        public val fullStacks: Boolean,
    ) : Perf

    public data class PerfEvent(public val seq: Long) : Perf

    /**
     * Backlog events after [since], then live events, closed frame seconds and (with [samples])
     * samples until disconnect. [start]: monitors this session starts and holds until it ends.
     */
    public data class PerfTail(
        public val since: Long,
        public val samples: Boolean,
        public val fps: Boolean,
        public val threads: Boolean,
        public val start: Set<PerfMonitor>,
    ) : Perf

    public companion object {
        public const val MAX_LINE_LENGTH: Int = 4096

        /** Points a `perf.snapshot` returns per series by default. */
        public const val DEFAULT_PERF_POINTS: Long = 120
        public const val DEFAULT_LIMIT: Int = 100
        public const val MAX_LIMIT: Int = 10_000

        /** Base64 chars one `blob.put` carries at most: 2304 bytes, so a line stays well under [MAX_LINE_LENGTH]. */
        public const val MAX_BLOB_CHUNK_CHARS: Int = 3072

        /** How deep a rule command nests: request, rule, edit, request side, header list, header pair. */
        public const val MAX_DEPTH: Int = 6

        /** Commands whose request may hold objects and arrays; every other one stays flat. */
        private val NESTED = setOf("rules.add", "rules.update", "perf.start", "perf.stop", "perf.snapshot", "perf.tail")

        /** Parses one NDJSON request line. Malformed input yields [BridgeParseResult.Invalid]. */
        @Suppress("UNCHECKED_CAST")
        public fun parse(line: String): BridgeParseResult {
            val fields = parseFlatJsonObject(line, MAX_LINE_LENGTH)
                ?: parseJsonTree(line, MAX_LINE_LENGTH, MAX_DEPTH)?.takeIf { it["cmd"] in NESTED }
                ?: return BridgeParseResult.Invalid("malformed JSON object")
            val cmd = fields["cmd"] as? String ?: return BridgeParseResult.Invalid("missing \"cmd\"")
            val command = when (cmd) {
                "hello" -> Hello
                "list" -> {
                    val since = nonNegative(fields, "since", 0) ?: return invalid("since")
                    val limit = nonNegative(fields, "limit", DEFAULT_LIMIT.toLong())
                        ?.takeIf { it in 1..MAX_LIMIT } ?: return invalid("limit")
                    List(since, limit.toInt())
                }
                "get" -> Get(requiredId(fields) ?: return idError(fields), trim = flag(fields, "trim") ?: return invalid("trim"))
                "tail" -> Tail(
                    nonNegative(fields, "since", 0) ?: return invalid("since"),
                    rules = flag(fields, "rules") ?: return invalid("rules"),
                )
                "clear" -> Clear
                "capture.set" -> CaptureSet(flag(fields, "fullBodies") ?: return invalid("fullBodies"))
                "rules.add" -> RulesAdd(fields["rule"] as? Map<String, Any?> ?: return BridgeParseResult.Invalid("missing \"rule\" object"))
                "rules.list" -> RulesList
                "rules.update" -> RulesUpdate(requiredId(fields) ?: return idError(fields), fields)
                "rules.remove" -> RulesRemove(requiredId(fields) ?: return idError(fields))
                "rules.clear" -> RulesClear
                "blob.put" -> {
                    val blob = (fields["blob"] as? Long)?.takeIf { it > 0 } ?: return invalid("blob")
                    val offset = (fields["offset"] as? Long)?.takeIf { it >= 0 } ?: return invalid("offset")
                    val data = fields["data"] as? String ?: return invalid("data")
                    if (data.length > MAX_BLOB_CHUNK_CHARS) return BridgeParseResult.Invalid("\"data\" longer than $MAX_BLOB_CHUNK_CHARS chars")
                    BlobPut(blob, offset, data)
                }
                "perf.start" -> PerfStart(
                    monitors(fields, "monitors") ?: return invalid("monitors"),
                    hangMs = (optionalLong(fields, "hangMs") ?: return invalid("hangMs")).takeIf { it != NOT_GIVEN },
                    intervalMs = (optionalLong(fields, "intervalMs") ?: return invalid("intervalMs")).takeIf { it != NOT_GIVEN },
                    ttlSec = nonNegative(fields, "ttlSec", dev.koga.hopi.core.perf.PerfLimits.DEFAULT_LEASE_SEC) ?: return invalid("ttlSec"),
                )
                "perf.stop" -> PerfStop(monitors(fields, "monitors") ?: return invalid("monitors"))
                "perf.status" -> PerfStatus
                "perf.snapshot" -> PerfSnapshot(
                    series = strings(fields, "series") ?: return invalid("series"),
                    points = nonNegative(fields, "points", DEFAULT_PERF_POINTS) ?: return invalid("points"),
                    events = flag(fields, "events", default = true) ?: return invalid("events"),
                    since = nonNegative(fields, "since", 0) ?: return invalid("since"),
                    threads = flag(fields, "threads") ?: return invalid("threads"),
                    pss = flag(fields, "pss") ?: return invalid("pss"),
                    fullStacks = flag(fields, "fullStacks") ?: return invalid("fullStacks"),
                )
                "perf.event" -> PerfEvent((fields["seq"] as? Long)?.takeIf { it > 0 } ?: return invalid("seq"))
                "perf.tail" -> PerfTail(
                    since = nonNegative(fields, "since", 0) ?: return invalid("since"),
                    samples = flag(fields, "samples") ?: return invalid("samples"),
                    fps = flag(fields, "fps") ?: return invalid("fps"),
                    threads = flag(fields, "threads") ?: return invalid("threads"),
                    start = monitors(fields, "start") ?: return invalid("start"),
                )
                else -> return BridgeParseResult.Invalid("unknown cmd")
            }
            return BridgeParseResult.Ok(command)
        }

        /** An absent key is the empty set; an unknown monitor name or a non-array is invalid. */
        private fun monitors(fields: Map<String, Any?>, key: String): Set<PerfMonitor>? {
            val names = strings(fields, key) ?: return null
            val out = LinkedHashSet<PerfMonitor>()
            for (name in names) out += PerfMonitor.fromWire(name) ?: return null
            return out
        }

        private fun strings(fields: Map<String, Any?>, key: String): kotlin.collections.List<String>? {
            if (!fields.containsKey(key)) return emptyList()
            val values = fields[key] as? kotlin.collections.List<*> ?: return null
            return values.map { it as? String ?: return null }
        }

        /** [NOT_GIVEN] for an absent key, null for a value that is not an integer. */
        private fun optionalLong(fields: Map<String, Any?>, key: String): Long? =
            if (!fields.containsKey(key)) NOT_GIVEN else fields[key] as? Long

        private fun requiredId(fields: Map<String, Any?>): Long? = (fields["id"] as? Long)?.takeIf { it > 0 }

        private fun idError(fields: Map<String, Any?>): BridgeParseResult =
            if (!fields.containsKey("id")) BridgeParseResult.Invalid("missing \"id\"") else invalid("id")

        private fun invalid(field: String): BridgeParseResult = BridgeParseResult.Invalid("invalid \"$field\"")

        private fun flag(fields: Map<String, Any?>, key: String, default: Boolean = false): Boolean? {
            if (!fields.containsKey(key)) return default
            return fields[key] as? Boolean
        }

        private const val NOT_GIVEN: Long = Long.MIN_VALUE

        private fun nonNegative(fields: Map<String, Any?>, key: String, default: Long): Long? {
            if (!fields.containsKey(key)) return default
            return (fields[key] as? Long)?.takeIf { it >= 0 }
        }
    }
}

@HopiInternalApi
public sealed interface BridgeParseResult {
    public data class Ok(public val command: BridgeCommand) : BridgeParseResult

    public data class Invalid(public val message: String) : BridgeParseResult
}
