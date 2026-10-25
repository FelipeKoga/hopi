package dev.koga.hopi.core.model

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.rules.ReplayTarget

@HopiInternalApi
public enum class TransactionState(public val wireName: String) {
    Pending("pending"),
    Complete("complete"),
    Failed("failed"),
    ;

    public val isTerminal: Boolean get() = this != Pending
}

/**
 * Captured request or response body. [bytes] holds at most the configured per-body cap; it is
 * empty when [skippedReason] is set. The array is never mutated after capture.
 */
@HopiInternalApi
public class CapturedBody(
    public val bytes: ByteArray,
    public val totalBytes: Long?,
    public val truncated: Boolean,
    public val contentType: String?,
    public val skippedReason: String?,
    /**
     * Set on a copy without the bytes (a bridge summary, a host viewer's [notFetched] body): how
     * many bytes the original held, so what a `get` would transfer. Null when [bytes] are the body.
     */
    public val originalCapturedBytes: Long? = null,
) {
    public val capturedBytes: Int get() = bytes.size

    /** Bytes the app holds for this body: [originalCapturedBytes] on a copy without them, else [capturedBytes]. */
    public val heldBytes: Long get() = originalCapturedBytes ?: bytes.size.toLong()

    /** Size to report: the known total, else what was captured. */
    public val reportedBytes: Long get() = totalBytes ?: bytes.size.toLong()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedBody) return false
        return totalBytes == other.totalBytes &&
            truncated == other.truncated &&
            contentType == other.contentType &&
            skippedReason == other.skippedReason &&
            originalCapturedBytes == other.originalCapturedBytes &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + (totalBytes?.hashCode() ?: 0)
        result = 31 * result + truncated.hashCode()
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + (skippedReason?.hashCode() ?: 0)
        result = 31 * result + (originalCapturedBytes?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "CapturedBody(captured=${bytes.size}, totalBytes=$totalBytes, truncated=$truncated, " +
            "contentType=$contentType, skippedReason=$skippedReason, originalCapturedBytes=$originalCapturedBytes)"

    /** True for a body a viewer knows only by its size so far ([notFetched]): not "no body". */
    public val isNotFetched: Boolean get() = skippedReason == NOT_FETCHED

    /** True for a body a viewer can no longer fetch ([gone]): the app evicted or cleared it. */
    public val isGone: Boolean get() = skippedReason == GONE

    public companion object {
        /**
         * [skippedReason] of a body a host viewer has not fetched yet: a bridge summary carries
         * only its size, the bytes come with `get` when someone opens it. Never set by the app.
         */
        public const val NOT_FETCHED: String = "not fetched"

        /**
         * [skippedReason] of a body a host viewer asked for with `get` after the app had evicted
         * or cleared it (`NOT_FOUND`): the viewer kept the summary, the bytes are gone. Never set
         * by the app.
         */
        public const val GONE: String = "gone from the app"

        public fun skipped(reason: String, contentType: String?, totalBytes: Long?): CapturedBody =
            CapturedBody(ByteArray(0), totalBytes, truncated = false, contentType, reason)

        /**
         * A body of [totalBytes] whose bytes are still on the app's side; the app holds
         * [heldBytes] of them (less when it truncated or skipped the body), which `get` transfers.
         */
        public fun notFetched(totalBytes: Long, heldBytes: Long = totalBytes): CapturedBody =
            CapturedBody(ByteArray(0), totalBytes, truncated = false, contentType = null, NOT_FETCHED, originalCapturedBytes = heldBytes)

        /** A body of [totalBytes] the app no longer has (see [GONE]). */
        public fun gone(totalBytes: Long): CapturedBody = skipped(GONE, contentType = null, totalBytes)
    }
}

/** What recorded a transaction ([HttpTransaction.source]); null in a record from an older app. */
@HopiInternalApi
public object TransactionSource {
    /** The Ktor client plugin. */
    public const val KTOR: String = "ktor"

    /** Automatic URLSession capture on iOS. */
    public const val URL_SESSION: String = "urlsession"

    /** Automatic OkHttp capture on Android. */
    public const val OKHTTP: String = "okhttp"

    /** Automatic `HttpURLConnection` capture on Android. */
    public const val URL_CONNECTION: String = "urlconnection"
}

