package dev.koga.hopi.cli

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

/**
 * [text] safe to write to a terminal: C0 controls, DEL and C1 controls become `\uXXXX`, so bytes a
 * server put in a body, header or error cannot move the cursor, rewrite lines or reach OSC
 * sequences (clipboard writes, titles). [multiline] keeps `\n` and `\t` (bodies); a single-line
 * field escapes them too. Applied to every bridge value human output prints; `--json` prints the
 * bridge's own escaped JSON instead.
 */
internal fun visible(text: String, multiline: Boolean = false): String {
    if (text.none { needsEscape(it, multiline) }) return text
    return buildString(text.length + 16) {
        for (c in text) {
            if (needsEscape(c, multiline)) append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }
}

private fun needsEscape(c: Char, multiline: Boolean): Boolean = when {
    c == '\n' || c == '\t' -> !multiline
    c < ' ' -> true
    else -> c in '\u007f'..'\u009f'
}

/** Human output. `--json` never goes through here: it prints the bridge's lines as received. */
internal object Render {
    val SUMMARY_HEADER: List<String> = listOf("ID", "METHOD", "CODE", "TIME", "SIZE", "URL")

    /** Columns aligned to the right; the others, and always the last, to the left. */
    private val RIGHT_ALIGNED = setOf(0, 3, 4)

    /** `tail` cannot measure rows it has not seen, so it pads to these widths instead. */
    private val TAIL_WIDTHS = intArrayOf(5, 7, 4, 8, 9)

    fun summaryCells(summary: JsonObject): List<String> {
        val state = summary.str("state")
        val code = summary.long("code")?.toString() ?: when (state) {
            "pending" -> "..."
            "failed" -> "ERR"
            else -> "-"
        }
        val time = summary.long("ms")?.let { "$it ms" } ?: "-"
        val size = if (state == "pending") "-" else formatBytes(summary.long("resBytes") ?: 0)
        val url = buildString {
            append(visible(summary.str("url").orEmpty()))
            if (summary.bool("urlTruncated") == true) append('…')
            if (state == "failed") {
                append("  (").append(visible(summary.str("error") ?: "failed"))
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

    fun droppedNotice(dropped: Long): String =
        "! the app's store has dropped $dropped transactions so far (it could not keep up); they stay pending here"

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
            add("ID" to (detail.long("id")?.toString() ?: "?"))
            add("State" to visible(detail.str("state").orEmpty()))
            add("Code" to (detail.long("code")?.toString() ?: "-"))
            add("Duration" to (detail.long("ms")?.let { "$it ms" } ?: "-"))
            add("Protocol" to visible(detail.str("protocol") ?: "-"))
            detail.long("startedAt")?.let { add("Started" to Instant.ofEpochMilli(it).toString()) }
            detail.str("error")?.let { add("Error" to visible(it)) }
        }
        val width = overview.maxOf { it.first.length }
        overview.forEach { (key, value) -> appendLine("  ${key.padEnd(width)}  $value") }
        exchange("Request", detail["request"] as? JsonObject)
        exchange("Response", detail["response"] as? JsonObject)
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

    private fun capturedBody(body: JsonObject): CapturedBody {
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
