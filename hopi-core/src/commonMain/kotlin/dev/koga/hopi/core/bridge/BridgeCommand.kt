package dev.koga.hopi.core.bridge

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.json.parseFlatJsonObject

/** A client-to-app bridge request. Unknown fields are ignored. */
@HopiInternalApi
public sealed interface BridgeCommand {
    public data object Hello : BridgeCommand

    public data class List(public val since: Long, public val limit: Int) : BridgeCommand

    public data class Get(public val id: Long) : BridgeCommand

    public data class Tail(public val since: Long) : BridgeCommand

    public data object Clear : BridgeCommand

    public companion object {
        public const val MAX_LINE_LENGTH: Int = 4096
        public const val DEFAULT_LIMIT: Int = 100
        public const val MAX_LIMIT: Int = 10_000

        /** Parses one NDJSON request line. Malformed input yields [BridgeParseResult.Invalid]. */
        public fun parse(line: String): BridgeParseResult {
            val fields = parseFlatJsonObject(line, MAX_LINE_LENGTH)
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
                "get" -> {
                    if (!fields.containsKey("id")) return BridgeParseResult.Invalid("missing \"id\"")
                    Get(nonNegative(fields, "id", 0)?.takeIf { it > 0 } ?: return invalid("id"))
                }
                "tail" -> Tail(nonNegative(fields, "since", 0) ?: return invalid("since"))
                "clear" -> Clear
                else -> return BridgeParseResult.Invalid("unknown cmd")
            }
            return BridgeParseResult.Ok(command)
        }

        private fun invalid(field: String): BridgeParseResult = BridgeParseResult.Invalid("invalid \"$field\"")

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
