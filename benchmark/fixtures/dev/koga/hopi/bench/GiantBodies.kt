package dev.koga.hopi.bench

/**
 * Deterministic JSON bodies for the giant-body benchmarks, generated in memory so no large file
 * is checked in. Every body is valid JSON of at least the requested size in UTF-8 bytes (a few
 * bytes over, to close the document); the same shape and size always give the same bytes.
 *
 * This file is compiled into the JVM test sources of every module that runs one of those
 * benchmarks (see each module's `jvmGiantBodyBenchmark` task).
 */
internal enum class Shape(val label: String) {
    /** One line: `{"items":[{…},{…},…]}`. */
    Minified("minified records"),

    /** The same records, two-space indented, one field per line. */
    Pretty("pretty records"),

    /** `[[[[…]]]]`: nesting as deep as the size allows, far beyond the pretty-printer's 256 cap. */
    Deep("deeply nested"),

    /** `{"data":"…"}`: a single string value of the whole size, no newline anywhere. */
    HugeString("one huge string"),

    /** CJK and emoji strings; byte 262144 (the default cap) falls inside a multi-byte sequence. */
    Unicode("unicode CJK/emoji"),

    /** `[0.5,-12,…]`: a flat array of numbers on one line. */
    Numbers("number array"),
}

internal object GiantBodies {
    const val KB: Int = 1024
    const val MB: Int = 1024 * 1024

    /** The default per-body cap, where the Unicode shape must cut a multi-byte sequence. */
    const val DEFAULT_CAP: Int = 256 * KB

    val SIZES: List<Int> = listOf(256 * KB, MB, 5 * MB, 20 * MB, 50 * MB)

    fun label(bytes: Int): String = when {
        bytes >= MB && bytes % MB == 0 -> "${bytes / MB} MB"
        bytes >= KB && bytes % KB == 0 -> "${bytes / KB} KB"
        else -> "$bytes B"
    }

    fun text(shape: Shape, bytes: Int): String = when (shape) {
        Shape.Minified -> records(bytes, pretty = false)
        Shape.Pretty -> records(bytes, pretty = true)
        Shape.Deep -> deep(bytes)
        Shape.HugeString -> hugeString(bytes)
        Shape.Unicode -> unicode(bytes)
        Shape.Numbers -> numbers(bytes)
    }

    fun bytes(shape: Shape, bytes: Int): ByteArray = text(shape, bytes).encodeToByteArray()

    private fun records(bytes: Int, pretty: Boolean): String {
        val out = StringBuilder(bytes + 256)
        val nl = if (pretty) "\n" else ""
        val i1 = if (pretty) "  " else ""
        val i2 = if (pretty) "    " else ""
        val i3 = if (pretty) "      " else ""
        val sep = if (pretty) ": " else ":"
        out.append('{').append(nl).append(i1).append("\"items\"").append(sep).append('[').append(nl)
        var id = 0
        // Every char is ASCII, so the builder's length is the UTF-8 size.
        while (out.length < bytes - 64) {
            if (id > 0) out.append(',').append(nl)
            out.append(i2).append('{').append(nl)
            out.append(i3).append("\"id\"").append(sep).append(id).append(',').append(nl)
            out.append(i3).append("\"name\"").append(sep).append("\"item-").append(id).append("\",").append(nl)
            out.append(i3).append("\"price\"").append(sep).append(id % 1000).append('.').append(id % 97 + 2).append(',').append(nl)
            out.append(i3).append("\"active\"").append(sep).append(if (id % 3 == 0) "false" else "true").append(',').append(nl)
            out.append(i3).append("\"tags\"").append(sep).append("[\"t").append(id % 7).append("\",\"t").append(id % 11).append("\"],").append(nl)
            out.append(i3).append("\"note\"").append(sep).append("\"Lorem ipsum dolor sit amet \\\"").append(id).append("\\\" \\u00e9\"").append(nl)
            out.append(i2).append('}')
            id++
        }
        out.append(nl).append(i1).append(']').append(nl).append('}')
        return out.toString()
    }

    private fun deep(bytes: Int): String {
        val depth = bytes / 2
        val out = StringBuilder(depth * 2)
        repeat(depth) { out.append('[') }
        repeat(depth) { out.append(']') }
        return out.toString()
    }

    private fun hugeString(bytes: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val length = maxOf(0, bytes - 11)
        val out = StringBuilder(bytes)
        out.append("{\"data\":\"")
        var seed = 0x2545F491
        repeat(length) {
            seed = seed xor (seed shl 13)
            seed = seed xor (seed ushr 17)
            seed = seed xor (seed shl 5)
            out.append(alphabet[seed and 63])
        }
        out.append("\"}")
        return out.toString()
    }

    /**
     * `{"u":["日本語…🎉…","…",…]}`. Leading spaces in front of the document move every later
     * byte, and the count is chosen so that byte [DEFAULT_CAP] is a continuation byte.
     */
    private fun unicode(bytes: Int): String {
        val words = listOf("日本語", "テキスト", "中文字符", "한국어", "🎉", "🚀", "👩‍💻", "ü", "é", "∑")
        for (lead in 0..3) {
            val out = StringBuilder(bytes)
            var utf8 = 0
            fun add(s: String) {
                out.append(s)
                utf8 += utf8Size(s)
            }
            repeat(lead) { add(" ") }
            add("{\"u\":[")
            var i = 0
            while (utf8 < bytes - 16) {
                if (i > 0) add(",")
                add("\"")
                for (k in 0 until 6) add(words[(i + k * 3) % words.size])
                add("\"")
                i++
            }
            add("]}")
            val text = out.toString()
            if (bytes <= DEFAULT_CAP) return text
            val encoded = text.encodeToByteArray()
            if (encoded[DEFAULT_CAP].toInt() and 0xC0 == 0x80) return text
        }
        error("no lead puts byte $DEFAULT_CAP inside a multi-byte sequence")
    }

    private fun numbers(bytes: Int): String {
        val out = StringBuilder(bytes + 32)
        out.append('[')
        var i = 0
        while (out.length < bytes - 24) {
            if (i > 0) out.append(',')
            when (i % 4) {
                0 -> out.append(i)
                1 -> out.append(-i).append(".25")
                2 -> out.append(i % 1000).append("e-3")
                else -> out.append("0.").append(i % 9973)
            }
            i++
        }
        out.append(']')
        return out.toString()
    }

    fun utf8Size(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return n
    }
}
