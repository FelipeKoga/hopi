package dev.koga.hopi.core.model

import dev.koga.hopi.core.HopiInternalApi

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
) {
    public val capturedBytes: Int get() = bytes.size

    /** Size to report: the known total, else what was captured. */
    public val reportedBytes: Long get() = totalBytes ?: bytes.size.toLong()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedBody) return false
        return totalBytes == other.totalBytes &&
            truncated == other.truncated &&
            contentType == other.contentType &&
            skippedReason == other.skippedReason &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + (totalBytes?.hashCode() ?: 0)
        result = 31 * result + truncated.hashCode()
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + (skippedReason?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "CapturedBody(captured=${bytes.size}, totalBytes=$totalBytes, truncated=$truncated, " +
            "contentType=$contentType, skippedReason=$skippedReason)"

    public companion object {
        public fun skipped(reason: String, contentType: String?, totalBytes: Long?): CapturedBody =
            CapturedBody(ByteArray(0), totalBytes, truncated = false, contentType, reason)
    }
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
    ): HttpTransaction = HttpTransaction(
        id, startedAtEpochMs, method, url, requestHeaders, requestBody, state, responseCode,
        responseHeaders, responseBody, durationMs, error, protocol,
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
            protocol == other.protocol
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
        ): HttpTransaction = HttpTransaction(
            id, startedAtEpochMs, method, url, requestHeaders, requestBody, TransactionState.Pending,
            responseCode = null, responseHeaders = emptyList(), responseBody = null,
            durationMs = null, error = null, protocol = null,
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
