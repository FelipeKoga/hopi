package dev.koga.hopi.urlsession

import dev.koga.hopi.core.json.parseJsonTree
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionSource
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.rules.DelaySpec
import dev.koga.hopi.core.rules.EditSpec
import dev.koga.hopi.core.rules.FailKind
import dev.koga.hopi.core.rules.MatchSpec
import dev.koga.hopi.core.rules.MockSpec
import dev.koga.hopi.core.rules.Predicate
import dev.koga.hopi.core.rules.RequestEdit
import dev.koga.hopi.core.rules.RuleBody
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleJson
import dev.koga.hopi.core.rules.RuleJsonContext
import dev.koga.hopi.core.rules.RuleParse
import dev.koga.hopi.core.rules.RuleResult
import dev.koga.hopi.core.rules.RuleSource
import dev.koga.hopi.core.rules.RuleSpec
import dev.koga.hopi.core.rules.RuleState
import dev.koga.hopi.ktor.Hopi
import dev.koga.hopi.ktor.HopiLogSink
import dev.koga.hopi.network.internal.UrlSessionRules
import dev.koga.hopi.network.internal.urlsession.hopi_us_test_can_init_calls
import dev.koga.hopi.network.internal.urlsession.hopi_us_test_factories_installed
import dev.koga.hopi.network.internal.urlsession.hopi_us_test_lists_protocol
import dev.koga.hopi.network.internal.urlsession.hopi_us_test_match
import dev.koga.hopi.network.internal.urlsession.hopi_us_test_protocol_registered
import dev.koga.hopi.network.internal.urlsession.hopi_us_test_timing
import dev.koga.hopi.urlsession.fixtures.HopiFxCompletion
import dev.koga.hopi.urlsession.fixtures.HopiFxRecorder
import dev.koga.hopi.urlsession.fixtures.hopi_fx_private_data_task
import dev.koga.hopi.urlsession.fixtures.hopi_fx_server_requests
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.HTTPMethod
import platform.Foundation.addValue
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTaskStateSuspended
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataTaskWithURL
import platform.Foundation.downloadTaskWithURL
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.uploadTaskWithRequest

/**
 * Rules on URLSession (docs/design/rules.md, "URLSession") against the loopback server: mock and
 * fail through the rule protocol, delays and request edits through the task factories, and the C
 * matcher against the shared vectors.
 */
@OptIn(ExperimentalForeignApi::class)
class UrlSessionRulesTest {
    init {
        Capture.ensureStarted()
    }

    @BeforeTest
    fun noRules() {
        RuleEngine.clear()
    }

    @AfterTest
    fun clearRules() {
        RuleEngine.clear()
    }

    private val host = "127.0.0.1"

    private fun add(spec: RuleSpec): Long = assertIs<RuleResult.Ok>(RuleEngine.add(spec), "add").rule.id

    private fun match(path: String, methods: Set<String>? = null) =
        MatchSpec(methods = methods, host = host, path = path, sources = setOf(RuleSource.URL_SESSION))

    private fun mock(path: String, status: Int = 418, body: String? = "mocked", headers: List<Pair<String, String>> = listOf("X-Mock" to "1"), times: Int? = null, delay: DelaySpec? = null) =
        add(RuleSpec(match(path), times = times, delay = delay, mock = MockSpec(status, headers, body?.let(RuleBody::text))))

    private fun HopiFxCompletion.status(): Int = (response as NSHTTPURLResponse).statusCode.toInt()

    private fun HopiFxCompletion.text(): String = data!!.toBytes().decodeToString()

    private fun requests(path: String): Long = hopi_fx_server_requests(path)

    private fun awaitEmptyState() = runBlocking {
        withTimeoutOrNull(5_000) { while (RuleEngine.state !== RuleState.EMPTY) delay(10) } ?: fail("the rule state never returned to EMPTY")
    }

