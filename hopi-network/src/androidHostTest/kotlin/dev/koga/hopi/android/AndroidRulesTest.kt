package dev.koga.hopi.android

import dev.koga.hopi.android.internal.RuledHttpConnection
import dev.koga.hopi.android.internal.RuledHttpsConnection
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
import dev.koga.hopi.core.rules.RuleJson
import dev.koga.hopi.core.rules.RuleParse
import dev.koga.hopi.core.rules.RuleResult
import dev.koga.hopi.core.rules.RuleSource
import dev.koga.hopi.core.rules.RuleSpec
import dev.koga.hopi.core.store.HopiStore
import java.net.InetAddress
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import javax.net.ssl.SSLSocketFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode

/**
 * Rules on OkHttp and `HttpURLConnection` (docs/design/rules.md, "OkHttp" and
 * "`HttpURLConnection`"): every action on each source, through the rewritten bytecode an app runs,
 * against loopback servers that count what reached them.
 */
class AndroidRulesTest {
    private lateinit var server: TestServer
    private lateinit var store: HopiStore

    @BeforeTest
    fun setUp() {
        RuleEngine.resetForTests()
        PlatformCapture.resetForTests()
        server = TestServer()
        store = HopiStore()
    }

    @AfterTest
    fun tearDown() {
        server.close()
        store.close()
        RuleEngine.resetForTests()
        PlatformCapture.resetForTests()
    }

    private fun add(spec: RuleSpec): Long = assertIs<RuleResult.Ok>(RuleEngine.add(spec), "add").rule.id

    private fun rule(
        match: String,
        source: RuleSource,
        mock: MockSpec? = null,
        fail: FailKind? = null,
        delay: DelaySpec? = null,
        edit: EditSpec? = null,
        times: Int? = null,
    ) = RuleSpec(MatchShorthand.parse(match, setOf(source)), times = times, delay = delay, mock = mock, fail = fail, edit = edit)

    private fun okhttp(vararg args: Pair<String, Any?>): Map<String, Any?> = runScenario("rule.okhttp", store, server.base, extra = mapOf(*args))

    private fun url(vararg args: Pair<String, Any?>): Map<String, Any?> = runScenario("rule.url", store, server.base, extra = mapOf(*args))

    private fun one(): HttpTransaction = store.awaitTerminal(1).single()

    /**
     * Adds a mock of the `/gzip` GET on [source] built from the one record, as both viewers build it
     * in the app (RuleJson's `fromTransaction`): the record holds the server's gzip bytes under their
     * `Content-Encoding`, and the mock copies them decoded, without it. `/gzip` decodes to exactly
     * the 1 MiB body limit, which a mock may hold (only more is refused).
     */
    private fun mockGzipFromItsRecord(source: RuleSource) {
        val record = one()
        assertEquals(listOf("gzip"), record.responseHeaders.filter { it.first.equals("Content-Encoding", ignoreCase = true) }.map { it.second })
        assertEquals(listOf(0x1f.toByte(), 0x8b.toByte()), record.responseBody!!.bytes.take(2), "the record holds the gzip bytes")
        val mock = assertIs<RuleParse.Ok<MockSpec>>(RuleJson.mockFromTransaction(record, status = null, headers = null, body = null)).value
        assertContentEquals(TestServer.large(), mock.body!!.bytes)
        assertTrue(mock.headers.none { it.first.equals("Content-Encoding", ignoreCase = true) }, mock.headers.toString())
        add(rule("GET */gzip", source, mock = mock))
    }

    // OkHttp

    @Test
    fun okHttpWithARuleThatDoesNotMatchSendsAndRecordsAsWithoutRules() {
        add(rule("GET */other", RuleSource.OKHTTP, mock = MockSpec(500)))
        val observed = okhttp("path" to "/json")
        assertEquals(200, observed["code"])
        assertEquals(1, server.received.size)
        val record = one()
        assertNull(record.ruleId)
        assertNull(record.ruleApplied)
        assertEquals("HTTP/1.1", record.protocol)
    }

    @Test
    fun okHttpMockNeverConnectsAndTheAppsInterceptorsSeeIt() {
        val id = add(rule("GET */users/*", RuleSource.OKHTTP, mock = MockSpec(503, listOf("Content-Type" to "application/json"), RuleBody.text("""{"mock":1}"""))))
        val observed = okhttp("path" to "/users/7")
        assertEquals(503, observed["code"])
        assertEquals("Service Unavailable", observed["message"])
        assertEquals("""{"mock":1}""", observed["body"])
        assertEquals(true, observed["handshakeNull"])
        assertEquals(listOf(503), observed["appSaw"])
        assertEquals(0, observed["networkCalls"])
        assertEquals(0, server.received.size)
        val record = one()
        assertEquals(503, record.responseCode)
        assertEquals("""{"mock":1}""", record.responseBody!!.bytes.decodeToString())
        assertEquals(id, record.ruleId)
        assertEquals("mock", record.ruleApplied)
        assertNull(record.protocol)
        assertEquals("okhttp", record.source)
        assertTrue("X-App" to "1" in record.requestHeaders, record.requestHeaders.toString())
    }

