package dev.koga.hopi.core.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonWriterTest {
    private fun quoted(s: String) = StringBuilder().also { JsonWriter.appendQuoted(it, s) }.toString()

    @Test
    fun escapesQuotesBackslashesAndControls() {
        assertEquals("\"a\\\"b\\\\c\"", quoted("a\"b\\c"))
        assertEquals("\"\\n\\r\\t\\b\\f\"", quoted("\n\r\t\b\u000C"))
        assertEquals("\"\\u0000\\u0001\\u001f\\u007f\"", quoted("\u0000\u0001\u001F\u007F"))
        assertEquals("\"\\u2028\\u2029\"", quoted("  "))
        assertEquals("\"/ é 😀\"", quoted("/ é 😀"))
        assertEquals("\"\"", quoted(""))
    }

    @Test
    fun escapedLengthMatchesOrBoundsTheEncodedBytes() {
        val sample = "a\"\\\n\u0001 é中😀"
        val encoded = quoted(sample).encodeToByteArray().size - 2
        val counted = sample.sumOf { JsonWriter.escapedUtf8Length(it) }
        assertEquals(encoded + 2, counted, "only the surrogate pair is over-counted, by 2")
    }

    @Test
    fun unpairedSurrogatesAreEscapedAndPairsKept() {
        assertEquals("\"\\ud83dx\"", quoted("\uD83Dx"))
        assertEquals("\"x\\ude00\"", quoted("x\uDE00"))
        assertEquals("\"\\ud83d😀\\ude00\"", quoted("\uD83D\uD83D\uDE00\uDE00"))
        assertEquals("\"😀\"", quoted("😀"))
        val lone = "a\uD83D"
        assertEquals(mapOf("v" to lone), parseFlatJsonObject(JsonWriter().beginObject().field("v", lone).endObject().toString()))
    }

    @Test
    fun escapedLengthOfAStringIsExact() {
        for (sample in listOf("", "a\"\\\n\u0001\u2028é中😀", "\uD83Dx", "x\uDE00", "\uD83D\uD83D\uDE00\uDE00")) {
            assertEquals(quoted(sample).encodeToByteArray().size - 2, JsonWriter.escapedUtf8Length(sample), sample)
        }
    }

    @Test
    fun writesNestedStructuresWithCommas() {
        val w = JsonWriter()
        w.beginObject()
            .field("s", "x")
            .field("n", 42L)
            .field("i", -1)
            .field("b", true)
            .field("z", null as String?)
            .name("arr").beginArray().value(1).value("two").beginArray().endArray().beginObject().endObject().nullValue().endArray()
            .name("obj").beginObject().field("k", false).endObject()
            .name("d").value(1.5)
            .name("whole").value(3.0)
            .name("nan").value(Double.NaN)
            .name("inf").value(Double.POSITIVE_INFINITY)
            .endObject()
        assertEquals(
            "{\"s\":\"x\",\"n\":42,\"i\":-1,\"b\":true,\"z\":null,\"arr\":[1,\"two\",[],{},null]," +
                "\"obj\":{\"k\":false},\"d\":1.5,\"whole\":3,\"nan\":null,\"inf\":null}",
            w.toString(),
        )
    }

    @Test
    fun deepNestingBeyondOneWord() {
        val w = JsonWriter()
        repeat(100) { if (it % 2 == 0) w.beginArray() else w.beginObject().name("k") }
        w.value(1)
        repeat(100) { i -> if ((99 - i) % 2 == 0) w.endArray() else w.endObject() }
        val s = w.toString()
        assertEquals(50, s.count { it == '[' })
        assertEquals(50, s.count { it == '{' })
    }

    @Test
    fun base64Value() {
        assertEquals("[\"AAH/\",\"\"]", JsonWriter().beginArray().base64Value(byteArrayOf(0, 1, -1)).base64Value(ByteArray(0)).endArray().toString())
    }

    @Test
    fun misuseThrows() {
        assertFailsWith<IllegalStateException> { JsonWriter().beginObject().value(1) }
        assertFailsWith<IllegalStateException> { JsonWriter().beginArray().endObject() }
        assertFailsWith<IllegalStateException> { JsonWriter().beginArray().name("x") }
        assertFailsWith<IllegalStateException> { JsonWriter().value(1).value(2) }
    }

    @Test
    fun writerOutputParsesBack() {
        val nasty = "q\"b\\s/\n\u0000 é😀"
        val line = JsonWriter().beginObject().field("cmd", nasty).field("id", 7L).field("ok", true).endObject().toString()
        assertEquals(mapOf("cmd" to nasty, "id" to 7L, "ok" to true), parseFlatJsonObject(line))
    }
}
