package dev.koga.hopi.core.json

import dev.koga.hopi.core.complete
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.pending
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.textBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TransactionJsonTest {
    @Test
    fun summaryLineHasTheContractShape() {
        val tx = pending(42).completed(200, emptyList(), CapturedBody(ByteArray(10), 5120, true, "application/json", null), 183, null)
        assertEquals(
            "{\"hopi\":1,\"id\":42,\"method\":\"GET\",\"url\":\"https://api.example.com/users?page=2\"," +
                "\"code\":200,\"ms\":183,\"reqBytes\":0,\"resBytes\":5120,\"state\":\"complete\"}",
            TransactionJson.summaryLogLine(tx, Redactor.Default),
        )
        // The bridge's line adds what the app holds of a body cut short (10 of 5120 bytes here).
        assertEquals(
            "{\"type\":\"transaction\",\"id\":42,\"method\":\"GET\",\"url\":\"https://api.example.com/users?page=2\"," +
                "\"code\":200,\"ms\":183,\"reqBytes\":0,\"resBytes\":5120,\"state\":\"complete\",\"resHeld\":10}",
            TransactionJson.bridgeSummary(tx, Redactor.Default),
        )
    }

    @Test
    fun theSourceFollowsTheStateAndIsOmittedWhenUnknown() {
        val tx = pending(42).copy(source = "urlsession", finalUrl = "https://api.example.com/final?token=abc")
            .completed(200, emptyList(), null, 7, "HTTP/1.1")
        assertEquals(
            "{\"hopi\":1,\"id\":42,\"method\":\"GET\",\"url\":\"https://api.example.com/users?page=2\"," +
                "\"code\":200,\"ms\":7,\"reqBytes\":0,\"resBytes\":0,\"state\":\"complete\",\"source\":\"urlsession\"," +
                "\"finalUrl\":\"https://api.example.com/final?token=<redacted>\"}",
            TransactionJson.summaryLogLine(tx, Redactor.Default),
        )
        val detail = TransactionJson.bridgeDetail(tx, Redactor.create(queryParameters = listOf("token")))
        assertTrue(
            "\"url\":\"https://api.example.com/users?page=2\",\"finalUrl\":\"https://api.example.com/final?token=<redacted>\"," +
                "\"state\":\"complete\",\"source\":\"urlsession\",\"code\":200" in detail,
            detail,
        )
        // An older app's records, and the plugin's before this field, carry neither.
        val plain = TransactionJson.bridgeDetail(pending(1), Redactor.Default)
        assertTrue("source" !in plain && "finalUrl" !in plain, plain)
    }

    @Test
    fun theLineBudgetHoldsWithTheSource() {
        val url = "https://api.example.com/" + "a".repeat(5000)
        for (maxBytes in listOf(1000, TransactionJson.MAX_SUMMARY_LINE_BYTES)) {
            val tx = pending(1, url = url).copy(source = "urlconnection").failed("e".repeat(2000), 5)
            val line = TransactionJson.summaryLogLine(tx, Redactor.Default, maxBytes)
            assertTrue(line.encodeToByteArray().size <= maxBytes, "maxBytes=$maxBytes size=${line.encodeToByteArray().size}")
            assertTrue("\"source\":\"urlconnection\"" in line, line)
        }
    }

    @Test
    fun failedSummaryCarriesTheError() {
        val tx = pending(3, method = "POST").failed("java.io.IOException: \"reset\"", 12)
        assertEquals(
            "{\"hopi\":1,\"id\":3,\"method\":\"POST\",\"url\":\"https://api.example.com/users?page=2\"," +
                "\"code\":null,\"ms\":12,\"reqBytes\":0,\"resBytes\":0,\"state\":\"failed\",\"error\":\"java.io.IOException: \\\"reset\\\"\"}",
            TransactionJson.summaryLogLine(tx, Redactor.Default),
        )
    }

    @Test
    fun summaryRedactsTheUrl() {
        val line = TransactionJson.summaryLogLine(pending(1, url = "https://h/?token=abc"), Redactor.create(queryParameters = listOf("token")))
        assertTrue("\"url\":\"https://h/?token=<redacted>\"" in line, line)
    }

    @Test
    fun errorMessagesMentioningTheUrlAreRedacted() {
        val url = "https://h/?token=abc"
        val tx = pending(1, url = url).failed("HttpRequestTimeoutException: Request timeout has expired [url=$url]", 5)
        val redactor = Redactor.create(queryParameters = listOf("token"))
        for (json in listOf(TransactionJson.summaryLogLine(tx, redactor), TransactionJson.bridgeDetail(tx, redactor))) {
            assertTrue("abc" !in json, json)
            assertTrue("[url=https://h/?token=<redacted>]" in json, json)
        }
        assertTrue("token=abc]" in TransactionJson.summaryLogLine(tx, Redactor.Disabled))
    }

    @Test
    fun longUrlsAreCutBelowTheByteCapAndMarked() {
        for (unit in listOf("a", "é", "😀", "\"", "\u0001", "中")) {
            val url = "https://h/" + unit.repeat(5000)
            val line = TransactionJson.summaryLogLine(pending(1, url = url), Redactor.Default)
            val bytes = line.encodeToByteArray().size
            assertTrue(bytes < 4000, "unit=$unit bytes=$bytes")
            assertTrue(bytes > 3900, "cut too aggressively: unit=$unit bytes=$bytes")
            assertTrue(line.endsWith(",\"urlTruncated\":true}"), line.takeLast(40))
            val parsedUrl = line.substringAfter("\"url\":\"").substringBefore("\",\"code\"")
            assertTrue(!parsedUrl.last().isHighSurrogate())
        }
        val short = TransactionJson.summaryLogLine(pending(1), Redactor.Default)
        assertTrue("urlTruncated" !in short)
    }

    @Test
    fun aSmallerBudgetCutsTheUrlAndKeepsTheLineParseable() {
        val url = "https://bucket.s3.amazonaws.com/key?X-Amz-Expires=" + "f".repeat(1500)
        val line = TransactionJson.summaryLogLine(pending(1, url = url), Redactor.Default, maxBytes = 1000)
        val bytes = line.encodeToByteArray().size
        assertTrue(bytes in 900..1000, "bytes=$bytes")
        val parsed = assertNotNull(parseFlatJsonObject(line), line)
        assertEquals(true, parsed["urlTruncated"])
        assertTrue(url.startsWith(parsed["url"] as String))
    }

    @Test
    fun aLongErrorIsBudgetedTogetherWithTheUrl() {
        val url = "https://h/" + "u".repeat(900)
        val error = "IOException: " + "\"e\"".repeat(200)
        for (maxBytes in listOf(1000, TransactionJson.MAX_SUMMARY_LINE_BYTES)) {
            val line = TransactionJson.summaryLogLine(pending(1, url = url).failed(error, 5), Redactor.Default, maxBytes)
            assertTrue(line.encodeToByteArray().size <= maxBytes, "maxBytes=$maxBytes size=${line.encodeToByteArray().size}")
            val parsed = assertNotNull(parseFlatJsonObject(line), line)
            assertTrue(error.startsWith(parsed["error"] as String))
            assertTrue(url.startsWith(parsed["url"] as String))
            if (maxBytes == 1000) {
                assertEquals(true, parsed["urlTruncated"])
                assertEquals(true, parsed["errorTruncated"])
                assertTrue((parsed["error"] as String).length >= 100, "the error keeps a fair share")
            } else {
                assertEquals(null, parsed["urlTruncated"])
                assertEquals(null, parsed["errorTruncated"])
            }
        }
    }

    @Test
    fun errorAndMethodCutsNeverSplitASurrogatePair() {
        val error = "a".repeat(511) + "😀 tail"
        val method = "M".repeat(63) + "😀"
        val line = TransactionJson.summaryLogLine(pending(1, method = method).failed(error, 5), Redactor.Default)
        assertTrue(line.indices.all { i ->
            val c = line[i]
            when {
                c.isHighSurrogate() -> i + 1 < line.length && line[i + 1].isLowSurrogate()
                c.isLowSurrogate() -> i > 0 && line[i - 1].isHighSurrogate()
                else -> true
            }
        }, "no unpaired surrogate in the line")
        val parsed = assertNotNull(parseFlatJsonObject(line), line)
        assertEquals("a".repeat(511), parsed["error"])
        assertEquals("M".repeat(63), parsed["method"])
    }

    @Test
    fun detailEncodesBodiesAsUtf8OrBase64AndRedacts() {
        val tx = pending(
            7,
            method = "POST",
            url = "https://h/?token=abc",
            headers = listOf("Authorization" to "Bearer x", "Accept" to "*/*"),
            body = CapturedBody(byteArrayOf(0, 1, -1), 3, false, "application/octet-stream", null),
        ).completed(201, listOf("Set-Cookie" to "s=1"), textBody("{\"ok\":true}"), 9, "HTTP/2.0")
        val json = TransactionJson.bridgeDetail(tx, Redactor.create(queryParameters = listOf("token")))
        assertEquals(
            "{\"type\":\"transaction\",\"id\":7,\"startedAt\":1700000000000,\"method\":\"POST\"," +
                "\"url\":\"https://h/?token=<redacted>\",\"state\":\"complete\",\"code\":201,\"ms\":9," +
                "\"protocol\":\"HTTP/2.0\",\"error\":null," +
                "\"request\":{\"headers\":[[\"Authorization\",\"<redacted>\"],[\"Accept\",\"*/*\"]]," +
                "\"body\":{\"encoding\":\"base64\",\"data\":\"AAH/\",\"truncated\":false,\"capturedBytes\":3,\"totalBytes\":3," +
                "\"contentType\":\"application/octet-stream\",\"skippedReason\":null}}," +
                "\"response\":{\"headers\":[[\"Set-Cookie\",\"<redacted>\"]]," +
                "\"body\":{\"encoding\":\"utf8\",\"text\":\"{\\\"ok\\\":true}\",\"truncated\":false,\"capturedBytes\":11,\"totalBytes\":11," +
                "\"contentType\":\"application/json\",\"skippedReason\":null}}}",
            json,
        )
    }

    @Test
    fun detailWithoutBodies() {
        val json = TransactionJson.bridgeDetail(complete(1), Redactor.Disabled)
        assertTrue(json.endsWith("\"request\":{\"headers\":[],\"body\":null},\"response\":{\"headers\":[[\"Content-Type\",\"application/json\"]],\"body\":null}}"), json)
    }

    @Test
    fun utf8LengthMatchesEncoding() {
        for (s in listOf("", "abc", "é中😀")) {
            assertEquals(s.encodeToByteArray().size, TransactionJson.utf8Length(s), s)
        }
        for (s in listOf("\uD800x", "x\uDC00")) {
            assertTrue(TransactionJson.utf8Length(s) >= s.encodeToByteArray().size, "lone surrogates are an upper bound")
        }
    }

    @Test
    fun slicedDetailIsExactlyTheDetail() {
        val text = CapturedBody(("{\"k\":\"日本😀\\n\"}" + "x".repeat(70_000)).encodeToByteArray(), null, false, "application/json", null)
        val binary = CapturedBody(ByteArray(50_000) { (it % 7).toByte() }, null, false, "application/octet-stream", null)
        val tx = pending(9, headers = (1..40).map { "X-H$it" to "v".repeat(it * 10) }, body = binary)
            .completed(200, listOf("Content-Type" to "application/json"), text, 12, "HTTP/1.1")
        val whole = TransactionJson.bridgeDetail(tx, Redactor.Default)
        for (chunk in listOf(2, 3, 100, 4_096, 32_768)) {
            val out = StringBuilder()
            val sent = StringBuilder()
            var flushes = 0
            TransactionJson.writeBridgeDetail(tx, Redactor.Default, out, chunk) {
                flushes++
                assertTrue(out.length <= chunk * 6 + 2_048, "a slice of ${out.length} chars for chunks of $chunk")
                sent.append(out)
                out.setLength(0)
            }
            sent.append(out)
            assertEquals(whole, sent.toString(), "chunks of $chunk")
            assertTrue(flushes > 1)
        }
    }

    @Test
    fun aFailureAfterTheResponseKeepsItsStatusHeadersAndPartialBodyOnTheWire() {
        val partial = CapturedBody("{\"items\":[1,2".encodeToByteArray(), 12, false, "application/json", null)
        val tx = pending(7).failedAfterResponse("IOException: unexpected end of stream", 40, 200, listOf("Content-Type" to "application/json"), partial, "HTTP/1.1")
        // The summary is the same shape as before: `code` and `resBytes` were always there, now set.
        assertEquals(
            "{\"hopi\":1,\"id\":7,\"method\":\"GET\",\"url\":\"https://api.example.com/users?page=2\"," +
                "\"code\":200,\"ms\":40,\"reqBytes\":0,\"resBytes\":12,\"state\":\"failed\",\"error\":\"IOException: unexpected end of stream\"}",
            TransactionJson.summaryLogLine(tx, Redactor.Default),
        )
        val detail = TransactionJson.bridgeDetail(tx, Redactor.Default)
        assertTrue("\"state\":\"failed\",\"code\":200,\"ms\":40,\"protocol\":\"HTTP/1.1\",\"error\":\"IOException: unexpected end of stream\"" in detail, detail)
        assertTrue("\"response\":{\"headers\":[[\"Content-Type\",\"application/json\"]],\"body\":{\"encoding\":\"utf8\",\"text\":\"{\\\"items\\\":[1,2\"" in detail, detail)
    }

    @Test
    fun theFinalUrlIsInEverySummaryAndSharesTheLineBudget() {
        val tx = pending(3).copy(finalUrl = "https://cdn.example.com/x").completed(200, emptyList(), null, 1, null)
        assertTrue(TransactionJson.bridgeSummary(tx, Redactor.Default).endsWith("\"state\":\"complete\",\"finalUrl\":\"https://cdn.example.com/x\"}"))
        val long = pending(4, url = "https://h/" + "a".repeat(3000)).copy(finalUrl = "https://f/" + "b".repeat(3000))
            .failed("e".repeat(400), 2)
        for (maxBytes in listOf(600, 1000, TransactionJson.MAX_SUMMARY_LINE_BYTES)) {
            for (line in listOf(TransactionJson.summaryLogLine(long, Redactor.Default, maxBytes), TransactionJson.bridgeSummary(long, Redactor.Default))) {
                val budget = if (line.startsWith("{\"type\"")) TransactionJson.MAX_SUMMARY_LINE_BYTES else maxBytes
                val fields = assertNotNull(parseFlatJsonObject(line, Int.MAX_VALUE), line)
                assertTrue(line.encodeToByteArray().size <= budget, "${line.length} > $budget")
                assertEquals(true, fields["urlTruncated"])
                assertEquals(true, fields["finalUrlTruncated"])
                assertTrue((fields["finalUrl"] as String).startsWith("https://f/b"), line)
                if (budget == TransactionJson.MAX_SUMMARY_LINE_BYTES) {
                    // Shorter than its third of the line: whole, and its unused share goes to the URLs.
                    assertEquals("e".repeat(400), fields["error"])
                } else {
                    assertEquals(true, fields["errorTruncated"])
                    assertTrue((fields["error"] as String).length > 50, line)
                }
            }
        }
        val builder = StringBuilder("prefix")
        TransactionJson.appendSummaryLogLine(long, Redactor.Default, 1000, builder)
        assertEquals("prefix" + TransactionJson.summaryLogLine(long, Redactor.Default, 1000), builder.toString())
    }
}