    @Test
    fun okHttpMocked302And401ReachTheAppUnfollowed() {
        add(rule("GET */moved", RuleSource.OKHTTP, mock = MockSpec(302, listOf("Location" to "/json"))))
        add(rule("GET */auth", RuleSource.OKHTTP, mock = MockSpec(401, listOf("WWW-Authenticate" to "Basic realm=\"x\""))))
        assertEquals(302, okhttp("path" to "/moved")["code"])
        val auth = okhttp("path" to "/auth")
        assertEquals(401, auth["code"])
        assertEquals(0, auth["authenticatorCalls"])
        assertEquals(0, server.received.size)
    }

    @Test
    fun okHttpFailsThrowTheStacksOwnExceptions() {
        val expected = mapOf(
            FailKind.TIMEOUT to ("java.net.SocketTimeoutException" to "timeout"),
            FailKind.RESET to ("java.net.SocketException" to "Connection reset"),
            FailKind.UNKNOWN_HOST to ("java.net.UnknownHostException" to "Unable to resolve host \"127.0.0.1\": No address associated with hostname"),
            FailKind.CANCEL to ("java.io.IOException" to "Canceled"),
        )
        for ((kind, exception) in expected) {
            RuleEngine.resetForTests()
            add(rule("GET */json", RuleSource.OKHTTP, fail = kind))
            val observed = okhttp("path" to "/json")
            assertEquals(exception.first, observed["exception"], kind.wireName)
            assertEquals(exception.second, observed["exceptionMessage"], kind.wireName)
            assertEquals(kind == FailKind.CANCEL, observed["isCanceled"])
        }
        assertEquals(0, server.received.size)
        val records = store.awaitTerminal(4)
        assertTrue(records.all { it.state == TransactionState.Failed && it.ruleApplied == "fail" }, records.toString())
    }

    @Test
    fun okHttpDelaysEndOnCancelAndOnTheClientsTimeouts() {
        add(rule("GET */json", RuleSource.OKHTTP, delay = DelaySpec(beforeRequestMs = 60_000)))
        val cancelled = okhttp("path" to "/json", "cancelAfterMs" to 200L)
        assertEquals("java.io.IOException", cancelled["exception"])
        assertTrue((cancelled["elapsedMs"] as Long) < 5_000)
        val callTimeout = okhttp("path" to "/json", "callTimeoutMs" to 200L)
        assertEquals("java.io.InterruptedIOException", callTimeout["exception"])
        assertEquals("timeout", callTimeout["exceptionMessage"])
        assertEquals(0, server.received.size)
        RuleEngine.resetForTests()
        add(rule("GET */json", RuleSource.OKHTTP, delay = DelaySpec(beforeResponseMs = 60_000)))
        val readTimeout = okhttp("path" to "/json", "readTimeoutMs" to 200L)
        assertEquals("java.net.SocketTimeoutException", readTimeout["exception"])
        assertTrue((readTimeout["elapsedMs"] as Long) < 5_000)
        assertEquals(1, server.received.size)
    }

    @Test
    fun okHttpDelayHoldsTheRequest() {
        val id = add(rule("GET */json", RuleSource.OKHTTP, delay = DelaySpec(beforeRequestMs = 250, beforeResponseMs = 100)))
        val observed = okhttp("path" to "/json")
        assertEquals(200, observed["code"])
        assertTrue((observed["elapsedMs"] as Long) >= 350, observed.toString())
        val record = one()
        assertEquals(id, record.ruleId)
        assertEquals("delay", record.ruleApplied)
        assertTrue(record.durationMs!! >= 350)
    }

    @Test
    fun okHttpRequestEditReachesTheServer() {
        val edit = RequestEdit(path = "/headers", setHeaders = listOf("X-Env" to "staging"), removeHeaders = listOf("X-Remove"))
        add(rule("GET */users/*", RuleSource.OKHTTP, edit = EditSpec(request = edit)))
        val observed = okhttp("path" to "/users/1", "headers" to mapOf("X-Env" to "prod", "X-Remove" to "1"))
        val lines = (observed["body"] as String).lines()
        assertTrue("x-env: staging" in lines, lines.toString())
        assertTrue(lines.none { it.startsWith("x-remove") }, lines.toString())
        assertTrue("x-app: 1" in lines, lines.toString())
        assertEquals(listOf("GET /headers"), server.received.toList())
        val record = one()
        assertEquals("${server.base}/headers", record.url)
        assertEquals("edit-request", record.ruleApplied)
    }

    @Test
    fun okHttpRefusesAnEditToAnotherOriginOnAClientWhoseAuthenticatorWouldAddACredential() {
        // localhost is another origin than the server's 127.0.0.1, and reaches the same server.
        add(rule("GET */users/*", RuleSource.OKHTTP, edit = EditSpec(request = RequestEdit(host = "localhost", path = "/headers"))))
        okhttp("path" to "/users/1")
        assertEquals(listOf("GET /users/1"), server.received.toList(), "an authenticator would answer a 401 with a credential")
        assertEquals(null, one().ruleApplied)
        okhttp("path" to "/users/1", "authenticator" to false)
        assertEquals(listOf("GET /users/1", "GET /headers"), server.received.toList())
        assertEquals("edit-request", store.awaitTerminal(2).last().ruleApplied)
    }

