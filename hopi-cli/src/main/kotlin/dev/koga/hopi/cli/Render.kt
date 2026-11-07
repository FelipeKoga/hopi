package dev.koga.hopi.cli

import dev.koga.hopi.connect.RemoteRule
import dev.koga.hopi.connect.bool
import dev.koga.hopi.connect.long
import dev.koga.hopi.connect.notReachable
import dev.koga.hopi.connect.str
import dev.koga.hopi.connect.visible
import dev.koga.hopi.core.body.formatBytes
import dev.koga.hopi.core.body.isJson
import dev.koga.hopi.core.body.prettyPrintJson
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Human output. `--json` never goes through here: it prints the bridge's lines as received. */
internal object Render {
    val SUMMARY_HEADER: List<String> = listOf("ID", "METHOD", "CODE", "TIME", "SIZE", "URL")

    /** Columns aligned to the right; the others, and always the last, to the left. */
    private val RIGHT_ALIGNED = setOf(0, 3, 4)

    /** `tail` cannot measure rows it has not seen, so it pads to these widths instead. */
    private val TAIL_WIDTHS = intArrayOf(5, 7, 4, 8, 9)

    fun summaryCells(summary: JsonObject): List<String> {
        val state = summary.str("state")
        val code = when {
            state == "failed" -> "ERR"
            else -> summary.long("code")?.toString() ?: if (state == "pending") "..." else "-"
        }
        val time = summary.long("ms")?.let { "$it ms" } ?: "-"
        val size = if (state == "pending") "-" else formatBytes(summary.long("resBytes") ?: 0)
        val url = buildString {
            append(visible(summary.str("url").orEmpty()))
            if (summary.bool("urlTruncated") == true) append('…')
            summary.str("finalUrl")?.let { final ->
                append("  -> ").append(visible(final))
                if (summary.bool("finalUrlTruncated") == true) append('…')
            }
            // The Ktor plugin's records (and an older app's, without the field) carry no tag.
            summary.str("source")?.takeIf { it != "ktor" }?.let { append("  [").append(visible(it)).append(']') }
            ruleMark(summary)?.let { append("  [").append(it).append(']') }
            if (state == "failed") {
                append("  (")
                // A failure after the response arrived keeps its status; the code column still says ERR.
                summary.long("code")?.let { append("HTTP ").append(it).append(", then ") }
                append(visible(summary.str("error") ?: "failed"))
                if (summary.bool("errorTruncated") == true) append('…')
                append(')')
            }
        }
        return listOf(summary.long("id")?.toString() ?: "?", visible(summary.str("method").orEmpty()), code, time, size, url)
    }

    fun table(rows: List<List<String>>): List<String> {
        val widths = IntArray(SUMMARY_HEADER.size - 1) { column -> rows.maxOf { it[column].length } }
        return rows.map { row(it, widths) }
    }

    fun tailRow(cells: List<String>): String = row(cells, TAIL_WIDTHS)

    private fun row(cells: List<String>, widths: IntArray): String = buildString {
        cells.forEachIndexed { column, cell ->
            if (column == cells.lastIndex) {
                append(cell)
                return@forEachIndexed
            }
            val pad = widths[column] - cell.length
            if (column in RIGHT_ALIGNED) repeat(pad) { append(' ') }
            append(cell)
            if (column !in RIGHT_ALIGNED) repeat(pad) { append(' ') }
            append("  ")
        }
    }.trimEnd()

    /** `mock #3`, `delay,mock #3`: what a rule did to the record, by rule id; null when no rule applied. */
    private fun ruleMark(summary: JsonObject): String? {
        val id = summary.long("rule") ?: return null
        return (summary.str("applied")?.let { visible(it) } ?: "rule") + " #" + id
    }

