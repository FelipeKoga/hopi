package dev.koga.hopi.core.rules

import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionSource

/** The content codings a mock copied from a record decodes ([decodeBody]): one of them, never stacked. */
internal val DECODABLE_CODINGS: Set<String> = setOf("gzip", "x-gzip", "deflate")

/**
 * The content codings record [from]'s response body is still held in, lower case, in the order the
 * server applied them; empty when the record does not show it encoded. The OkHttp and
 * `HttpURLConnection` stacks decode gzip, and remove `Content-Encoding`, only when they asked for
 * gzip themselves, so a record of theirs that still names an encoding is one its app reads raw
 * (docs/design/android-capture.md). A Ktor or URLSession record keeps the raw headers over what the
 * app received after any decoder, so they do not tell: its body is copied as held.
 */
internal fun heldCodings(from: HttpTransaction): List<String> {
    if (from.source != TransactionSource.OKHTTP && from.source != TransactionSource.URL_CONNECTION) return emptyList()
    return from.responseHeaders
        .filter { it.first.equals("Content-Encoding", ignoreCase = true) }
        .flatMap { it.second.split(',') }
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() && it != "identity" }
}

/**
 * [bytes] decoded from [coding], one of [DECODABLE_CODINGS]. Decoding stops after [maxBytes] + 1
 * bytes, read into an array that doubles up to that size and never past it, so a result longer than
 * [maxBytes] means the body decodes to more, and a small body that decodes to far more (a gzip bomb)
 * costs no more than one that decodes to [maxBytes]: at most about twice [maxBytes] held at once.
 * Null when [bytes] do not decode, and on iOS, which decodes none: no record there shows an encoded
 * body ([heldCodings]).
 */
internal expect fun decodeBody(coding: String, bytes: ByteArray, maxBytes: Int): ByteArray?
