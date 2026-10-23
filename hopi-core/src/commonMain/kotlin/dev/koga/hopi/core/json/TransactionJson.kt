package dev.koga.hopi.core.json

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.body.textOrNull
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.redact.Redactor

/** Encoders for the agent channels. Every output is redacted with the given [Redactor]. */
@HopiInternalApi
public object TransactionJson {
    /** Logcat drops the tail of longer entries; by default a summary line never reaches 4000 UTF-8 bytes. */
    public const val MAX_SUMMARY_LINE_BYTES: Int = 3999
    public const val SUMMARY_VERSION: Int = 1
    private const val MAX_METHOD_CHARS = 64
    private const val MAX_ERROR_CHARS = 512

    /**
     * `{"hopi":1,"id":…,…}`: one log-stream line, no bodies, at most [maxBytes] UTF-8 bytes.
     * A URL or error that does not fit is cut at a code point boundary and marked.
     */
    public fun summaryLogLine(
        transaction: HttpTransaction,
        redactor: Redactor,
        maxBytes: Int = MAX_SUMMARY_LINE_BYTES,
    ): String = summary(transaction, redactor, bridge = false, maxBytes)

    /** `{"type":"transaction",…summary…}`: one bridge `list`/`tail` line. */
    public fun bridgeSummary(transaction: HttpTransaction, redactor: Redactor): String =
        summary(transaction, redactor, bridge = true, MAX_SUMMARY_LINE_BYTES)

    /** `{"type":"transaction",…}` with headers and bodies: the bridge `get` reply. */
    public fun bridgeDetail(transaction: HttpTransaction, redactor: Redactor): String {
        val out = StringBuilder(256 + transaction.capturedBytes.toInt() * 4 / 3)
        writeBridgeDetail(transaction, redactor, out, chunkChars = Int.MAX_VALUE) {}
        return out.toString()
    }

    /**
     * [bridgeDetail] written to [out] in slices: [flush] is called whenever [out] holds about
     * [chunkChars] chars of a body (or the headers before it) and may empty it, so a giant body is
     * encoded and sent piece by piece instead of as one string several times its size. The
     * slices, concatenated, are exactly [bridgeDetail]; [flush] is not called for what is left in
     * [out] at the end.
     */
    public fun writeBridgeDetail(
        transaction: HttpTransaction,
        redactor: Redactor,
        out: StringBuilder,
        chunkChars: Int,
        flush: () -> Unit,
    ) {
        val w = JsonWriter(out)
        w.beginObject()
            .field("type", "transaction")
            .field("id", transaction.id)
            .field("startedAt", transaction.startedAtEpochMs)
            .field("method", transaction.method)
            .field("url", redactor.url(transaction.url))
            .field("state", transaction.state.wireName)
            .field("code", transaction.responseCode)
            .field("ms", transaction.durationMs)
            .field("protocol", transaction.protocol)
            .field("error", transaction.error?.let { redactor.textMentioning(it, transaction.url) })
        w.name("request")
        writeExchange(w, transaction.requestHeaders, transaction.requestBody, redactor, chunkChars, flush)
        w.name("response")
        writeExchange(w, transaction.responseHeaders, transaction.responseBody, redactor, chunkChars, flush)
        w.endObject()
    }

    private fun summary(transaction: HttpTransaction, redactor: Redactor, bridge: Boolean, maxBytes: Int): String {
        val url = redactor.url(transaction.url)
        val error = if (transaction.state == TransactionState.Failed) {
            transaction.error?.let { takeCodePoints(redactor.textMentioning(it, transaction.url), MAX_ERROR_CHARS) }
        } else {
            null
        }
        val full = writeSummary(transaction, url, error, bridge, urlTruncated = false, errorTruncated = false)
        if (utf8Length(full) <= maxBytes) return full

        val hasError = !error.isNullOrEmpty()
        val skeleton = writeSummary(transaction, "", error?.let { "" }, bridge, urlTruncated = true, errorTruncated = hasError)
        val available = (maxBytes - utf8Length(skeleton)).coerceAtLeast(0)
        val urlBytes = JsonWriter.escapedUtf8Length(url)
        val errorBytes = error?.let(JsonWriter::escapedUtf8Length) ?: 0
        val errorBudget = when {
            errorBytes <= available / 2 -> errorBytes
            urlBytes <= available / 2 -> available - urlBytes
            else -> available - available / 2
        }
        val cutUrl = url.substring(0, cutToEscapedBytes(url, available - errorBudget))
        val cutError = error?.let { it.substring(0, cutToEscapedBytes(it, errorBudget)) }
        return writeSummary(
            transaction,
            cutUrl,
            cutError,
            bridge,
            urlTruncated = cutUrl.length < url.length,
            errorTruncated = (cutError?.length ?: 0) < (error?.length ?: 0),
        )
    }

