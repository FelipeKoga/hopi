package dev.koga.hopi.core.rules

import dev.koga.hopi.api.HopiInternalApi

/** The limits of docs/design/rules.md, "Limits". */
@HopiInternalApi
public object RuleLimits {
    public const val MAX_RULES: Int = 64
    public const val MAX_BODY_BYTES: Int = 1024 * 1024
    public const val MAX_TOTAL_BODY_BYTES: Long = 8L * 1024 * 1024
    public const val MAX_HEADERS: Int = 32
    public const val MAX_HEADER_BYTES: Int = 8 * 1024
    public const val MAX_PREDICATES: Int = 8
    public const val MAX_PATTERN_CHARS: Int = Glob.MAX_PATTERN_CHARS
    public const val MAX_DELAY_MS: Long = 120_000
    public const val MAX_TIMES: Int = 1_000_000
    public const val MAX_LABEL_CHARS: Int = 80
    public const val MAX_BLOBS_PER_SESSION: Int = 2
}

/**
 * Checks a [RuleSpec] against the design's rules; every message names
 * the field (`"match.path: pattern longer than 256 chars"`), as the bridge sends it. Limits on the
 * rule set as a whole (count, total body bytes) are the engine's.
 */
@HopiInternalApi
public object RuleValidation {
    /** Headers a mock or response edit may not give: the stack computes or decodes them, or they persist. */
    private val REJECTED_RESPONSE_HEADERS = setOf("content-length", "transfer-encoding", "content-encoding", "set-cookie", "set-cookie2")

    /** Headers a request edit may not name: the origin is edited through host and port, the rest each stack computes. */
    private val REJECTED_REQUEST_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection")

    public fun validate(spec: RuleSpec): RuleError? {
        spec.label?.let {
            if (it.length > RuleLimits.MAX_LABEL_CHARS) return bad("label: longer than ${RuleLimits.MAX_LABEL_CHARS} chars")
            if (it.any { c -> c < ' ' || c == '\u007f' }) return bad("label: control characters")
        }
        spec.times?.let { if (it !in 1..RuleLimits.MAX_TIMES) return bad("times: must be 1..${RuleLimits.MAX_TIMES}") }
        validateMatch(spec.match)?.let { return it }
        spec.delay?.let { delay ->
            if (delay.beforeRequestMs !in 0..RuleLimits.MAX_DELAY_MS) return bad("delay.beforeRequestMs: must be 0..${RuleLimits.MAX_DELAY_MS}")
            if (delay.beforeResponseMs !in 0..RuleLimits.MAX_DELAY_MS) return bad("delay.beforeResponseMs: must be 0..${RuleLimits.MAX_DELAY_MS}")
        }
        val actions = listOfNotNull(spec.mock, spec.fail, spec.edit).size
        if (actions > 1) return bad("rule: at most one of mock, fail, edit")
        if (actions == 0 && (spec.delay == null || spec.delay.isZero)) return bad("rule: needs mock, fail, edit or a non-zero delay")
        spec.mock?.let { mock ->
            validateStatus(mock.status, "mock.status")?.let { return it }
            validateResponseHeaders(mock.headers, "mock.headers")?.let { return it }
            mock.body?.let { body ->
                if (body.size > RuleLimits.MAX_BODY_BYTES) return limit("mock.body: larger than ${RuleLimits.MAX_BODY_BYTES} bytes")
                if (!allowsBody(mock.status)) return bad("mock.body: status ${mock.status} has no body")
            }
        }
        spec.edit?.let { edit ->
            val request = edit.request?.takeIf { !it.isEmpty }
            val response = edit.response?.takeIf { !it.isEmpty }
            if (request == null && response == null) return bad("edit: changes nothing")
            request?.let { validateRequestEdit(it, "edit.request")?.let { e -> return e } }
            response?.let { r ->
                r.status?.let { validateStatus(it, "edit.response.status")?.let { e -> return e } }
                validateResponseHeaders(r.setHeaders, "edit.response.setHeaders")?.let { return it }
                validateResponseHeaders(r.addHeaders, "edit.response.addHeaders")?.let { return it }
                validateNames(r.removeHeaders, "edit.response.removeHeaders")?.let { return it }
                r.body?.let { body ->
                    if (body.size > RuleLimits.MAX_BODY_BYTES) return limit("edit.response.body: larger than ${RuleLimits.MAX_BODY_BYTES} bytes")
                    r.status?.let { if (!allowsBody(it)) return bad("edit.response.body: status $it has no body") }
                }
            }
        }
        return null
    }


