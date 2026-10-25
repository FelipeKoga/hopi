package dev.koga.hopi.cli

import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.redact.Redactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class RenderTest {
    private fun cells(transaction: HttpTransaction): List<String> =
        Render.summaryCells(Json.parseToJsonElement(TransactionJson.bridgeSummary(transaction, Redactor.Default)) as JsonObject)

    private val pending = HttpTransaction.pending(1, 0, "GET", "https://api.example.com/a", emptyList(), null)

    @Test
    fun aSummaryShowsWhereARedirectEnded() {
        val row = cells(pending.copy(finalUrl = "https://cdn.example.com/b").completed(200, emptyList(), null, 3, null))
        assertEquals("200", row[2])
        assertEquals("https://api.example.com/a  -> https://cdn.example.com/b", row[5])
    }

    @Test
    fun aFailureAfterTheResponseSaysSoAndStillReadsErr() {
        val body = CapturedBody("par".encodeToByteArray(), 3, false, "text/plain", null)
        val row = cells(pending.failedAfterResponse("IOException: reset", 9, 200, emptyList(), body, "HTTP/1.1"))
        assertEquals("ERR", row[2])
        assertTrue(row[5].endsWith("(HTTP 200, then IOException: reset)"), row[5])
        assertEquals("ERR", cells(pending.failed("ConnectException: refused", 1))[2])
    }
}
