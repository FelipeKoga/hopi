package dev.koga.hopi.ui.rules

import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.TransactionSource
import dev.koga.hopi.ui.complete
import dev.koga.hopi.ui.pending
import dev.koga.hopi.ui.textBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RuleActionsTest {
    @Test
    fun theExactMatchIsMethodSchemeHostPortAndPathWithoutTheQuery() {
        val match = assertNotNull(exactMatchOf(complete(1, method = "POST", url = "https://API.example.com:8443/v1/users?page=2")))
        assertEquals("POST https://api.example.com:8443/v1/users", match.describe())
        assertEquals("GET http://example.com/", assertNotNull(exactMatchOf(complete(1, url = "http://example.com"))).describe())
        assertNull(exactMatchOf(complete(1, url = "ftp://example.com/x")))
    }

    @Test
    fun mockNeedsTheResponseBodyHeldInFull() {
        assertNull(mockRefusal(complete(1)))
        assertEquals("no response to mock", mockRefusal(pending(1)))
        assertEquals("response body not held in full", mockRefusal(complete(1, responseBody = textBody("{", truncated = true, total = 10))))
        assertEquals("response body not held in full", mockRefusal(complete(1, responseBody = CapturedBody.gone(10))))
        // A host viewer's summary: the app holds what `resHeld` says.
        assertNull(mockRefusal(complete(1, responseBody = CapturedBody.notFetched(10))))
        assertEquals("response body not held in full", mockRefusal(complete(1, responseBody = CapturedBody.notFetched(10, heldBytes = 4))))
    }

    @Test
    fun mockNeedsABodyHeldInAnEncodingItDecodes() {
        fun encoded(encoding: String, source: String, body: CapturedBody? = textBody("raw")) =
            complete(1, responseBody = body, responseHeaders = listOf("Content-Type" to "application/json", "Content-Encoding" to encoding)).copy(source = source)
        // OkHttp and HttpURLConnection hold the server's bytes when the app asked for the encoding: gzip and deflate are decoded.
        assertNull(mockRefusal(encoded("gzip", TransactionSource.OKHTTP)))
        assertNull(mockRefusal(encoded("deflate", TransactionSource.URL_CONNECTION)))
        assertEquals("response body is encoded (Content-Encoding: br)", mockRefusal(encoded("br", TransactionSource.OKHTTP)))
        assertEquals("response body is encoded (Content-Encoding: gzip, br)", mockRefusal(encoded("gzip, br", TransactionSource.URL_CONNECTION)))
        // Ktor and URLSession records hold what the decoder handed the app, under the raw headers.
        assertNull(mockRefusal(encoded("br", TransactionSource.KTOR)))
        assertNull(mockRefusal(encoded("br", TransactionSource.URL_SESSION)))
        // No body, nothing to copy; a host viewer's summary has no headers, so the app refuses itself.
        assertNull(mockRefusal(encoded("br", TransactionSource.OKHTTP, body = null)))
        assertNull(mockRefusal(complete(1, responseBody = CapturedBody.notFetched(10), responseHeaders = emptyList()).copy(source = TransactionSource.OKHTTP)))
    }
}