    private fun validateMatch(match: MatchSpec): RuleError? {
        match.methods?.forEach { if (!isToken(it)) return bad("match.methods: '$it' is not a method token") }
        match.scheme?.let { if (it != "http" && it != "https") return bad("match.scheme: must be http or https") }
        match.host?.let {
            if (it.isEmpty()) return bad("match.host: empty")
            if (it.length > RuleLimits.MAX_PATTERN_CHARS) return bad("match.host: pattern longer than ${RuleLimits.MAX_PATTERN_CHARS} chars")
            if (it.any { c -> c <= ' ' || c == '/' || c == '?' || c == '#' || c == '\u007f' }) return bad("match.host: invalid characters")
        }
        match.port?.let { if (it !in 1..65535) return bad("match.port: must be 1..65535") }
        match.path?.let {
            if (it.length > RuleLimits.MAX_PATTERN_CHARS) return bad("match.path: pattern longer than ${RuleLimits.MAX_PATTERN_CHARS} chars")
            if (!it.startsWith("/") && !it.startsWith("*")) return bad("match.path: must start with / or *")
            if (it.any { c -> c <= ' ' || c == '?' || c == '#' || c == '\u007f' }) return bad("match.path: invalid characters")
        }
        if (match.query.size > RuleLimits.MAX_PREDICATES) return bad("match.query: more than ${RuleLimits.MAX_PREDICATES} predicates")
        if (match.headers.size > RuleLimits.MAX_PREDICATES) return bad("match.headers: more than ${RuleLimits.MAX_PREDICATES} predicates")
        for (p in match.query) validatePredicate(p, "match.query", header = false)?.let { return it }
        for (p in match.headers) validatePredicate(p, "match.headers", header = true)?.let { return it }
        if (match.sources?.isEmpty() == true) return bad("match.sources: empty")
        return null
    }

    private fun validatePredicate(p: Predicate, field: String, header: Boolean): RuleError? {
        if (p.name.isEmpty()) return bad("$field: empty name")
        if (header && !isToken(p.name)) return bad("$field: '${p.name}' is not a header name")
        if (p.name.length > RuleLimits.MAX_PATTERN_CHARS) return bad("$field: name longer than ${RuleLimits.MAX_PATTERN_CHARS} chars")
        if (p.absent && p.value != null) return bad("$field: an absent predicate has no value")
        p.value?.let {
            if (it.length > RuleLimits.MAX_PATTERN_CHARS) return bad("$field: pattern longer than ${RuleLimits.MAX_PATTERN_CHARS} chars")
            if (it.any { c -> c < ' ' || c == '\u007f' }) return bad("$field: control characters")
        }
        return null
    }

