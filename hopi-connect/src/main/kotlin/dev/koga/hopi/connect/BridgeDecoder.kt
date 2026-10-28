package dev.koga.hopi.connect

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionState
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bridge lines back into [HttpTransaction]s, the inverse of core's `TransactionJson`: a host
 * viewer keeps them in a `HopiStore` of its own, keyed by the app's ids. Every value is
 * already redacted by the app. A line that is not a well-formed transaction decodes to null.
 */
@HopiInternalApi
public object BridgeDecoder {
    /**
     * A `list`/`tail` summary line: no headers, and each body only as its size and the bytes the
     * app holds of it ([CapturedBody.notFetched]) until a `get` brings it. The summary has no
     * start time, so [startedAtFallback] stands in (the time a viewer first saw the id) until the
     * detail replaces it. A pending transaction has no response body yet.
     */
    public fun summary(json: JsonObject, startedAtFallback: Long): HttpTransaction? {
        if (json.str("type") != "transaction") return null
        val id = json.long("id") ?: return null
        val state = stateOf(json.str("state")) ?: return null
        val method = json.str("method") ?: return null
        val url = json.str("url") ?: return null
        val requestBytes = json.long("reqBytes") ?: 0L
        val responseBytes = json.long("resBytes") ?: 0L
        // Sent only when the app holds less than the size (truncated, skipped): what `get` moves.
        val requestHeld = json.long("reqHeld") ?: requestBytes
        val responseHeld = json.long("resHeld") ?: responseBytes
        return HttpTransaction(
            id = id,
            startedAtEpochMs = json.long("startedAt") ?: startedAtFallback,
            method = method,
            url = url,
            requestHeaders = emptyList(),
            requestBody = CapturedBody.notFetched(requestBytes, requestHeld),
            state = state,
            responseCode = json.long("code")?.toInt(),
            responseHeaders = emptyList(),
            responseBody = if (state == TransactionState.Pending) null else CapturedBody.notFetched(responseBytes, responseHeld),
            durationMs = json.long("ms"),
            error = if (state == TransactionState.Failed) json.str("error") else null,
            protocol = null,
            // Absent from an older app's lines: null, as both fields are additive.
            source = json.str("source"),
            // Possibly cut to fit the line (`finalUrlTruncated`); the detail carries it whole.
            finalUrl = json.str("finalUrl"),
            // The rule marks, additive like `source`: absent from an older app's lines.
            ruleId = json.long("rule"),
            ruleApplied = json.str("applied"),
        )
    }

    /** A `get` reply: the whole transaction, headers and captured bodies included. */
    public fun detail(json: JsonObject): HttpTransaction? {
        if (json.str("type") != "transaction") return null
        val request = json["request"] as? JsonObject ?: return null
        val response = json["response"] as? JsonObject ?: return null
        val state = stateOf(json.str("state")) ?: return null
        return HttpTransaction(
            id = json.long("id") ?: return null,
            startedAtEpochMs = json.long("startedAt") ?: return null,
            method = json.str("method") ?: return null,
            url = json.str("url") ?: return null,
            requestHeaders = headers(request) ?: return null,
            requestBody = (body(request) ?: return null).value,
            state = state,
            responseCode = json.long("code")?.toInt(),
            responseHeaders = headers(response) ?: return null,
            responseBody = (body(response) ?: return null).value,
            durationMs = json.long("ms"),
            error = json.str("error"),
            protocol = json.str("protocol"),
            source = json.str("source"),
            finalUrl = json.str("finalUrl"),
            ruleId = json.long("rule"),
            ruleApplied = json.str("applied"),
        )
    }

    private fun stateOf(wireName: String?): TransactionState? = TransactionState.entries.firstOrNull { it.wireName == wireName }

    private fun headers(exchange: JsonObject): List<Pair<String, String>>? {
        val array = exchange["headers"] as? JsonArray ?: return null
        val headers = ArrayList<Pair<String, String>>(array.size)
        for (element in array) {
            val pair = element as? JsonArray ?: return null
            val name = (pair.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val value = (pair.getOrNull(1) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            headers += name to value
        }
        return headers
    }

    /** The exchange's body, holding null when it is JSON `null`; null itself when malformed. */
    private fun body(exchange: JsonObject): Body? {
        val element = exchange["body"] ?: return null
        if (element is JsonNull) return NO_BODY
        val body = element as? JsonObject ?: return null
        val bytes = when (body.str("encoding")) {
            "utf8" -> body.str("text")?.encodeToByteArray()
            "base64" -> body.str("data")?.let {
                try {
                    Base64.getDecoder().decode(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            else -> null
        } ?: return null
        return Body(
            CapturedBody(
                bytes = bytes,
                totalBytes = body.long("totalBytes"),
                truncated = body.bool("truncated") == true,
                contentType = body.str("contentType"),
                skippedReason = body.str("skippedReason"),
            ),
        )
    }

    /** Keeps "no body" (a holder of null) apart from "malformed" (no holder). */
    private class Body(val value: CapturedBody?)

    private val NO_BODY = Body(null)
}