/** Immutable snapshot of one exchange. The store replaces snapshots by [id], never mutates them. */
@HopiInternalApi
public class HttpTransaction(
    public val id: Long,
    public val startedAtEpochMs: Long,
    public val method: String,
    public val url: String,
    public val requestHeaders: List<Pair<String, String>>,
    public val requestBody: CapturedBody?,
    public val state: TransactionState,
    public val responseCode: Int?,
    public val responseHeaders: List<Pair<String, String>>,
    public val responseBody: CapturedBody?,
    public val durationMs: Long?,
    public val error: String?,
    public val protocol: String?,
    /** What recorded it ([TransactionSource]); null when unknown (a record from an older app). */
    public val source: String? = null,
    /** The URL the response came from when a redirect followed inside the client made it differ from [url]. */
    public val finalUrl: String? = null,
    /** The rule applied to this request, or null (docs/design/rules.md, "How records are marked"). */
    public val ruleId: Long? = null,
    /** What the rule did, comma-separated in [dev.koga.hopi.core.rules.RuleMark.ORDER]; null when no rule applied. */
    public val ruleApplied: String? = null,
    /** The record this request replays, or null. */
    public val replayOf: Long? = null,
    /**
     * How to replay this request through the client that sent it; set by the recorder on records it
     * can replay. In memory only: never serialized, never compared, dropped with the record.
     */
    public val replayTarget: ReplayTarget? = null,
) {
    /** Bytes this snapshot holds in memory; what the store's byte cap counts. */
    public val capturedBytes: Long
        get() = (requestBody?.bytes?.size ?: 0).toLong() + (responseBody?.bytes?.size ?: 0)

    public fun copy(
        startedAtEpochMs: Long = this.startedAtEpochMs,
        method: String = this.method,
        url: String = this.url,
        requestHeaders: List<Pair<String, String>> = this.requestHeaders,
        requestBody: CapturedBody? = this.requestBody,
        state: TransactionState = this.state,
        responseCode: Int? = this.responseCode,
        responseHeaders: List<Pair<String, String>> = this.responseHeaders,
        responseBody: CapturedBody? = this.responseBody,
        durationMs: Long? = this.durationMs,
        error: String? = this.error,
        protocol: String? = this.protocol,
        source: String? = this.source,
        finalUrl: String? = this.finalUrl,
        ruleId: Long? = this.ruleId,
        ruleApplied: String? = this.ruleApplied,
        replayOf: Long? = this.replayOf,
        replayTarget: ReplayTarget? = this.replayTarget,
    ): HttpTransaction = HttpTransaction(
        id, startedAtEpochMs, method, url, requestHeaders, requestBody, state, responseCode,
        responseHeaders, responseBody, durationMs, error, protocol, source, finalUrl,
        ruleId, ruleApplied, replayOf, replayTarget,
    )

    public fun completed(
        responseCode: Int,
        responseHeaders: List<Pair<String, String>>,
        responseBody: CapturedBody?,
        durationMs: Long,
        protocol: String?,
    ): HttpTransaction = copy(
        state = TransactionState.Complete,
        responseCode = responseCode,
        responseHeaders = responseHeaders,
        responseBody = responseBody,
        durationMs = durationMs,
        protocol = protocol,
    )

    public fun failed(error: String, durationMs: Long): HttpTransaction =
        copy(state = TransactionState.Failed, error = error, durationMs = durationMs)

    /**
     * [failed] after the response's status and headers arrived (a body read failed, the app closed
     * the body early, the connection dropped): the status, headers and protocol are kept, and
     * [responseBody] is what arrived of the body before the failure, a partial body (its
     * `totalBytes` counts the bytes received). On the wire this is a failed transaction with a
     * `code` and a response, fields every reader already knows, so older viewers parse it. They do
     * not all show it as failed: a CLI from before this record (`hopi list`/`tail`) prints the
     * status, say `200`, in the code column instead of `ERR`, and marks the failure only with the
     * `(error)` after the URL. The current CLI and both viewers' detail read the state first.
     */
    public fun failedAfterResponse(
        error: String,
        durationMs: Long,
        responseCode: Int,
        responseHeaders: List<Pair<String, String>>,
        responseBody: CapturedBody?,
        protocol: String?,
    ): HttpTransaction = copy(
        state = TransactionState.Failed,
        error = error,
        durationMs = durationMs,
        responseCode = responseCode,
        responseHeaders = responseHeaders,
        responseBody = responseBody,
        protocol = protocol,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpTransaction) return false
        return id == other.id &&
            startedAtEpochMs == other.startedAtEpochMs &&
            method == other.method &&
            url == other.url &&
            requestHeaders == other.requestHeaders &&
            requestBody == other.requestBody &&
            state == other.state &&
            responseCode == other.responseCode &&
            responseHeaders == other.responseHeaders &&
            responseBody == other.responseBody &&
            durationMs == other.durationMs &&
            error == other.error &&
            protocol == other.protocol &&
            source == other.source &&
            finalUrl == other.finalUrl &&
            ruleId == other.ruleId &&
            ruleApplied == other.ruleApplied &&
            replayOf == other.replayOf
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "HttpTransaction(id=$id, method=$method, state=$state, code=$responseCode, ms=$durationMs)"

    public companion object {
        public fun pending(
            id: Long,
            startedAtEpochMs: Long,
            method: String,
            url: String,
            requestHeaders: List<Pair<String, String>>,
            requestBody: CapturedBody?,
            source: String? = null,
            ruleId: Long? = null,
            ruleApplied: String? = null,
            replayOf: Long? = null,
            replayTarget: ReplayTarget? = null,
        ): HttpTransaction = HttpTransaction(
            id, startedAtEpochMs, method, url, requestHeaders, requestBody, TransactionState.Pending,
            responseCode = null, responseHeaders = emptyList(), responseBody = null,
            durationMs = null, error = null, protocol = null, source = source,
            ruleId = ruleId, ruleApplied = ruleApplied, replayOf = replayOf, replayTarget = replayTarget,
        )
    }
}

@HopiInternalApi
public sealed interface TransactionEvent {
    public val transaction: HttpTransaction

    /** First snapshot of a transaction the store had not seen. */
    public class Started(override val transaction: HttpTransaction) : TransactionEvent

    /** A non-terminal snapshot replacing an earlier one. */
    public class Updated(override val transaction: HttpTransaction) : TransactionEvent

    /** A terminal snapshot: [TransactionState.Complete] or [TransactionState.Failed]. */
    public class Completed(override val transaction: HttpTransaction) : TransactionEvent
}
