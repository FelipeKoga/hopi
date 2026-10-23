package dev.koga.hopi.core.bridge

import dev.koga.hopi.core.json.parseFlatJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BridgeCommandTest {
    private fun ok(line: String) = assertIs<BridgeParseResult.Ok>(BridgeCommand.parse(line), line).command

    private fun invalid(line: String) = assertIs<BridgeParseResult.Invalid>(BridgeCommand.parse(line), line)

    @Test
    fun parsesEveryContractCommand() {
        assertEquals(BridgeCommand.Hello, ok("{\"cmd\":\"hello\"}"))
        assertEquals(BridgeCommand.List(0, 100), ok("{\"cmd\":\"list\",\"since\":0,\"limit\":100}"))
        assertEquals(BridgeCommand.List(5, 100), ok(" { \"since\" : 5 , \"cmd\" : \"list\" } "))
        assertEquals(BridgeCommand.Get(42), ok("{\"cmd\":\"get\",\"id\":42}"))
        assertEquals(BridgeCommand.Tail(0), ok("{\"cmd\":\"tail\",\"since\":0}"))
        assertEquals(BridgeCommand.Tail(0), ok("{\"cmd\":\"tail\"}"))
        assertEquals(BridgeCommand.Clear, ok("{\"cmd\":\"clear\",\"extra\":null}"))
    }

    @Test
    fun rejectsBadCommands() {
        invalid("")
        invalid("hello")
        invalid("{\"cmd\":\"nope\"}")
        invalid("{\"cmd\":1}")
        invalid("{}")
        invalid("{\"cmd\":\"get\"}")
        invalid("{\"cmd\":\"get\",\"id\":0}")
        invalid("{\"cmd\":\"get\",\"id\":\"42\"}")
        invalid("{\"cmd\":\"list\",\"since\":-1}")
        invalid("{\"cmd\":\"list\",\"limit\":0}")
        invalid("{\"cmd\":\"list\",\"limit\":1000000}")
        invalid("{\"cmd\":\"get\",\"id\":1.5}")
        invalid("{\"cmd\":\"get\",\"id\":99999999999999999999}")
        invalid("{\"cmd\":\"hello\"" + " ".repeat(5000) + "}")
    }

    @Test
    fun flatParserRejectsMalformedInputSafely() {
        val bad = listOf(
            "{", "}", "[1]", "{\"a\":}", "{\"a\":1,}", "{\"a\":1}x", "{\"a\":[1]}", "{\"a\":{}}",
            "{\"a\":\"x}", "{\"a\":\"\\q\"}", "{\"a\":\"\\u12\"}", "{\"a\":\"\u0001\"}", "{\"a\":01}",
            "{\"a\":1,\"a\":2}", "{a:1}", "{\"a\":tru}", "{\"a\":-}", "{\"a\":1 \"b\":2}",
        )
        for (s in bad) assertNull(parseFlatJsonObject(s), s)
        assertEquals(mapOf("a" to "\u00e9\n", "b" to -3L, "c" to false, "d" to null), parseFlatJsonObject("{\"a\":\"\\u00e9\\n\",\"b\":-3,\"c\":false,\"d\":null}"))
        assertEquals(emptyMap(), parseFlatJsonObject(" {} "))
    }
}
