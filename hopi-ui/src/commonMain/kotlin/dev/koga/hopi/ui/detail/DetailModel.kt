package dev.koga.hopi.ui.detail

import dev.koga.hopi.core.body.formatBytes
import dev.koga.hopi.core.body.isJson
import dev.koga.hopi.core.body.prettyPrintJsonTo
import dev.koga.hopi.core.body.textOrNull
import dev.koga.hopi.core.curl.CURL_BODY_CUT_WARNING
import dev.koga.hopi.core.curl.toCurl
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionSource
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.ui.format.PieceWriter
import dev.koga.hopi.ui.format.TextPieces
import dev.koga.hopi.ui.format.cutForDisplay
import dev.koga.hopi.ui.format.formatDuration
import dev.koga.hopi.ui.format.sourceLabel
import dev.koga.hopi.ui.format.hexDump
import dev.koga.hopi.ui.format.reasonPhrase
import dev.koga.hopi.ui.format.splitText
import dev.koga.hopi.ui.platform.MAX_SHARE_CHARS
import dev.koga.hopi.ui.platform.formatTimeOfDay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How a body is shown. Every string is final: composing it formats nothing. */
internal sealed interface BodyView {
    /** A line under the body's title: size, content type. */
    val summary: String?

    /** Shown above the content when the body is not what the app saw in full. */
    val notice: String?

    /** No body at all (a GET, a 204, a failure before the response). */
    class None(val message: String) : BodyView {
        override val summary: String? get() = null
        override val notice: String? get() = null
    }

    /** Recorded without its bytes ([CapturedBody.skippedReason]). */
    class Skipped(override val summary: String?, val reason: String) : BodyView {
        override val notice: String get() = "Not captured: $reason"
    }

    class Text(
        override val summary: String,
        override val notice: String?,
        /**
         * The body (pretty-printed when JSON) in small pieces for a lazy list. The pieces are the
         * only copy kept: copy rebuilds the text from them ([TextPieces.join]) off the main thread.
         */
        val pieces: TextPieces,
        val isJson: Boolean,
    ) : BodyView {
        val chunks: List<String> get() = pieces.pieces
    }

    class Binary(override val summary: String, override val notice: String?, val hexPreview: String, val previewBytes: Int) :
        BodyView
}

/**
 * One request or response's displayed content. [loading] while a host viewer has only the
 * summary ([CapturedBody.isNotFetched]): its headers and body are still on the app's side.
 * [gone] when the app no longer had them once asked ([CapturedBody.isGone]): they never arrive.
 */
internal class ExchangeView(
    val headers: List<Pair<String, String>>,
    val body: BodyView,
    val loading: Boolean = false,
    val gone: Boolean = false,
)

/** [opens]: the transaction the value links to ("Replay of #42"), or null. */
internal class OverviewField(val label: String, val value: String, val opens: Long? = null)

/** The detail screen's content for one transaction snapshot. */
internal class DetailModel(
    val transaction: HttpTransaction,
    /** The URL after the store's redaction: what the screen shows and copies. */
    val url: String,
    val title: String,
    val overview: List<OverviewField>,
    val request: ExchangeView,
    val response: ExchangeView,
) {
    /** A summary only (host viewer): headers and bodies are being fetched, so cURL would lack them. */
    val loading: Boolean get() = request.loading || response.loading

    /** A summary whose headers and bodies the app no longer has: cURL would lack them for good. */
    val gone: Boolean get() = request.gone || response.gone
}

/**
 * Builds [DetailModel]s and caches the expensive part, the body views (decode, JSON pretty-print,
 * chunking, hex), by body instance: a body never changes after capture, and a transaction that
 * completes keeps its request body instance, so its view is reused. At most [capacity] bodies and
 * [maxChars] characters of formatted text are kept, least recently used dropped first; the view
 * just formatted is always kept, even when it alone is larger. Callers run [model] off the main
 * thread; the cache is safe to share between coroutines.
 */
internal class DetailFormatter(private val capacity: Int = 16, private val maxChars: Int = DEFAULT_MAX_CHARS) {
    private val mutex = Mutex()
    private val bodies = ArrayList<Pair<CapturedBody, BodyView>>(capacity)
    private var chars = 0

    /** Body views formatted since creation; tests read it to prove reuse. */
    var formatted: Int = 0
        private set

