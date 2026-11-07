package dev.koga.hopi.connect

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.body.textOrNull
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.store.HopiStore
import java.io.Writer
import java.net.URLDecoder
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@HopiInternalApi
public enum class ExportFormat(public val extension: String, public val label: String) {
    Har("har", "HAR"),
    Ndjson("ndjson", "NDJSON"),
}

/**
 * How much body an export may fetch from the app: a transaction whose bodies together exceed
 * [maxTransactionBytes], or would take the export past [maxTotalBytes], is written without them
 * (sizes kept, a comment says why), so exporting a session never pulls every giant body over the
 * bridge at once. Both count the bytes `get` would move (what the app holds,
 * [CapturedBody.heldBytes]), not the bodies' sizes on the wire: a 50 MB download the app
 * truncated to 256 KB costs 256 KB. Bodies fetched before (the ones opened) are always written.
 */
@HopiInternalApi
public class ExportBudget(public val maxTransactionBytes: Long = 32L * 1024 * 1024, public val maxTotalBytes: Long = 256L * 1024 * 1024)

@HopiInternalApi
public class ExportResult(public val transactions: Int, public val fetched: Int, public val withoutBodies: Int) {
    public fun describe(file: Path): String = buildString {
        append("Exported $transactions request").append(if (transactions == 1) "" else "s").append(" to ${file.fileName}")
        if (withoutBodies > 0) append(" ($withoutBodies without bodies: over the export budget or gone from the app)")
    }
}

/**
 * Writes a session as HAR 1.2 or as NDJSON (one bridge `get` line per transaction, exactly what
 * `hopi get --json` prints, so `BridgeDecoder.detail` reads it back), streaming: one
 * transaction's text at a time, giant bodies in slices. Summaries are completed with `get`
 * ([fetch]) within [ExportBudget]; the store's redactor ([Redactor.Default] in the viewer) applies
 * on top of the app's, as it does for copy as cURL.
 */
@HopiInternalApi
public object SessionExport {
    public fun write(
        format: ExportFormat,
        store: HopiStore,
        out: Writer,
        fetch: (Long) -> HttpTransaction?,
        budget: ExportBudget = ExportBudget(),
        creatorVersion: String,
    ): ExportResult = write(format, store.transactions.value, store.redactor, out, fetch, budget, creatorVersion)

    public fun write(
        format: ExportFormat,
        transactions: List<HttpTransaction>,
        redactor: Redactor,
        out: Writer,
        fetch: (Long) -> HttpTransaction?,
        budget: ExportBudget = ExportBudget(),
        creatorVersion: String,
    ): ExportResult {
        val resolver = Resolver(fetch, budget)
        when (format) {
            ExportFormat.Har -> writeHar(transactions, redactor, out, resolver, creatorVersion)
            ExportFormat.Ndjson -> writeNdjson(transactions, redactor, out, resolver)
        }
        out.flush()
        return ExportResult(transactions.size, resolver.fetched, resolver.withoutBodies)
    }

    private fun writeNdjson(transactions: List<HttpTransaction>, redactor: Redactor, out: Writer, resolver: Resolver) {
        val buffer = StringBuilder(SLICE_CHARS + 1024)
        for (transaction in transactions) {
            val resolved = resolver.resolve(transaction)
            TransactionJson.writeBridgeDetail(resolved, redactor, buffer, SLICE_CHARS) { buffer.drainTo(out) }
            buffer.append('\n')
            buffer.drainTo(out)
        }
    }

    private fun writeHar(transactions: List<HttpTransaction>, redactor: Redactor, out: Writer, resolver: Resolver, version: String) {
        val buffer = StringBuilder(SLICE_CHARS + 1024)
        val flush = { buffer.drainTo(out) }
        val w = JsonWriter(buffer)
        w.beginObject().name("log").beginObject()
            .field("version", "1.2")
        w.name("creator").beginObject().field("name", "Hopi").field("version", version).endObject()
        w.name("entries").beginArray()
        for (transaction in transactions) {
            writeEntry(w, resolver.resolve(transaction), redactor, flush)
            flush()
        }
        w.endArray().endObject().endObject()
        buffer.append('\n')
        flush()
    }

