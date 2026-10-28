package dev.koga.hopi.core.rules

import dev.koga.hopi.api.HopiInternalApi

/**
 * What every source builds the same way for a mock or a response edit (docs/design/rules.md,
 * "Actions"): the headers delivered to the app and the status line's reason phrase. A source only
 * maps the result onto its own response type.
 */
@HopiInternalApi
public object ResponseEdits {
    /**
     * The headers of [mock] as the app receives them: the rule's own, plus the `Content-Length` Hopi
     * computes when the status has a body (a matched `HEAD` gets the length the body would have,
     * without the body, as a server answers it).
     */
    public fun mockHeaders(mock: MockSpec): List<Pair<String, String>> {
        if (!statusHasBody(mock.status)) return mock.headers
        return mock.headers + (CONTENT_LENGTH to (mock.body?.size ?: 0).toString())
    }

    /**
     * [headers] after [edit]: remove, then set (replacing every value of the name), then add. A
     * replaced body also removes `Content-Encoding` and `Transfer-Encoding` (the replacement is plain
     * bytes) and sets `Content-Length` to its size.
     */
    public fun editHeaders(headers: List<Pair<String, String>>, edit: ResponseEdit): List<Pair<String, String>> {
        val removed = HashSet<String>()
        edit.removeHeaders.forEach { removed += it.lowercase() }
        edit.setHeaders.forEach { removed += it.first.lowercase() }
        if (edit.body != null) BODY_HEADERS.forEach { removed += it }
        val result = ArrayList<Pair<String, String>>(headers.size + edit.setHeaders.size + edit.addHeaders.size + 1)
        for (header in headers) if (header.first.lowercase() !in removed) result += header
        result += edit.setHeaders
        result += edit.addHeaders
        edit.body?.let { result += CONTENT_LENGTH to it.size.toString() }
        return result
    }

    /** Whether a response with [status] to [method] carries a body at all. */
    public fun hasBody(method: String, status: Int): Boolean = !method.equals("HEAD", ignoreCase = true) && statusHasBody(status)

    private fun statusHasBody(status: Int): Boolean = status !in 100..199 && status != 204 && status != 304

    /** The standard reason phrase of [status], as a server sends it in the status line; `""` for an unknown code. */
    public fun reasonPhrase(status: Int): String = when (status) {
        100 -> "Continue"
        102 -> "Processing"
        103 -> "Early Hints"
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        203 -> "Non-Authoritative Information"
        204 -> "No Content"
        205 -> "Reset Content"
        206 -> "Partial Content"
        300 -> "Multiple Choices"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        304 -> "Not Modified"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        402 -> "Payment Required"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        406 -> "Not Acceptable"
        407 -> "Proxy Authentication Required"
        408 -> "Request Timeout"
        409 -> "Conflict"
        410 -> "Gone"
        411 -> "Length Required"
        412 -> "Precondition Failed"
        413 -> "Content Too Large"
        414 -> "URI Too Long"
        415 -> "Unsupported Media Type"
        416 -> "Range Not Satisfiable"
        417 -> "Expectation Failed"
        418 -> "I'm a teapot"
        422 -> "Unprocessable Content"
        425 -> "Too Early"
        426 -> "Upgrade Required"
        428 -> "Precondition Required"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        451 -> "Unavailable For Legal Reasons"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        505 -> "HTTP Version Not Supported"
        511 -> "Network Authentication Required"
        else -> ""
    }

    private const val CONTENT_LENGTH = "Content-Length"
    private val BODY_HEADERS = listOf("content-length", "content-encoding", "transfer-encoding")
}
