package dev.koga.hopi.core.rules

import dev.koga.hopi.api.HopiInternalApi

/**
 * The match shorthand of the CLI's `--match` and `HopiRules`' `match` (docs/design/rules.md,
 * "Shorthand"): `[METHODS ]URL-PATTERN`, METHODS being `GET`, `GET|POST` or `*`, URL-PATTERN
 * `[scheme://]host[:port]path[?query]`. A pattern that starts with `/` has host `*`; without a path
 * any path matches. `?page=2&debug&!trace` gives the predicates `page=2`, `debug` (present) and
 * `!trace` (absent). The CLI sends the structured result on the wire; the app never parses it.
 */
@HopiInternalApi
public object MatchShorthand {
    /** Throws [IllegalArgumentException] with a message starting with `match:`. */
    public fun parse(
        text: String,
        sources: Set<RuleSource>? = null,
        extraQuery: List<Predicate> = emptyList(),
        headers: List<Predicate> = emptyList(),
    ): MatchSpec {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "match: empty" }
        val space = trimmed.indexOfFirst { it == ' ' || it == '\t' }
        val methodsPart = if (space >= 0) trimmed.substring(0, space) else null
        val urlPart = if (space >= 0) trimmed.substring(space + 1).trim() else trimmed
        require(urlPart.none { it == ' ' || it == '\t' }) { "match: '$text' has more than a method list and a URL pattern" }
        val methods = methodsPart?.takeIf { it != "*" }?.split('|')?.map { method ->
            require(RuleValidation.isToken(method) && method != "*") { "match: '$method' is not a method" }
            method
        }?.toSet()

        var rest = urlPart
        var scheme: String? = null
        val schemeEnd = rest.indexOf("://")
        if (schemeEnd >= 0) {
            val given = rest.substring(0, schemeEnd).lowercase()
            require(given == "http" || given == "https" || given == "*") { "match: scheme must be http, https or *, not '$given'" }
            scheme = given.takeIf { it != "*" }
            rest = rest.substring(schemeEnd + 3)
        }
        var query: String? = null
        val question = rest.indexOf('?')
        if (question >= 0) {
            query = rest.substring(question + 1)
            rest = rest.substring(0, question)
        }
        require('#' !in rest) { "match: no fragment" }
        var host: String? = null
        var port: Int? = null
        val path: String?
        if (rest.startsWith("/")) {
            require(scheme == null) { "match: a pattern with a scheme needs a host (use * for any)" }
            path = rest
        } else {
            val slash = rest.indexOf('/')
            val authority = if (slash >= 0) rest.substring(0, slash) else rest
            path = if (slash >= 0) rest.substring(slash) else null
            require(authority.isNotEmpty()) { "match: no host" }
            var hostPart = authority
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                require(close > 0) { "match: unclosed [ in host" }
                hostPart = authority.substring(1, close)
                val after = authority.substring(close + 1)
                if (after.isNotEmpty()) {
                    require(after.startsWith(":")) { "match: unexpected '$after' after the host" }
                    port = parsePort(after.substring(1))
                }
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon >= 0) {
                    hostPart = authority.substring(0, colon)
                    port = parsePort(authority.substring(colon + 1))
                }
            }
            require(hostPart.isNotEmpty()) { "match: no host" }
            host = hostPart.takeIf { it != "*" }
        }
        val predicates = query?.split('&')?.filter { it.isNotEmpty() }?.map(::predicate).orEmpty() + extraQuery
        val spec = MatchSpec(
            methods = methods,
            scheme = scheme,
            host = host,
            port = port,
            path = path?.takeIf { it != "/**" },
            query = predicates,
            headers = headers,
            sources = sources,
        )
        RuleValidation.validate(RuleSpec(spec, delay = DelaySpec(beforeRequestMs = 1)))?.let { throw IllegalArgumentException(it.message) }
        return spec
    }

    /** `name=value-glob`, `name` (present) or `!name` (absent): a query predicate, or with `:` for headers. */
    public fun predicate(text: String): Predicate = when {
        text.startsWith("!") -> Predicate(text.substring(1).also { require(it.isNotEmpty()) { "match: '!' needs a name" } }, null, absent = true)
        '=' in text -> Predicate(text.substringBefore('='), text.substringAfter('='))
        else -> Predicate(text, null)
    }

    /** A header predicate: `Name: value-glob`, `Name` (present) or `!Name` (absent). */
    public fun headerPredicate(text: String): Predicate {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("!") -> Predicate(trimmed.substring(1).trim(), null, absent = true)
            ':' in trimmed -> Predicate(trimmed.substringBefore(':').trim(), trimmed.substringAfter(':').trim())
            else -> Predicate(trimmed, null)
        }
    }

    private fun parsePort(text: String): Int =
        text.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("match: port must be 1..65535, not '$text'")
}