    private fun writeEntry(w: JsonWriter, tx: HttpTransaction, redactor: Redactor, flush: () -> Unit) {
        val url = redactor.url(tx.url)
        val time = if (tx.state.isTerminal) (tx.durationMs ?: 0L).coerceAtLeast(0L) else 0L
        val httpVersion = tx.protocol.orEmpty()
        w.beginObject()
            .field("startedDateTime", ISO_MILLIS.format(Instant.ofEpochMilli(tx.startedAtEpochMs)))
            .field("time", time)

        w.name("request").beginObject()
            .field("method", tx.method)
            .field("url", url)
            .field("httpVersion", httpVersion)
        w.name("cookies").beginArray().endArray()
        writeHeaders(w, redactor.headers(tx.requestHeaders))
        w.name("queryString").beginArray()
        for ((name, value) in queryString(url)) w.beginObject().field("name", name).field("value", value).endObject()
        w.endArray()
        val requestBody = tx.requestBody
        if (requestBody != null && (requestBody.bytes.isNotEmpty() || requestBody.skippedReason != null)) {
            w.name("postData").beginObject()
                .field("mimeType", requestBody.contentType ?: header(tx.requestHeaders, "Content-Type").orEmpty())
            writeBodyText(w, requestBody, base64Field = "_encoding", flush)
            bodyComment(requestBody)?.let { w.field("comment", it) }
            w.endObject()
        }
        w.field("headersSize", -1)
            .field("bodySize", requestBody?.reportedBytes ?: 0L)
        w.endObject()

        val responseBody = tx.responseBody
        w.name("response").beginObject()
            .field("status", tx.responseCode ?: 0)
            .field("statusText", "")
            .field("httpVersion", httpVersion)
        w.name("cookies").beginArray().endArray()
        writeHeaders(w, redactor.headers(tx.responseHeaders))
        w.name("content").beginObject()
            .field("size", responseBody?.reportedBytes ?: 0L)
            .field("mimeType", responseBody?.contentType ?: header(tx.responseHeaders, "Content-Type").orEmpty())
        if (responseBody != null) {
            writeBodyText(w, responseBody, base64Field = "encoding", flush)
            bodyComment(responseBody)?.let { w.field("comment", it) }
        }
        w.endObject()
        w.field("redirectURL", header(tx.responseHeaders, "Location")?.let(redactor::url).orEmpty())
            .field("headersSize", -1)
            .field("bodySize", if (tx.state == TransactionState.Complete) responseBody?.reportedBytes ?: 0L else -1L)
        tx.error?.let { w.field("_error", redactor.textMentioning(it, tx.url)) }
        w.endObject()

        w.name("cache").beginObject().endObject()
        // Only the total is measured: it is all `wait`, so the timings add up to `time`.
        w.name("timings").beginObject()
            .field("send", 0)
            .field("wait", time)
            .field("receive", 0)
            .endObject()
        entryComment(tx)?.let { w.field("comment", it) }
        w.field("_hopiId", tx.id)
        // HAR allows custom fields with a leading underscore.
        tx.source?.let { w.field("_source", it) }
        tx.finalUrl?.let { w.field("_finalUrl", redactor.url(it)) }
        // What a Hopi rule did to the request.
        tx.ruleId?.let { w.field("_rule", it) }
        tx.ruleApplied?.let { w.field("_ruleApplied", it) }
        w.endObject()
    }

    private fun writeHeaders(w: JsonWriter, headers: List<Pair<String, String>>) {
        w.name("headers").beginArray()
        for ((name, value) in headers) w.beginObject().field("name", name).field("value", value).endObject()
        w.endArray()
    }

    /** `text` as UTF-8 text, else base64 with [base64Field] = "base64"; nothing for a body without bytes. */
    private fun writeBodyText(w: JsonWriter, body: CapturedBody, base64Field: String, flush: () -> Unit) {
        if (body.bytes.isEmpty()) return
        val text = body.textOrNull()
        w.name("text")
        if (text != null) {
            w.chunkedValue(text, SLICE_CHARS, flush)
        } else {
            w.chunkedBase64Value(body.bytes, SLICE_CHARS, flush)
            w.field(base64Field, "base64")
        }
    }

    private fun bodyComment(body: CapturedBody): String? = when {
        body.isNotFetched -> "body not exported (${body.reportedBytes} bytes): over the export budget, or gone from the app"
        body.isGone -> "body not exported (${body.reportedBytes} bytes): gone from the app (evicted or cleared) before it was opened"
        body.skippedReason != null -> "body not captured: ${body.skippedReason}"
        body.truncated -> "body truncated: ${body.capturedBytes} of ${body.totalBytes ?: "?"} bytes captured"
        else -> null
    }

    private fun entryComment(tx: HttpTransaction): String? = when {
        tx.state == TransactionState.Pending -> "pending when exported: no response yet"
        tx.state == TransactionState.Failed -> "failed: no response"
        tx.requestBody?.isNotFetched == true -> "headers and bodies not exported: over the export budget, or gone from the app"
        tx.requestBody?.isGone == true -> "headers and bodies not exported: gone from the app (evicted or cleared) before they were opened"
        else -> null
    }

    private fun header(headers: List<Pair<String, String>>, name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** The query of [url] as name/value pairs, percent-decoded where it decodes. */
    public fun queryString(url: String): List<Pair<String, String>> {
        val query = url.substringBefore('#').substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map { part ->
            val name = part.substringBefore('=')
            val value = part.substringAfter('=', missingDelimiterValue = "")
            decode(name) to decode(value)
        }
    }

    private fun decode(text: String): String = try {
        URLDecoder.decode(text, Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        text
    }

    private fun StringBuilder.drainTo(out: Writer) {
        if (isEmpty()) return
        out.append(this)
        setLength(0)
    }

    /** Completes summaries with `get` within the budget. */
    private class Resolver(private val fetch: (Long) -> HttpTransaction?, private val budget: ExportBudget) {
        var fetched = 0
        var withoutBodies = 0
        private var fetchedBytes = 0L

        fun resolve(transaction: HttpTransaction): HttpTransaction {
            val request = transaction.requestBody
            val response = transaction.responseBody
            if (request?.isGone == true || response?.isGone == true) {
                withoutBodies++
                return transaction
            }
            if (request?.isNotFetched != true && response?.isNotFetched != true) return transaction
            // What `get` moves, not the sizes on the wire: a truncated or skipped body costs what was kept.
            val size = (request?.heldBytes ?: 0) + (response?.heldBytes ?: 0)
            if (size <= budget.maxTransactionBytes && fetchedBytes + size <= budget.maxTotalBytes) {
                val detail = fetch(transaction.id)
                if (detail != null && detail.requestBody?.isNotFetched != true) {
                    fetched++
                    fetchedBytes += detail.capturedBytes
                    return detail
                }
            }
            withoutBodies++
            return transaction
        }
    }

    private const val SLICE_CHARS = 32 * 1024

    private val ISO_MILLIS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC)
}
