package dev.koga.hopi.core.rules

import dev.koga.hopi.core.bridge.BridgeCommand
import dev.koga.hopi.core.bridge.BridgeParseResult
import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.json.parseFlatJsonObject
import dev.koga.hopi.core.json.parseJsonTree
import dev.koga.hopi.core.pending
import dev.koga.hopi.core.redact.Redactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tree parser, the rule commands' parsing and the record marks on the wire. */
class RuleWireTest {
    @Test
    fun treeParserIsAsStrictAsTheFlatOne() {
        val bad = listOf(
            "{", "[1]", "{\"a\":}", "{\"a\":[1,]}", "{\"a\":{\"b\":1,}}", "{\"a\":1}x", "{\"a\":1.5}", "{\"a\":1e3}",
            "{\"a\":01}", "{\"a\":1,\"a\":2}", "{\"a\":{\"b\":1,\"b\":2}}", "{\"a\":\"\u0001\"}", "{a:1}", "{\"a\":[1 2]}",
        )
        for (s in bad) assertNull(parseJsonTree(s), s)
        assertEquals(
            mapOf("a" to listOf(1L, "x", null, true, mapOf("b" to emptyList<Any?>())), "c" to emptyMap<String, Any?>()),
            parseJsonTree("{\"a\":[1,\"x\",null,true,{\"b\":[]}],\"c\":{}}"),
        )
    }

    @Test
    fun treeParserBoundsDepthAndLength() {
        fun nested(depth: Int) = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth)
        assertNotNull(parseJsonTree(nested(6)))
        assertNull(parseJsonTree(nested(7)))
        assertNull(parseJsonTree("{\"a\":\"" + "x".repeat(5000) + "\"}"))
        assertNotNull(parseJsonTree("{\"a\":\"" + "x".repeat(5000) + "\"}", maxLength = 6000))
    }

    private fun ok(line: String) = assertIs<BridgeParseResult.Ok>(BridgeCommand.parse(line), line).command

    private fun invalid(line: String) = assertIs<BridgeParseResult.Invalid>(BridgeCommand.parse(line), line).message

    @Test
    fun ruleCommandsParse() {
        val add = assertIs<BridgeCommand.RulesAdd>(ok("{\"cmd\":\"rules.add\",\"rule\":{\"match\":{\"path\":\"/a\"},\"fail\":\"reset\"}}"))
        assertEquals("reset", add.rule["fail"])
        assertTrue(add.isWrite)
        assertEquals(BridgeCommand.RulesList, ok("{\"cmd\":\"rules.list\"}"))
        assertEquals(BridgeCommand.RulesRemove(3), ok("{\"cmd\":\"rules.remove\",\"id\":3}"))
        assertEquals(BridgeCommand.RulesClear, ok("{\"cmd\":\"rules.clear\"}"))
        val update = assertIs<BridgeCommand.RulesUpdate>(ok("{\"cmd\":\"rules.update\",\"id\":3,\"enabled\":false,\"times\":null}"))
        assertEquals(3, update.id)
        assertEquals(BridgeCommand.BlobPut(1, 0, "AAEC"), ok("{\"cmd\":\"blob.put\",\"blob\":1,\"offset\":0,\"data\":\"AAEC\"}"))
        assertEquals(BridgeCommand.Tail(5, rules = true), ok("{\"cmd\":\"tail\",\"since\":5,\"rules\":true}"))
        assertFalse(BridgeCommand.Tail(0).isWrite)
    }

    @Test
    fun ruleCommandsRejectBadFields() {
        invalid("{\"cmd\":\"rules.add\"}")
        invalid("{\"cmd\":\"rules.add\",\"rule\":1}")
        assertEquals("missing \"id\"", invalid("{\"cmd\":\"rules.remove\"}"))
        invalid("{\"cmd\":\"rules.remove\",\"id\":0}")
        invalid("{\"cmd\":\"blob.put\",\"blob\":0,\"offset\":0,\"data\":\"\"}")
        invalid("{\"cmd\":\"blob.put\",\"blob\":1,\"data\":\"\"}")
        invalid("{\"cmd\":\"blob.put\",\"blob\":1,\"offset\":0,\"data\":\"" + "A".repeat(3076) + "\"}")
        invalid("{\"cmd\":\"tail\",\"rules\":1}")
        // Commands that were flat stay flat: a nested value is still malformed, as for an older app.
        assertEquals("malformed JSON object", invalid("{\"cmd\":\"list\",\"x\":{}}"))
        assertEquals("malformed JSON object", invalid("{\"cmd\":\"rules.list\",\"x\":[1]}"))
        val tooDeep = "{\"cmd\":\"rules.add\",\"rule\":{\"edit\":{\"request\":{\"setHeaders\":[[[\"x\"]]]}}}}"
        assertEquals("malformed JSON object", invalid(tooDeep))
    }

    @Test
    fun replayIsNotACommand() {
        // Removed from the product (docs/ROADMAP.md, "Replay (no priority)"): a client that still
        // sends it gets what an older app answers any command it does not know.
        assertEquals("unknown cmd", invalid("{\"cmd\":\"replay\",\"id\":42}"))
        assertEquals("malformed JSON object", invalid("{\"cmd\":\"replay\",\"id\":42,\"edit\":{\"setHeaders\":[[\"X\",\"1\"]]}}"))
    }

    @Test
    fun marksGoOnEveryLineAfterFinalUrlAndBeforeTheError() {
        val tx = pending(9).copy(source = "okhttp", finalUrl = "https://api.example.com/final", ruleId = 3, ruleApplied = "delay,mock")
            .failed("boom", 5)
        val log = TransactionJson.summaryLogLine(tx, Redactor.Default)
        assertTrue("\"finalUrl\":\"https://api.example.com/final\",\"rule\":3,\"applied\":\"delay,mock\",\"error\":\"boom\"" in log, log)
        val bridge = parseFlatJsonObject(TransactionJson.bridgeSummary(tx, Redactor.Default))
        assertNotNull(bridge)
        assertEquals(3L, bridge["rule"])
        assertEquals("delay,mock", bridge["applied"])
        val detail = TransactionJson.bridgeDetail(tx, Redactor.Default)
        assertTrue("\"source\":\"okhttp\",\"rule\":3,\"applied\":\"delay,mock\",\"code\"" in detail, detail)
        val plain = TransactionJson.summaryLogLine(pending(10), Redactor.Default)
        assertFalse("\"rule\"" in plain, plain)
    }

    @Test
    fun theLineBudgetHoldsWithWorstCaseMarks() {
        val url = "https://api.example.com/" + "a".repeat(5000)
        for (maxBytes in listOf(1000, TransactionJson.MAX_SUMMARY_LINE_BYTES)) {
            val tx = pending(Long.MAX_VALUE, url = url).copy(
                source = "urlconnection",
                finalUrl = url + "/final",
                ruleId = Long.MAX_VALUE,
                ruleApplied = "delay,edit-request,edit-response",
            ).failed("e".repeat(2000), 5)
            val line = TransactionJson.summaryLogLine(tx, Redactor.Default, maxBytes)
            assertTrue(line.encodeToByteArray().size <= maxBytes, "maxBytes=$maxBytes size=${line.encodeToByteArray().size}")
            assertTrue("\"applied\":\"delay,edit-request,edit-response\"" in line, line)
        }
        // The fixed fields the marks add stay within the 72 bytes the design reserves.
        val marks = "\"rule\":${Long.MAX_VALUE},\"applied\":\"delay,edit-request,edit-response\","
        assertTrue(marks.length <= 72, "${marks.length}")
    }
}