    @Test
    fun okHttpEditToAPinnedHostStillFailsThePinAndAMockOnItNeverConnects() {
        TlsServer().use { tls ->
            val wrongPin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
            val tlsArgs = arrayOf(
                "url" to "${tls.base}/a",
                "tlsFactory" to tls.sslContext.socketFactory,
                "tlsTrust" to tls.trustManager,
                "pin" to wrongPin,
                "pinHost" to "127.0.0.1",
            )
            // The pin is enforced without rules...
            assertEquals("javax.net.ssl.SSLPeerUnverifiedException", okhttp(*tlsArgs)["exception"])
            // ...and after an edit of the request, by the client's own pinner.
            add(rule("GET */a", RuleSource.OKHTTP, edit = EditSpec(request = RequestEdit(path = "/b", setHeaders = listOf("X-Edited" to "1")))))
            assertEquals("javax.net.ssl.SSLPeerUnverifiedException", okhttp(*tlsArgs)["exception"])
            RuleEngine.resetForTests()
            add(rule("GET */a", RuleSource.OKHTTP, mock = MockSpec(200, body = RuleBody.text("mocked"))))
            val mocked = okhttp(*tlsArgs)
            assertEquals("mocked", mocked["body"])
            assertEquals(true, mocked["handshakeNull"])
            // The pinned handshakes never sent a request; the mock never connected.
            assertEquals(0, tls.received.size)
        }
    }

