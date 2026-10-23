package dev.koga.hopi.core.curl

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.body.decodeUtf8Prefix
import dev.koga.hopi.core.body.textOrNull
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.redact.Redactor

/** File name the binary-body note tells the user to create; the command reads it with `@`. */
@HopiInternalApi
public const val CURL_BINARY_BODY_FILE: String = "body.bin"

/**
 * The request as a single-line POSIX shell `curl` command. Comment lines (`# warning:` or
 * `# note:`) precede it whenever the command cannot reproduce the request byte for byte.
 * `Content-Length` and `Transfer-Encoding` are left to curl, which frames the body it sends.
 *
 * [maxChars] bounds the command for a clipboard or a share sheet: a text body that would make it
 * longer is cut (at a code point, inside a properly closed quote) with a `# warning:` line
 * starting with [CURL_BODY_CUT_WARNING], and only that much of it is decoded and quoted, so a
 * giant body never costs more than the limit. The command exceeds [maxChars] only when its URL
 * and headers alone do.
 */
@HopiInternalApi
public fun toCurl(transaction: HttpTransaction, redaction: Redactor = Redactor.Default, maxChars: Int = Int.MAX_VALUE): String {
    require(maxChars > 0) { "maxChars must be positive" }
    val comments = StringBuilder()
    val headers = StringBuilder()
    var contentType: String? = null
    var declaredLength: String? = null
    var transferEncoding = false
    var compressed = false
    for ((name, value) in transaction.requestHeaders) {
        when {
            name.equals("Content-Length", ignoreCase = true) -> {
                declaredLength = value.trim()
                continue
            }
            name.equals("Transfer-Encoding", ignoreCase = true) -> {
                transferEncoding = true
                continue
            }
            name.equals("Content-Type", ignoreCase = true) -> contentType = value
            name.equals("Accept-Encoding", ignoreCase = true) && advertisesCompression(value) -> compressed = true
        }
        headers.append(" -H ")
        val shown = redaction.headerValue(name, value)
        // curl drops a header written as `Name:` with nothing after it; `Name;` sends it empty.
        appendShellQuoted(headers, if (shown.isBlank()) "$name;" else "$name: $shown")
    }
    if (compressed) headers.append(" --compressed")
    val url = redaction.url(transaction.url)

    val data = StringBuilder()
    var sentBytes = 0
    val body = transaction.requestBody
    val isMultipart = (contentType ?: body?.contentType)?.trim()?.startsWith("multipart/", ignoreCase = true) == true
    when {
        body == null -> if (isMultipart) multipartWarning(comments)
        body.skippedReason != null || isMultipart -> {
            if (isMultipart) {
                multipartWarning(comments)
            } else {
                comments.append("# warning: request body was not captured (")
                    .append(body.skippedReason)
                    .append("); this command sends no body\n")
            }
        }
        body.bytes.isEmpty() -> Unit
        else -> {
            if (body.truncated) {
                comments.append("# warning: request body truncated to ")
                    .append(body.bytes.size)
                    .append(" of ")
                    .append(body.totalBytes?.toString() ?: "unknown")
                    .append(" bytes; this command sends the truncated body\n")
            }
            sentBytes = body.bytes.size
            // What the body's quoted text may take: the limit minus everything else the command
            // holds, with room for the comment lines still to come.
            val budget = if (maxChars == Int.MAX_VALUE) {
                Int.MAX_VALUE
            } else {
                val rest = comments.length.toLong() + headers.length + shellQuotedLength(url) + transaction.method.length +
                    DATA_RAW.length + COMMAND_SLACK
                (maxChars - rest).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            }
            // A body longer than 4 bytes per char of the budget is cut for sure: decode only that.
            val text = if (budget < Int.MAX_VALUE / 4 && body.bytes.size > budget * 4) {
                decodeUtf8Prefix(body.bytes, budget * 4)
            } else {
                body.textOrNull()
            }
            if (text != null && budget < MIN_BODY_BUDGET && shellQuotedLength(text) > budget) {
                // The URL and headers alone fill the limit: no room for any of the body.
                sentBytes = 0
                comments.append(CURL_BODY_CUT_WARNING).append(" out (").append(body.bytes.size)
                    .append(" bytes): the URL and headers alone fill the copy; this command sends no body\n")
            } else if (text != null) {
                data.append(DATA_RAW)
                if (shellQuotedLength(text) <= budget) {
                    // Sized once: a large body must not be copied again by every doubling of the builder.
                    data.ensureCapacity(text.length + 16)
                    appendShellQuoted(data, text)
                } else {
                    val kept = appendShellQuotedPrefix(data, text, budget)
                    sentBytes = utf8Length(text, kept)
                    comments.append(CURL_BODY_CUT_WARNING).append(" to its first ").append(kept)
                        .append(" characters (").append(sentBytes).append(" of ").append(body.bytes.size)
                        .append(" bytes) to fit the copy; this command sends the cut body\n")
                }
            } else {
                comments.append("# note: binary request body (")
                    .append(body.bytes.size)
                    .append(" bytes) is not inlined; save it as ")
                    .append(CURL_BINARY_BODY_FILE)
                    .append(" before running this command\n")
                data.append(" --data-binary '@").append(CURL_BINARY_BODY_FILE).append('\'')
            }
        }
    }
    if (declaredLength != null && declaredLength != sentBytes.toString()) {
        comments.append("# note: Content-Length header (").append(declaredLength)
            .append(") omitted; curl sends the length of the body in this command (").append(sentBytes).append(")\n")
    }
    if (transferEncoding) {
        comments.append("# note: Transfer-Encoding header omitted; curl frames the body itself\n")
    }

    // Comments first, in the one builder that becomes the result, sized for all of it.
    val command = StringBuilder(comments.length + url.length + headers.length + data.length + transaction.method.length + 32)
    command.append(comments).append("curl ")
    if (transaction.method.equals("HEAD", ignoreCase = true) && data.isEmpty()) {
        // -X HEAD makes curl wait for a body a HEAD response never sends.
        command.append("-I")
    } else {
        command.append("-X ")
        appendMethod(command, transaction.method)
    }
    command.append(' ')
    appendShellQuoted(command, url)
    command.append(headers).append(data)
    return command.toString()
}

