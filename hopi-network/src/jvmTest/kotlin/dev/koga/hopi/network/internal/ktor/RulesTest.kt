package dev.koga.hopi.network.internal.ktor

import dev.koga.hopi.core.capture.PlatformCapture
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.rules.DelaySpec
import dev.koga.hopi.core.rules.EditSpec
import dev.koga.hopi.core.rules.FailKind
import dev.koga.hopi.core.rules.MatchShorthand
import dev.koga.hopi.core.rules.MockSpec
import dev.koga.hopi.core.rules.RequestEdit
import dev.koga.hopi.core.rules.ResponseEdit
import dev.koga.hopi.core.rules.RuleBody
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleResult
import dev.koga.hopi.core.rules.RuleSource
import dev.koga.hopi.core.rules.RuleSpec
import dev.koga.hopi.core.rules.RuleState
import dev.koga.hopi.ktor.HopiLogSink
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.fullPath
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.ByteArrayOutputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Rules on the Ktor plugin (docs/design/rules.md, "Ktor plugin"), every action, against `MockEngine`. */
class RulesTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest
    @AfterTest
    fun reset() {
        RuleEngine.resetForTests()
        PlatformCapture.resetForTests()
    }

    private fun add(spec: RuleSpec): Long = assertIs<RuleResult.Ok>(RuleEngine.add(spec), "add").rule.id

    private fun rule(
        match: String,
        mock: MockSpec? = null,
        fail: FailKind? = null,
        delay: DelaySpec? = null,
        edit: EditSpec? = null,
        times: Int? = null,
        sources: Set<RuleSource>? = null,
    ) = RuleSpec(MatchShorthand.parse(match, sources), times = times, delay = delay, mock = mock, fail = fail, edit = edit)

    private fun TestStore.only(): HttpTransaction = store.transactions.value.single()

    @Test
    fun aRequestNoRuleMatchesIsSentAndRecordedAsWithoutRules() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("GET */other/*", mock = MockSpec(500)))
            val client = mockClient(t.store) { respond("""{"a":1}""", HttpStatusCode.OK, jsonHeaders) }
            assertEquals("""{"a":1}""", client.get("https://api.example.com/users/1").bodyAsText())
            val record = t.awaitTerminal().single()
            assertEquals(1, (client.engine as MockEngine).requestHistory.size)
            assertEquals(200, record.responseCode)
            assertNull(record.ruleId)
            assertNull(record.ruleApplied)
            assertEquals("HTTP/1.1", record.protocol)
        }
    }

    @Test
    fun theRuleStateIsEmptyByReferenceWithoutRules() {
        // The plugin's whole no-rule cost is this comparison (see Recorder.send).
        assertTrue(RuleEngine.state === RuleState.EMPTY)
    }

    @Test
    fun aMockNeverCallsTheEngineAndIsRecordedWithItsMark() = runBlocking<Unit> {
        TestStore().use { t ->
            val id = add(rule("GET */users/*", mock = MockSpec(201, listOf("Content-Type" to "application/json", "X-Mock" to "1"), RuleBody.text("""{"mock":true}"""))))
            val client = mockClient(t.store) { error("the engine must not be called") }
            val response = client.get("https://api.example.com/users/42")
            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("1", response.headers["X-Mock"])
            assertEquals("13", response.headers[HttpHeaders.ContentLength])
            assertEquals("""{"mock":true}""", response.bodyAsText())
            assertEquals(0, (client.engine as MockEngine).requestHistory.size)
            val record = t.awaitTerminal().single()
            assertEquals(TransactionState.Complete, record.state)
            assertEquals(201, record.responseCode)
            assertEquals("""{"mock":true}""", record.responseBody!!.bytes.decodeToString())
            assertEquals(id, record.ruleId)
            assertEquals("mock", record.ruleApplied)
            assertNull(record.protocol)
            assertEquals("ktor", record.source)
            assertEquals(1L, RuleEngine.get(id)!!.hits)
        }
    }

    @Test
    fun aMockedHeadGetsTheHeadersWithoutTheBody() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("HEAD */users/*", mock = MockSpec(200, body = RuleBody.text("body"))))
            val client = mockClient(t.store) { error("the engine must not be called") }
            val response = client.head("https://api.example.com/users/42")
            assertEquals("4", response.headers[HttpHeaders.ContentLength])
            assertEquals("", response.bodyAsText())
        }
    }

    @Test
    fun aMocked500ThrowsUnderExpectSuccessAsARealOneWould() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("GET */users/*", mock = MockSpec(500, body = RuleBody.text("boom"))))
            val client = HttpClient(MockEngine { error("the engine must not be called") }) {
                expectSuccess = true
                hopi(t.store) {
                    logSink = HopiLogSink.Off
                }
            }
            val error = assertFailsWith<ServerResponseException> { client.get("https://api.example.com/users/1") }
            assertEquals(500, error.response.status.value)
            assertEquals(500, t.awaitTerminal().single().responseCode)
        }
    }

    @Test
    fun aMockCompletesItsCallJobUnderHttpStatementExecute() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("GET */users/*", mock = MockSpec(200, body = RuleBody.text("ok"))))
            val client = mockClient(t.store) { error("the engine must not be called") }
            val job = client.prepareGet("https://api.example.com/users/1").execute { response ->
                assertEquals("ok", response.bodyAsText())
                response.coroutineContext[kotlinx.coroutines.Job]!!
            }
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCompleted)
        }
    }

    @Test
    fun timesBoundsTheMockThenRequestsReachTheEngineAgain() = runBlocking<Unit> {
        TestStore().use { t ->
            val id = add(rule("GET */users/*", mock = MockSpec(503), times = 1))
            val client = mockClient(t.store) { respond("real", HttpStatusCode.OK) }
            assertEquals(503, client.get("https://api.example.com/users/1").status.value)
            assertEquals(200, client.get("https://api.example.com/users/1").status.value)
            assertTrue(RuleEngine.state === RuleState.EMPTY)
            val records = t.awaitTerminal(2).sortedBy { it.id }
            assertEquals(id, records[0].ruleId)
            assertNull(records[1].ruleId)
            assertEquals(1, (client.engine as MockEngine).requestHistory.size)
        }
    }

    @Test
    fun aStreamingRequestBodyIsDrainedUnderAMockAndItsWriterCompletes() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("POST */upload", mock = MockSpec(204)))
            val client = mockClient(t.store) { error("the engine must not be called") }
            val written = CompletableDeferred<Unit>()
            val body = object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Text.Plain

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    repeat(64) { channel.writeFully(ByteArray(16 * 1024) { 'x'.code.toByte() }) }
                    written.complete(Unit)
                }
            }
            assertEquals(204, client.post("https://api.example.com/upload") { setBody(body) }.status.value)
            withTimeout(5_000) { written.await() }
            val record = t.awaitTerminal().single()
            assertEquals(64L * 16 * 1024, record.requestBody!!.totalBytes)
        }
    }

    @Test
    fun eachFailIsThrownAsTheEngineWouldThrowItAndRecorded() = runBlocking<Unit> {
        val expected = mapOf(
            FailKind.TIMEOUT to SocketTimeoutException::class,
            FailKind.RESET to SocketException::class,
            FailKind.UNKNOWN_HOST to UnknownHostException::class,
            FailKind.CANCEL to CancellationException::class,
        )
        for ((kind, type) in expected) {
            RuleEngine.resetForTests()
            TestStore().use { t ->
                val id = add(rule("GET */users/*", fail = kind))
                val client = mockClient(t.store) { error("the engine must not be called") }
                val thrown = runCatching { client.get("https://api.example.com/users/1") }.exceptionOrNull()
                assertNotNull(thrown, kind.wireName)
                assertTrue(type.isInstance(thrown), "$kind threw $thrown")
                val record = t.awaitTerminal().single()
                assertEquals(TransactionState.Failed, record.state)
                assertEquals(id, record.ruleId)
                assertEquals("fail", record.ruleApplied)
                assertEquals(0, (client.engine as MockEngine).requestHistory.size)
            }
        }
    }

    @Test
    fun unknownHostNamesTheHostAndTimeoutIsHttpTimeoutsWhenInstalled() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("GET */a", fail = FailKind.UNKNOWN_HOST))
            add(rule("GET */b", fail = FailKind.TIMEOUT))
            val client = HttpClient(MockEngine { error("the engine must not be called") }) {
                install(HttpTimeout)
                hopi(t.store) {
                    logSink = HopiLogSink.Off
                }
            }
            assertEquals("api.example.com", assertFailsWith<UnknownHostException> { client.get("https://api.example.com/a") }.message)
            assertFailsWith<HttpRequestTimeoutException> { client.get("https://api.example.com/b") }
        }
    }

    @Test
    fun aDelayBeforeTheRequestHoldsItAndCountsInTheDuration() = runBlocking<Unit> {
        TestStore().use { t ->
            val id = add(rule("GET */slow", delay = DelaySpec(beforeRequestMs = 300)))
            val sentAt = Collections.synchronizedList(mutableListOf<Long>())
            val start = TimeSource.Monotonic.markNow()
            val client = mockClient(t.store) {
                sentAt += start.elapsedNow().inWholeMilliseconds
                respond("ok", HttpStatusCode.OK)
            }
            assertEquals("ok", client.get("https://api.example.com/slow").bodyAsText())
            assertTrue(sentAt.single() >= 300, "sent after ${sentAt.single()} ms")
            val record = t.awaitTerminal().single()
            assertEquals(id, record.ruleId)
            assertEquals("delay", record.ruleApplied)
            assertTrue(record.durationMs!! >= 300)
        }
    }

    @Test
    fun aDelayBeforeTheResponseHoldsTheResponse() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("GET */slow", delay = DelaySpec(beforeResponseMs = 300), mock = MockSpec(200, body = RuleBody.text("late"))))
            val client = mockClient(t.store) { error("the engine must not be called") }
            val start = TimeSource.Monotonic.markNow()
            assertEquals("late", client.get("https://api.example.com/slow").bodyAsText())
            assertTrue(start.elapsedNow().inWholeMilliseconds >= 300)
            assertEquals("delay,mock", t.awaitTerminal().single().ruleApplied)
        }
    }

    @Test
    fun httpTimeoutsRequestTimeoutEndsALongDelay() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("GET */slow", delay = DelaySpec(beforeRequestMs = 60_000)))
            val client = HttpClient(MockEngine { respond("never") }) {
                install(HttpTimeout) { requestTimeoutMillis = 200 }
                hopi(t.store) {
                    logSink = HopiLogSink.Off
                }
            }
            val start = TimeSource.Monotonic.markNow()
            assertFailsWith<HttpRequestTimeoutException> { client.get("https://api.example.com/slow") }
            assertTrue(start.elapsedNow().inWholeMilliseconds < 5_000)
            assertEquals(0, (client.engine as MockEngine).requestHistory.size)
            assertEquals(TransactionState.Failed, t.awaitTerminal().single().state)
        }
    }

    @Test
    fun cancellingTheCallerEndsADelay() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("GET */slow", delay = DelaySpec(beforeRequestMs = 60_000)))
            val client = mockClient(t.store) { respond("never") }
            val start = TimeSource.Monotonic.markNow()
            val call = async { client.get("https://api.example.com/slow") }
            delay(100.milliseconds)
            call.cancel()
            runCatching { call.await() }
            assertTrue(start.elapsedNow().inWholeMilliseconds < 5_000)
            assertEquals("cancelled", t.awaitTerminal().single().error)
        }
    }

    @Test
    fun aRequestEditReachesTheEngineAndTheRecordHoldsTheRequestAsSent() = runBlocking<Unit> {
        TestStore().use { t ->
            val edit = RequestEdit(
                method = "PUT",
                path = "/v2/users/7",
                setQuery = listOf("debug" to "1"),
                removeQuery = listOf("trace"),
                setHeaders = listOf("X-Env" to "staging"),
                removeHeaders = listOf("X-Remove"),
            )
            val id = add(rule("GET */users/*", edit = EditSpec(request = edit)))
            val client = mockClient(t.store) { request -> respond(request.method.value + " " + request.url.fullPath + " " + request.headers["X-Env"] + " " + request.headers["X-Remove"]) }
            val text = client.get("https://api.example.com/users/1?trace=1&keep=2") {
                header("X-Env", "prod")
                header("X-Remove", "1")
            }.bodyAsText()
            assertEquals("PUT /v2/users/7?keep=2&debug=1 staging null", text)
            val record = t.awaitTerminal().single()
            assertEquals("PUT", record.method)
            assertEquals("https://api.example.com/v2/users/7?keep=2&debug=1", record.url)
            assertTrue("X-Env" to "staging" in record.requestHeaders, record.requestHeaders.toString())
            assertTrue(record.requestHeaders.none { it.first == "X-Remove" })
            assertEquals(id, record.ruleId)
            assertEquals("edit-request", record.ruleApplied)
        }
    }

    @Test
    fun anOriginChangeDropsTheCredentialsTheEditDoesNotSet() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("GET */users/*", edit = EditSpec(request = RequestEdit(host = "staging.example.com"))))
            val seen = Collections.synchronizedList(mutableListOf<HttpRequestData>())
            val client = mockClient(t.store) { request ->
                seen += request
                respond("ok")
            }
            client.get("https://api.example.com/users/1") {
                header(HttpHeaders.Authorization, "Bearer secret")
                header(HttpHeaders.Cookie, "a=b")
                header("X-Keep", "1")
            }
            val sent = seen.single()
            assertEquals("staging.example.com", sent.url.host)
            assertNull(sent.headers[HttpHeaders.Authorization])
            assertNull(sent.headers[HttpHeaders.Cookie])
            assertEquals("1", sent.headers["X-Keep"])
        }
    }

    @Test
    fun anOriginChangeSendsOnlyTheCredentialsTheEditAdds() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("GET */users/*", edit = EditSpec(request = RequestEdit(host = "staging.example.com", addHeaders = listOf("Cookie" to "debug=1", "Authorization" to "Bearer test")))))
            val seen = Collections.synchronizedList(mutableListOf<HttpRequestData>())
            val client = mockClient(t.store) { request ->
                seen += request
                respond("ok")
            }
            client.get("https://api.example.com/users/1") {
                header(HttpHeaders.Authorization, "Bearer secret")
                header(HttpHeaders.Cookie, "session=SECRET")
            }
            val sent = seen.single()
            assertEquals(listOf("Bearer test"), sent.headers.getAll(HttpHeaders.Authorization))
            assertEquals(listOf("debug=1"), sent.headers.getAll(HttpHeaders.Cookie))
        }
    }

    @Test
    fun aBodyEditReplacesTheBodyAndKeepsItsTypeUnlessTheEditSetsOne() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("POST */a", edit = EditSpec(request = RequestEdit(body = RuleBody.text("""{"edited":1}""")))))
            add(rule("POST */b", edit = EditSpec(request = RequestEdit(setHeaders = listOf("Content-Type" to "text/plain")))))
            val client = mockClient(t.store) { request -> respond(request.body.contentType.toString() + " " + request.body.toByteArray().decodeToString()) }
            val a = client.post("https://api.example.com/a") {
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"original":1}""")
            }.bodyAsText()
            assertEquals("""application/json {"edited":1}""", a)
            val b = client.post("https://api.example.com/b") {
                header(HttpHeaders.ContentType, "application/json")
                setBody("same")
            }.bodyAsText()
            assertEquals("text/plain same", b)
            val records = t.awaitTerminal(2).sortedBy { it.id }
            assertEquals("""{"edited":1}""", records[0].requestBody!!.bytes.decodeToString())
            assertTrue("Content-Type" to "text/plain" in records[1].requestHeaders, records[1].requestHeaders.toString())
        }
    }

    @Test
    fun aResponseEditChangesStatusHeadersAndBody() = runBlocking<Unit> {
        TestStore().use { t ->
            val edit = ResponseEdit(status = 503, setHeaders = listOf("X-Edited" to "1"), removeHeaders = listOf("X-Remove"), body = RuleBody.text("edited"))
            val id = add(rule("GET */users/*", edit = EditSpec(response = edit)))
            val client = mockClient(t.store) { respond("original", HttpStatusCode.OK, headersOf("X-Remove" to listOf("1"), "Content-Type" to listOf("text/plain"))) }
            val response = client.get("https://api.example.com/users/1")
            assertEquals(503, response.status.value)
            assertEquals("1", response.headers["X-Edited"])
            assertNull(response.headers["X-Remove"])
            assertEquals("edited", response.bodyAsText())
            val record = t.awaitTerminal().single()
            assertEquals(503, record.responseCode)
            assertEquals("edited", record.responseBody!!.bytes.decodeToString())
            assertEquals(id, record.ruleId)
            assertEquals("edit-response", record.ruleApplied)
            assertEquals("HTTP/1.1", record.protocol)
        }
    }

    @Test
    fun aBodyEditUnderContentEncodingDropsTheEncodingAndTheAppReadsTheReplacement() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("GET */gz", edit = EditSpec(response = ResponseEdit(body = RuleBody.text("plain replacement")))))
            val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write("compressed original".toByteArray()) } }.toByteArray()
            val client = HttpClient(MockEngine { respond(ByteReadChannel(zipped), HttpStatusCode.OK, headersOf(HttpHeaders.ContentEncoding, "gzip")) }) {
                install(ContentEncoding) { gzip() }
                hopi(t.store) {
                    logSink = HopiLogSink.Off
                }
            }
            val response = client.get("https://api.example.com/gz")
            assertEquals("plain replacement", response.bodyAsText())
            assertEquals("plain replacement", t.awaitTerminal().single().responseBody!!.bytes.decodeToString())
        }
    }

    @Test
    fun httpCacheDoesNotStoreAMock() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("GET */cached", mock = MockSpec(200, listOf("Cache-Control" to "max-age=600"), RuleBody.text("mocked")), times = 1))
            val client = HttpClient(MockEngine { respond("real", HttpStatusCode.OK, headersOf(HttpHeaders.CacheControl, "no-store")) }) {
                install(HttpCache)
                hopi(t.store) {
                    logSink = HopiLogSink.Off
                }
            }
            val first = client.get("https://api.example.com/cached")
            assertEquals("no-store", first.headers[HttpHeaders.CacheControl])
            assertEquals("mocked", first.bodyAsText())
            assertEquals("real", client.get("https://api.example.com/cached").bodyAsText())
        }
    }

    @Test
    fun aRedirectMockIsFollowedToASeparatelyMatchedHop() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("GET */old", mock = MockSpec(302, listOf("Location" to "https://api.example.com/new"))))
            val client = mockClient(t.store) { request -> respond("at " + request.url.encodedPath) }
            assertEquals("at /new", client.get("https://api.example.com/old").bodyAsText())
            val records = t.awaitTerminal(2).sortedBy { it.id }
            assertEquals(302, records[0].responseCode)
            assertEquals("mock", records[0].ruleApplied)
            assertNull(records[1].ruleId)
        }
    }

    @Test
    fun aCoveredEngineAppliesNoRule() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("GET */users/*", mock = MockSpec(500)))
            PlatformCapture.cover(MockEngine::class.qualifiedName!!.substringBeforeLast('.') + ".")
            val client = mockClient(t.store) { respond("real") }
            assertEquals("real", client.get("https://api.example.com/users/1").bodyAsText())
            assertEquals(1, (client.engine as MockEngine).requestHistory.size)
            assertTrue(t.store.transactions.value.isEmpty())
        }
    }

    @Test
    fun aPassThroughRequestIsRuledOnceByThePlugin() = runBlocking<Unit> {
        TestStore().use { t ->
            val id = add(rule("GET */users/*", edit = EditSpec(request = RequestEdit(setHeaders = listOf("X-Edited" to "1")))))
            PlatformCapture.coverWithPassThrough(MockEngine::class.qualifiedName!!.substringBeforeLast('.') + ".")
            val client = mockClient(t.store) { request -> respond(request.headers["X-Edited"] + " " + request.headers[PlatformCapture.PASS_THROUGH_HEADER]) }
            assertEquals("1 1", client.get("https://api.example.com/users/1") { header(HttpHeaders.AcceptEncoding, "gzip") }.bodyAsText())
            assertEquals(id, t.awaitTerminal().single().ruleId)
            assertEquals(1L, RuleEngine.get(id)!!.hits)
        }
    }

    @Test
    fun rulesOffNeverAppliesARule() = runBlocking<Unit> {
        TestStore().use { t ->
            add(rule("GET */users/*", mock = MockSpec(500)))
            val client = mockClient(t.store, { rules = false }) { respond("real") }
            assertEquals("real", client.get("https://api.example.com/users/1").bodyAsText())
            assertNull(t.awaitTerminal().single().ruleId)
        }
    }

    @Test
    fun theInternalCallConstructorsHopiBuildsOnExist() {
        // Pinned to the Ktor version Hopi builds against (docs/design/rules.md, "Risks"): a Ktor
        // that changes these fails here, not in an app.
        HttpClientCall::class.java.getConstructor(HttpClient::class.java, HttpRequestData::class.java, HttpResponseData::class.java)
        HttpClientCall::class.java.getConstructor(HttpClient::class.java)
        HttpRequestData::class.java.constructors.single { it.parameterCount == 6 }
        HttpResponseData::class.java.constructors.single { it.parameterCount == 6 }
    }
}
