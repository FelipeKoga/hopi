package dev.koga.hopi.connect

import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.redact.Redactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal fun textBody(text: String, contentType: String? = "application/json", truncated: Boolean = false, total: Long? = null) =
    CapturedBody(text.encodeToByteArray(), total ?: text.encodeToByteArray().size.toLong(), truncated, contentType, null)

internal fun pendingTx(
    id: Long,
    url: String = "https://api.example.com/users?page=2",
    method: String = "GET",
    headers: List<Pair<String, String>> = listOf("Accept" to "application/json"),
    body: CapturedBody? = null,
) = HttpTransaction.pending(id, 1_700_000_000_000 + id, method, url, headers, body)

internal fun completeTx(
    id: Long,
    url: String = "https://api.example.com/users?page=2",
    responseBody: CapturedBody? = textBody("""{"name":"Ada"}"""),
    requestBody: CapturedBody? = null,
    method: String = "GET",
    code: Int = 200,
    responseHeaders: List<Pair<String, String>> = listOf("Content-Type" to "application/json"),
) = pendingTx(id, url, method, body = requestBody).completed(code, responseHeaders, responseBody, 183, "HTTP/1.1")

private fun parse(line: String): JsonObject = Json.parseToJsonElement(line) as JsonObject

class BridgeDecoderTest {
    // Disabled, so the decoded transaction equals the original field for field.
    private val redactor = Redactor.Disabled

    private fun detailRoundTrip(transaction: HttpTransaction): HttpTransaction {
        val decoded = assertNotNull(BridgeDecoder.detail(parse(TransactionJson.bridgeDetail(transaction, redactor))))
        assertEquals(transaction, decoded)
        assertEquals(transaction.startedAtEpochMs, decoded.startedAtEpochMs)
        return decoded
    }

    @Test
    fun detailRoundTripsTextBinarySkippedAndEmptyBodies() {
        val binary = ByteArray(300) { (it * 7).toByte() }.also { it[0] = 0; it[1] = -1 }
        detailRoundTrip(completeTx(1))
        detailRoundTrip(completeTx(2, responseBody = CapturedBody(binary, 300, false, "application/octet-stream", null)))
        // A NUL makes otherwise valid UTF-8 binary: it travels as base64.
        detailRoundTrip(completeTx(3, responseBody = CapturedBody("a\u0000b".encodeToByteArray(), 3, false, "text/plain", null)))
        detailRoundTrip(completeTx(4, responseBody = CapturedBody.skipped("binary content-type", "image/png", 2048)))
        detailRoundTrip(completeTx(5, responseBody = CapturedBody.skipped("streaming", "text/event-stream", null)))
        detailRoundTrip(completeTx(6, responseBody = CapturedBody(ByteArray(0), 0, false, null, null)))
        detailRoundTrip(completeTx(7, code = 204, responseBody = null, responseHeaders = emptyList()))
        detailRoundTrip(
            completeTx(
                8,
                method = "POST",
                requestBody = textBody("""{"q":"x"}"""),
                responseBody = textBody("ok", contentType = "text/plain", total = null),
            ),
        )
    }

    @Test
    fun detailRoundTripsTruncatedBodies() {
        val text = "é".repeat(1_000)
        // Cut at a code point boundary, as the plugin's capture is when it ends on one.
        val cut = text.substring(0, 300)
        detailRoundTrip(completeTx(1, responseBody = textBody(cut, truncated = true, total = text.encodeToByteArray().size.toLong())))
        detailRoundTrip(completeTx(2, responseBody = CapturedBody(ByteArray(64) { 1 }, null, true, null, null)))
    }

