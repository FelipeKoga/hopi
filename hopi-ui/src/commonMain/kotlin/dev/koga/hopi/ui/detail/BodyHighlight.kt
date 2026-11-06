package dev.koga.hopi.ui.detail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import dev.koga.hopi.ui.theme.HopiColors

/** A piece starts inside a string (a long string value cut across pieces). */
private const val IN_STRING = 1

/** ...right after its backslash. */
private const val ESCAPED = 2

/**
 * Where each of a JSON body's [pieces] starts: inside a string or not, so a piece is highlighted on
 * its own when a long string runs across pieces. One pass over the body, off the main thread.
 */
internal fun jsonPieceStates(pieces: List<String>): ByteArray {
    val states = ByteArray(pieces.size)
    var state = 0
    for (i in pieces.indices) {
        states[i] = state.toByte()
        var inString = state and IN_STRING != 0
        var escaped = state and ESCAPED != 0
        for (c in pieces[i]) {
            when {
                !inString -> if (c == '"') inString = true
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
        }
        state = (if (inString) IN_STRING else 0) or (if (escaped) ESCAPED else 0)
    }
    return states
}

/** The colors of a JSON body's tokens, from the theme. */
internal class JsonPalette(
    val key: Color,
    val string: Color,
    val number: Color,
    val literal: Color,
    val punctuation: Color,
    val match: Color,
    val currentMatch: Color,
    val onCurrentMatch: Color,
) {
    companion object {
        fun of(colors: HopiColors): JsonPalette = JsonPalette(
            key = colors.info.fg,
            string = colors.success.fg,
            number = colors.warning.fg,
            literal = colors.fuchsia.fg,
            punctuation = colors.textFaint,
            match = colors.warning.bg,
            currentMatch = colors.primary,
            onCurrentMatch = colors.onPrimary,
        )
    }
}

/**
 * [piece] with its JSON tokens in [palette]'s colors (when [state] is not null: the piece is JSON,
 * [jsonPieceStates]' entry for it) and the search's [matches] in it marked, the [current] one
 * strongest. A string whose end is in a later piece is a value's color: a key is never that long.
 */
internal fun highlightPiece(
    piece: String,
    state: Int?,
    palette: JsonPalette,
    matches: List<IntRange> = emptyList(),
    current: IntRange? = null,
): AnnotatedString = buildAnnotatedString {
    append(piece)
    if (state != null) addJsonStyles(piece, state, palette)
    for (range in matches) {
        if (range == current) {
            addStyle(SpanStyle(background = palette.currentMatch, color = palette.onCurrentMatch), range.first, range.last + 1)
        } else {
            addStyle(SpanStyle(background = palette.match), range.first, range.last + 1)
        }
    }
}

private fun AnnotatedString.Builder.addJsonStyles(piece: String, state: Int, palette: JsonPalette) {
    val n = piece.length
    var i = 0
    if (state and IN_STRING != 0) {
        val end = stringEnd(piece, 0, escaped = state and ESCAPED != 0)
        addStyle(SpanStyle(color = palette.string), 0, end)
        i = end
    }
    while (i < n) {
        val c = piece[i]
        when {
            c == '"' -> {
                val end = stringEnd(piece, i + 1, escaped = false)
                var after = end
                while (after < n && piece[after].isWhitespace()) after++
                val isKey = after < n && piece[after] == ':'
                addStyle(SpanStyle(color = if (isKey) palette.key else palette.string), i, end)
                i = end
            }
            c == '-' || c in '0'..'9' -> {
                var end = i + 1
                while (end < n && (piece[end] in '0'..'9' || piece[end] == '.' || piece[end] == 'e' || piece[end] == 'E' || piece[end] == '+' || piece[end] == '-')) end++
                addStyle(SpanStyle(color = palette.number), i, end)
                i = end
            }
            c == 't' || c == 'f' || c == 'n' -> {
                var end = i + 1
                while (end < n && piece[end] in 'a'..'z') end++
                addStyle(SpanStyle(color = palette.literal), i, end)
                i = end
            }
            c == '{' || c == '}' || c == '[' || c == ']' || c == ',' || c == ':' -> {
                addStyle(SpanStyle(color = palette.punctuation), i, i + 1)
                i++
            }
            else -> i++
        }
    }
}

/** The index after the closing quote of a string whose content starts at [from], or the piece's end. */
private fun stringEnd(piece: String, from: Int, escaped: Boolean): Int {
    var i = from
    var escape = escaped
    while (i < piece.length) {
        val c = piece[i]
        when {
            escape -> escape = false
            c == '\\' -> escape = true
            c == '"' -> return i + 1
        }
        i++
    }
    return piece.length
}

/** One occurrence of a search in a body: its piece and where in it. */
internal class BodyMatch(val piece: Int, val range: IntRange)

/** At most this many matches are kept: enough to step through, and bounded for any body. */
internal const val MAX_BODY_MATCHES = 10_000

/**
 * Every occurrence of [query] in [pieces], ignoring case, in order, up to [limit]. A match is found
 * within one piece; one cut by a piece's edge (inside a line longer than a piece) is not.
 */
internal fun findInBody(pieces: List<String>, query: String, limit: Int = MAX_BODY_MATCHES): List<BodyMatch> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<BodyMatch>()
    for (p in pieces.indices) {
        val piece = pieces[p]
        var from = 0
        while (true) {
            val at = piece.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            out += BodyMatch(p, at until at + query.length)
            if (out.size >= limit) return out
            from = at + query.length
        }
    }
    return out
}
