package dev.koga.hopi.core.rules

import dev.koga.hopi.api.HopiInternalApi

/**
 * A compiled glob (docs/design/rules.md, "Match"). [Mode.HOST] and [Mode.TEXT]: `*` matches any
 * run of characters. [Mode.PATH]: `*` matches within one segment (no `/`), `**` across segments.
 * [Mode.HOST] compares case-insensitively (the pattern is lowercased at compile time); the others
 * are case-sensitive. No other character is special.
 *
 * The literal prefix and suffix are checked first with one `regionMatches` each, so most
 * non-matching inputs are rejected without a scan, and a pattern without a wildcard is one
 * comparison. What lies between runs as a state-set simulation (a Thompson NFA over the pattern's
 * characters), linear in the input times the number of live states and never backtracking: at
 * most O(pattern × input), with the pattern capped at [MAX_PATTERN_CHARS]. A middle part of up to
 * 63 tokens (every realistic pattern) keeps its states in one `Long` and allocates nothing.
 */
@HopiInternalApi
public class Glob private constructor(
    public val pattern: String,
    private val mode: Mode,
    private val prefix: String,
    private val suffix: String,
    /** The tokens between [prefix] and [suffix]; empty when the pattern has no wildcard. */
    private val tokens: IntArray,
    private val hasWildcard: Boolean,
) {
    @HopiInternalApi
    public enum class Mode { HOST, PATH, TEXT }

    private val ignoreCase = mode == Mode.HOST

    /** Bits of [tokens] that are wildcards (a star matches empty, so its state also enables the next). */
    private val starBits: Long = if (tokens.size < 64) {
        var bits = 0L
        for (i in tokens.indices) if (tokens[i] < 0) bits = bits or (1L shl i)
        bits
    } else {
        0L
    }

    /** The literal suffix every match ends with (a host pattern's `.example.com`), lowercased for a host. */
    public val literalSuffix: String get() = suffix

    public fun matches(input: String): Boolean {
        if (!hasWildcard) return input.length == prefix.length && input.regionMatches(0, prefix, 0, prefix.length, ignoreCase)
        val end = input.length - suffix.length
        if (end < prefix.length) return false
        if (!input.regionMatches(0, prefix, 0, prefix.length, ignoreCase)) return false
        if (!input.regionMatches(end, suffix, 0, suffix.length, ignoreCase)) return false
        return if (tokens.size < 64) matchSmall(input, prefix.length, end) else matchLarge(input, prefix.length, end)
    }

    private fun matchSmall(input: String, from: Int, to: Int): Boolean {
        val m = tokens.size
        val accept = 1L shl m
        var states = close(1L)
        var i = from
        while (i < to && states != 0L) {
            val c = input[i]
            var next = 0L
            var live = states and (accept - 1)
            while (live != 0L) {
                val j = live.countTrailingZeroBits()
                live = live and (live - 1)
                val token = tokens[j]
                when {
                    token == ANY -> next = next or (1L shl j)
                    token == ANY_IN_SEGMENT -> if (c != '/') next = next or (1L shl j)
                    sameChar(token.toChar(), c) -> next = next or (1L shl (j + 1))
                }
            }
            states = close(next)
            i++
        }
        return states and accept != 0L
    }

    /** A star's state also holds the state after it (the star matched nothing so far). Stars never follow each other. */
    private fun close(states: Long): Long = states or ((states and starBits) shl 1)

    /** [matchSmall] for a middle part of 64 tokens or more: the same simulation over a bit array. */
    private fun matchLarge(input: String, from: Int, to: Int): Boolean {
        val m = tokens.size
        var states = BooleanArray(m + 1)
        var next = BooleanArray(m + 1)
        states[0] = true
        closeLarge(states)
        var i = from
        while (i < to) {
            val c = input[i]
            next.fill(false)
            var any = false
            for (j in 0 until m) {
                if (!states[j]) continue
                val token = tokens[j]
                when {
                    token == ANY -> next[j] = true
                    token == ANY_IN_SEGMENT -> if (c != '/') next[j] = true
                    sameChar(token.toChar(), c) -> next[j + 1] = true
                }
            }
            closeLarge(next)
            for (s in next) if (s) any = true
            val swap = states
            states = next
            next = swap
            if (!any) return false
            i++
        }
        return states[m]
    }

    private fun closeLarge(states: BooleanArray) {
        for (j in tokens.indices) if (states[j] && tokens[j] < 0) states[j + 1] = true
    }

    private fun sameChar(a: Char, b: Char): Boolean = a == b || ignoreCase && a == b.lowercaseChar()

    override fun toString(): String = pattern

    override fun equals(other: Any?): Boolean = other is Glob && other.pattern == pattern && other.mode == mode

    override fun hashCode(): Int = pattern.hashCode() * 31 + mode.hashCode()

    public companion object {
        public const val MAX_PATTERN_CHARS: Int = 256
        private const val ANY = -1
        private const val ANY_IN_SEGMENT = -2

        /** Compiles [pattern]; the caller has checked its length. */
        public fun compile(pattern: String, mode: Mode): Glob {
            val source = if (mode == Mode.HOST) pattern.lowercase() else pattern
            val raw = ArrayList<Int>(source.length)
            var i = 0
            while (i < source.length) {
                val c = source[i]
                if (c == '*') {
                    var stars = 1
                    while (i + stars < source.length && source[i + stars] == '*') stars++
                    val token = if (mode == Mode.PATH && stars == 1) ANY_IN_SEGMENT else ANY
                    // Adjacent wildcards merge: `*` next to `**` is `**`.
                    if (raw.isNotEmpty() && raw.last() < 0) {
                        if (token == ANY) raw[raw.lastIndex] = ANY
                    } else {
                        raw += token
                    }
                    i += stars
                } else {
                    raw += c.code
                    i++
                }
            }
            val first = raw.indexOfFirst { it < 0 }
            if (first < 0) return Glob(pattern, mode, source, "", IntArray(0), hasWildcard = false)
            val last = raw.indexOfLast { it < 0 }
            val prefix = raw.subList(0, first).joinToString("") { it.toChar().toString() }
            val suffix = raw.subList(last + 1, raw.size).joinToString("") { it.toChar().toString() }
            return Glob(pattern, mode, prefix, suffix, raw.subList(first, last + 1).toIntArray(), hasWildcard = true)
        }
    }
}