    @Test
    fun okHttpResponseEditChangesWhatTheAppAndTheRecordSee() {
        val edit = ResponseEdit(status = 418, setHeaders = listOf("X-Edited" to "1"), body = RuleBody.text("edited"))
        val id = add(rule("GET */json", RuleSource.OKHTTP, edit = EditSpec(response = edit)))
        val observed = okhttp("path" to "/json")
        assertEquals(418, observed["code"])
        assertEquals("I'm a teapot", observed["message"])
        assertEquals("edited", observed["body"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("1"), (observed["headers"] as Map<String, List<String>>)["x-edited"])
        val record = one()
        assertEquals(418, record.responseCode)
        assertEquals("edited", record.responseBody!!.bytes.decodeToString())
        assertEquals(id, record.ruleId)
        assertEquals("edit-response", record.ruleApplied)
    }

    @Test
    fun okHttpCacheNeverStoresAResponseBodyEdit() {
        val cache = Files.createTempDirectory("hopi-cache").toFile()
        try {
            add(rule("GET */cacheable", RuleSource.OKHTTP, edit = EditSpec(response = ResponseEdit(body = RuleBody.text("edited"))), times = 1))
            val observed = okhttp("path" to "/cacheable", "cache" to cache, "secondPath" to "/cacheable")
            assertEquals("edited", observed["body"])
            // The cache sits below Hopi and writes what the network sent: closing the original
            // drains a small body into it, so the cache holds the server's response, never the edit.
            assertEquals("cacheable 1", observed["second"])
            assertEquals(1, server.received.size)
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun okHttpHeaderOnlyEditCachesTheOriginal() {
        val cache = Files.createTempDirectory("hopi-cache").toFile()
        try {
            add(rule("GET */cacheable", RuleSource.OKHTTP, edit = EditSpec(response = ResponseEdit(setHeaders = listOf("X-Edited" to "1"))), times = 1))
            val observed = okhttp("path" to "/cacheable", "cache" to cache, "secondPath" to "/cacheable")
            assertEquals("cacheable 1", observed["body"])
            assertEquals("cacheable 1", observed["second"])
            assertEquals(1, server.received.size)
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun okHttpBodyEditUnderTheAppsOwnAcceptEncodingDropsContentEncoding() {
        add(rule("GET */gzip", RuleSource.OKHTTP, edit = EditSpec(response = ResponseEdit(body = RuleBody.text("plain")))))
        val observed = okhttp("path" to "/gzip", "headers" to mapOf("Accept-Encoding" to "gzip"))
        assertEquals("plain", observed["body"])
        @Suppress("UNCHECKED_CAST")
        assertNull((observed["headers"] as Map<String, List<String>>)["content-encoding"])
    }

    @Test
    fun okHttpMockFromARecordHeldGzippedReachesTheAppDecoded() {
        // The app names its own Accept-Encoding: OkHttp leaves it the gzip bytes and their Content-Encoding.
        val gzip = arrayOf("path" to "/gzip", "headers" to mapOf("Accept-Encoding" to "gzip"))
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("gzip"), (okhttp(*gzip)["headers"] as Map<String, List<String>>)["content-encoding"])
        mockGzipFromItsRecord(RuleSource.OKHTTP)
        val mocked = okhttp(*gzip)
        assertEquals(TestServer.large().decodeToString(), mocked["body"])
        @Suppress("UNCHECKED_CAST")
        assertNull((mocked["headers"] as Map<String, List<String>>)["content-encoding"])
        assertEquals(1, server.received.size)
        assertEquals("mock", store.awaitTerminal(2).last().ruleApplied)
    }

    @Test
    fun okHttpRulesOffAppliesNothing() {
        add(rule("GET */json", RuleSource.OKHTTP, mock = MockSpec(500)))
        val observed = runScenario("rule.okhttp", store, server.base, extra = mapOf("path" to "/json", "rules" to false))
        assertEquals(200, observed["code"])
        assertNull(one().ruleId)
    }

    // HttpURLConnection

    @Test
    fun urlConnectionWithARuleThatDoesNotMatchSendsAndRecordsAsWithoutRules() {
        add(rule("GET */other", RuleSource.URL_CONNECTION, mock = MockSpec(500)))
        val observed = url("path" to "/json")
        assertEquals(200, observed["code"])
        assertEquals(1, server.received.size)
        val record = one()
        assertNull(record.ruleId)
    }

    @Test
    fun urlConnectionWithOnlyAnotherSourcesRulesTakesThePathWithoutRules() {
        add(rule("GET */json", RuleSource.OKHTTP, mock = MockSpec(500)))
        val observed = url("path" to "/json", "headers" to mapOf("X-A" to "1"))
        assertEquals(200, observed["code"])
        val record = one()
        assertNull(record.ruleId)
        assertNull(record.ruleApplied)
        assertTrue("X-A" to "1" in record.requestHeaders, record.requestHeaders.toString())
        assertEquals(0L, RuleEngine.list().single().hits)
    }

    @Test
    fun theUrlConnectionRuleRequestReadsAsTheUrlRuleRequestDoes() {
        val properties = linkedMapOf<String?, List<String>>(null to listOf("ignored"), "X-A" to listOf("1"), "x-a" to listOf("2"), "Accept" to listOf("*/*"))
        val flat = listOf("X-A" to "1", "x-a" to "2", "Accept" to "*/*")
        for (text in listOf("https://H.example:8443/p%20q?x=%31&x=2&y&z=a+b", "http://h", "http://h?only", "https://[::1]/v6", "http://h:80/", "https://h/a/b/")) {
            val url = java.net.URL(text)
            val lazy = dev.koga.hopi.android.internal.UrlConnectionRuleRequest("GET", url, properties)
            val reference = dev.koga.hopi.core.rules.UrlRuleRequest("GET", url.toString(), flat)
            assertEquals(reference.scheme, lazy.scheme, text)
            assertEquals(reference.host, lazy.host, text)
            assertEquals(reference.port, lazy.port, text)
            assertEquals(reference.encodedPath, lazy.encodedPath, text)
            for (name in listOf("x", "y", "z", "only", "missing")) assertEquals(reference.queryValues(name), lazy.queryValues(name), "$text ?$name")
            for (name in listOf("x-a", "ACCEPT", "missing")) assertEquals(reference.headerValues(name), lazy.headerValues(name), "$text $name")
        }
    }

    /**
     * The in-place predicate queries of the OkHttp and `URLConnection` views (no list per predicate)
     * agree with their own value lists on generated requests, the `Content-Type` fallback to an
     * OkHttp body's type included, and a compiled rule over the view agrees with one over the
     * [dev.koga.hopi.core.rules.UrlRuleRequest] of the same request.
     */
    @Test
    fun theSourceViewsAnswerPredicatesInPlaceAsTheirListsDo() {
        val random = kotlin.random.Random(25)
        val names = listOf("X-Env", "x-env", "Accept", "Content-Type", "Missing")
        val values = listOf("staging", "stag", "prod", "*/*", "", "application/json")
        val globs = listOf(null, "stag*", "*json", "prod", "", "*").map { it?.let { g -> dev.koga.hopi.core.rules.Glob.compile(g, dev.koga.hopi.core.rules.Glob.Mode.TEXT) } }
        val queries = listOf("", "?page=2", "?page=2&page=3", "?x=%31&x=2&y&z=a+b", "?only", "?%70age=2")
        val rules = listOf("GET */json?page=2" to null, "* */json" to "X-Env: stag*", "GET */json?!page" to null, "* api.example.com/**" to null, "GET */json" to "!Accept")
        for ((match, header) in rules) {
            val headerPredicates = listOfNotNull(header?.let(MatchShorthand::headerPredicate))
            assertIs<RuleResult.Ok>(RuleEngine.add(RuleSpec(MatchShorthand.parse(match, headers = headerPredicates), mock = MockSpec(200))))
        }
        val compiled = RuleEngine.state.rulesFor(RuleSource.URL_CONNECTION).toList()
        assertEquals(rules.size, compiled.size)
        repeat(500) {
            val url = "http://h.example/json" + queries.random(random)
            val headers = List(random.nextInt(4)) { listOf("X-Env", "Accept", "x-env").random(random) to values.random(random) }
            val bodyType = listOf(null, "application/json", "text/plain").random(random)
            val builder = okhttp3.Request.Builder().url(url)
            for ((n, v) in headers) builder.addHeader(n, v)
            if (bodyType != null) builder.post("{}".toRequestBody(bodyType.toMediaType()))
            val okhttp = dev.koga.hopi.android.internal.OkHttpRuleRequest(builder.build())
            val properties = LinkedHashMap<String?, List<String>>()
            for ((n, v) in headers) properties[n] = properties[n].orEmpty() + v
            val connection = dev.koga.hopi.android.internal.UrlConnectionRuleRequest("GET", java.net.URL(url), properties)
            for (glob in globs) {
                for (name in names) {
                    for (view in listOf(okhttp, connection)) {
                        val expected = view.headerValues(name).let { v -> if (glob == null) v.isNotEmpty() else v.any { glob.matches(it) } }
                        assertEquals(expected, view.hasHeaderValue(name, glob), "$view $headers $bodyType $name $glob")
                    }
                }
                for (name in listOf("page", "x", "y", "z", "only", "missing")) {
                    for (view in listOf(okhttp, connection)) {
                        val expected = view.queryValues(name).let { v -> if (glob == null) v.isNotEmpty() else v.any { glob.matches(it) } }
                        assertEquals(expected, view.hasQueryValue(name, glob), "$view $url ?$name $glob")
                    }
                }
            }
            val reference = dev.koga.hopi.core.rules.UrlRuleRequest("GET", url, headers)
            for (rule in compiled) assertEquals(rule.matches(reference), rule.matches(connection), "$rule $url $headers")
        }
    }

    @Test
    fun urlConnectionMockNeverConnects() {
        val id = add(rule("GET */users/*", RuleSource.URL_CONNECTION, mock = MockSpec(200, listOf("Content-Type" to "application/json", "X-Mock" to "1"), RuleBody.text("""{"m":1}"""))))
        val observed = url("path" to "/users/1")
        assertEquals(200, observed["code"])
        assertEquals("OK", observed["message"])
        assertEquals("""{"m":1}""", observed["body"])
        assertEquals("HTTP/1.1 200 OK", observed["header0"])
        assertEquals("1", observed["headerX"])
        assertEquals(7L, observed["contentLength"])
        assertEquals("application/json", observed["contentType"])
        assertEquals(false, observed["usingProxy"])
        assertEquals(0, server.received.size)
        val record = one()
        assertEquals(id, record.ruleId)
        assertEquals("mock", record.ruleApplied)
        assertEquals("""{"m":1}""", record.responseBody!!.bytes.decodeToString())
        assertNull(record.protocol)
        assertEquals("urlconnection", record.source)
    }

    @Test
    fun urlConnection4xxMockThrowsFromTheInputStreamAndServesTheErrorStream() {
        add(rule("GET */missing", RuleSource.URL_CONNECTION, mock = MockSpec(404, body = RuleBody.text("not here"))))
        val observed = url("path" to "/missing", "errorStreamFirst" to true)
        assertNull(observed["errorBeforeConnect"])
        assertEquals(404, observed["code"])
        assertEquals("java.io.FileNotFoundException", observed["inputException"])
        assertEquals("not here", observed["error"])
        assertEquals(0, server.received.size)
    }

    @Test
    fun urlConnectionHttpsMockHasNoCertificates() {
        TlsServer().use { tls ->
            add(rule("GET */a", RuleSource.URL_CONNECTION, mock = MockSpec(200, body = RuleBody.text("mocked"))))
            val observed = url("url" to "${tls.base}/a")
            assertEquals("mocked", observed["body"])
            assertEquals("javax.net.ssl.SSLPeerUnverifiedException: No peer certificate (response mocked by Hopi)", observed["certificates"])
            assertEquals(0, tls.received.size)
        }
    }

    @Test
    fun urlConnectionUrlEditKeepsTheAppsSocketFactory() {
        TlsServer().use { tls ->
            val created = AtomicInteger()
            val recording = RecordingSocketFactory(tls.sslContext.socketFactory, created)
            add(rule("GET */a", RuleSource.URL_CONNECTION, edit = EditSpec(request = RequestEdit(path = "/edited"))))
            val observed = url("url" to "${tls.base}/a", "tlsFactory" to recording, "verifier" to javax.net.ssl.HostnameVerifier { _, _ -> true })
            assertEquals("tls /edited", observed["body"], observed.toString())
            assertTrue(created.get() > 0, "the app's socket factory made the edited connection")
            assertEquals(listOf("GET /edited"), tls.received.toList())
            assertEquals("${tls.base}/edited", one().url)
        }
    }

    @Test
    fun urlConnectionHeaderRemovalReachesTheServerWithoutTheHeader() {
        add(rule("GET */headers", RuleSource.URL_CONNECTION, edit = EditSpec(request = RequestEdit(removeHeaders = listOf("X-Remove"), setHeaders = listOf("X-Env" to "staging")))))
        val observed = url("path" to "/headers", "headers" to mapOf("X-Remove" to "1", "X-Env" to "prod"))
        val lines = (observed["body"] as String).lines()
        assertTrue(lines.none { it.startsWith("x-remove") }, lines.toString())
        assertTrue("x-env: staging" in lines, lines.toString())
        val record = one()
        assertTrue(record.requestHeaders.none { it.first.equals("X-Remove", ignoreCase = true) })
        assertEquals("edit-request", record.ruleApplied)
    }

    @Test
    fun urlConnectionBodyEditWithFixedLengthSendsTheReplacement() {
        add(rule("POST */echo", RuleSource.URL_CONNECTION, edit = EditSpec(request = RequestEdit(body = RuleBody.text("replacement body")))))
        val observed = url("path" to "/echo", "method" to "POST", "body" to "short", "fixedLength" to true, "headers" to mapOf("Content-Type" to "text/plain"))
        assertEquals("replacement body", observed["body"])
        assertEquals("replacement body", server.receivedBodies.single().decodeToString())
        assertEquals("replacement body", one().requestBody!!.bytes.decodeToString())
    }

    @Test
    fun urlConnectionBodyEditWithFixedLengthAndAResponseEditSendsTheReplacement() {
        // The response edit swaps the delegate before the body is written: the replacement's size
        // must still reach the real connection, whatever the app's fixed length was.
        for (replacement in listOf("{\"a\":1}", "a replacement longer than what the app wrote")) {
            RuleEngine.resetForTests()
            server.receivedBodies.clear()
            add(rule("POST */echo", RuleSource.URL_CONNECTION, edit = EditSpec(request = RequestEdit(body = RuleBody.text(replacement)), response = ResponseEdit(status = 203))))
            val observed = url("path" to "/echo", "method" to "POST", "body" to "the app's own body", "fixedLength" to true, "headers" to mapOf("Content-Type" to "text/plain"))
            assertNull(observed["exception"], observed.toString())
            assertEquals(203, observed["code"])
            assertEquals(replacement, observed["body"])
            assertEquals(replacement, server.receivedBodies.single().decodeToString())
        }
    }

    @Test
    fun urlConnectionBodyEditSentAfterTheEndChangesNothingCountedForIt() {
        add(rule("POST */echo", RuleSource.URL_CONNECTION, edit = EditSpec(request = RequestEdit(body = RuleBody.text("replacement body")))))
        val observed = runScenario("urlReplacementAfterEnd", store, server.base)
        assertEquals("replacement body", observed["written"], "the rule's body still reaches the connection")
        assertEquals(0L, observed["heldAtEnd"])
        assertEquals(observed["heldAtEnd"], observed["heldAfter"], "what the store counted for the end does not change after it")
        val record = one()
        assertEquals(TransactionState.Failed, record.state)
        assertEquals("cancelled", record.error)
        assertNull(record.requestBody, "the record ended before the rule's body went out")
    }

    @Test
    fun urlConnectionDelayEndsOnDisconnect() {
        add(rule("GET */json", RuleSource.URL_CONNECTION, delay = DelaySpec(beforeRequestMs = 60_000)))
        val observed = url("path" to "/json", "disconnectAfterMs" to 200L)
        assertEquals("java.net.SocketException", observed["exception"])
        assertEquals("Socket closed", observed["exceptionMessage"])
        assertTrue((observed["elapsedMs"] as Long) < 5_000)
        assertEquals(0, server.received.size)
    }

    @Test
    fun urlConnectionDelaysEndAtTheConnectionsTimeouts() {
        add(rule("GET */json", RuleSource.URL_CONNECTION, delay = DelaySpec(beforeRequestMs = 60_000)))
        val connect = url("path" to "/json", "connectTimeoutMs" to 200)
        assertEquals("java.net.SocketTimeoutException", connect["exception"])
        assertEquals("connect timed out", connect["exceptionMessage"])
        RuleEngine.resetForTests()
        add(rule("GET */json", RuleSource.URL_CONNECTION, delay = DelaySpec(beforeResponseMs = 60_000)))
        val read = url("path" to "/json", "readTimeoutMs" to 200)
        assertEquals("java.net.SocketTimeoutException", read["exception"])
        assertEquals("Read timed out", read["exceptionMessage"])
    }

    @Test
    fun urlConnectionDelayHoldsTheRequest() {
        add(rule("GET */json", RuleSource.URL_CONNECTION, delay = DelaySpec(beforeRequestMs = 250, beforeResponseMs = 100)))
        val observed = url("path" to "/json")
        assertEquals(200, observed["code"])
        assertTrue((observed["elapsedMs"] as Long) >= 350, observed.toString())
        assertEquals("delay", one().ruleApplied)
    }

    @Test
    fun urlConnectionFailsThrowThePlatformsExceptions() {
        val expected = mapOf(
            FailKind.TIMEOUT to ("java.net.SocketTimeoutException" to "connect timed out"),
            FailKind.RESET to ("java.net.SocketException" to "Connection reset"),
            FailKind.UNKNOWN_HOST to ("java.net.UnknownHostException" to "Unable to resolve host \"127.0.0.1\": No address associated with hostname"),
            FailKind.CANCEL to ("java.net.SocketException" to "Socket closed"),
        )
        for ((kind, exception) in expected) {
            RuleEngine.resetForTests()
            add(rule("GET */json", RuleSource.URL_CONNECTION, fail = kind))
            val observed = url("path" to "/json")
            assertEquals(exception.first, observed["exception"], kind.wireName)
            assertEquals(exception.second, observed["exceptionMessage"], kind.wireName)
        }
        assertEquals(0, server.received.size)
        assertTrue(store.awaitTerminal(4).all { it.state == TransactionState.Failed && it.ruleApplied == "fail" })
    }

    @Test
    fun urlConnectionResponseEditChangesStatusHeadersAndBody() {
        add(rule("GET */json", RuleSource.URL_CONNECTION, edit = EditSpec(response = ResponseEdit(status = 500, setHeaders = listOf("X-Edited" to "1"), body = RuleBody.text("edited")))))
        val observed = url("path" to "/json")
        assertEquals(500, observed["code"])
        assertEquals("1", observed["headerX"])
        assertEquals("java.io.FileNotFoundException", observed["inputException"])
        assertEquals("edited", observed["error"])
        assertEquals(1, server.received.size)
        val record = one()
        assertEquals(500, record.responseCode)
        assertEquals("edited", record.responseBody!!.bytes.decodeToString())
        assertEquals("edit-response", record.ruleApplied)
    }

    @Test
    fun urlConnectionMockFromARecordHeldGzippedReachesTheAppDecoded() {
        // HttpURLConnection never decodes a gzip the app asked for itself.
        val gzip = arrayOf("path" to "/gzip", "headers" to mapOf("Accept-Encoding" to "gzip"))
        assertEquals("gzip", url(*gzip)["contentEncoding"])
        mockGzipFromItsRecord(RuleSource.URL_CONNECTION)
        val mocked = url(*gzip)
        assertEquals(TestServer.large().decodeToString(), mocked["body"])
        assertNull(mocked["contentEncoding"])
        assertEquals(1, server.received.size)
        assertEquals("mock", store.awaitTerminal(2).last().ruleApplied)
    }

    // Cache-Control: no-store on every platform mock and response edit (ResponseEdits.noStore)

    /**
     * A mock with the rule's own `Cache-Control` (two values, two cases), a body edit, and a
     * header-only edit that adds a `Cache-Control` of its own over the server's (`/cacheable`'s
     * `max-age=600`), each limited to [source]: the path it matches, the rule, and the start of the
     * body the app must read.
     */
    private fun noStoreCases(source: RuleSource): List<Triple<String, RuleSpec, String>> = listOf(
        Triple(
            "/json",
            rule("GET */json", source, mock = MockSpec(200, listOf("Cache-Control" to "max-age=600", "cache-control" to "public", "Content-Type" to "text/plain"), RuleBody.text("mocked"))),
            "mocked",
        ),
        Triple("/cacheable", rule("GET */cacheable", source, edit = EditSpec(response = ResponseEdit(body = RuleBody.text("edited")))), "edited"),
        Triple(
            "/cacheable",
            rule("GET */cacheable", source, edit = EditSpec(response = ResponseEdit(setHeaders = listOf("X-Edited" to "1"), addHeaders = listOf("Cache-Control" to "max-age=60")))),
            "cacheable",
        ),
    )

    /** Every `Cache-Control` value of [headers], whatever the name's case. */
    private fun cacheControl(headers: List<Pair<String, String>>): List<String> =
        headers.filter { it.first.equals("Cache-Control", ignoreCase = true) }.map { it.second }

    @Test
    fun okHttpMocksAndResponseEditsReachTheAppUnderNoStore() {
        // A platform rule cannot see a cache above OkHttp (Ktor's HttpCache): no Cache-Control of the
        // rule's or the server's reaches the app under a mock or a response edit, only no-store.
        for ((path, spec, body) in noStoreCases(RuleSource.OKHTTP)) {
            RuleEngine.resetForTests()
            add(spec)
            val observed = okhttp("path" to path)
            @Suppress("UNCHECKED_CAST")
            val headers = observed["headers"] as Map<String, List<String>>
            assertEquals(listOf("no-store"), headers["cache-control"], "$path: $headers")
            assertTrue((observed["body"] as String).startsWith(body), observed.toString())
            if (body == "cacheable") assertEquals(listOf("1"), headers["x-edited"], headers.toString())
        }
        assertEquals(listOf("GET /cacheable", "GET /cacheable"), server.received.toList(), "the mock reached no server")
        // The records hold the responses as delivered.
        val records = store.awaitTerminal(3)
        assertTrue(records.all { cacheControl(it.responseHeaders) == listOf("no-store") }, records.map { it.responseHeaders }.toString())
    }

    @Test
    fun urlConnectionMocksAndResponseEditsReachTheAppUnderNoStore() {
        for ((path, spec, body) in noStoreCases(RuleSource.URL_CONNECTION)) {
            RuleEngine.resetForTests()
            add(spec)
            val observed = url("path" to path)
            assertEquals(listOf("no-store"), observed["cacheControl"], "$path: $observed")
            assertTrue((observed["body"] as String).startsWith(body), observed.toString())
            if (body == "cacheable") assertEquals("1", observed["headerX"], observed.toString())
        }
        assertEquals(listOf("GET /cacheable", "GET /cacheable"), server.received.toList(), "the mock reached no server")
        val records = store.awaitTerminal(3)
        assertTrue(records.all { cacheControl(it.responseHeaders) == listOf("no-store") }, records.map { it.responseHeaders }.toString())
    }

    /**
     * Ktor's `HttpCache` above a platform rule ([engine] `okhttp`: Ktor's OkHttp engine over the
     * interceptor; `android`: its Android engine over the wrappers; no Hopi plugin): the client
     * fetches [path] under the `times = 1` rule [spec], the rule is removed, and the client fetches
     * [path] again. Returns both responses as the app read them.
     */
    private fun underHttpCache(engine: String, path: String, spec: RuleSpec): Pair<Map<String, Any?>, Map<String, Any?>> {
        RuleEngine.resetForTests()
        server.received.clear()
        val id = add(spec)
        val observed = runScenario(
            "rule.httpCache", store, server.base,
            extra = mapOf("engine" to engine, "path" to path, "between" to Runnable { check(RuleEngine.remove(id)) { "rule $id was gone already" } }),
            jars = if (engine == "okhttp") testJars("ktorOkhttp") else emptyList(),
        )
        assertTrue((observed["engine"] as String).startsWith("io.ktor.client.engine.$engine."), observed.toString())
        @Suppress("UNCHECKED_CAST")
        return observed["first"] as Map<String, Any?> to observed["second"] as Map<String, Any?>
    }

    @Test
    fun ktorsHttpCacheKeepsNoPlatformMockOrResponseEdit() {
        // Stage 10's R5: without no-store, HttpCache stored each of these (max-age=600, the rule's or
        // the server's) and served it again after the rule was gone, without a request.
        for ((engine, source) in listOf("okhttp" to RuleSource.OKHTTP, "android" to RuleSource.URL_CONNECTION)) {
            val mock = MockSpec(200, listOf("Cache-Control" to "max-age=600", "Content-Type" to "text/plain"), RuleBody.text("mocked"))
            val (mocked, afterMock) = underHttpCache(engine, "/json", rule("GET */json", source, mock = mock, times = 1))
            assertEquals("mocked", mocked["body"], engine)
            assertEquals(listOf("no-store"), mocked["cacheControl"], engine)
            assertEquals("""{"hello":"world"}""", afterMock["body"], "$engine: the second GET is real, not the cached mock")
            assertEquals(listOf("GET /json"), server.received.toList(), engine)

            val bodyEdit = EditSpec(response = ResponseEdit(body = RuleBody.text("edited")))
            val (edited, afterEdit) = underHttpCache(engine, "/cacheable", rule("GET */cacheable", source, edit = bodyEdit, times = 1))
            assertEquals("edited", edited["body"], engine)
            assertEquals(listOf("no-store"), edited["cacheControl"], engine)
            assertEquals("cacheable 2", afterEdit["body"], "$engine: the second GET is real, not the cached edit")
            assertEquals(listOf("max-age=600"), afterEdit["cacheControl"], engine)

            val headerEdit = EditSpec(response = ResponseEdit(setHeaders = listOf("X-Edited" to "1")))
            val (headerEdited, afterHeaderEdit) = underHttpCache(engine, "/cacheable", rule("GET */cacheable", source, edit = headerEdit, times = 1))
            assertEquals("cacheable 1", headerEdited["body"], engine)
            assertEquals("1", headerEdited["xEdited"], engine)
            assertEquals(listOf("no-store"), headerEdited["cacheControl"], engine)
            assertEquals("cacheable 2", afterHeaderEdit["body"], "$engine: the second GET is real, not the cached edit")
            assertNull(afterHeaderEdit["xEdited"], engine)
            assertEquals(2, server.received.size, engine)
        }
    }

    @Test
    fun theRuledConnectionsAnswerEveryMethodOrInheritOneThatReadsTheirAnswers() {
        val androidJar = System.getProperty("hopi.androidJar")
        val expected = mutableMapOf<String, MutableSet<String>>()
        ZipFile(androidJar).use { zip ->
            for (name in listOf("java/net/URLConnection", "java/net/HttpURLConnection", "javax/net/ssl/HttpsURLConnection")) {
                val node = ClassNode().also { ClassReader(zip.getInputStream(zip.getEntry("$name.class")).readAllBytes()).accept(it, ClassReader.SKIP_CODE) }
                expected[name] = node.methods.filter { m ->
                    m.name != "<init>" && m.name != "<clinit>" &&
                        m.access and (Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_PRIVATE) == 0 &&
                        m.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0
                }.mapTo(mutableSetOf()) { it.name + it.desc.substringBeforeLast(')') + ")" }
            }
        }
        fun declared(type: Class<*>) = type.declaredMethods.filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .mapTo(mutableSetOf()) { it.name + Type.getMethodDescriptor(it).substringBeforeLast(')') + ")" }
        // URLConnection's own implementations of these read the header methods the ruled classes
        // answer (getContentType is getHeaderField("content-type"), ...), or are not about a request.
        val inherited = setOf(
            "getContent()", "getContent([Ljava/lang/Class;)", "getContentLength()", "getContentLengthLong()", "getContentType()",
            "getContentEncoding()", "getExpiration()", "getDate()", "getLastModified()", "getHeaderFieldInt(Ljava/lang/String;I)",
            "getHeaderFieldLong(Ljava/lang/String;J)", "getHeaderFieldDate(Ljava/lang/String;J)", "getDefaultUseCaches()",
            "setDefaultUseCaches(Z)", "toString()",
        )
        val http = declared(RuledHttpConnection::class.java)
        val https = declared(RuledHttpsConnection::class.java)
        val httpExpected = expected.getValue("java/net/URLConnection") + expected.getValue("java/net/HttpURLConnection")
        assertEquals(emptySet(), httpExpected - http - inherited, "RuledHttpConnection neither answers nor inherits")
        assertEquals(emptySet(), httpExpected + expected.getValue("javax/net/ssl/HttpsURLConnection") - https - inherited, "RuledHttpsConnection neither answers nor inherits")
    }

    /** The app's socket factory, counting the sockets it makes. */
    private class RecordingSocketFactory(private val delegate: SSLSocketFactory, private val created: AtomicInteger) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket = delegate.createSocket(s, host, port, autoClose).also { created.incrementAndGet() }
        override fun createSocket(): Socket = delegate.createSocket().also { created.incrementAndGet() }
        override fun createSocket(host: String?, port: Int): Socket = delegate.createSocket(host, port).also { created.incrementAndGet() }
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            delegate.createSocket(host, port, localHost, localPort).also { created.incrementAndGet() }
        override fun createSocket(host: InetAddress?, port: Int): Socket = delegate.createSocket(host, port).also { created.incrementAndGet() }
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            delegate.createSocket(address, port, localAddress, localPort).also { created.incrementAndGet() }
    }
}