    @Test
    fun withoutRulesNothingIsRegisteredAndEachTaskCostsOneCanInit() {
        assertTrue(RuleEngine.state === RuleState.EMPTY)
        assertFalse(hopi_us_test_protocol_registered(), "the shared session has no protocol without rules")
        assertTrue(UrlSessionRules.sessionsCovered, "the session factory passed its self-check")
        val session = session(SessionKind.Ephemeral)
        assertTrue(hopi_us_test_lists_protocol(session.configuration), "a session created after start lists the rule protocol")
        assertFalse(hopi_us_test_lists_protocol(NSURLSessionConfiguration.defaultSessionConfiguration), "the default configuration is untouched")
        hopi_us_test_timing(true)
        try {
            repeat(5) {
                val url = Capture.url("/bytes?n=10")
                val watch = Watch(url)
                val done = HopiFxCompletion()
                session.dataTaskWithURL(nsUrl(url), done.dataHandler()!!).resume()
                done.awaitDone()
                assertNull(watch.single().ruleId)
            }
            assertEquals(5L, hopi_us_test_can_init_calls(), "one canInitWithTask: per task, and nothing more")
        } finally {
            hopi_us_test_timing(false)
        }
        session.finishTasksAndInvalidate()
    }

    @Test
    fun theCMatcherAgreesWithEveryVector() {
        val cases = vectors()
        assertTrue(cases.size >= 50, "the table shrank")
        val noContext = object : RuleJsonContext {
            override fun blob(id: Long): ByteArray? = null

            override fun transaction(id: Long): HttpTransaction? = null
        }
        var normalized = 0
        for ((index, case) in cases.withIndex()) {
            val rule = mapOf("match" to case["match"], "delay" to mapOf("beforeRequestMs" to 1L))
            val spec = assertIs<RuleParse.Ok<RuleSpec>>(RuleJson.parseRule(rule, noContext), "case $index").value
            val id = add(spec)
            @Suppress("UNCHECKED_CAST")
            val headers = (case["headers"] as List<List<String>>?).orEmpty()
            val request = NSMutableURLRequest(uRL = nsUrl(case["url"] as String)).apply {
                setHTTPMethod(case["method"] as String)
                for ((name, value) in headers) setValue(value, forHTTPHeaderField = name)
            }
            val expected = case["expect"] as Boolean
            if (request.HTTPMethod != case["method"]) {
                // NSURLRequest uppercases a standard method ("get" reads "GET"), which is what
                // URLSession sends: the vector's lowercase method never reaches a URLSession rule.
                assertEquals((case["method"] as String).uppercase(), request.HTTPMethod, "case $index")
                normalized++
            } else {
                assertEquals(if (expected) id else 0L, hopi_us_test_match(request), "case $index: $case")
            }
            RuleEngine.clear()
        }
        assertTrue(normalized <= 1, "only the lowercase-method vector is normalized")
    }

    @Suppress("UNCHECKED_CAST")
    private fun vectors(): List<Map<String, Any?>> {
        // The test binary runs from <repo>/hopi-network/build/bin/…; the simulator shares the host's files.
        val binary = NSProcessInfo.processInfo.arguments.first() as String
        val marker = "/hopi-network/build/"
        val root = binary.substringBefore(marker, missingDelimiterValue = "").ifEmpty { fail("unexpected test binary path $binary") }
        val path = "$root/hopi-core/src/commonTest/resources/rules/match-vectors.json"
        val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: fail("cannot read $path")
        val tree = parseJsonTree(text, Int.MAX_VALUE, 8) ?: fail("the vectors are not JSON")
        return tree["cases"] as List<Map<String, Any?>>
    }

    @Test
    fun aMockAnswersOnEverySessionKindWithoutTheNetwork() {
        val id = mock("/m-kinds", headers = listOf("X-Mock" to "1", "Content-Type" to "text/plain"))
        assertTrue(hopi_us_test_protocol_registered(), "registered for the shared session while rules exist")
        val before = requests("/m-kinds")
        for (kind in SessionKind.entries) {
            val session = session(kind)
            val url = Capture.url("/m-kinds")
            val watch = Watch(url)
            val done = HopiFxCompletion()
            session.dataTaskWithURL(nsUrl(url), done.dataHandler()!!).resume()
            done.awaitDone()
            assertNull(done.error, "$kind: ${done.error}")
            assertEquals(418, done.status(), "$kind")
            assertEquals("mocked", done.text(), "$kind")
            assertEquals("1", (done.response as NSHTTPURLResponse).allHeaderFields["X-Mock"], "$kind")
            val tx = watch.single()
            assertEquals(id, tx.ruleId, "$kind")
            assertEquals("mock", tx.ruleApplied, "$kind")
            assertEquals(TransactionSource.URL_SESSION, tx.source)
            assertEquals(418, tx.responseCode)
            assertEquals("mocked", tx.responseBody!!.bytes.decodeToString(), "$kind: recorded by the byte path")
            assertNull(tx.protocol, "$kind: no network protocol")
            if (kind != SessionKind.Shared) session.finishTasksAndInvalidate()
        }
        assertEquals(before, requests("/m-kinds"), "nothing reached the server")
        assertEquals(3L, RuleEngine.get(id)!!.hits)
    }