    @Test
    fun aTruncatedBodyCutInsideASequenceDecodesWithoutItsTail() {
        // 'é' is two bytes: 301 bytes end inside one. The bridge drops the incomplete sequence.
        val bytes = "é".repeat(1_000).encodeToByteArray().copyOf(301)
        val tx = completeTx(1, responseBody = CapturedBody(bytes, 2_000, true, "text/plain", null))
        val json = parse(TransactionJson.bridgeDetail(tx, redactor))
        val decoded = assertNotNull(BridgeDecoder.detail(json))
        val body = assertNotNull(decoded.responseBody)
        assertEquals(300, body.bytes.size)
        assertTrue(body.truncated)
        assertEquals(2_000, body.totalBytes)
        assertEquals("é".repeat(150), body.bytes.decodeToString())
    }

    @Test
    fun detailRoundTripsPendingFailedAndCancelledStates() {
        val pending = detailRoundTrip(pendingTx(1, method = "PUT", body = textBody("{}")))
        assertEquals(TransactionState.Pending, pending.state)
        assertNull(pending.responseBody)
        val failed = detailRoundTrip(pendingTx(2).failed("java.net.UnknownHostException: down.example.com", 12))
        assertEquals("java.net.UnknownHostException: down.example.com", failed.error)
        // A cancelled call is a failure whose error names the cancellation: there is no third state.
        val cancelled = detailRoundTrip(pendingTx(3).failed("kotlinx.coroutines.JobCancellationException: Job was cancelled", 4))
        assertEquals(TransactionState.Failed, cancelled.state)
    }

    @Test
    fun detailRoundTripsUnicodeAndControlCharacters() {
        val text = """{"name":"Ada 😀 中文 \" \\ / ","bell":"""" + "\u0007" + "\",\"nl\":\"a\nb\"}"
        detailRoundTrip(
            completeTx(
                1,
                url = "https://h.example/路径?q=😀&x=\"y\"",
                responseBody = textBody(text),
                responseHeaders = listOf("X-Emoji" to "😀", "Set-Cookie" to "a=1", "Set-Cookie" to "b=2", "X-Tab" to "a\tb"),
            ),
        )
    }

    @Test
    fun detailRoundTripsGiantBodies() {
        val giant = buildString(5 * 1024 * 1024) { while (length < 5 * 1024 * 1024) append("""{"id":$length,"v":"é😀"},""") }
        detailRoundTrip(completeTx(1, responseBody = textBody(giant)))
        val binary = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        detailRoundTrip(completeTx(2, responseBody = CapturedBody(binary, binary.size.toLong(), false, "application/octet-stream", null)))
    }

    @Test
    fun summaryKeepsSizesAsNotFetchedBodiesAndReencodesToTheSameLine() {
        val cases = listOf(
            completeTx(1, requestBody = textBody("{}"), method = "POST"),
            completeTx(2, code = 404, responseBody = CapturedBody.skipped("binary content-type", "image/png", 2048)),
            pendingTx(3, body = textBody("abc")),
            pendingTx(4).failed("java.io.IOException: \"reset\"", 12),
            completeTx(5, url = "https://h.example/路径?q=😀", responseBody = textBody("é".repeat(10))),
        )
        for (tx in cases) {
            val line = TransactionJson.bridgeSummary(tx, redactor)
            val decoded = assertNotNull(BridgeDecoder.summary(parse(line), startedAtFallback = 42))
            assertEquals(line, TransactionJson.bridgeSummary(decoded, redactor))
            assertEquals(tx.id, decoded.id)
            assertEquals(tx.state, decoded.state)
            assertEquals(tx.url, decoded.url)
            assertEquals(42, decoded.startedAtEpochMs)
            assertEquals(emptyList(), decoded.requestHeaders)
            val request = assertNotNull(decoded.requestBody)
            assertTrue(request.isNotFetched)
            assertEquals(tx.requestBody?.reportedBytes ?: 0, request.totalBytes)
            if (tx.state == TransactionState.Pending) {
                assertNull(decoded.responseBody)
            } else {
                val response = assertNotNull(decoded.responseBody)
                assertTrue(response.isNotFetched)
                assertEquals(0, response.bytes.size)
                assertEquals(tx.responseBody?.reportedBytes ?: 0, response.totalBytes)
            }
        }
    }