    /** Length of the longest prefix of [text] whose escaped UTF-8 form fits [budget], at a code point boundary. */
    private fun cutToEscapedBytes(text: String, budget: Int): Int {
        var used = 0
        var cut = 0
        while (cut < text.length) {
            val size = JsonWriter.escapedCodePointBytes(text, cut)
            if (used + size > budget) break
            used += size
            cut += JsonWriter.codePointChars(text, cut)
        }
        return cut
    }

    /** At most [maxChars] chars of [text], never ending inside a surrogate pair. */
    private fun takeCodePoints(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val end = if (text[maxChars - 1].isHighSurrogate() && text[maxChars].isLowSurrogate()) maxChars - 1 else maxChars
        return text.substring(0, end)
    }

    private fun writeSummary(
        transaction: HttpTransaction,
        url: String,
        error: String?,
        bridge: Boolean,
        urlTruncated: Boolean,
        errorTruncated: Boolean,
    ): String {
        val w = JsonWriter(StringBuilder(160 + url.length))
        w.beginObject()
        if (bridge) w.field("type", "transaction") else w.field("hopi", SUMMARY_VERSION)
        w.field("id", transaction.id)
            .field("method", takeCodePoints(transaction.method, MAX_METHOD_CHARS))
            .field("url", url)
            .field("code", transaction.responseCode)
            .field("ms", transaction.durationMs)
            .field("reqBytes", transaction.requestBody?.reportedBytes ?: 0L)
            .field("resBytes", transaction.responseBody?.reportedBytes ?: 0L)
            .field("state", transaction.state.wireName)
        if (bridge) {
            // What a `get` would transfer, when the app holds less than the size (a truncated or
            // skipped body): a host viewer budgets its fetches on it.
            heldBytes(transaction.requestBody)?.let { w.field("reqHeld", it) }
            heldBytes(transaction.responseBody)?.let { w.field("resHeld", it) }
        }
        if (transaction.state == TransactionState.Failed) {
            w.field("error", error)
        }
        if (urlTruncated) w.field("urlTruncated", true)
        if (errorTruncated) w.field("errorTruncated", true)
        w.endObject()
        return w.toString()
    }

    private fun heldBytes(body: CapturedBody?): Long? = body?.heldBytes?.takeIf { it != body.reportedBytes }

    private fun writeExchange(
        w: JsonWriter,
        headers: List<Pair<String, String>>,
        body: CapturedBody?,
        redactor: Redactor,
        chunkChars: Int,
        flush: () -> Unit,
    ) {
        w.beginObject().name("headers").beginArray()
        for ((name, value) in headers) {
            w.beginArray().value(name).value(redactor.headerValue(name, value)).endArray()
            if (w.out.length >= chunkChars) flush()
        }
        w.endArray().name("body")
        if (body == null) {
            w.nullValue()
        } else {
            w.beginObject()
            val text = body.textOrNull()
            if (text != null) {
                w.field("encoding", "utf8").name("text").chunkedValue(text, chunkChars, flush)
            } else {
                // Base64 grows 3 bytes into 4 chars: slices of about chunkChars chars.
                w.field("encoding", "base64").name("data").chunkedBase64Value(body.bytes, chunkChars / 4 * 3, flush)
            }
            w.field("truncated", body.truncated)
                .field("capturedBytes", body.bytes.size)
                .field("totalBytes", body.totalBytes)
                .field("contentType", body.contentType)
                .field("skippedReason", body.skippedReason)
                .endObject()
        }
        w.endObject()
    }

    /** UTF-8 byte length of [text] without encoding it; unpaired surrogates count as 3 (U+FFFD). */
    public fun utf8Length(text: CharSequence): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }
}