    @Test
    fun aMockReachesADelegateAndUnmatchedRequestsGoToTheNetwork() {
        mock("/m-delegate")
        val recorder = HopiFxRecorder()
        val session = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.defaultSessionConfiguration, recorder, NSOperationQueue())
        session.dataTaskWithURL(nsUrl(Capture.url("/m-delegate"))).resume()
        recorder.awaitDone()
        assertNull(recorder.error)
        assertEquals("mocked", recorder.received!!.toBytes().decodeToString())
        // Not matched: the same session sends it, observe only.
        val url = Capture.url("/bytes?n=321")
        val watch = Watch(url)
        val done = HopiFxCompletion()
        session.dataTaskWithURL(nsUrl(url), done.dataHandler()!!).resume()
        done.awaitDone()
        assertContentEquals(expectedBytes(321), done.data!!.toBytes())
        val tx = watch.single()
        assertNull(tx.ruleId)
        assertEquals("HTTP/1.1", tx.protocol)
        session.finishTasksAndInvalidate()
    }

    @Test
    fun aSessionCreatedBeforeStartIsNotMockedButSharedIs() {
        mock("/m-before")
        val done = HopiFxCompletion()
        BeforeStart.preSession.dataTaskWithURL(nsUrl(Capture.url("/m-before")), done.dataHandler()!!).resume()
        done.awaitDone()
        assertEquals(404, done.status(), "a session created before start() lacks the protocol: the documented gap")
    }

    @Test
    fun aMockedRedirectIsTheFinalResponse() {
        mock("/m-redirect", status = 302, body = null, headers = listOf("Location" to "/bytes?n=10"))
        val done = HopiFxCompletion()
        session(SessionKind.Ephemeral).dataTaskWithURL(nsUrl(Capture.url("/m-redirect")), done.dataHandler()!!).resume()
        done.awaitDone()
        assertEquals(302, done.status())
    }

    @Test
    fun aDownloadTaskGetsTheMockAsItsFile() {
        mock("/m-download", body = "file body")
        val done = HopiFxCompletion()
        session(SessionKind.Default).downloadTaskWithURL(nsUrl(Capture.url("/m-download")), done.downloadHandler()!!).resume()
        done.awaitDone()
        assertEquals("file body", done.text())
    }

    @Test
    fun eachFailKindFailsAsTheSystemDoes() {
        val kinds = mapOf(
            FailKind.TIMEOUT to NSURLErrorTimedOut,
            FailKind.RESET to NSURLErrorNetworkConnectionLost,
            FailKind.UNKNOWN_HOST to NSURLErrorCannotFindHost,
            FailKind.CANCEL to NSURLErrorCancelled,
        )
        for ((kind, code) in kinds) {
            val path = "/f-${kind.wireName}"
            val id = add(RuleSpec(match(path), fail = kind))
            val url = Capture.url(path)
            val watch = Watch(url)
            val done = HopiFxCompletion()
            session(SessionKind.Ephemeral).dataTaskWithURL(nsUrl(url), done.dataHandler()!!).resume()
            done.awaitDone()
            val error = assertNotNull(done.error, "$kind")
            assertEquals(NSURLErrorDomain, error.domain)
            assertEquals(code, error.code, "$kind")
            val tx = watch.single()
            assertEquals(TransactionState.Failed, tx.state)
            assertEquals(id, tx.ruleId)
            assertEquals("fail", tx.ruleApplied)
            when (kind) {
                FailKind.TIMEOUT -> assertEquals("NSURLErrorDomain -1001: The request timed out.", tx.error)
                FailKind.CANCEL -> assertEquals("cancelled", tx.error)
                else -> assertTrue(tx.error!!.startsWith("NSURLErrorDomain $code: "), tx.error)
            }
            assertEquals(0L, requests(path))
        }
    }

    @Test
    fun aDelayedMockWaitsAndIsMarked() {
        val id = mock("/m-delay", delay = DelaySpec(beforeResponseMs = 400))
        val url = Capture.url("/m-delay")
        val watch = Watch(url)
        val done = HopiFxCompletion()
        val started = TimeSource.Monotonic.markNow()
        session(SessionKind.Ephemeral).dataTaskWithURL(nsUrl(url), done.dataHandler()!!).resume()
        done.awaitDone()
        assertTrue(started.elapsedNow().inWholeMilliseconds >= 400, "waited ${started.elapsedNow()}")
        val tx = watch.single()
        assertEquals(id, tx.ruleId)
        assertEquals("delay,mock", tx.ruleApplied)
        assertTrue(tx.durationMs!! >= 390, "duration ${tx.durationMs}")
    }

    @Test
    fun aDelayedMockCancelledDuringTheDelayEndsCancelled() {
        mock("/m-delay-cancel", delay = DelaySpec(beforeResponseMs = 2_000))
        val url = Capture.url("/m-delay-cancel")
        val watch = Watch(url)
        val done = HopiFxCompletion()
        val task = session(SessionKind.Ephemeral).dataTaskWithURL(nsUrl(url), done.dataHandler()!!)
        val started = TimeSource.Monotonic.markNow()
        task.resume()
        platform.posix.usleep(200_000u)
        task.cancel()
        done.awaitDone()
        assertEquals(NSURLErrorCancelled, done.error!!.code)
        assertTrue(started.elapsedNow().inWholeMilliseconds < 1_500, "the cancel ended the wait")
        assertEquals("cancelled", watch.single().error)
    }

    @Test
    fun aDelayBeforeARealRequestHoldsTheTaskSuspended() {
        val id = add(RuleSpec(match("/bytes"), delay = DelaySpec(beforeRequestMs = 500)))
        assertTrue(hopi_us_test_factories_installed())
        val url = Capture.url("/bytes?n=77")
        val watch = Watch(url)
        val done = HopiFxCompletion()
        val task = session(SessionKind.Default).dataTaskWithURL(nsUrl(url), done.dataHandler()!!)
        val started = TimeSource.Monotonic.markNow()
        task.resume()
        assertEquals(NSURLSessionTaskStateSuspended, task.state, "suspended during the delay (stated)")
        done.awaitDone()
        assertTrue(started.elapsedNow().inWholeMilliseconds >= 500)
        assertContentEquals(expectedBytes(77), done.data!!.toBytes(), "the app's own session sent it")
        val tx = watch.single()
        assertEquals(id, tx.ruleId)
        assertEquals("delay", tx.ruleApplied)
        assertEquals("HTTP/1.1", tx.protocol)
    }

    @Test
    fun aCancelDuringADelayBeforeTheRequestSendsNothing() {
        add(RuleSpec(match("/stall"), delay = DelaySpec(beforeRequestMs = 600)))
        val before = requests("/stall")
        val url = Capture.url("/stall?ms=10")
        val watch = Watch(url)
        val done = HopiFxCompletion()
        val task = session(SessionKind.Ephemeral).dataTaskWithURL(nsUrl(url), done.dataHandler()!!)
        task.resume()
        platform.posix.usleep(100_000u)
        task.cancel()
        done.awaitDone()
        assertEquals(NSURLErrorCancelled, done.error!!.code)
        assertEquals("cancelled", watch.single().error)
        platform.posix.usleep(700_000u)
        assertEquals(before, requests("/stall"), "the late resume of a cancelled task does nothing")
    }

    @Test
    fun aSuspendDuringTheDelayDropsTheDeferredResume() {
        add(RuleSpec(match("/bytes"), delay = DelaySpec(beforeRequestMs = 300)))
        val url = Capture.url("/bytes?n=9")
        val done = HopiFxCompletion()
        val task = session(SessionKind.Ephemeral).dataTaskWithURL(nsUrl(url), done.dataHandler()!!)
        task.resume()
        task.suspend()
        platform.posix.usleep(600_000u)
        assertEquals(NSURLSessionTaskStateSuspended, task.state, "the app's suspend holds")
        assertEquals(0L, done.calls)
        task.resume()
        done.awaitDone()
        assertContentEquals(expectedBytes(9), done.data!!.toBytes())
    }

    @Test
    fun anEditMovesTheRequestAndDropsCredentialsAcrossOrigins() {
        val id = add(
            RuleSpec(
                MatchSpec(host = host, port = 1, path = "/headers", sources = setOf(RuleSource.URL_SESSION)),
                edit = EditSpec(
                    request = RequestEdit(
                        port = Capture.port,
                        setHeaders = listOf("X-Edited" to "yes"),
                        removeHeaders = listOf("X-Remove"),
                        setQuery = listOf("added" to "a b"),
                    ),
                ),
            ),
        )
        val original = "http://127.0.0.1:1/headers?t=edit"
        val request = NSMutableURLRequest(uRL = nsUrl(original)).apply {
            setValue("Bearer secret", forHTTPHeaderField = "Authorization")
            setValue("drop me", forHTTPHeaderField = "X-Remove")
            setValue("kept", forHTTPHeaderField = "X-Keep")
        }
        val edited = "http://127.0.0.1:${Capture.port}/headers?t=edit&added=a%20b"
        val watch = Watch(edited)
        val done = HopiFxCompletion()
        session(SessionKind.Default).dataTaskWithRequest(request, done.dataHandler()!!).resume()
        done.awaitDone()
        assertNull(done.error, "${done.error}")
        val received = done.text()
        assertTrue("x-edited: yes" in received, received)
        assertTrue("x-keep: kept" in received, received)
        assertFalse("authorization" in received, "an origin change drops the credentials: $received")
        assertFalse("x-remove" in received, received)
        val tx = watch.single()
        assertEquals(id, tx.ruleId)
        assertEquals("edit-request", tx.ruleApplied)
        assertTrue(tx.requestHeaders.any { it.first == "X-Edited" }, "the record holds the request as sent")
    }

    @Test
    fun anAddedCredentialAcrossOriginsNeverTravelsNextToTheAppsOwn() {
        add(
            RuleSpec(
                MatchSpec(host = host, port = 1, path = "/headers", sources = setOf(RuleSource.URL_SESSION)),
                edit = EditSpec(request = RequestEdit(port = Capture.port, addHeaders = listOf("Authorization" to "Bearer test", "Cookie" to "debug=1"))),
            ),
        )
        val request = NSMutableURLRequest(uRL = nsUrl("http://127.0.0.1:1/headers?t=add-credential")).apply {
            setValue("Bearer secret", forHTTPHeaderField = "Authorization")
            setValue("session=SECRET", forHTTPHeaderField = "Cookie")
        }
        val done = HopiFxCompletion()
        session(SessionKind.Ephemeral).dataTaskWithRequest(request, done.dataHandler()!!).resume()
        done.awaitDone()
        assertNull(done.error, "${done.error}")
        val received = done.text()
        assertTrue("authorization: Bearer test" in received, received)
        assertTrue("cookie: debug=1" in received, received)
        assertFalse("secret" in received.lowercase(), "the app's own credentials stay behind: $received")
    }

    private fun sessionAddingAuthorization(): NSURLSession {
        val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration
        configuration.HTTPAdditionalHeaders = mapOf<Any?, Any?>("Authorization" to "Bearer session-secret")
        return NSURLSession.sessionWithConfiguration(configuration)
    }

    @Test
    fun anEditToAnotherOriginIsNotAppliedOnASessionWhoseConfigurationAddsACredential() {
        val id = add(
            RuleSpec(
                MatchSpec(host = host, port = 1, path = "/headers", sources = setOf(RuleSource.URL_SESSION)),
                edit = EditSpec(request = RequestEdit(port = Capture.port, setHeaders = listOf("X-Edited" to "yes"))),
            ),
        )
        val session = sessionAddingAuthorization()
        val before = requests("/headers")
        val done = HopiFxCompletion()
        session.dataTaskWithURL(nsUrl("http://127.0.0.1:1/headers?t=session-credential"), done.dataHandler()!!).resume()
        done.awaitDone()
        // Unedited, it went to port 1, where nothing listens: the session's token never moved.
        assertNotNull(done.error, "the request was sent unedited")
        assertEquals(before, requests("/headers"))
        assertEquals(0L, RuleEngine.get(id)!!.hits, "the rule was not claimed")
        // The same edit that supplies the header itself overrides the session's value, so it applies.
        RuleEngine.clear()
        val supplying = add(
            RuleSpec(
                MatchSpec(host = host, port = 1, path = "/headers", sources = setOf(RuleSource.URL_SESSION)),
                edit = EditSpec(request = RequestEdit(port = Capture.port, setHeaders = listOf("Authorization" to "Bearer mine"))),
            ),
        )
        val edited = HopiFxCompletion()
        session.dataTaskWithURL(nsUrl("http://127.0.0.1:1/headers?t=session-credential"), edited.dataHandler()!!).resume()
        edited.awaitDone()
        assertNull(edited.error, "${edited.error}")
        assertTrue("authorization: Bearer mine" in edited.text(), edited.text())
        assertFalse("session-secret" in edited.text(), edited.text())
        assertEquals(1L, RuleEngine.get(supplying)!!.hits)
        session.finishTasksAndInvalidate()
    }

    @Test
    fun aHeaderPredicateMatchesEachValueOfARepeatedHeader() {
        val id = add(
            RuleSpec(
                MatchSpec(host = host, path = "/predicate", headers = listOf(Predicate("X-Feature", "beta")), sources = setOf(RuleSource.URL_SESSION)),
                mock = MockSpec(200),
            ),
        )
        val repeated = NSMutableURLRequest(uRL = nsUrl(Capture.url("/predicate"))).apply {
            addValue("alpha", forHTTPHeaderField = "X-Feature")
            addValue("beta", forHTTPHeaderField = "X-Feature")
        }
        assertEquals(id, hopi_us_test_match(repeated), "beta is one of the values, as on the Kotlin sources")
        val other = NSMutableURLRequest(uRL = nsUrl(Capture.url("/predicate"))).apply { addValue("alpha", forHTTPHeaderField = "X-Feature") }
        assertEquals(0L, hopi_us_test_match(other))
        // A glob over the whole field still matches a single value that holds a comma.
        RuleEngine.clear()
        val whole = add(
            RuleSpec(
                MatchSpec(host = host, path = "/predicate", headers = listOf(Predicate("Accept", "text/html, */*")), sources = setOf(RuleSource.URL_SESSION)),
                mock = MockSpec(200),
            ),
        )
        val accept = NSMutableURLRequest(uRL = nsUrl(Capture.url("/predicate"))).apply { setValue("text/html, */*", forHTTPHeaderField = "Accept") }
        assertEquals(whole, hopi_us_test_match(accept))
    }

    @Test
    fun anotherSourcesRuleDoesNotRebuildTheNativeSet() {
        mock("/mirror")
        val before = UrlSessionRules.publishes
        add(RuleSpec(MatchSpec(path = "/other", sources = setOf(RuleSource.OKHTTP)), mock = MockSpec(500)))
        assertEquals(before, UrlSessionRules.publishes, "the URLSession rules did not change")
        mock("/mirror-2")
        assertEquals(before + 1, UrlSessionRules.publishes)
    }

    @Test
    fun aUrlFactoryEditKeepsTheCompletionHandlerAndChangesThePath() {
        add(
            RuleSpec(
                match("/nowhere"),
                edit = EditSpec(request = RequestEdit(method = "POST", path = "/headers", setHeaders = listOf("X-Via" to "url"), body = RuleBody.text("12345"))),
            ),
        )
        val done = HopiFxCompletion()
        session(SessionKind.Ephemeral).dataTaskWithURL(nsUrl(Capture.url("/nowhere")), done.dataHandler()!!).resume()
        done.awaitDone()
        val received = done.text()
        assertTrue(received.startsWith("POST 5\n"), received)
        assertTrue("x-via: url" in received, received)
    }

    @Test
    fun thePrivateFactoriesOfSwiftsAsyncApisAreEditedToo() {
        // data(for:) and data(from:) create their tasks through these selectors (spike 6).
        add(RuleSpec(match("/async-edit"), edit = EditSpec(request = RequestEdit(path = "/headers", setHeaders = listOf("X-Async" to "edited")))))
        val session = session(SessionKind.Ephemeral)
        val byRequest = HopiFxCompletion()
        val request = NSMutableURLRequest(uRL = nsUrl(Capture.url("/async-edit")))
        val task = assertNotNull(hopi_fx_private_data_task(session, request, null, byRequest.dataHandler()), "the private request factory exists")
        task.resume()
        byRequest.awaitDone()
        assertTrue("x-async: edited" in byRequest.text(), byRequest.text())
        val byUrl = HopiFxCompletion()
        assertNotNull(hopi_fx_private_data_task(session, null, nsUrl(Capture.url("/async-edit")), byUrl.dataHandler()), "the private URL factory exists").resume()
        byUrl.awaitDone()
        assertTrue("x-async: edited" in byUrl.text(), byUrl.text())
        session.finishTasksAndInvalidate()
    }

    @Test
    fun oneRulePerRequestAnEditedRequestIsNeverMockedAfterwards() {
        mock("/headers")
        val edit = add(RuleSpec(match("/one-rule"), edit = EditSpec(request = RequestEdit(path = "/headers"))))
        val done = HopiFxCompletion()
        session(SessionKind.Ephemeral).dataTaskWithURL(nsUrl(Capture.url("/one-rule")), done.dataHandler()!!).resume()
        done.awaitDone()
        assertEquals(200, done.status())
        assertTrue(done.text().startsWith("GET 0\n"), "the server answered: ${done.text()}")
        assertEquals(1L, RuleEngine.get(edit)!!.hits)
    }

    @Test
    fun anUploadFromDataSendsTheEditedBody() {
        add(RuleSpec(match("/echo", setOf("POST")), edit = EditSpec(request = RequestEdit(body = RuleBody.text("edited body")))))
        val url = Capture.url("/echo")
        val watch = Watch(url)
        val done = HopiFxCompletion()
        session(SessionKind.Default).uploadTaskWithRequest(post(url), "original".encodeToByteArray().toNSData(), done.dataHandler()!!).resume()
        done.awaitDone()
        assertEquals("edited body", done.text())
        assertEquals("edited body", watch.single().requestBody!!.bytes.decodeToString(), "the record holds the body as sent")
    }

    @Test
    fun timesUseUpTheRuleAndTheStateReturnsToEmpty() {
        val id = mock("/bytes", times = 1)
        val first = HopiFxCompletion()
        session(SessionKind.Ephemeral).dataTaskWithURL(nsUrl(Capture.url("/bytes?n=4")), first.dataHandler()!!).resume()
        first.awaitDone()
        assertEquals("mocked", first.text())
        awaitEmptyState()
        assertFalse(hopi_us_test_protocol_registered())
        val second = HopiFxCompletion()
        session(SessionKind.Ephemeral).dataTaskWithURL(nsUrl(Capture.url("/bytes?n=4")), second.dataHandler()!!).resume()
        second.awaitDone()
        assertContentEquals(expectedBytes(4), second.data!!.toBytes())
        val info = RuleEngine.get(id)!!
        assertEquals(1L, info.hits)
        assertTrue(info.exhausted)
    }

    @Test
    fun ktorDarwinIsMockedAsAUrlSessionClient() = runBlocking {
        val id = mock("/m-ktor", headers = listOf("Content-Type" to "text/plain"))
        HttpClient(Darwin) { install(Hopi) { logSink = HopiLogSink.Off } }.use { client ->
            val url = Capture.url("/m-ktor")
            val watch = Watch(url)
            assertEquals("mocked", client.get(url).bodyAsText())
            val tx = watch.single()
            assertEquals(TransactionSource.URL_SESSION, tx.source)
            assertEquals(id, tx.ruleId)
        }
    }
}