    /** One rule, as `add`, `enable` and `disable` print it: id, match, action, then the limit and state. */
    fun ruleLine(rule: RemoteRule): String = buildString {
        append('#').append(rule.id).append("  ").append(visible(rule.match)).append("  ").append(visible(rule.action))
        rule.times?.let { append("  times ").append(it) }
        if (!rule.enabled) append("  (disabled)")
        if (rule.exhausted) append("  (exhausted)")
        rule.label?.let { append("  \"").append(visible(it)).append('"') }
    }

    /** `rules ls`: `ID ON PRI HITS MATCH ACTION`, hits as `n/times` when the rule has a limit. */
    fun rulesTable(rules: List<RemoteRule>): List<String> {
        val header = listOf("ID", "ON", "PRI", "HITS", "MATCH", "ACTION")
        val rows = rules.map { rule ->
            listOf(
                rule.id.toString(),
                if (rule.exhausted) "done" else if (rule.enabled) "yes" else "no",
                rule.priority.toString(),
                rule.times?.let { "${rule.hits}/$it" } ?: rule.hits.toString(),
                visible(rule.match),
                visible(rule.action) + (rule.label?.let { "  \"${visible(it)}\"" } ?: ""),
            )
        }
        val all = listOf(header) + rows
        val widths = IntArray(header.size - 1) { column -> all.maxOf { it[column].length } }
        return all.map { cells ->
            buildString {
                cells.forEachIndexed { column, cell ->
                    if (column == cells.lastIndex) {
                        append(cell)
                    } else {
                        val pad = widths[column] - cell.length
                        if (column == 0 || column == 2 || column == 3) repeat(pad) { append(' ') }
                        append(cell)
                        if (!(column == 0 || column == 2 || column == 3)) repeat(pad) { append(' ') }
                        append("  ")
                    }
                }
            }.trimEnd()
        }
    }

    fun droppedNotice(dropped: Long): String =
        "! the app's store has dropped $dropped transactions so far (it could not keep up); they stay pending here"

    /** `hopi apps`: app, platform, device (or bridge file), device name. */
    fun apps(rows: List<List<String>>): List<String> {
        val all = listOf(listOf("APP", "PLATFORM", "DEVICE", "NAME")) + rows
        val widths = IntArray(3) { column -> all.maxOf { visible(it[column]).length } }
        return all.map { cells ->
            cells.mapIndexed { i, cell -> if (i < 3) visible(cell).padEnd(widths[i]) else visible(cell) }.joinToString("  ").trimEnd()
        }
    }

    fun devices(rows: List<List<String>>): List<String> {
        val all = listOf(listOf("PLATFORM", "ID", "STATE", "NAME")) + rows
        val widths = IntArray(3) { column -> all.maxOf { it[column].length } }
        return all.map { cells ->
            buildString {
                cells.forEachIndexed { column, cell ->
                    append(cell)
                    if (column < 3) repeat(widths[column] - cell.length + 2) { append(' ') }
                }
            }.trimEnd()
        }
    }

    /** The `get` detail: an overview block, then request and response headers and bodies. */
    fun detail(detail: JsonObject): String = buildString {
        appendLine("${visible(detail.str("method").orEmpty())} ${visible(detail.str("url").orEmpty())}")
        val overview = buildList {
            detail.str("finalUrl")?.let { add("Final URL" to visible(it)) }
            add("ID" to (detail.long("id")?.toString() ?: "?"))
            add("State" to visible(detail.str("state").orEmpty()))
            add("Code" to (detail.long("code")?.toString() ?: "-"))
            add("Duration" to (detail.long("ms")?.let { "$it ms" } ?: "-"))
            add("Protocol" to visible(detail.str("protocol") ?: "-"))
            detail.str("source")?.let { add("Source" to visible(it)) }
            detail.long("rule")?.let { id ->
                add("Rule" to "#$id" + (detail.str("applied")?.let { ": " + visible(it).replace(",", ", ") } ?: ""))
            }
            detail.long("startedAt")?.let { add("Started" to Instant.ofEpochMilli(it).toString()) }
            detail.str("error")?.let { add("Error" to visible(it)) }
        }
        val width = overview.maxOf { it.first.length }
        overview.forEach { (key, value) -> appendLine("  ${key.padEnd(width)}  $value") }
        exchange("Request", detail["request"] as? JsonObject)
        // A failed exchange's response is what arrived before the failure.
        exchange(if (detail.str("state") == "failed") "Response (partial)" else "Response", detail["response"] as? JsonObject)
    }.trimEnd()

