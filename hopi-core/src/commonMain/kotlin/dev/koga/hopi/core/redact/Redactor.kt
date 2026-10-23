package dev.koga.hopi.core.redact

import dev.koga.hopi.core.HopiInternalApi

/**
 * Replaces secret header values and query parameter values with [REDACTED]. Header and parameter
 * names match case-insensitively; parameter names are percent-decoded before matching.
 */
@HopiInternalApi
public class Redactor private constructor(
    public val enabled: Boolean,
    private val headerNames: Set<String>,
    private val queryParameterNames: Set<String>,
) {
    public fun isRedactedHeader(name: String): Boolean =
        enabled && name.lowercase() in headerNames

    public fun headerValue(name: String, value: String): String =
        if (isRedactedHeader(name)) REDACTED else value

    public fun headers(headers: List<Pair<String, String>>): List<Pair<String, String>> {
        if (!enabled || headers.none { isRedactedHeader(it.first) }) return headers
        return headers.map { (name, value) -> if (isRedactedHeader(name)) name to REDACTED else name to value }
    }

    /** [text] (an error message, say) with every occurrence of [url] replaced by its redacted form. */
    public fun textMentioning(text: String, url: String): String {
        if (!enabled || url.isEmpty() || !text.contains(url)) return text
        val redacted = url(url)
        return if (redacted === url) text else text.replace(url, redacted)
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

    private fun redactQuery(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val fragmentStart = url.indexOf('#', queryStart + 1).let { if (it < 0) url.length else it }
        var out: StringBuilder? = null
        var partStart = queryStart + 1
        while (partStart <= fragmentStart) {
            val partEnd = url.indexOf('&', partStart).let { if (it < 0 || it > fragmentStart) fragmentStart else it }
            val equals = url.indexOf('=', partStart).let { if (it < 0 || it > partEnd) -1 else it }
            if (equals >= 0 && equals + 1 < partEnd &&
                percentDecode(url, partStart, equals).lowercase() in queryParameterNames
            ) {
                val builder = out ?: StringBuilder(url.length).also { it.append(url, 0, partStart) }
                builder.append(url, partStart, equals + 1).append(REDACTED)
                out = builder
            } else {
                out?.append(url, partStart, partEnd)
            }
            out?.let { if (partEnd < fragmentStart) it.append('&') }
            partStart = partEnd + 1
        }
        val result = out ?: return url
        result.append(url, fragmentStart, url.length)
        return result.toString()
    }

    public companion object {
        public const val REDACTED: String = "<redacted>"

        public val DEFAULT_HEADERS: List<String> =
            listOf("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie")

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
                queryParameterNames = queryParameters.mapTo(HashSet()) { it.lowercase() },
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
