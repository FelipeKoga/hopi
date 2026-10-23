package dev.koga.hopi.ui.detail

import dev.koga.hopi.core.body.formatBytes
import dev.koga.hopi.core.body.isJson
import dev.koga.hopi.core.body.prettyPrintJson
import dev.koga.hopi.core.body.textOrNull
import dev.koga.hopi.core.curl.toCurl
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.ui.format.TextPieces
import dev.koga.hopi.ui.format.formatDuration
import dev.koga.hopi.ui.format.hexDump
import dev.koga.hopi.ui.format.reasonPhrase
import dev.koga.hopi.ui.format.splitText
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

/** One request or response's displayed content. */
internal class ExchangeView(val headers: List<Pair<String, String>>, val body: BodyView)

internal class OverviewField(val label: String, val value: String)

/** The detail screen's content for one transaction snapshot. */
internal class DetailModel(
    val transaction: HttpTransaction,
    /** The URL after the store's redaction: what the screen shows and copies. */
    val url: String,
    val title: String,
    val overview: List<OverviewField>,
    val request: ExchangeView,
    val response: ExchangeView,
)

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
            request = ExchangeView(redactor.headers(transaction.requestHeaders), requestBody),
            response = ExchangeView(redactor.headers(transaction.responseHeaders), responseBody),
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
    fields += OverviewField("URL", url)
    fields += OverviewField("Method", transaction.method)
    fields += OverviewField(
        "Status",
        when (transaction.state) {
            TransactionState.Pending -> "Pending"
            TransactionState.Failed -> "Failed"
            TransactionState.Complete -> {
                val code = transaction.responseCode ?: 0
                reasonPhrase(code)?.let { "$code $it" } ?: code.toString()
            }
        },
    )
    transaction.error?.let { fields += OverviewField("Error", redactor.textMentioning(it, transaction.url)) }
    fields += OverviewField("Started", formatTimeOfDay(transaction.startedAtEpochMs, withMillis = true))
    fields += OverviewField("Duration", transaction.durationMs?.let(::formatDuration) ?: "—")
    transaction.protocol?.let { fields += OverviewField("Protocol", it) }
    fields += OverviewField("Request size", sizeText(transaction.requestBody))
    if (transaction.state == TransactionState.Complete) {
        fields += OverviewField("Response size", sizeText(transaction.responseBody))
    }
    return fields
}

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
    val shown = if (json) prettyPrintJson(text) else text
    return BodyView.Text(summary ?: formatBytes(body.bytes.size.toLong()), notice, splitText(shown), json)
}

/** Characters a cached view holds, for the cache's character budget. */
private fun BodyView.chars(): Int = when (this) {
    is BodyView.Text -> pieces.length
    is BodyView.Binary -> hexPreview.length
    is BodyView.None, is BodyView.Skipped -> 0
}

private const val HEX_PREVIEW_BYTES = 512

/** The request as cURL with the store's redaction; core's export, unchanged. */
internal fun curlOf(transaction: HttpTransaction, redactor: Redactor): String = toCurl(transaction, redactor)

/** What the share sheet receives: a summary line, the URL, then the cURL command. */
internal fun shareTextOf(model: DetailModel, redactor: Redactor): String = buildString {
    append(model.transaction.method).append(' ').append(model.url).append('\n')
    model.overview.firstOrNull { it.label == "Status" }?.let { append("Status: ").append(it.value).append('\n') }
    model.overview.firstOrNull { it.label == "Duration" }?.let { append("Duration: ").append(it.value).append('\n') }
    append('\n').append(curlOf(model.transaction, redactor))
}
