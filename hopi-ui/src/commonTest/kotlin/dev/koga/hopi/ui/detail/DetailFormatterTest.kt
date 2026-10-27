package dev.koga.hopi.ui.detail

import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.ui.complete
import dev.koga.hopi.ui.pending
import dev.koga.hopi.ui.platform.MAX_SHARE_CHARS
import dev.koga.hopi.ui.platform.capForShare
import dev.koga.hopi.ui.textBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DetailFormatterTest {
    @Test
    fun aBodyNotFetchedYetIsLoadingNotMissingOrSkipped() = runTest {
        val summary = complete(1, responseBody = CapturedBody.notFetched(5_120), requestBody = CapturedBody.notFetched(0))
        val model = DetailFormatter().model(summary, Redactor.Default)
        assertEquals("Loading body…", assertIs<BodyView.None>(model.response.body).message)
        assertEquals("Loading body…", assertIs<BodyView.None>(model.request.body).message)
        assertTrue(model.loading && model.request.loading && model.response.loading)
        assertEquals("5.0 KB", model.overview.single { it.label == "Response size" }.value)

        val fetched = DetailFormatter().model(complete(1), Redactor.Default)
        assertTrue(!fetched.loading)
        assertEquals("No request body", assertIs<BodyView.None>(fetched.request.body).message)
    }

    @Test
    fun aBodyGoneFromTheAppStopsLoadingAndSaysSo() = runTest {
        val gone = complete(1, responseBody = CapturedBody.gone(5_120), requestBody = CapturedBody.gone(0))
        val model = DetailFormatter().model(gone, Redactor.Default)
        assertEquals(GONE_BODY, assertIs<BodyView.None>(model.response.body).message)
        assertEquals(GONE_BODY, assertIs<BodyView.None>(model.request.body).message)
        assertTrue(!model.loading, "not loading: nothing is on its way")
        assertTrue(model.gone && model.request.gone && model.response.gone)
        assertEquals("5.0 KB", model.overview.single { it.label == "Response size" }.value)
    }

    @Test
    fun jsonBodiesArePrettyPrintedWithCoreAndChunked() = runTest {
        val model = DetailFormatter().model(complete(1, responseBody = textBody("""{"name":"Ada","tags":[1,2]}""")), Redactor.Default)
        val body = assertIs<BodyView.Text>(model.response.body)
        assertTrue(body.isJson)
        val pretty = "{\n  \"name\": \"Ada\",\n  \"tags\": [\n    1,\n    2\n  ]\n}"
        assertEquals(listOf(pretty), body.chunks)
        assertEquals(pretty, body.pieces.join())
        assertEquals("27 B · application/json", body.summary)
        assertEquals("GET 200", model.title)
    }

    @Test
    fun bodyViewsAreCachedByBodyInstance() = runTest {
        val formatter = DetailFormatter()
        val body = textBody("""{"a":1}""")
        val first = formatter.bodyView(body, "response")
        val second = formatter.bodyView(body, "response")
        assertSame(first, second)
        assertEquals(1, formatter.formatted)

        // Equal bytes in another instance is another body: formatted again, never compared by content.
        assertNotSame(first, formatter.bodyView(textBody("""{"a":1}"""), "response"))
        assertEquals(2, formatter.formatted)
    }

    @Test
    fun aTransactionThatCompletesReusesItsRequestBodyView() = runTest {
        val formatter = DetailFormatter()
        val request = textBody("""{"q":"x"}""")
        val started = pending(1, method = "POST", body = request)
        val before = formatter.model(started, Redactor.Default)
        assertIs<BodyView.None>(before.response.body).also { assertEquals("Waiting for the response", it.message) }
        val after = formatter.model(complete(1, method = "POST", requestBody = request), Redactor.Default)
        assertSame(before.request.body, after.request.body)
        assertEquals(2, formatter.formatted) // the request once, the response once
    }

    @Test
    fun theCacheIsBoundedLeastRecentlyUsedFirst() = runTest {
        val formatter = DetailFormatter(capacity = 2)
        val a = textBody("a", "text/plain")
        val b = textBody("b", "text/plain")
        val c = textBody("c", "text/plain")
        val viewA = formatter.bodyView(a, "response")
        formatter.bodyView(b, "response")
        formatter.bodyView(a, "response") // a is now the most recent
        formatter.bodyView(c, "response") // evicts b
        assertSame(viewA, formatter.bodyView(a, "response"))
        assertEquals(3, formatter.formatted)
        formatter.bodyView(b, "response")
        assertEquals(4, formatter.formatted)
    }

    @Test
    fun theCacheIsBoundedByCharactersTooAndKeepsTheNewestView() = runTest {
        val formatter = DetailFormatter(capacity = 16, maxChars = 10_000)
        val a = textBody("a".repeat(4_000), "text/plain")
        val b = textBody("b".repeat(4_000), "text/plain")
        val c = textBody("c".repeat(4_000), "text/plain")
        formatter.bodyView(a, "response")
        formatter.bodyView(b, "response")
        assertEquals(8_000, formatter.cachedChars)
        formatter.bodyView(c, "response") // 12 000 > 10 000: a goes
        assertEquals(8_000, formatter.cachedChars)
        formatter.bodyView(b, "response")
        assertEquals(3, formatter.formatted)
        formatter.bodyView(a, "response")
        assertEquals(4, formatter.formatted)

        // One view larger than the whole budget is still kept, alone, so a reopened body is not formatted again.
        val huge = textBody("h".repeat(20_000), "text/plain")
        val view = formatter.bodyView(huge, "response")
        assertEquals(20_000, formatter.cachedChars)
        assertSame(view, formatter.bodyView(huge, "response"))
    }

    @Test
    fun truncationSkipAndBinaryAreExplained() = runTest {
        val formatter = DetailFormatter()
        val truncated = formatter.bodyView(textBody("{\"a\":", truncated = true, total = 4_096), "response")
        assertEquals("Truncated: showing the first 5 B of 4.0 KB", truncated.notice)

        val skipped = formatter.bodyView(CapturedBody.skipped("binary content-type", "image/png", 2_048), "response")
        assertIs<BodyView.Skipped>(skipped)
        assertEquals("Not captured: binary content-type", skipped.notice)
        assertEquals("2.0 KB · image/png", skipped.summary)

        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)
        val binary = assertIs<BodyView.Binary>(
            formatter.bodyView(CapturedBody(png, png.size.toLong(), false, "application/x-thing", null), "response"),
        )
        assertEquals(9, binary.previewBytes)
        assertTrue(binary.hexPreview.startsWith("00000000  89 50 4e 47 0d 0a 1a 0a  00"), binary.hexPreview)

        val empty = formatter.bodyView(CapturedBody(ByteArray(0), 0, false, null, null), "request")
        assertEquals("Empty request body", assertIs<BodyView.None>(empty).message)
        assertEquals("No request body", assertIs<BodyView.None>(formatter.bodyView(null, "request")).message)
    }

    @Test
    fun headersUrlAndErrorFollowTheStoresRedactor() = runTest {
        val redactor = Redactor.create(extraHeaders = listOf("X-Api-Key"), queryParameters = listOf("token"))
        val url = "https://api.example.com/a?token=s3cret"
        val transaction = pending(1, url = url, headers = listOf("Authorization" to "Bearer t", "X-Api-Key" to "k", "Accept" to "*/*"))
            .failed("java.io.IOException: failed to reach $url", 40)
        val model = DetailFormatter().model(transaction, redactor)
        assertEquals("https://api.example.com/a?token=<redacted>", model.url)
        assertEquals(
            listOf("Authorization" to "<redacted>", "X-Api-Key" to "<redacted>", "Accept" to "*/*"),
            model.request.headers,
        )
        val error = model.overview.single { it.label == "Error" }.value
        assertEquals("java.io.IOException: failed to reach https://api.example.com/a?token=<redacted>", error)
        assertEquals("GET failed", model.title)
        assertEquals("No response: the request failed", assertIs<BodyView.None>(model.response.body).message)
    }

    @Test
    fun theSourceAndTheFinalUrlAreShownWhenKnown() = runTest {
        val redactor = Redactor.create(queryParameters = listOf("token"))
        val captured = complete(1).copy(source = "urlsession", finalUrl = "https://api.example.com/final?token=s3cret")
        val model = DetailFormatter().model(captured, redactor)
        assertEquals("URLSession", model.overview.single { it.label == "Source" }.value)
        assertEquals("https://api.example.com/final?token=<redacted>", model.overview.single { it.label == "Final URL" }.value)
        assertEquals("183 ms (network)", model.overview.single { it.label == "Duration" }.value)
        // The plugin's records keep the plain duration; an older app's records have no source row.
        val ktor = DetailFormatter().model(complete(2).copy(source = "ktor"), redactor)
        assertEquals("Ktor", ktor.overview.single { it.label == "Source" }.value)
        assertEquals("183 ms", ktor.overview.single { it.label == "Duration" }.value)
        val old = DetailFormatter().model(complete(3), redactor)
        assertEquals(null, old.overview.firstOrNull { it.label == "Source" || it.label == "Final URL" })
    }

    @Test
    fun userinfoIsNeverShownCopiedOrShared() = runTest {
        val transaction = complete(1, url = "https://alice:s3cret@api.example.com/v1")
        val model = DetailFormatter().model(transaction, Redactor.Default)
        assertEquals("https://<redacted>@api.example.com/v1", model.url)
        assertEquals(model.url, model.overview.single { it.label == "URL" }.value)
        val share = shareTextOf(model, Redactor.Default)
        assertTrue("s3cret" !in share && "alice" !in share, share)
        assertTrue("s3cret" !in curlOf(transaction, Redactor.Default))
    }

    @Test
    fun overviewListsTheFields() = runTest {
        val model = DetailFormatter().model(complete(1, code = 404), Redactor.Default)
        assertEquals(
            listOf("URL", "Method", "Status", "Started", "Duration", "Protocol", "Request size", "Response size"),
            model.overview.map { it.label },
        )
        assertEquals("404 Not Found", model.overview.single { it.label == "Status" }.value)
        assertEquals("183 ms", model.overview.single { it.label == "Duration" }.value)
        assertEquals("—", model.overview.single { it.label == "Request size" }.value)
    }

    @Test
    fun aFailureAfterTheResponseShowsTheStatusAndThePartialBody() = runTest {
        val partial = textBody("{\"items\":[1,", contentType = "application/json")
        val transaction = complete(1).copy(state = dev.koga.hopi.core.model.TransactionState.Failed, error = "IOException: reset", responseBody = partial)
        val model = DetailFormatter().model(transaction, Redactor.Default)
        assertEquals("Failed after 200 OK", model.overview.single { it.label == "Status" }.value)
        assertTrue(model.overview.single { it.label == "Response received" }.value.endsWith("(partial)"))
        assertTrue(model.overview.none { it.label == "Response size" })
    }

    @Test
    fun copyAndShareOfAGiantRequestBodyStayWithinTheLimitAndValidShell() = runTest {
        val body = textBody("x".repeat(8 * 1024 * 1024), contentType = "text/plain")
        val transaction = complete(1, method = "POST", requestBody = body)
        val model = DetailFormatter().model(transaction, Redactor.Default)
        val curl = curlOf(transaction, Redactor.Default)
        assertTrue(curl.length <= MAX_SHARE_CHARS, "${curl.length}")
        assertTrue(curlBodyWasCut(curl))
        assertTrue(curl.endsWith("x'"), curl.takeLast(10))
        val share = shareTextOf(model, Redactor.Default)
        assertTrue(share.length <= MAX_SHARE_CHARS, "${share.length}")
        assertEquals(share, capForShare(share), "the share sheet does not cut it again, inside the quote")
        assertTrue(share.endsWith("x'"))
        assertTrue(!curlBodyWasCut(curlOf(complete(2), Redactor.Default)))
    }

    @Test
    fun giantHeaderAndUrlValuesAreCutForDisplayButCopiedWhole() = runTest {
        val query = "q=" + "a".repeat(200_000)
        val cookie = "s=" + "\uD83D\uDE00".repeat(40_000)
        val transaction = pending(1, url = "https://api.example.com/search?$query", headers = listOf("X-Blob" to cookie, "Accept" to "*/*"))
            .completed(200, listOf("X-Blob" to cookie), null, 5, null)
        val model = DetailFormatter().model(transaction, Redactor.Default)
        val shownUrl = model.overview.single { it.label == "URL" }.value
        assertTrue(shownUrl.length < 4_200 && shownUrl.endsWith("more chars"), shownUrl.takeLast(40))
        assertEquals("https://api.example.com/search?$query", model.url, "copy URL keeps it whole")
        for ((name, value) in model.request.headers + model.response.headers) {
            assertTrue(value.length < 4_200, "$name: ${value.length} chars")
        }
        val shownCookie = model.request.headers.first().second
        assertTrue(!shownCookie.substringBefore('…').last().isHighSurrogate())
        assertEquals("*/*", model.request.headers[1].second)
        assertTrue(cookie in toCurlOfWhole(transaction))
    }

    private fun toCurlOfWhole(transaction: dev.koga.hopi.core.model.HttpTransaction) =
        curlOf(transaction, Redactor.Default, maxChars = Int.MAX_VALUE)

    @Test
    fun curlAndShareTextComeFromCoreWithRedaction() = runTest {
        val transaction = pending(1, headers = listOf("Authorization" to "Bearer t")).completed(200, emptyList(), null, 5, null)
        val model = DetailFormatter().model(transaction, Redactor.Default)
        val curl = curlOf(transaction, Redactor.Default)
        assertEquals("curl -X GET 'https://api.example.com/users?page=2' -H 'Authorization: <redacted>'", curl)
        val share = shareTextOf(model, Redactor.Default)
        assertTrue(share.startsWith("GET https://api.example.com/users?page=2\nStatus: 200 OK\nDuration: 5 ms\n\n"), share)
        assertTrue(share.endsWith(curl))
    }

    @Test
    fun theShareTextAppliesTheRedactorInForceAtShareTime() = runTest {
        val transaction = complete(1, url = "https://api.example.com/items?token=t-123")
        // Built before another installation added `token` to the store's redaction.
        val model = DetailFormatter().model(transaction, Redactor.Default)
        val share = shareTextOf(model, Redactor.create(queryParameters = listOf("token")))
        assertTrue(share.startsWith("GET https://api.example.com/items?token=${Redactor.REDACTED}\n"), share)
        assertTrue("t-123" !in share, share)
    }

    @Test
    fun theOverviewNamesTheRule() = runTest {
        val model = DetailFormatter().model(complete(2).copy(ruleId = 3, ruleApplied = "delay,mock"), Redactor.Default)
        assertEquals("#3: delay, mock", model.overview.single { it.label == "Rule" }.value)
        val plain = DetailFormatter().model(complete(1), Redactor.Default)
        assertTrue(plain.overview.none { it.label == "Rule" })
    }
}
