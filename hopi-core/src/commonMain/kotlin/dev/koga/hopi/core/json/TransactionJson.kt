package dev.koga.hopi.core.json

import dev.koga.hopi.api.HopiInternalApi
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
    private const val MAX_SOURCE_CHARS = 13

    /** `delay,edit-request,edit-response`, the longest mark a rule can leave ([dev.koga.hopi.core.rules.RuleMark]). */
    private const val MAX_APPLIED_CHARS = 32

    /**
     * `{"hopi":1,"id":…,…}`: one log-stream line, no bodies, at most [maxBytes] UTF-8 bytes.
     * A URL, final URL or error that does not fit is cut at a code point boundary and marked.
     */
    public fun summaryLogLine(
        transaction: HttpTransaction,
        redactor: Redactor,
        maxBytes: Int = MAX_SUMMARY_LINE_BYTES,
    ): String = StringBuilder(256).also { appendSummary(transaction, redactor, bridge = false, maxBytes, it) }.toString()

    /** [summaryLogLine], appended to [out]: the log sink reuses one builder and makes no string of the line. */
    public fun appendSummaryLogLine(transaction: HttpTransaction, redactor: Redactor, maxBytes: Int, out: StringBuilder) {
        appendSummary(transaction, redactor, bridge = false, maxBytes, out)
    }

    /** `{"type":"transaction",…summary…}`: one bridge `list`/`tail` line. */
    public fun bridgeSummary(transaction: HttpTransaction, redactor: Redactor): String =
        StringBuilder(256).also { appendBridgeSummary(transaction, redactor, it) }.toString()

    /** [bridgeSummary], appended to [out]. */
    public fun appendBridgeSummary(transaction: HttpTransaction, redactor: Redactor, out: StringBuilder) {
        appendSummary(transaction, redactor, bridge = true, MAX_SUMMARY_LINE_BYTES, out)
    }

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
        transaction.finalUrl?.let { w.field("finalUrl", redactor.url(it)) }
        w.field("state", transaction.state.wireName)
        transaction.source?.let { w.field("source", it) }
        writeRuleMarks(w, transaction)
        w.field("code", transaction.responseCode)
            .field("ms", transaction.durationMs)
            .field("protocol", transaction.protocol)
            .field("error", transaction.error?.let { redactor.textMentioning(it, transaction.url) })
        w.name("request")
        writeExchange(w, transaction.requestHeaders, transaction.requestBody, redactor, chunkChars, flush)
        w.name("response")
        writeExchange(w, transaction.responseHeaders, transaction.responseBody, redactor, chunkChars, flush)
        w.endObject()
    }

    /**
     * Appends the summary line to [out]. The line is written once; only one that may exceed
     * [maxBytes] (more than a third of [maxBytes] in chars) is measured, and only one that does is
     * written again, cut.
     */
    private fun appendSummary(transaction: HttpTransaction, redactor: Redactor, bridge: Boolean, maxBytes: Int, out: StringBuilder) {
        val url = redactor.url(transaction.url)
        val finalUrl = transaction.finalUrl?.let(redactor::url)
        val error = if (transaction.state == TransactionState.Failed) {
            transaction.error?.let { takeCodePoints(redactor.textMentioning(it, transaction.url), MAX_ERROR_CHARS) }
        } else {
            null
        }
        val mark = out.length
        writeSummary(transaction, url, finalUrl, error, bridge, Truncated.NONE, out)
        val chars = out.length - mark
        if (chars <= maxBytes / 3 || utf8Length(out, mark) <= maxBytes) return
        out.setLength(mark)
        appendCutSummary(transaction, url, finalUrl, error, bridge, maxBytes, out)
    }

    /** Which values a summary line cut. */
    private class Truncated(val url: Boolean, val finalUrl: Boolean, val error: Boolean) {
        companion object {
            val NONE = Truncated(url = false, finalUrl = false, error = false)
        }
    }

    /**
     * The summary line with its URL, final URL and error cut to share what [maxBytes] leaves after
     * the rest: each gets an equal share, and a value shorter than its share leaves the rest to
     * the others. Without a final URL the error and the URL split exactly as before it existed.
     */
    private fun appendCutSummary(
        transaction: HttpTransaction,
        url: String,
        finalUrl: String?,
        error: String?,
        bridge: Boolean,
        maxBytes: Int,
        out: StringBuilder,
    ) {
        val hasError = !error.isNullOrEmpty()
        val hasFinalUrl = !finalUrl.isNullOrEmpty()
        val skeleton = StringBuilder()
        writeSummary(transaction, "", finalUrl?.let { "" }, error?.let { "" }, bridge, Truncated(true, hasFinalUrl, hasError), skeleton)
        val available = (maxBytes - utf8Length(skeleton)).coerceAtLeast(0)
        val urlBytes = JsonWriter.escapedUtf8Length(url)
        val errorBytes = error?.let(JsonWriter::escapedUtf8Length) ?: 0
        val urlBudget: Int
        val finalUrlBudget: Int
        val errorBudget: Int
        if (finalUrl == null) {
            errorBudget = when {
                errorBytes <= available / 2 -> errorBytes
                urlBytes <= available / 2 -> available - urlBytes
                else -> available - available / 2
            }
            urlBudget = available - errorBudget
            finalUrlBudget = 0
        } else {
            val budgets = shareFairly(available, intArrayOf(errorBytes, JsonWriter.escapedUtf8Length(finalUrl), urlBytes))
            errorBudget = budgets[0]
            finalUrlBudget = budgets[1]
            urlBudget = budgets[2]
        }
        val cutUrl = url.substring(0, cutToEscapedBytes(url, urlBudget))
        val cutFinalUrl = finalUrl?.let { it.substring(0, cutToEscapedBytes(it, finalUrlBudget)) }
        val cutError = error?.let { it.substring(0, cutToEscapedBytes(it, errorBudget)) }
        writeSummary(
            transaction,
            cutUrl,
            cutFinalUrl,
            cutError,
            bridge,
            Truncated(
                url = cutUrl.length < url.length,
                finalUrl = (cutFinalUrl?.length ?: 0) < (finalUrl?.length ?: 0),
                error = (cutError?.length ?: 0) < (error?.length ?: 0),
            ),
            out,
        )
    }

    /** Splits [available] among [wanted] by water-filling: the smallest are served whole first, the rest share equally. */
    private fun shareFairly(available: Int, wanted: IntArray): IntArray {
        val order = wanted.indices.sortedBy { wanted[it] }
        val budgets = IntArray(wanted.size)
        var left = available
        for ((served, index) in order.withIndex()) {
            val share = left / (wanted.size - served)
            budgets[index] = minOf(wanted[index], share)
            left -= budgets[index]
        }
        return budgets
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
        finalUrl: String?,
        error: String?,
        bridge: Boolean,
        truncated: Truncated,
        out: StringBuilder,
    ) {
        val w = JsonWriter(out)
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
        // Additive: omitted when unknown, and readers that ignore unknown fields are unaffected.
        transaction.source?.let { w.field("source", takeCodePoints(it, MAX_SOURCE_CHARS)) }
        // Additive like `source`: only after a redirect inside the client changed the URL.
        finalUrl?.let { w.field("finalUrl", it) }
        // Additive like `source`, and part of every line's fixed fields (at most 72 bytes with
        // 19-digit ids), so the cut below leaves room for them.
        writeRuleMarks(w, transaction)
        if (bridge) {
            // What a `get` would transfer, when the app holds less than the size (a truncated or
            // skipped body): a host viewer budgets its fetches on it.
            heldBytes(transaction.requestBody)?.let { w.field("reqHeld", it) }
            heldBytes(transaction.responseBody)?.let { w.field("resHeld", it) }
        }
        if (transaction.state == TransactionState.Failed) {
            w.field("error", error)
        }
        if (truncated.url) w.field("urlTruncated", true)
        if (truncated.finalUrl) w.field("finalUrlTruncated", true)
        if (truncated.error) w.field("errorTruncated", true)
        w.endObject()
    }

    /** `"rule":3,"applied":"delay,mock"`, only when set. */
    private fun writeRuleMarks(w: JsonWriter, transaction: HttpTransaction) {
        transaction.ruleId?.let { id ->
            w.field("rule", id)
            transaction.ruleApplied?.let { w.field("applied", takeCodePoints(it, MAX_APPLIED_CHARS)) }
        }
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
    public fun utf8Length(text: CharSequence): Int = utf8Length(text, 0)

    /** [utf8Length] of [text] from [from] on. */
    public fun utf8Length(text: CharSequence, from: Int): Int {
        var bytes = 0
        var i = from
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
