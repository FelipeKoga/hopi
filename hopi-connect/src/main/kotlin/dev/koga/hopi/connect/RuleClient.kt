package dev.koga.hopi.connect

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.bridge.BridgeCommand
import dev.koga.hopi.core.json.JsonWriter
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The rule commands over one [BridgeClient] (docs/design/rules.md, "Bridge"). Every method needs a
 * client whose [BridgeClient.handshake] ran with no pipelined request and verified `hello` (the
 * app, the platform, on Android the proof), and checks `hello.rules` first: a write line is never
 * sent before that, so a squatter holding the socket name receives `hello` and nothing else.
 */
@HopiInternalApi
public class RuleClient(private val client: BridgeClient) {
    init {
        if (client.hello.json.bool("rules") != true) {
            throw CliException(ExitCode.UNSUPPORTED, "this app's Hopi does not support rules (older Hopi, or rules = false)")
        }
    }

    private var nextBlob = 1L

    // Chars of the bodies [body] inlined since the last line was sent: they share one line's budget.
    private var inlinedChars = 0

    /** `rules.add` with [rule], a JSON object; returns the `rule` reply. */
    public fun add(rule: String): Reply = request(BridgeClient.request("rules.add").name("rule").rawValue(rule).endObject().toString(), "rule")

    /** Every rule, in the order the app added them. */
    public fun list(): List<Reply> {
        client.send(BridgeClient.request("rules.list").endObject().toString())
        val rules = ArrayList<Reply>()
        while (true) {
            val reply = client.reply()
            when (reply.type) {
                "end" -> return rules
                "rule" -> rules += reply
                else -> throw unexpected(reply)
            }
        }
    }

    /** `rules.update`; [clearTimes] sends `"times":null`. Returns the `rule` reply. */
    public fun update(
        id: Long,
        enabled: Boolean? = null,
        priority: Int? = null,
        times: Int? = null,
        clearTimes: Boolean = false,
        resetHits: Boolean = false,
    ): Reply {
        val w = BridgeClient.request("rules.update").field("id", id)
        enabled?.let { w.field("enabled", it) }
        priority?.let { w.field("priority", it) }
        if (clearTimes) w.name("times").nullValue() else times?.let { w.field("times", it) }
        if (resetHits) w.field("resetHits", true)
        return request(w.endObject().toString(), "rule")
    }

    /** `rules.remove`; a missing rule is a [CliException] with [ExitCode.NOT_FOUND]. */
    public fun remove(id: Long) {
        request(BridgeClient.request("rules.remove").field("id", id).endObject().toString(), "ok")
    }

    /** `rules.clear`; returns how many rules the app removed. */
    public fun clear(): Long = request(BridgeClient.request("rules.clear").endObject().toString(), "ok").json.long("removed") ?: 0

    /**
     * A body object for a rule: inline (`text` when [bytes] are UTF-8, else `base64`)
     * while the bodies inlined for the next line stay within [MAX_INLINE_BODY_CHARS] together,
     * leaving room for the rest of the line (a rule can carry a request and a response body), else
     * uploaded first as a blob of this session and named by `{"blob":n,"size":N}`.
     */
    public fun body(bytes: ByteArray): String {
        val text = bytes.decodeUtf8OrNull()
        val inline = if (text != null) {
            JsonWriter().beginObject().field("text", text).endObject().toString()
        } else {
            JsonWriter().beginObject().field("base64", Base64.getEncoder().encodeToString(bytes)).endObject().toString()
        }
        if (inlinedChars + inline.length <= MAX_INLINE_BODY_CHARS) {
            inlinedChars += inline.length
            return inline
        }
        val blob = nextBlob++
        uploadBlob(blob, bytes)
        return JsonWriter().beginObject().field("blob", blob).field("size", bytes.size).endObject().toString()
    }

    /**
     * Uploads [bytes] as blob [blob] in `blob.put` lines of at most [BridgeCommand.MAX_BLOB_CHUNK_CHARS]
     * base64 chars, pipelined in windows of [BLOB_WINDOW] (half the app's reply queue) with their
     * acknowledgements read between windows, so neither side can block the other.
     */
    public fun uploadBlob(blob: Long, bytes: ByteArray) {
        val chunk = BridgeCommand.MAX_BLOB_CHUNK_CHARS / 4 * 3
        var offset = 0
        // An empty blob is one empty slice, so the app knows it.
        val slices = if (bytes.isEmpty()) listOf(0 to 0) else (bytes.indices step chunk).map { it to minOf(chunk, bytes.size - it) }
        for (window in slices.chunked(BLOB_WINDOW)) {
            val lines = StringBuilder()
            for ((start, length) in window) {
                lines.append(
                    BridgeClient.request("blob.put").field("blob", blob).field("offset", start.toLong())
                        .field("data", Base64.getEncoder().encodeToString(bytes.copyOfRange(start, start + length))).endObject().toString(),
                ).append('\n')
            }
            client.sendLines(lines)
            for ((start, length) in window) {
                val reply = client.reply()
                if (reply.type != "ok") throw unexpected(reply)
                offset = start + length
                if (reply.json.long("size") != offset.toLong()) throw notReachable("the bridge acknowledged blob $blob at ${reply.json.long("size")} bytes, not $offset")
            }
        }
    }

