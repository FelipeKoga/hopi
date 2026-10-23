package dev.koga.hopi.core.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BridgeLineReaderTest {
    /** Hands out [chunks] one read at a time, then end of input; counts what was read. */
    private class ChunkConnection(private val chunks: Iterator<ByteArray>) : BridgeConnection {
        var bytesRead = 0L

        override fun read(buffer: ByteArray): Int {
            if (!chunks.hasNext()) return -1
            val chunk = chunks.next()
            chunk.copyInto(buffer, 0, 0, chunk.size)
            bytesRead += chunk.size
            return chunk.size
        }

        override fun write(bytes: ByteArray, length: Int) = error("read only")

        override fun shutdown() = Unit

        override fun close() = Unit
    }

    private fun reader(vararg chunks: String, maxChars: Int = 8) =
        BridgeLineReader(ChunkConnection(chunks.map { it.encodeToByteArray() }.iterator()), maxChars)

    private fun BridgeLineReader.text() = assertIs<BridgeLine.Text>(readLine()).text

    @Test
    fun splitsLinesAcrossReadsAndDropsCarriageReturns() {
        val r = reader("ab", "c\nde\r", "\n\nf")
        assertEquals("abc", r.text())
        assertEquals("de", r.text())
        assertEquals("", r.text())
        // A last line without a newline is incomplete: the peer went away mid-request.
        assertSame(BridgeLine.End, r.readLine())
    }

    @Test
    fun capCountsCharsNotBytes() {
        // 8 chars, 16 UTF-8 bytes: within the cap.
        assertEquals("éééééééé", reader("éééééééé\n").text())
        // A supplementary code point is two chars.
        assertEquals("😀😀😀😀", reader("😀😀😀😀\n").text())
        assertSame(BridgeLine.TooLong, reader("😀😀😀😀a\n").readLine())
        assertSame(BridgeLine.TooLong, reader("123456789\n").readLine())
        // The `\r` of a `\r\n` does not count; a `\r` inside the line does.
        assertEquals("12345678", reader("12345678\r\n").text())
        assertSame(BridgeLine.TooLong, reader("12345678\rx\n").readLine())
        assertSame(BridgeLine.TooLong, reader("1234567\r8\n").readLine())
    }

    @Test
    fun anOversizedLineIsDiscardedThroughItsNewlineWithoutBeingHeld() {
        val chunk = ByteArray(4096) { 'x'.code.toByte() }
        val connection = ChunkConnection((List(256) { chunk } + "\nok\n".encodeToByteArray()).iterator())
        val r = BridgeLineReader(connection, maxChars = 8)
        assertSame(BridgeLine.TooLong, r.readLine())
        assertTrue(connection.bytesRead > 1_000_000)
        assertEquals("ok", r.text())
        assertSame(BridgeLine.End, r.readLine())
    }

    @Test
    fun tokensCompareWholeValues() {
        val token = BridgeToken.fromBytes(ByteArray(16) { it.toByte() })
        val text = StringBuilder().also(token::writeTo).toString()
        assertEquals("000102030405060708090a0b0c0d0e0f", text)
        assertTrue(token.matches(text))
        assertFalse(token.matches(text.dropLast(1)))
        assertFalse(token.matches(text + "0"))
        assertFalse(token.matches(text.uppercase()))
        assertFalse(token.matches(""))
        assertFalse("0001" in token.toString())
        assertEquals(64, StringBuilder().also(BridgeToken.generate()::writeTo).length)
    }
}
