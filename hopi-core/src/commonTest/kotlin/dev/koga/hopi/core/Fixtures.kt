package dev.koga.hopi.core

import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction

internal fun textBody(text: String, contentType: String? = "application/json", truncated: Boolean = false, total: Long? = null) =
    CapturedBody(text.encodeToByteArray(), total ?: text.encodeToByteArray().size.toLong(), truncated, contentType, null)

internal fun bytesBody(size: Int) = CapturedBody(ByteArray(size) { 'a'.code.toByte() }, size.toLong(), false, "text/plain", null)

internal fun pending(
    id: Long,
    method: String = "GET",
    url: String = "https://api.example.com/users?page=2",
    headers: List<Pair<String, String>> = emptyList(),
    body: CapturedBody? = null,
) = HttpTransaction.pending(id, 1_700_000_000_000, method, url, headers, body)

internal fun complete(id: Long, responseBody: CapturedBody? = null, requestBody: CapturedBody? = null) =
    pending(id, body = requestBody).completed(200, listOf("Content-Type" to "application/json"), responseBody, 183, "HTTP/1.1")