    private fun validateRequestEdit(edit: RequestEdit, field: String): RuleError? {
        edit.method?.let { if (!isToken(it)) return bad("$field.method: '$it' is not a method token") }
        edit.host?.let {
            if (it.isEmpty() || it.length > 253 + 2) return bad("$field.host: invalid host")
            val ipv6 = it.length > 2 && it.startsWith("[") && it.endsWith("]")
            val inner = if (ipv6) it.substring(1, it.length - 1) else it
            if (inner.isEmpty() || inner.any { c -> !(c.isLetterOrDigit() || c == '-' || c == '.' || c == '_' || ipv6 && c == ':') }) {
                return bad("$field.host: invalid host")
            }
        }
        edit.port?.let { if (it !in 1..65535) return bad("$field.port: must be 1..65535") }
        edit.path?.let {
            if (!it.startsWith("/")) return bad("$field.path: must start with /")
            if (it.length > 2048 || it.any { c -> c <= ' ' || c == '?' || c == '#' || c == '\u007f' }) return bad("$field.path: invalid characters")
        }
        for ((name, value) in edit.setQuery) {
            if (name.isEmpty()) return bad("$field.setQuery: empty name")
            if ((name + value).any { c -> c < ' ' || c == '\u007f' }) return bad("$field.setQuery: control characters")
        }
        if (edit.removeQuery.any { it.isEmpty() }) return bad("$field.removeQuery: empty name")
        validateRequestHeaders(edit.setHeaders, "$field.setHeaders")?.let { return it }
        validateRequestHeaders(edit.addHeaders, "$field.addHeaders")?.let { return it }
        validateNames(edit.removeHeaders, "$field.removeHeaders")?.let { return it }
        for (name in edit.removeHeaders) {
            if (rejectedRequestHeader(name)) return bad("$field.removeHeaders: '$name' may not be edited")
        }
        edit.body?.let { if (it.size > RuleLimits.MAX_BODY_BYTES) return limit("$field.body: larger than ${RuleLimits.MAX_BODY_BYTES} bytes") }
        return null
    }

    private fun validateRequestHeaders(headers: List<Pair<String, String>>, field: String): RuleError? {
        validateHeaderList(headers, field)?.let { return it }
        for ((name, _) in headers) {
            if (rejectedRequestHeader(name)) return bad("$field: '$name' may not be edited")
        }
        return null
    }

    private fun rejectedRequestHeader(name: String): Boolean = name.startsWith(":") || name.lowercase() in REJECTED_REQUEST_HEADERS

    private fun validateResponseHeaders(headers: List<Pair<String, String>>, field: String): RuleError? {
        validateHeaderList(headers, field)?.let { return it }
        for ((name, _) in headers) {
            if (name.lowercase() in REJECTED_RESPONSE_HEADERS) return bad("$field: '$name' may not be given")
        }
        return null
    }

    private fun validateHeaderList(headers: List<Pair<String, String>>, field: String): RuleError? {
        if (headers.size > RuleLimits.MAX_HEADERS) return bad("$field: more than ${RuleLimits.MAX_HEADERS} headers")
        var bytes = 0
        for ((name, value) in headers) {
            if (!isToken(name)) return bad("$field: '$name' is not a header name")
            if (value.any { c -> (c < ' ' && c != '\t') || c == '\u007f' }) return bad("$field: '$name' has control characters in its value")
            bytes += name.length + value.length
        }
        if (bytes > RuleLimits.MAX_HEADER_BYTES) return bad("$field: more than ${RuleLimits.MAX_HEADER_BYTES} bytes")
        return null
    }

    private fun validateNames(names: List<String>, field: String): RuleError? {
        if (names.size > RuleLimits.MAX_HEADERS) return bad("$field: more than ${RuleLimits.MAX_HEADERS} names")
        for (name in names) if (!isToken(name)) return bad("$field: '$name' is not a header name")
        return null
    }

    private fun validateStatus(status: Int, field: String): RuleError? =
        if (status !in 100..599 || status == 101) bad("$field: must be 100..599 and not 101") else null

    /** Statuses that carry no body: 1xx, 204, 304. */
    public fun allowsBody(status: Int): Boolean = status >= 200 && status != 204 && status != 304

    /** An RFC 9110 token (method, header name). */
    public fun isToken(text: String): Boolean = text.isNotEmpty() && text.length <= 256 && text.all { c ->
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!#$%&'*+-.^_`|~"
    }

    private fun bad(message: String) = RuleError(RuleErrorCode.BAD_REQUEST, message)

    private fun limit(message: String) = RuleError(RuleErrorCode.LIMIT, message)
}
