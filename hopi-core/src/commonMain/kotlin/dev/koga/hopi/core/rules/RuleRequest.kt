package dev.koga.hopi.core.rules

import dev.koga.hopi.api.HopiInternalApi

/**
 * The request a rule is matched against, as a view each source implements over its own objects
 * (OkHttp's `HttpUrl` and `Headers`, Ktor's `HttpRequestBuilder`, a connection's URL and request
 * properties), so nothing is converted unless a rule asks for it. Created only when the rule state
 * is not [RuleState.EMPTY]. Rules read the fields in the cheapest-first order: method, scheme,
 * port, host, path, then query and headers only when a rule has predicates.
 */
@HopiInternalApi
public interface RuleRequest {
    public val method: String

    /** Lowercase `http` or `https`. */
    public val scheme: String

    /** The host as sent; compared case-insensitively. An IPv6 literal without brackets. */
    public val host: String

    /** The port, the scheme's default when the URL has none. */
    public val port: Int

    /** The percent-encoded path as sent, without query or fragment; `/` when empty. */
    public val encodedPath: String

    /** The percent-decoded values of the query parameter [name] (decoded names compared exactly); empty when absent. */
    public fun queryValues(name: String): List<String>

    /** The values of the request header [name], compared case-insensitively; empty when absent. */
    public fun headerValues(name: String): List<String>

    /**
     * Whether some value of the query parameter [name] matches [value], or, with [value] null,
     * whether the parameter is present: what a rule asks, which a source answers in place, with no
     * list built. Must agree with [queryValues].
     */
    public fun hasQueryValue(name: String, value: Glob?): Boolean {
        val values = queryValues(name)
        return if (value == null) values.isNotEmpty() else values.any { value.matches(it) }
    }

    /** [hasQueryValue] for the request header [name]; must agree with [headerValues]. */
    public fun hasHeaderValue(name: String, value: Glob?): Boolean {
        val values = headerValues(name)
        return if (value == null) values.isNotEmpty() else values.any { value.matches(it) }
    }
}

/**
 * A [RuleRequest] over a URL string and a header list: what tests and the sources whose request
 * is a URL plus properties (`HttpURLConnection`) use. The URL is split once, at creation.
 */