    private fun StringBuilder.exchange(title: String, exchange: JsonObject?) {
        appendLine()
        appendLine("$title headers")
        val headers = headerPairs(exchange)
        if (headers.isEmpty()) appendLine("  (none)")
        headers.forEach { (name, value) -> appendLine("  ${visible(name)}: ${visible(value)}") }
        appendLine()
        val body = exchange?.get("body") as? JsonObject
        if (body == null) {
            appendLine("$title body: none")
            return
        }
        val contentType = body.str("contentType")
        val captured = body.long("capturedBytes") ?: 0
        val total = body.long("totalBytes")
        val facts = buildList {
            contentType?.let { add(visible(it)) }
            add(
                if (body.bool("truncated") == true) {
                    "truncated: ${formatBytes(captured)} of ${total?.let(::formatBytes) ?: "unknown"} captured"
                } else {
                    formatBytes(total ?: captured)
                },
            )
        }.joinToString(", ")
        val skipped = body.str("skippedReason")
        when {
            skipped != null -> appendLine("$title body: not captured (${visible(skipped)}); $facts")
            body.str("encoding") == "utf8" -> {
                val text = body.str("text").orEmpty()
                appendLine("$title body ($facts)")
                // After pretty-printing, so whatever it decodes is escaped as well.
                appendLine(visible(if (isJson(contentType, text)) prettyPrintJson(text) else text, multiline = true))
            }
            else -> appendLine("$title body: binary, ${formatBytes(captured)} captured ($facts); --json has it as base64")
        }
    }

    private fun headerPairs(exchange: JsonObject?): List<Pair<String, String>> =
        (exchange?.get("headers") as? JsonArray).orEmpty().mapNotNull { element ->
            val pair = element as? JsonArray ?: return@mapNotNull null
            val name = (pair.getOrNull(0) as? JsonPrimitive)?.content ?: return@mapNotNull null
            val value = (pair.getOrNull(1) as? JsonPrimitive)?.content ?: return@mapNotNull null
            name to value
        }

    /**
     * The request side of a bridge `get` reply as a transaction, so core's `toCurl` renders it. The
     * bridge has already redacted it with the app's redactor.
     */
    fun requestOf(detail: JsonObject): HttpTransaction {
        val request = detail["request"] as? JsonObject ?: throw malformed()
        return HttpTransaction.pending(
            id = detail.long("id") ?: throw malformed(),
            startedAtEpochMs = detail.long("startedAt") ?: 0,
            method = detail.str("method") ?: throw malformed(),
            url = detail.str("url") ?: throw malformed(),
            requestHeaders = headerPairs(request),
            requestBody = (request["body"] as? JsonObject)?.let(::capturedBody),
        )
    }

    fun capturedBody(body: JsonObject): CapturedBody {
        val bytes = when (body.str("encoding")) {
            "utf8" -> body.str("text")?.encodeToByteArray()
            "base64" -> body.str("data")?.let {
                try {
                    Base64.getDecoder().decode(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            else -> null
        } ?: throw malformed()
        return CapturedBody(
            bytes = bytes,
            totalBytes = body.long("totalBytes"),
            truncated = body.bool("truncated") == true,
            contentType = body.str("contentType"),
            skippedReason = body.str("skippedReason"),
        )
    }

    private fun malformed() = notReachable("the bridge sent a malformed transaction")
}
