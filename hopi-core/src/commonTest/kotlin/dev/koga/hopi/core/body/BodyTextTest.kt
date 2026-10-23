package dev.koga.hopi.core.body

import dev.koga.hopi.core.model.CapturedBody
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyTextTest {
    @Test
    fun strictUtf8Decoding() {
        assertEquals("héllo 😀", decodeUtf8Strict("héllo 😀".encodeToByteArray()))
        assertEquals("", decodeUtf8Strict(ByteArray(0)))
        assertNull(decodeUtf8Strict(byteArrayOf(0xC3.toByte(), 0x28)), "invalid continuation")
        assertNull(decodeUtf8Strict(byteArrayOf(0xC0.toByte(), 0x80.toByte())), "overlong")
        assertNull(decodeUtf8Strict(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())), "surrogate")
        assertNull(decodeUtf8Strict(byteArrayOf(0xFF.toByte())))
        assertNull(decodeUtf8Strict(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte())), "NUL means binary")
    }

    @Test
    fun strictDecodingAgreesWithThePlatformsStrictDecoder() {
        // Every 1- to 3-byte input over the bytes that matter (ASCII, NUL, continuation bytes at
        // the edges of each range, every lead class), then random runs mixing them with valid text.
        val interesting = intArrayOf(
            0x00, 0x41, 0x7F, 0x80, 0x8F, 0x90, 0x9F, 0xA0, 0xBF, 0xC0, 0xC1, 0xC2, 0xDF, 0xE0, 0xE1,
            0xEC, 0xED, 0xEE, 0xEF, 0xF0, 0xF1, 0xF3, 0xF4, 0xF5, 0xFF,
        ).map { it.toByte() }
        fun check(bytes: ByteArray) {
            val expected = try {
                bytes.decodeToString(0, bytes.size, throwOnInvalidSequence = true).takeIf { '\u0000' !in it }
            } catch (_: CharacterCodingException) {
                null
            }
            assertEquals(expected, decodeUtf8Strict(bytes), bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16) })
        }
        for (a in interesting) {
            check(byteArrayOf(a))
            for (b in interesting) {
                check(byteArrayOf(a, b))
                for (c in interesting) check(byteArrayOf(a, b, c))
            }
        }
        val pieces = listOf("a", "é", "日", "😀", "\u0000").map { it.encodeToByteArray() } + interesting.map { byteArrayOf(it) }
        val random = Random(42)
        repeat(20_000) {
            val bytes = (0 until random.nextInt(1, 12)).fold(ByteArray(0)) { acc, _ -> acc + pieces[random.nextInt(pieces.size)] }
            check(bytes)
        }
        // Four-byte sequences at the edges: U+10000, U+10FFFF, one past it, and an overlong form.
        check(byteArrayOf(0xF0.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()))
        check(byteArrayOf(0xF4.toByte(), 0x8F.toByte(), 0xBF.toByte(), 0xBF.toByte()))
        check(byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()))
        check(byteArrayOf(0xF0.toByte(), 0x8F.toByte(), 0xBF.toByte(), 0xBF.toByte()))
    }

    @Test
    fun decodesUpToEnd() {
        val bytes = "ab日".encodeToByteArray()
        assertEquals("ab", decodeUtf8Strict(bytes, 2))
        assertNull(decodeUtf8Strict(bytes, 3), "a sequence cut by end is invalid")
    }

    @Test
    fun truncatedBodyDropsAnIncompleteTrailingSequence() {
        val full = "ab😀".encodeToByteArray()
        for (cut in 3 until full.size) {
            val bytes = full.copyOf(cut)
            assertEquals("ab", CapturedBody(bytes, full.size.toLong(), true, "text/plain", null).textOrNull(), "cut=$cut")
            assertNull(CapturedBody(bytes, cut.toLong(), false, "text/plain", null).textOrNull(), "untruncated cut=$cut")
        }
        assertNull(CapturedBody(byteArrayOf(0xFF.toByte(), 'a'.code.toByte()), 10, true, null, null).textOrNull())
    }

    @Test
    fun jsonDetection() {
        assertTrue(isJson("application/json; charset=utf-8", null))
        assertTrue(isJson("application/problem+json", null))
        assertTrue(isJson("text/plain", "  \n[1]"))
        assertTrue(isJson(null, "{\"a\":1}"))
        assertFalse(isJson("text/html", "<html>"))
        assertFalse(isJson(null, "   "))
        assertFalse(isJson(null, null))
    }

    @Test
    fun prettyPrintsValidJson() {
        assertEquals(
            "{\n  \"a\": [\n    1,\n    -2.5e+3,\n    \"x\\\"y\"\n  ],\n  \"b\": {},\n  \"c\": [],\n  \"d\": null,\n  \"e\": true\n}",
            prettyPrintJson(" {\"a\":[1, -2.5e+3,\"x\\\"y\"],\"b\":{ },\"c\":[],\"d\":null,\"e\":true} "),
        )
        assertEquals("\"s\"", prettyPrintJson("\"s\""))
        assertEquals("[\n\t0\n]", prettyPrintJson("[0]", indent = "\t"))
    }

    @Test
    fun invalidJsonIsReturnedUnchanged() {
        val bad = listOf("", "{", "{\"a\":1,}", "[1 2]", "{\"a\" 1}", "01", "1.", "-", "nul", "\"\\x\"", "{} {}", "[\"a\nb\"]", "<html>")
        for (s in bad) assertEquals(s, prettyPrintJson(s), s)
        val deep = "[".repeat(1000) + "]".repeat(1000)
        assertEquals(deep, prettyPrintJson(deep))
    }

    @Test
    fun formatsByteSizes() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.0 MB", formatBytes(1024L * 1024 - 1))
        assertEquals("16.0 MB", formatBytes(16L * 1024 * 1024))
        assertEquals("2.0 GB", formatBytes(2L * 1024 * 1024 * 1024))
    }
}
