package dev.koga.hopi.ui.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextTest {
    @Test
    fun durations() {
        assertEquals("0 ms", formatDuration(0))
        assertEquals("999 ms", formatDuration(999))
        assertEquals("1.00 s", formatDuration(1_000))
        assertEquals("1.24 s", formatDuration(1_235))
        assertEquals("12.3 s", formatDuration(12_340))
        assertEquals("1m 05s", formatDuration(65_000))
    }

    @Test
    fun urlParts() {
        splitUrl("https://api.example.com/users?page=2#top").let {
            assertEquals("api.example.com", it.host)
            assertEquals("/users?page=2#top", it.path)
        }
        splitUrl("http://u:p@127.0.0.1:8765").let {
            assertEquals("127.0.0.1:8765", it.host)
            assertEquals("/", it.path)
        }
    }

    @Test
    fun reasonPhrases() {
        assertEquals("Not Found", reasonPhrase(404))
        assertEquals(null, reasonPhrase(299))
    }

    @Test
    fun chunksSplitAtLineBoundariesAndKeepEveryLine() {
        val lines = (1..200).map { "line $it" }
        val chunks = chunkText(lines.joinToString("\n"), maxLines = 64)
        assertEquals(listOf(64, 64, 64, 8), chunks.map { it.lines().size })
        assertEquals(lines, chunks.flatMap { it.lines() })
    }

    @Test
    fun chunksCutLinesLongerThanAPieceAndRespectTheCharBudget() {
        val long = "x".repeat(10_000)
        val text = "head\n$long\ntail"
        val chunks = chunkText(text, maxChars = 4_096)
        assertTrue(chunks.all { it.length <= 4_096 }, chunks.map { it.length }.toString())
        // The long line starts its own piece instead of being glued to "head".
        assertEquals(listOf("head", "x".repeat(4_096), "x".repeat(4_096), "x".repeat(1_808) + "\ntail"), chunks)
    }

    @Test
    fun chunksNeverSplitASurrogatePair() {
        val text = "a".repeat(4_095) + "😀" + "b"
        val chunks = chunkText(text, maxChars = 4_096)
        assertEquals(4_095, chunks[0].length)
        assertEquals("😀b", chunks[1])
    }

    @Test
    fun piecesRebuildTheExactText() {
        val long = "x".repeat(10_000)
        for (text in listOf("", "a", "a\n", "\n\n", "head\n$long\ntail\n", (1..300).joinToString("\n") { "line $it" }, long)) {
            val pieces = splitText(text, maxChars = 4_096)
            assertEquals(chunkText(text, maxChars = 4_096), pieces.pieces)
            assertEquals(text, pieces.join(), "round trip of a ${text.length}-char text")
            assertEquals(text.take(5_000), pieces.join(maxChars = 5_000))
        }
    }

    @Test
    fun hexDumpShowsOffsetsBytesAndAscii() {
        val bytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(14)
        val dump = hexDump(bytes).lines()
        assertEquals(2, dump.size)
        assertEquals("00000000  89 50 4e 47 00 00 00 00  00 00 00 00 00 00 00 00  .PNG............", dump[0])
        assertTrue(dump[1].startsWith("00000010  00 00 "))
        assertTrue(dump[1].endsWith(" .."))
        assertEquals(32, hexDump(ByteArray(1_000), limit = 512).lines().size)
    }
}
