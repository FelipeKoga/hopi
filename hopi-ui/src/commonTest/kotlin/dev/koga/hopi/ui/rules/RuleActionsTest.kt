package dev.koga.hopi.ui.rules

import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.TransactionSource
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.ui.complete
import dev.koga.hopi.ui.pending
import dev.koga.hopi.ui.textBody
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class RuleActionsTest {
    @BeforeTest
    fun setUp() = RuleEngine.resetForTests()

    @AfterTest
    fun tearDown() = RuleEngine.resetForTests()

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

    @Test
    fun theMockIsBuiltOnTheGivenDispatcherAndReportedInTheScope() = runTest {
        // The scope runs at once, as the tap does; the dispatcher only when the test lets it.
        val format = StandardTestDispatcher(testScheduler)
        val outcomes = mutableListOf<InAppOutcome>()
        val mock = assertNotNull(inAppRuleActions(complete(1), CoroutineScope(UnconfinedTestDispatcher(testScheduler)), format) { outcomes += it }.mock)
        mock.run()
        assertTrue(outcomes.isEmpty())
        assertTrue(RuleEngine.list().isEmpty())
        advanceUntilIdle()
        val mocked = assertIs<InAppOutcome.Mocked>(outcomes.single())
        assertEquals(listOf(mocked.ruleId), RuleEngine.list().map { it.id })
    }

    @Test
    fun aBodyThatDoesNotDecodeIsRefusedInTheAppsWords() {
        // Named gzip, so the button is enabled; the tap finds bytes that are not gzip.
        val notGzip = complete(1, responseHeaders = listOf("Content-Type" to "application/json", "Content-Encoding" to "gzip")).copy(source = TransactionSource.OKHTTP)
        assertNull(mockRefusal(notGzip))
        assertEquals("response body does not decode as gzip", assertIs<InAppOutcome.Refused>(mockInApp(notGzip)).message)
        assertTrue(RuleEngine.list().isEmpty())
    }
}
