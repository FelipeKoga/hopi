package dev.koga.hopi.desktop

import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction

internal fun textBody(text: String, contentType: String? = "application/json", truncated: Boolean = false, total: Long? = null) =
    CapturedBody(text.encodeToByteArray(), total ?: text.encodeToByteArray().size.toLong(), truncated, contentType, skippedReason = null)

internal fun binaryBody(bytes: ByteArray, contentType: String = "image/png") =
    CapturedBody(bytes, bytes.size.toLong(), truncated = false, contentType, skippedReason = null)

internal fun pendingTx(
    id: Long,
    method: String = "GET",
    url: String = "https://api.example.com/users?page=2&q=a%20b",
    body: CapturedBody? = null,
    startedAt: Long = 1_700_000_000_000 + id,
) = HttpTransaction.pending(id, startedAt, method, url, listOf("Accept" to "application/json", "Authorization" to "Bearer secret"), body)

internal fun completeTx(
    id: Long,
    url: String = "https://api.example.com/users?page=2&q=a%20b",
    method: String = "GET",
    requestBody: CapturedBody? = null,
    code: Int = 200,
    responseBody: CapturedBody? = textBody("""{"ok":true}"""),
) = pendingTx(id, method, url, requestBody).completed(code, listOf("Content-Type" to "application/json", "Set-Cookie" to "s=1"), responseBody, 42, "HTTP/2")

/** What a viewer holds before `get`: sizes only, as `BridgeDecoder.summary` builds it. */
internal fun summaryOnly(transaction: HttpTransaction) = transaction.copy(
    requestHeaders = emptyList(),
    requestBody = CapturedBody.notFetched(transaction.requestBody?.reportedBytes ?: 0, transaction.requestBody?.heldBytes ?: 0),
    responseHeaders = emptyList(),
    responseBody = transaction.responseBody?.let { CapturedBody.notFetched(it.reportedBytes, it.heldBytes) },
    protocol = null,
)
