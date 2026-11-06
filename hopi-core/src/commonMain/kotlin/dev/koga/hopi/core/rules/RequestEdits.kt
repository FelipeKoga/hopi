package dev.koga.hopi.core.rules

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.redact.Redactor

/** A request after a [RequestEdit]: what a source sends, and what the record holds as sent. */
@HopiInternalApi
public class EditedRequest(
    public val method: String,
    public val url: String,
    public val headers: List<Pair<String, String>>,
    /** The body to send; null when the edit kept the original (or there is none). */
    public val body: RuleBody?,
    /** True when [url] differs from the original in host or port. */
    public val originChanged: Boolean,
)

/**
 * Applies a [RequestEdit] the same way on every source (docs/design/rules.md, "Edit"): the scheme
 * never changes; an edit that changes the origin removes every original value of `Authorization`,
 * `Proxy-Authorization`, `Cookie` and every header [redactor] redacts, as OkHttp and browsers do on
 * a cross-host redirect, so only the values the edit supplies itself (set or added) reach the other
 * host; header edits run remove, then set (replacing every value of the name), then add.
 */
@HopiInternalApi
public object RequestEdits {
    private val CREDENTIALS = setOf("authorization", "proxy-authorization", "cookie")

    /** Null when [url] is not an absolute `http`/`https` URL (the edit cannot be placed). */
    public fun apply(
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        edit: RequestEdit,
        redactor: Redactor,
    ): EditedRequest? {
        val parts = UrlParts.parse(url) ?: return null
        val editedUrl = if (edit.host == null && edit.port == null && edit.path == null && edit.setQuery.isEmpty() && edit.removeQuery.isEmpty()) {
            url
        } else {
            val host = edit.host?.removePrefix("[")?.removeSuffix("]") ?: parts.host
            val port = edit.port?.let { if (it == parts.defaultPort && parts.port == null) null else it } ?: parts.port
            parts.copy(host = host, port = port, path = edit.path ?: parts.path, query = editQuery(parts.query, edit)).toString()
        }
        val originChanged = changesOrigin(parts, edit)
        return EditedRequest(
            method = edit.method ?: method,
            url = editedUrl,
            headers = editHeaders(headers, edit, if (originChanged) redactor else null),
            body = edit.body,
            originChanged = originChanged,
        )
    }

    /**
     * Whether [edit] sends a request for [url] to another origin (host or port). Hosts compare
     * ignoring ASCII case only: a Unicode case mapping (`ı` and `i`) never makes two hosts the same,
     * so a look-alike host is always another origin. True for a URL that is not absolute `http(s)`.
     */
    public fun changesOrigin(url: String, edit: RequestEdit): Boolean {
        if (edit.host == null && edit.port == null) return false
        return changesOrigin(UrlParts.parse(url) ?: return true, edit)
    }

    private fun changesOrigin(parts: UrlParts, edit: RequestEdit): Boolean =
        (edit.host != null && !asciiEqualsIgnoreCase(edit.host.removePrefix("[").removeSuffix("]"), parts.host)) ||
            (edit.port != null && edit.port != parts.effectivePort)

    private fun asciiEqualsIgnoreCase(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (x == y) continue
            if (x.code >= 0x80 || y.code >= 0x80) return false
            if ((if (x in 'A'..'Z') x + 32 else x) != (if (y in 'A'..'Z') y + 32 else y)) return false
        }
        return true
    }

    /**
     * [headers] after [edit]'s header changes; with [crossOrigin] (the redactor of the store), every
     * original value of the credentials and redacted headers is removed first, whatever the edit
     * sets or adds: an added `Cookie` never travels next to the app's own.
     */
    public fun editHeaders(headers: List<Pair<String, String>>, edit: RequestEdit, crossOrigin: Redactor?): List<Pair<String, String>> {
        val removed = (edit.removeHeaders + edit.setHeaders.map { it.first }).map { it.lowercase() }.toSet()
        val result = ArrayList<Pair<String, String>>(headers.size + edit.setHeaders.size + edit.addHeaders.size)
        for (header in headers) {
            val name = header.first.lowercase()
            if (name in removed) continue
            if (crossOrigin != null && (name in CREDENTIALS || crossOrigin.isRedactedHeader(header.first))) continue
            result += header
        }
        result += edit.setHeaders
        result += edit.addHeaders
        return result
    }

    /** The raw query after `removeQuery` and `setQuery` (each set name replaces every value of it). */
    private fun editQuery(query: String?, edit: RequestEdit): String? {
        if (edit.setQuery.isEmpty() && edit.removeQuery.isEmpty()) return query
        val drop = (edit.removeQuery + edit.setQuery.map { it.first }).toSet()
        val kept = query?.split('&')?.filter { part ->
            part.isNotEmpty() && UrlParts.percentDecode(part.substringBefore('=')) !in drop
        }.orEmpty()
        val added = edit.setQuery.map { (name, value) -> UrlParts.percentEncode(name) + "=" + UrlParts.percentEncode(value) }
        val all = kept + added
        return if (all.isEmpty()) null else all.joinToString("&")
    }
}
