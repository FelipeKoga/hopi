package dev.koga.hopi.core.redact

/** [name] as body field names compare: lowercase, without `_` and `-` (`accessToken` is `access_token`). */
internal fun bodyFieldKey(name: CharSequence): String = buildString(name.length) {
    for (c in name) if (c != '_' && c != '-') append(c.lowercaseChar())
}

/** Longer keys are never a secret field's name: they are not compared, nor copied. */
private const val MAX_FIELD_KEY_CHARS = 40

/**
 * [text] (JSON, possibly truncated) with the value of every member whose key is in [names] (as
 * [bodyFieldKey]s) replaced by `"<redacted>"` when it is a string, a number or a literal; an object
 * or array value is kept and its own members are checked. One pass; string contents are skipped
 * whole, so a key quoted inside a string is not a key. Returns [text] itself when nothing matched.
 */
internal fun redactJsonFields(text: String, names: Set<String>): String {
    var out: StringBuilder? = null
    var cursor = 0
    var i = 0
    val n = text.length
    while (i < n) {
        if (text[i] != '"') {
            i++
            continue
        }
        val keyEnd = jsonStringEnd(text, i)
        if (keyEnd < 0) break
        var j = keyEnd
        while (j < n && text[j].isWhitespace()) j++
        if (j >= n || text[j] != ':') {
            i = keyEnd
            continue
        }
        j++
        while (j < n && text[j].isWhitespace()) j++
        val keyChars = keyEnd - 1 - (i + 1)
        if (j < n && keyChars <= MAX_FIELD_KEY_CHARS && bodyFieldKey(text.subSequence(i + 1, keyEnd - 1)) in names) {
            val valueEnd = when (text[j]) {
                '{', '[' -> -1
                // A string cut by truncation runs to the end.
                '"' -> jsonStringEnd(text, j).let { if (it < 0) n else it }
                else -> {
                    var k = j
                    while (k < n && text[k] != ',' && text[k] != '}' && text[k] != ']' && !text[k].isWhitespace()) k++
                    k
                }
            }
            if (valueEnd > j) {
                val builder = out ?: StringBuilder(text.length).also { out = it }
                builder.append(text, cursor, j).append('"').append(Redactor.REDACTED).append('"')
                cursor = valueEnd
                i = valueEnd
                continue
            }
        }
        i = j
    }
    val result = out ?: return text
    result.append(text, cursor, n)
    return result.toString()
}

/** The index after the string that opens at [start] (a `"`), or -1 when it is not closed. */
private fun jsonStringEnd(text: String, start: Int): Int {
    var i = start + 1
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i += 2
            '"' -> return i + 1
            else -> i++
        }
    }
    return -1
}
