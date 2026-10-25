package dev.koga.hopi.core.bridge

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.json.parseFlatJsonObject
import dev.koga.hopi.core.json.parseJsonTree

/** A client-to-app bridge request. Unknown fields are ignored. */
@HopiInternalApi
public sealed interface BridgeCommand {
    /**
     * True for a command that changes the app's behavior or reads its rules (rules, blobs,
     * replay): accepted only after `hello` on the same connection, and sent by the host only after
     * it verified `hello`, so a squatter holding the socket name never receives one.
     */
    public val isWrite: Boolean get() = false

    public data object Hello : BridgeCommand

    public data class List(public val since: Long, public val limit: Int) : BridgeCommand

    public data class Get(public val id: Long) : BridgeCommand

    /** [rules]: also send `{"type":"rules","rev":N}` whenever the rule set changes. */
    public data class Tail(public val since: Long, public val rules: Boolean = false) : BridgeCommand

    public data object Clear : BridgeCommand

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

    /** Replays record [id]; [edit] is the request edit object, or null. */
    public data class Replay(public val id: Long, public val edit: Map<String, Any?>?) : BridgeCommand {
        override val isWrite: Boolean get() = true
    }

    public companion object {
        public const val MAX_LINE_LENGTH: Int = 4096
        public const val DEFAULT_LIMIT: Int = 100
        public const val MAX_LIMIT: Int = 10_000

        /** Base64 chars one `blob.put` carries at most: 2304 bytes, so a line stays well under [MAX_LINE_LENGTH]. */
        public const val MAX_BLOB_CHUNK_CHARS: Int = 3072

        /** How deep a rule command nests: request, rule, edit, request side, header list, header pair. */
        public const val MAX_DEPTH: Int = 6

        /** Commands whose request may hold objects and arrays; every other one stays flat. */
        private val NESTED = setOf("rules.add", "rules.update", "replay")

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
                "get" -> Get(requiredId(fields) ?: return idError(fields))
                "tail" -> Tail(
                    nonNegative(fields, "since", 0) ?: return invalid("since"),
                    rules = flag(fields, "rules") ?: return invalid("rules"),
                )
                "clear" -> Clear
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
                "replay" -> {
                    val id = requiredId(fields) ?: return idError(fields)
                    val edit = fields["edit"]
                    if (edit != null && edit !is Map<*, *>) return invalid("edit")
                    Replay(id, edit as Map<String, Any?>?)
                }
                else -> return BridgeParseResult.Invalid("unknown cmd")
            }
            return BridgeParseResult.Ok(command)
        }

        private fun requiredId(fields: Map<String, Any?>): Long? = (fields["id"] as? Long)?.takeIf { it > 0 }

        private fun idError(fields: Map<String, Any?>): BridgeParseResult =
            if (!fields.containsKey("id")) BridgeParseResult.Invalid("missing \"id\"") else invalid("id")

        private fun invalid(field: String): BridgeParseResult = BridgeParseResult.Invalid("invalid \"$field\"")

        private fun flag(fields: Map<String, Any?>, key: String): Boolean? {
            if (!fields.containsKey(key)) return false
            return fields[key] as? Boolean
        }

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