    @Test
    fun aSummaryOfAGiantBodyIsSmall() {
        val tx = completeTx(1, responseBody = CapturedBody(ByteArray(0), 300L * 1024 * 1024, true, "application/json", null))
        val line = TransactionJson.bridgeSummary(tx, redactor)
        assertTrue(line.length < 400, line)
        assertEquals(300L * 1024 * 1024, BridgeDecoder.summary(parse(line), 0)?.responseBody?.totalBytes)
    }

    @Test
    fun aCutUrlStaysCutUntilTheDetailArrives() {
        val url = "https://h/" + "u".repeat(5_000)
        val line = TransactionJson.bridgeSummary(completeTx(1, url = url), redactor)
        val decoded = assertNotNull(BridgeDecoder.summary(parse(line), 0))
        assertTrue(url.startsWith(decoded.url) && decoded.url.length < url.length)
    }

    @Test
    fun theSourceAndTheFinalUrlRoundTripAndAnOlderAppsLinesDecodeToNull() {
        val tx = completeTx(9).copy(source = "urlsession", finalUrl = "https://api.example.com/final")
        detailRoundTrip(tx)
        val summary = assertNotNull(BridgeDecoder.summary(parse(TransactionJson.bridgeSummary(tx, redactor)), 0))
        assertEquals("urlsession", summary.source)
        // A line from an app built before the field: no source, no final URL.
        val old = """{"type":"transaction","id":1,"method":"GET","url":"u","code":200,"ms":1,"reqBytes":0,"resBytes":0,"state":"complete"}"""
        assertNull(assertNotNull(BridgeDecoder.summary(parse(old), 0)).source)
        val decoded = assertNotNull(BridgeDecoder.detail(parse(TransactionJson.bridgeDetail(completeTx(2), redactor))))
        assertNull(decoded.source)
        assertNull(decoded.finalUrl)
    }

    @Test
    fun malformedLinesDecodeToNull() {
        val good = parse(TransactionJson.bridgeDetail(completeTx(1), redactor))
        assertNull(BridgeDecoder.detail(parse("""{"type":"notice","dropped":3}""")))
        assertNull(BridgeDecoder.summary(parse("""{"type":"notice","dropped":3}"""), 0))
        assertNull(BridgeDecoder.summary(parse("""{"type":"transaction","id":1,"method":"GET","url":"u","state":"weird"}"""), 0))
        assertNull(BridgeDecoder.summary(parse("""{"type":"transaction","method":"GET","url":"u","state":"complete"}"""), 0))
        assertNull(BridgeDecoder.detail(JsonObject(good - "request")))
        val badBase64 = TransactionJson.bridgeDetail(completeTx(2, responseBody = CapturedBody(ByteArray(3) { 0 }, 3, false, null, null)), redactor)
            .replace("\"data\":\"AAAA\"", "\"data\":\"!!\"")
        assertNull(BridgeDecoder.detail(parse(badBase64)))
    }

    @Test
    fun aSummaryCarriesTheFinalUrlAndAFailureAfterTheResponseKeepsItsCode() {
        val redirected = completeTx(5).copy(finalUrl = "https://cdn.example.com/final")
        val summary = assertNotNull(BridgeDecoder.summary(parse(TransactionJson.bridgeSummary(redirected, redactor)), 0))
        assertEquals("https://cdn.example.com/final", summary.finalUrl)
        val failed = pendingTx(6).failedAfterResponse("IOException: reset", 12, 200, listOf("Content-Type" to "text/plain"), textBody("par", "text/plain"), "HTTP/1.1")
        val decodedSummary = assertNotNull(BridgeDecoder.summary(parse(TransactionJson.bridgeSummary(failed, redactor)), 0))
        assertEquals(TransactionState.Failed, decodedSummary.state)
        assertEquals(200, decodedSummary.responseCode)
        assertEquals(3L, decodedSummary.responseBody?.totalBytes)
        assertEquals(failed, detailRoundTrip(failed))
    }
}
