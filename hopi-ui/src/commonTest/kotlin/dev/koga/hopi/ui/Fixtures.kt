package dev.koga.hopi.ui

import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction

internal fun textBody(text: String, contentType: String? = "application/json", truncated: Boolean = false, total: Long? = null) =
    CapturedBody(text.encodeToByteArray(), total ?: text.encodeToByteArray().size.toLong(), truncated, contentType, null)

internal fun pending(
    id: Long,
    method: String = "GET",
    url: String = "https://api.example.com/users?page=2",
    headers: List<Pair<String, String>> = emptyList(),
    body: CapturedBody? = null,
) = HttpTransaction.pending(id, 1_700_000_000_000, method, url, headers, body)

internal fun complete(
    id: Long,
    code: Int = 200,
    url: String = "https://api.example.com/users?page=2",
    responseBody: CapturedBody? = textBody("""{"name":"Ada"}"""),
    requestBody: CapturedBody? = null,
    method: String = "GET",
    responseHeaders: List<Pair<String, String>> = listOf("Content-Type" to "application/json"),
) = pending(id, method = method, url = url, body = requestBody).completed(code, responseHeaders, responseBody, 183, "HTTP/1.1")

internal fun failed(id: Long, url: String = "https://down.example.com/health") =
    pending(id, url = url).failed("java.net.UnknownHostException: down.example.com", 12)