    suspend fun model(transaction: HttpTransaction, redactor: Redactor): DetailModel {
        val url = redactor.url(transaction.url)
        val requestBody = bodyView(transaction.requestBody, side = "request")
        val responseBody = when (transaction.state) {
            TransactionState.Pending -> BodyView.None("Waiting for the response")
            TransactionState.Failed -> BodyView.None("No response: the request failed")
            TransactionState.Complete -> bodyView(transaction.responseBody, side = "response")
        }
        return DetailModel(
            transaction = transaction,
            url = url,
            title = "${transaction.method} ${statusText(transaction)}",
            overview = overview(transaction, url, redactor),
            request = ExchangeView(
                shownHeaders(redactor.headers(transaction.requestHeaders)),
                requestBody,
                loading = transaction.requestBody?.isNotFetched == true,
                gone = transaction.requestBody?.isGone == true,
            ),
            response = ExchangeView(
                shownHeaders(redactor.headers(transaction.responseHeaders)),
                responseBody,
                loading = transaction.responseBody?.isNotFetched == true,
                gone = transaction.responseBody?.isGone == true,
            ),
        )
    }

    /** [side] is `request` or `response`, for the messages of a missing or empty body. */
    suspend fun bodyView(body: CapturedBody?, side: String): BodyView {
        if (body == null) return BodyView.None("No $side body")
        mutex.withLock {
            val index = bodies.indexOfFirst { it.first === body }
            if (index >= 0) {
                val hit = bodies.removeAt(index)
                bodies += hit
                return hit.second
            }
        }
        val view = formatBody(body, side)
        mutex.withLock {
            formatted++
            if (bodies.none { it.first === body }) {
                val size = view.chars()
                while (bodies.isNotEmpty() && (bodies.size >= capacity || chars + size > maxChars)) {
                    chars -= bodies.removeAt(0).second.chars()
                }
                bodies += body to view
                chars += size
            }
        }
        return view
    }

    /** Characters the cached views hold; tests read it. */
    val cachedChars: Int get() = chars

    private companion object {
        /** 4M UTF-16 units: at most 8 MB (4 MB for ASCII text on ART and the JVM) while the screen is open. */
        const val DEFAULT_MAX_CHARS: Int = 4 * 1024 * 1024
    }
}

private fun statusText(transaction: HttpTransaction): String = when (transaction.state) {
    TransactionState.Pending -> "pending"
    TransactionState.Failed -> "failed"
    TransactionState.Complete -> transaction.responseCode.toString()
}

private fun overview(transaction: HttpTransaction, url: String, redactor: Redactor): List<OverviewField> {
    val fields = ArrayList<OverviewField>(10)
    fields += OverviewField("URL", cutForDisplay(url))
    transaction.finalUrl?.let { fields += OverviewField("Final URL", cutForDisplay(redactor.url(it))) }
    fields += OverviewField("Method", transaction.method)
    fields += OverviewField(
        "Status",
        when (transaction.state) {
            TransactionState.Pending -> "Pending"
            // The response arrived, then the exchange failed (a dropped connection, a cancel mid-body).
            TransactionState.Failed -> transaction.responseCode?.let { "Failed after ${statusLine(it)}" } ?: "Failed"
            TransactionState.Complete -> statusLine(transaction.responseCode ?: 0)
        },
    )
    transaction.error?.let { fields += OverviewField("Error", cutForDisplay(redactor.textMentioning(it, transaction.url))) }
    fields += OverviewField("Started", formatTimeOfDay(transaction.startedAtEpochMs, withMillis = true))
    // Platform capture times to the network's end; the Ktor plugin to the reader's.
    val networkOnly = transaction.source != null && transaction.source != TransactionSource.KTOR
    fields += OverviewField(
        "Duration",
        transaction.durationMs?.let { formatDuration(it) + if (networkOnly) " (network)" else "" } ?: "—",
    )
    transaction.protocol?.let { fields += OverviewField("Protocol", it) }
    transaction.source?.let { fields += OverviewField("Source", sourceLabel(it)) }
    transaction.ruleId?.let { id ->
        fields += OverviewField("Rule", "#$id" + (transaction.ruleApplied?.let { ": " + it.replace(",", ", ") } ?: ""))
    }
    transaction.replayOf?.let { fields += OverviewField("Replay of", "#$it", opens = it) }
    fields += OverviewField("Request size", sizeText(transaction.requestBody))
    if (transaction.state == TransactionState.Complete) {
        fields += OverviewField("Response size", sizeText(transaction.responseBody))
    } else if (transaction.state == TransactionState.Failed && transaction.responseBody != null) {
        fields += OverviewField("Response received", sizeText(transaction.responseBody) + " (partial)")
    }
    return fields
}

private fun statusLine(code: Int): String = reasonPhrase(code)?.let { "$code $it" } ?: code.toString()