    private fun request(line: String, type: String): Reply {
        inlinedChars = 0
        client.send(line)
        val reply = client.reply()
        if (reply.type != type) throw unexpected(reply)
        return reply
    }

    private fun unexpected(reply: Reply) = notReachable("unexpected reply from the bridge: ${reply.type?.let(::visible)}")

    public companion object {
        /** `blob.put` lines sent before reading their acknowledgements. */
        public const val BLOB_WINDOW: Int = 32

        /** The inline body objects of one line together stay within this; the rest of the 4096-char line is the rule's. */
        public const val MAX_INLINE_BODY_CHARS: Int = 2048

        private fun ByteArray.decodeUtf8OrNull(): String? = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(this)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }
}

/** A `{"type":"rule",…}` line, decoded for display (the CLI's lists, the desktop panel). */
@HopiInternalApi
public class RemoteRule(
    public val id: Long,
    public val label: String?,
    public val enabled: Boolean,
    public val priority: Int,
    public val times: Long?,
    public val hits: Long,
    public val exhausted: Boolean,
    /** The match in shorthand form, as the app wrote it (header values redacted). */
    public val match: String,
    /** The sources the match names (`okhttp`, `urlsession`); null when it names none and the app's platform decides. */
    public val sources: Set<String>?,
    /** What the rule does, as text: `mock 500 (61 B)`, `fail timeout`, `edit request+response`, with the delay. */
    public val action: String,
    /** Every source that cannot apply all or part of the rule, as the app's engine listed them. */
    public val unsupported: List<RemoteUnsupported>,
) {
    /**
     * True when every source the match names excludes the rule ([RemoteUnsupported.applies] false):
     * it applies to nothing in the app. Never for a rule that names no sources: the line does not
     * say which sources the app's platform has (and Ktor, on every platform, excludes nothing).
     */
    public val inactive: Boolean
        get() = !sources.isNullOrEmpty() && sources.all { source -> unsupported.any { it.source == source && !it.applies } }

    public companion object {
        /** Null when [json] is not a well-formed rule line. */
        public fun decode(json: JsonObject): RemoteRule? {
            if (json.str("type") != "rule") return null
            val match = json["match"] as? JsonObject ?: return null
            return RemoteRule(
                id = json.long("id") ?: return null,
                label = json.str("label"),
                enabled = json.bool("enabled") ?: return null,
                priority = json.long("priority")?.toInt() ?: 0,
                times = json.long("times"),
                hits = json.long("hits") ?: 0,
                exhausted = json.bool("exhausted") == true,
                match = match.str("shorthand") ?: "?",
                sources = (match["sources"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.toSet(),
                action = action(json),
                unsupported = (json["unsupported"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val entry = element as? JsonObject ?: return@mapNotNull null
                    // Without `applies`, a stated difference: never an exclusion the app did not report.
                    RemoteUnsupported(entry.str("source") ?: "?", entry.str("reason").orEmpty(), applies = entry.bool("applies") != false)
                },
            )
        }

        private fun action(json: JsonObject): String = buildList {
            (json["mock"] as? JsonObject)?.let { mock ->
                val bytes = (mock["body"] as? JsonObject)?.long("bytes")
                add("mock ${mock.long("status") ?: "?"}" + (bytes?.let { " ($it B)" } ?: ""))
            }
            json.str("fail")?.let { add("fail $it") }
            (json["edit"] as? JsonObject)?.let { edit ->
                add("edit " + listOfNotNull(edit["request"]?.let { "request" }, edit["response"]?.let { "response" }).joinToString("+"))
            }
            (json["delay"] as? JsonObject)?.let { delay ->
                val parts = listOfNotNull(
                    delay.long("beforeRequestMs")?.takeIf { it > 0 }?.let { "${it}ms before request" },
                    delay.long("beforeResponseMs")?.takeIf { it > 0 }?.let { "${it}ms before response" },
                )
                if (parts.isNotEmpty()) add("delay " + parts.joinToString(", "))
            }
        }.joinToString(", ").ifEmpty { "?" }
    }
}

/**
 * One entry of a rule line's `unsupported` list: [source] (`urlsession`) never gets the rule
 * ([applies] false, an exclusion), or applies it with the difference [reason] states.
 */
@HopiInternalApi
public class RemoteUnsupported(public val source: String, public val reason: String, public val applies: Boolean) {
    /** `source: reason`, as the CLI warns it. */
    override fun toString(): String = "$source: $reason"
}