private fun multipartWarning(comments: StringBuilder) {
    comments.append("# warning: multipart request body is not reproduced; this command sends no body\n")
}

/** Starts the `# warning:` line of a body that [toCurl] cut to fit its `maxChars`. */
@HopiInternalApi
public const val CURL_BODY_CUT_WARNING: String = "# warning: request body cut"

private const val DATA_RAW = " --data-raw "

/** Below this many chars a cut body is left out instead: a few chars of it help nobody. */
private const val MIN_BODY_BUDGET = 16

/** Room for `curl -X `, the quotes and the comment lines added after the body's budget is set. */
private const val COMMAND_SLACK = 400

/** Length of [value] once [appendShellQuoted] quotes it. */
private fun shellQuotedLength(value: String): Long {
    var quotes = 0L
    for (c in value) if (c == '\'') quotes++
    return value.length + 2 + 3 * quotes
}

/**
 * Quotes the longest prefix of [value] whose quoted form, closing quote included, takes at most
 * [budget] chars (never ending inside a surrogate pair) and returns its length in chars.
 */
private fun appendShellQuotedPrefix(out: StringBuilder, value: String, budget: Int): Int {
    out.append('\'')
    var used = 2
    var i = 0
    while (i < value.length) {
        val c = value[i]
        val step = if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) 2 else 1
        val cost = if (c == '\'') 4 else step
        if (used + cost > budget) break
        if (c == '\'') out.append("'\\''") else out.append(value, i, i + step)
        used += cost
        i += step
    }
    out.append('\'')
    return i
}

/** UTF-8 bytes of the first [end] chars of [text]. */
private fun utf8Length(text: String, end: Int): Int {
    var bytes = 0
    var i = 0
    while (i < end) {
        val c = text[i].code
        bytes += when {
            c < 0x80 -> 1
            c < 0x800 -> 2
            c in 0xD800..0xDBFF && i + 1 < end -> {
                i++
                4
            }
            else -> 3
        }
        i++
    }
    return bytes
}

/** POSIX single quoting: everything literal, `'` becomes `'\''`. */
@HopiInternalApi
public fun appendShellQuoted(out: StringBuilder, value: String) {
    out.append('\'')
    var runStart = 0
    var quote = value.indexOf('\'')
    while (quote >= 0) {
        out.append(value, runStart, quote).append("'\\''")
        runStart = quote + 1
        quote = value.indexOf('\'', runStart)
    }
    out.append(value, runStart, value.length).append('\'')
}

private fun appendMethod(out: StringBuilder, method: String) {
    val isToken = method.isNotEmpty() && method.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
    if (isToken) out.append(method) else appendShellQuoted(out, method)
}

private fun advertisesCompression(acceptEncoding: String): Boolean =
    acceptEncoding.split(',').any { token ->
        when (token.substringBefore(';').trim().lowercase()) {
            "gzip", "deflate", "br" -> true
            else -> false
        }
    }