/** Header values as shown: each one text layout, so a giant cookie or token is cut ([cutForDisplay]). */
private fun shownHeaders(headers: List<Pair<String, String>>): List<Pair<String, String>> =
    if (headers.none { it.second.length > MAX_SHOWN_VALUE_CHARS }) {
        headers
    } else {
        headers.map { (name, value) -> name to cutForDisplay(value, MAX_SHOWN_VALUE_CHARS) }
    }

private const val MAX_SHOWN_VALUE_CHARS = 4_096

private fun sizeText(body: CapturedBody?): String = when {
    body == null -> "—"
    body.totalBytes == null && body.skippedReason != null -> "unknown"
    else -> formatBytes(body.reportedBytes)
}

private fun formatBody(body: CapturedBody, side: String): BodyView {
    val type = body.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
    val total = body.totalBytes
    val size = when {
        total != null -> formatBytes(total)
        body.skippedReason != null -> null
        else -> formatBytes(body.bytes.size.toLong())
    }
    val summary = listOfNotNull(size, type).joinToString(" · ").ifEmpty { null }
    // Not "not captured": the bytes exist on the app's side and are on their way.
    if (body.isNotFetched) return BodyView.None(LOADING_BODY)
    if (body.isGone) return BodyView.None(GONE_BODY)
    body.skippedReason?.let { return BodyView.Skipped(summary, it) }
    if (body.bytes.isEmpty()) return BodyView.None(if (body.truncated) "Nothing captured" else "Empty $side body")
    val notice = if (body.truncated) {
        "Truncated: showing the first ${formatBytes(body.bytes.size.toLong())}" +
            (body.totalBytes?.let { " of ${formatBytes(it)}" } ?: "")
    } else {
        null
    }
    val text = body.textOrNull()
    if (text == null) {
        val preview = minOf(body.bytes.size, HEX_PREVIEW_BYTES)
        return BodyView.Binary(summary ?: "binary", notice, hexDump(body.bytes, preview), preview)
    }
    val json = isJson(body.contentType, text)
    // Printed straight into its pieces: no whole pretty string, nor the builder that grows to hold
    // it (2 bytes per char on ART and Kotlin/Native), is ever built next to the text. A truncated
    // body is almost never valid JSON, so it is tried only when it could be (it ends a container).
    val tryPretty = json && (!body.truncated || endsAContainer(text))
    val pieces = (if (tryPretty) prettyPieces(text) else null) ?: splitText(text)
    return BodyView.Text(summary ?: formatBytes(body.bytes.size.toLong()), notice, pieces, json)
}

private fun endsAContainer(text: String): Boolean {
    for (i in text.indices.reversed()) {
        val c = text[i]
        if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue
        return c == '}' || c == ']'
    }
    return false
}

private fun prettyPieces(text: String): TextPieces? {
    val writer = PieceWriter()
    return if (prettyPrintJsonTo(text, writer)) writer.finish() else null
}

/** Characters a cached view holds, for the cache's character budget. */
private fun BodyView.chars(): Int = when (this) {
    is BodyView.Text -> pieces.length
    is BodyView.Binary -> hexPreview.length
    is BodyView.None, is BodyView.Skipped -> 0
}

private const val HEX_PREVIEW_BYTES = 512

internal const val LOADING_BODY = "Loading body…"

internal const val GONE_BODY = "Gone from the app: it evicted or cleared this request before the body was opened"

/**
 * The request as cURL with the store's redaction (core's export), at most [maxChars] long: a body
 * that does not fit is cut inside the command, with a warning line, so the command stays valid
 * shell and a giant body is never quoted whole only to be cut afterwards.
 */
internal fun curlOf(transaction: HttpTransaction, redactor: Redactor, maxChars: Int = MAX_SHARE_CHARS): String =
    toCurl(transaction, redactor, maxChars)

/** True when [curlOf] had to cut the request body to fit. */
internal fun curlBodyWasCut(curl: String): Boolean = curl.startsWith(CURL_BODY_CUT_WARNING) || "\n$CURL_BODY_CUT_WARNING" in curl

/** What the share sheet receives, within [MAX_SHARE_CHARS]: a summary line, the URL, then the cURL command. */
internal fun shareTextOf(model: DetailModel, redactor: Redactor): String {
    val head = buildString {
        append(model.transaction.method).append(' ').append(model.url).append('\n')
        model.overview.firstOrNull { it.label == "Status" }?.let { append("Status: ").append(it.value).append('\n') }
        model.overview.firstOrNull { it.label == "Duration" }?.let { append("Duration: ").append(it.value).append('\n') }
        append('\n')
    }
    val curl = curlOf(model.transaction, redactor, maxChars = (MAX_SHARE_CHARS - head.length).coerceAtLeast(1))
    return StringBuilder(head.length + curl.length).append(head).append(curl).toString()
}
