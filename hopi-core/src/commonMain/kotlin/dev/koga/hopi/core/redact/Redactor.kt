package dev.koga.hopi.core.redact

import dev.koga.hopi.api.HopiInternalApi

/**
 * Replaces secret header values and query parameter values with [REDACTED]. Header and parameter
 * names match case-insensitively; parameter names are percent-decoded before matching. On by
 * default, with [DEFAULT_HEADERS] and [DEFAULT_QUERY_PARAMETERS]; only [Disabled] (the configs'
 * `unsafeDisableRedaction()`) turns it off.
 */
@HopiInternalApi
public class Redactor private constructor(
    public val enabled: Boolean,
    private val headerNames: Set<String>,
    private val queryParameterNames: Set<String>,
) {
    public fun isRedactedHeader(name: String): Boolean =
        enabled && name.lowercase() in headerNames

    /** The lowercased header names [isRedactedHeader] answers true for; empty when disabled. */
    public val redactedHeaderNames: Set<String> get() = if (enabled) headerNames else emptySet()

    /** [REDACTED] for a redacted header; a header that carries a URL (`Location`, `Referer`…) gets [url]. */
    public fun headerValue(name: String, value: String): String = when {
        !enabled -> value
        isRedactedHeader(name) -> REDACTED
        name.lowercase() in URL_HEADERS -> url(value)
        else -> value
    }

    /** The value of the (decoded) query parameter [name], or [REDACTED] when the parameter is redacted. */
    public fun queryValue(name: String, value: String): String =
        if (enabled && name.lowercase() in queryParameterNames) REDACTED else value

    public fun headers(headers: List<Pair<String, String>>): List<Pair<String, String>> {
        if (!enabled) return headers
        var changed = false
        val result = headers.map { header ->
            val value = headerValue(header.first, header.second)
            if (value == header.second) header else { changed = true; header.first to value }
        }
        return if (changed) result else headers
    }

    /**
     * [text] (an error message, say) with every occurrence of [url] replaced by its redacted form,
     * and every other `scheme://…` in it (a redirect's URL an engine quotes) redacted too.
     */
    public fun textMentioning(text: String, url: String): String {
        if (!enabled) return text
        val known = if (url.isNotEmpty() && text.contains(url)) {
            val redacted = url(url)
            if (redacted === url) text else text.replace(url, redacted)
        } else {
            text
        }
        return redactEmbeddedUrls(known)
    }

    /** Each `scheme://…` in [text], up to a space, a quote or an angle bracket, through [url]. */
    private fun redactEmbeddedUrls(text: String): String {
        var schemeSeparator = text.indexOf("://")
        if (schemeSeparator < 0) return text
        val out = StringBuilder(text.length)
        var cursor = 0
        while (schemeSeparator >= 0) {
            var start = schemeSeparator
            while (start > cursor && isSchemeChar(text[start - 1])) start--
            var end = schemeSeparator + 3
            while (end < text.length && !isUrlEnd(text[end])) end++
            // Punctuation that closes the sentence or a bracket around the URL is not part of it.
            while (end > schemeSeparator + 3 && text[end - 1] in TRAILING_PUNCTUATION) end--
            out.append(text, cursor, start).append(url(text.substring(start, end)))
            cursor = end
            schemeSeparator = text.indexOf("://", maxOf(end, schemeSeparator + 3))
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    /** Redacts what either redacts: disabled only when both are disabled. */
    public fun union(other: Redactor): Redactor = when {
        !other.enabled || other === this -> this
        !enabled -> other
        headerNames.containsAll(other.headerNames) &&
            queryParameterNames.containsAll(other.queryParameterNames) -> this
        else -> Redactor(
            enabled = true,
            headerNames = headerNames + other.headerNames,
            queryParameterNames = queryParameterNames + other.queryParameterNames,
        )
    }

    /**
     * [url] with its userinfo (`user:password@`) replaced by [REDACTED] and the values of the
     * redacted query parameters replaced by [REDACTED]. Returns [url] itself when nothing changed.
     */
    public fun url(url: String): String {
        if (!enabled) return url
        val masked = maskUserinfo(url)
        return if (queryParameterNames.isEmpty()) masked else redactQuery(masked)
    }

    /** The values of the redacted parameters in [url]'s query and in its fragment (`#access_token=…`). */
    private fun redactQuery(url: String): String {
        val fragmentStart = url.indexOf('#')
        // A `?` after the `#` belongs to the fragment.
        val queryStart = url.indexOf('?').let { if (it < 0 || (fragmentStart in 0 until it)) -1 else it }
        if (queryStart < 0 && fragmentStart < 0) return url
        val out = StringBuilder(url.length)
        var changed = false
        var cursor = 0
        fun redactParameters(start: Int, end: Int) {
            out.append(url, cursor, start)
            var partStart = start
            while (partStart <= end) {
                // `&` separates parameters, and `;` too (`?a=1;token=…`).
                var partEnd = partStart
                while (partEnd < end && url[partEnd] != '&' && url[partEnd] != ';') partEnd++
                val equals = url.indexOf('=', partStart).let { if (it < 0 || it > partEnd) -1 else it }
                if (equals >= 0 && equals + 1 < partEnd &&
                    percentDecode(url, partStart, equals).lowercase().removeSuffix("[]") in queryParameterNames
                ) {
                    out.append(url, partStart, equals + 1).append(REDACTED)
                    changed = true
                } else {
                    out.append(url, partStart, partEnd)
                }
                if (partEnd < end) out.append(url[partEnd])
                partStart = partEnd + 1
            }
            cursor = end
        }
        if (queryStart >= 0) redactParameters(queryStart + 1, if (fragmentStart < 0) url.length else fragmentStart)
        // A hash route's parameters (`#/route?token=…`) follow its own `?`.
        if (fragmentStart >= 0) redactParameters(url.indexOf('?', fragmentStart).let { if (it < 0) fragmentStart else it } + 1, url.length)
        if (!changed) return url
        out.append(url, cursor, url.length)
        return out.toString()
    }

    public companion object {
        public const val REDACTED: String = "<redacted>"

        /** The credential headers redacted unless redaction is disabled. */
        public val DEFAULT_HEADERS: List<String> = listOf(
            "Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie",
            "X-Api-Key", "Api-Key", "X-Auth-Token", "X-Access-Token", "X-Refresh-Token", "X-Session-Token",
            "X-Amz-Security-Token", "X-Goog-Api-Key", "X-CSRF-Token", "X-XSRF-Token",
        )

        /**
         * The query (and fragment) parameters redacted unless redaction is disabled: OAuth's tokens
         * and code, API keys, passwords, and the signatures and credentials of presigned URLs.
         */
        public val DEFAULT_QUERY_PARAMETERS: List<String> = listOf(
            "access_token", "refresh_token", "id_token", "token", "code", "auth", "authorization",
            "api_key", "apikey", "key", "client_secret", "secret", "password", "passwd",
            "session", "sessionid", "sig", "signature",
            "X-Amz-Signature", "X-Amz-Credential", "X-Amz-Security-Token",
            "X-Goog-Signature", "X-Goog-Credential",
        )

        /** Headers whose value is a URL, redacted with [url]. */
        private val URL_HEADERS = setOf("location", "content-location", "referer")

        private const val TRAILING_PUNCTUATION = ".,;:)]}"

        private fun isSchemeChar(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '+' || c == '-' || c == '.'

        private fun isUrlEnd(c: Char): Boolean =
            c.isWhitespace() || c == '"' || c == '\'' || c == '<' || c == '>' || c == '`'

        public val Default: Redactor = create()

        public val Disabled: Redactor = Redactor(enabled = false, emptySet(), emptySet())

        public fun create(
            extraHeaders: Collection<String> = emptyList(),
            queryParameters: Collection<String> = emptyList(),
            enabled: Boolean = true,
        ): Redactor {
            if (!enabled) return Disabled
            val headers = HashSet<String>()
            DEFAULT_HEADERS.forEach { headers += it.lowercase() }
            extraHeaders.forEach { headers += it.trim().lowercase() }
            return Redactor(
                enabled = true,
                headerNames = headers,
                queryParameterNames = (DEFAULT_QUERY_PARAMETERS + queryParameters).mapTo(HashSet()) { it.trim().lowercase() },
            )
        }

        /**
         * `scheme://userinfo@host…` becomes `scheme://<redacted>@host…`. The authority runs from
         * `://` to the first `/`, `?` or `#`; its userinfo ends at the last `@` in it. A URL without
         * userinfo is returned as is.
         */
        private fun maskUserinfo(url: String): String {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return url
            val authorityStart = schemeEnd + 3
            var at = -1
            for (i in authorityStart until url.length) {
                val c = url[i]
                if (c == '/' || c == '?' || c == '#') break
                if (c == '@') at = i
            }
            if (at < 0) return url
            return url.substring(0, authorityStart) + REDACTED + url.substring(at)
        }

        private fun percentDecode(text: String, start: Int, end: Int): String {
            val raw = text.substring(start, end)
            if (raw.indexOf('%') < 0 && raw.indexOf('+') < 0) return raw
            val src = raw.encodeToByteArray()
            val out = ByteArray(src.size)
            var count = 0
            var i = 0
            while (i < src.size) {
                val b = src[i].toInt()
                if (b == '%'.code && i + 2 < src.size) {
                    val high = hexValue(src[i + 1].toInt())
                    val low = hexValue(src[i + 2].toInt())
                    if (high >= 0 && low >= 0) {
                        out[count++] = ((high shl 4) or low).toByte()
                        i += 3
                        continue
                    }
                }
                out[count++] = if (b == '+'.code) ' '.code.toByte() else src[i]
                i++
            }
            return out.decodeToString(0, count)
        }

        private fun hexValue(b: Int): Int = when (b) {
            in '0'.code..'9'.code -> b - '0'.code
            in 'a'.code..'f'.code -> b - 'a'.code + 10
            in 'A'.code..'F'.code -> b - 'A'.code + 10
            else -> -1
        }
    }
}