@HopiInternalApi
public class UrlRuleRequest(
    override val method: String,
    url: String,
    private val headers: List<Pair<String, String>>,
) : RuleRequest {
    private val parts = UrlParts.parse(url)

    override val scheme: String get() = parts?.scheme.orEmpty()
    override val host: String get() = parts?.host.orEmpty()
    override val port: Int get() = parts?.effectivePort ?: -1
    override val encodedPath: String get() = parts?.path?.ifEmpty { "/" } ?: "/"

    private val query: List<Pair<String, String>> by lazy { parts?.decodedQuery().orEmpty() }

    override fun queryValues(name: String): List<String> = query.filter { it.first == name }.map { it.second }

    override fun headerValues(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
}

/**
 * An absolute `http`/`https` URL split into its parts, without normalizing anything: [toString]
 * gives back the input when nothing was changed. Enough for matching and for applying a
 * [RequestEdit]; never used to validate what a stack will accept.
 */
@HopiInternalApi
public class UrlParts(
    /** Lowercase. */
    public val scheme: String,
    /** `user:pass` before `@`, as written; null when absent. */
    public val userInfo: String?,
    /** The host as written, an IPv6 literal without its brackets. */
    public val host: String,
    /** The explicit port; null when the URL has none. */
    public val port: Int?,
    /** The percent-encoded path, possibly empty. */
    public val path: String,
    /** The raw query without `?`; null when the URL has no `?`. */
    public val query: String?,
    /** The raw fragment without `#`; null when absent. */
    public val fragment: String?,
) {
    public val defaultPort: Int get() = if (scheme == "https") 443 else 80

    public val effectivePort: Int get() = port ?: defaultPort

    /** The query as decoded name and value pairs, in order; `a` without `=` has value `""`. */
    public fun decodedQuery(): List<Pair<String, String>> = decodeQuery(query)

    public fun copy(host: String = this.host, port: Int? = this.port, path: String = this.path, query: String? = this.query): UrlParts =
        UrlParts(scheme, userInfo, host, port, path, query, fragment)

    override fun toString(): String = buildString {
        append(scheme).append("://")
        userInfo?.let { append(it).append('@') }
        if (host.contains(':')) append('[').append(host).append(']') else append(host)
        port?.let { append(':').append(it) }
        append(path)
        query?.let { append('?').append(it) }
        fragment?.let { append('#').append(it) }
    }

    public companion object {
        /** A raw query (without `?`) as decoded name and value pairs, in order; `a` without `=` has value `""`. */
        public fun decodeQuery(query: String?): List<Pair<String, String>> {
            if (query.isNullOrEmpty()) return emptyList()
            return query.split('&').filter { it.isNotEmpty() }.map { part ->
                val eq = part.indexOf('=')
                if (eq < 0) percentDecode(part) to "" else percentDecode(part.substring(0, eq)) to percentDecode(part.substring(eq + 1))
            }
        }

        /** Null when [url] is not an absolute `http` or `https` URL with a host. */
        public fun parse(url: String): UrlParts? {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = url.substring(0, schemeEnd).lowercase()
            if (scheme != "http" && scheme != "https") return null
            val authorityStart = schemeEnd + 3
            var authorityEnd = url.length
            for (i in authorityStart until url.length) {
                val c = url[i]
                if (c == '/' || c == '?' || c == '#') {
                    authorityEnd = i
                    break
                }
            }
            var authority = url.substring(authorityStart, authorityEnd)
            val at = authority.lastIndexOf('@')
            val userInfo = if (at >= 0) authority.substring(0, at) else null
            if (at >= 0) authority = authority.substring(at + 1)
            val host: String
            var port: Int? = null
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                host = authority.substring(1, close)
                val rest = authority.substring(close + 1)
                if (rest.isNotEmpty()) {
                    if (!rest.startsWith(":")) return null
                    port = rest.substring(1).toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
                }
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon >= 0) {
                    host = authority.substring(0, colon)
                    val digits = authority.substring(colon + 1)
                    if (digits.isNotEmpty()) port = digits.toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
                } else {
                    host = authority
                }
            }
            if (host.isEmpty()) return null
            var rest = url.substring(authorityEnd)
            var fragment: String? = null
            val hash = rest.indexOf('#')
            if (hash >= 0) {
                fragment = rest.substring(hash + 1)
                rest = rest.substring(0, hash)
            }
            var query: String? = null
            val question = rest.indexOf('?')
            if (question >= 0) {
                query = rest.substring(question + 1)
                rest = rest.substring(0, question)
            }
            return UrlParts(scheme, userInfo, host, port, rest, query, fragment)
        }

        /** Decodes `%XX` (as UTF-8) and `+` as a space; a malformed escape is kept as written. */
        public fun percentDecode(text: String): String {
            if (text.none { it == '%' || it == '+' }) return text
            val out = StringBuilder(text.length)
            val pending = ArrayList<Byte>()
            fun flushBytes() {
                if (pending.isEmpty()) return
                out.append(pending.toByteArray().decodeToString())
                pending.clear()
            }
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val escaped = c == '%' && i + 2 < text.length &&
                    text[i + 1].digitToIntOrNull(16) != null && text[i + 2].digitToIntOrNull(16) != null
                when {
                    escaped -> {
                        pending += ((text[i + 1].digitToInt(16) shl 4) or text[i + 2].digitToInt(16)).toByte()
                        i += 3
                    }
                    c == '+' -> {
                        flushBytes()
                        out.append(' ')
                        i++
                    }
                    else -> {
                        flushBytes()
                        out.append(c)
                        i++
                    }
                }
            }
            flushBytes()
            return out.toString()
        }

        /** Encodes a query name or value: unreserved characters stay, everything else becomes `%XX` of its UTF-8. */
        public fun percentEncode(text: String): String = buildString {
            for (b in text.encodeToByteArray()) {
                val c = (b.toInt() and 0xff)
                val ch = c.toChar()
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "-._~") {
                    append(ch)
                } else {
                    append('%').append(HEX[c shr 4]).append(HEX[c and 15])
                }
            }
        }

        private const val HEX = "0123456789ABCDEF"
    }
}
