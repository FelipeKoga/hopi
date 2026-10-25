package dev.koga.hopi.android

import dev.koga.hopi.android.internal.AndroidCapture
import dev.koga.hopi.android.internal.HopiHttpURLConnection
import dev.koga.hopi.android.internal.HopiHttpsURLConnection
import dev.koga.hopi.core.body.ResponseSkip
import dev.koga.hopi.core.capture.PlatformCapture
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.instrument.HookDescriptors
import dev.koga.hopi.instrument.HopiRewriter
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode

class AndroidCaptureTest {
    private lateinit var server: TestServer
    private lateinit var store: HopiStore

    @BeforeTest
    fun setUp() {
        PlatformCapture.resetForTests()
        server = TestServer()
        store = HopiStore()
    }

    @AfterTest
    fun tearDown() {
        server.close()
        store.close()
        PlatformCapture.resetForTests()
    }

    @Test
    fun okHttpGetIsRecordedOnceAndNothingTheAppReadsChanges() {
        val observed = runScenario("okhttpGet", store, server.base)
        assertEquals(true, observed["started"])
        assertEquals(true, observed["instrumented"])
        // Two calls (a client and its newBuilder() copy), two requests sent, two records.
        val records = store.awaitTerminal(2)
        assertEquals(2, server.received.size)
        assertEquals(2, records.size)
        val record = records.first()
        assertEquals(TransactionState.Complete, record.state)
        assertEquals("okhttp", record.source)
        assertEquals("GET", record.method)
        assertEquals("${server.base}/json", record.url)
        assertEquals(200, record.responseCode)
        // Hopi runs after the app's interceptor, so it records the header that interceptor added.
        assertTrue("X-App" to "1" in record.requestHeaders, record.requestHeaders.toString())
        assertEquals(observed["body"], record.responseBody!!.bytes.decodeToString())
        assertEquals("HTTP/1.1", record.protocol)
        // The app's lists are what it built: one application and one network interceptor, the copy two.
        assertEquals(1, observed["interceptors"])
        assertEquals(1, observed["networkInterceptors"])
        assertEquals(2, observed["derivedInterceptors"])
        assertEquals(1, observed["builderInterceptors"])
        @Suppress("UNCHECKED_CAST")
        assertTrue((observed["interceptorClasses"] as List<String>).none { it.startsWith("dev.koga.hopi") }, observed.toString())
        // The rewrite ran on the real RealCall, once.
        @Suppress("UNCHECKED_CAST")
        val rewritten = (observed["rewritten"] as List<String>).filter { "OkHttpClient.interceptors" in it }
        assertEquals(listOf("okhttp3/internal/connection/RealCall: OkHttpClient.interceptors in getResponseWithInterceptorChain\$okhttp"), rewritten)
    }

    @Test
    fun clientsWhoseCallsInterleaveEachKeepTheirOwnInterceptorView() {
        val observed = runScenario("interceptorViews", store, server.base)
        assertEquals(true, observed["interleavedSame"], observed.toString())
        assertEquals(true, observed["hopiLast"], observed.toString())
        assertEquals(true, observed["appFirst"], observed.toString())
        assertEquals(true, observed["imagesKept"], observed.toString())
        assertEquals(true, observed["oldestRebuilt"], observed.toString())
    }

    @Test
    fun requestBodiesAreCapturedWithoutWritingThemAgain() {
        val observed = runScenario("okhttpPost", store, server.base)
        val record = store.awaitTerminal(1).single()
        assertEquals("POST", record.method)
        assertEquals("""{"title":"keyboard","price":129}""", record.requestBody!!.bytes.decodeToString())
        assertEquals("application/json; charset=utf-8", record.requestBody!!.contentType)
        assertTrue(record.requestHeaders.any { it.first == "Content-Type" })
        assertEquals(observed["body"], record.responseBody!!.bytes.decodeToString())

        val oneShot = runScenario("okhttpOneShot", HopiStore().also { store.close(); store = it }, server.base)
        assertEquals(1, oneShot["writes"])
        assertEquals("streamed once", oneShot["body"])
        assertEquals("streamed once", store.awaitTerminal(1).single().requestBody!!.bytes.decodeToString())
    }

    @Test
    fun aLargeBodyReachesTheAppWholeAndTheRecordKeepsTheCap() {
        val cap = 64L * 1024
        for (scenario in listOf("okhttpLarge", "okhttpChunked")) {
            store.close()
            store = HopiStore()
            val observed = runScenario(scenario, store, server.base, maxBodyBytes = cap)
            assertEquals(sha(TestServer.large()), observed["sha"], scenario)
            val body = store.awaitTerminal(1).single().responseBody!!
            assertEquals(cap.toInt(), body.bytes.size, scenario)
            assertTrue(body.truncated, scenario)
            assertEquals(TestServer.LARGE_BYTES.toLong(), body.totalBytes, scenario)
            assertTrue(TestServer.large().copyOf(cap.toInt()).contentEquals(body.bytes), scenario)
        }
    }

    @Test
    fun gzipIsRecordedDecodedAsTheAppReadsIt() {
        val observed = runScenario("okhttpGzip", store, server.base, maxBodyBytes = 2L * TestServer.LARGE_BYTES)
        assertEquals(sha(TestServer.large()), observed["sha"])
        val body = store.awaitTerminal(1).single().responseBody!!
        assertEquals(sha(TestServer.large()), sha(body.bytes))
        assertFalse(body.truncated)
    }

    @Test
    fun binaryAndEmptyResponsesAreNotTeed() {
        runScenario("okhttpImage", store, server.base)
        val image = store.awaitTerminal(1).single()
        assertEquals("binary content-type", image.responseBody!!.skippedReason)
        assertEquals(4096L, image.responseBody!!.totalBytes)

        store.close()
        store = HopiStore()
        runScenario("okhttpEmpty", store, server.base)
        val empty = store.awaitTerminal(1).single()
        assertEquals(204, empty.responseCode)
        assertNull(empty.responseBody)
    }

    @Test
    fun aBinaryBodyIsCountedToItsEndNotClosedAtItsHeaders() {
        for (scenario in listOf("okhttpBinaryChunked", "urlBinaryChunked")) {
            store.close()
            store = HopiStore()
            val observed = runScenario(scenario, store, server.base)
            assertEquals(TestServer.BINARY_BYTES, observed["size"], scenario)
            val record = store.awaitTerminal(1).single()
            assertEquals(TransactionState.Complete, record.state, scenario)
            val body = record.responseBody!!
            assertEquals(ResponseSkip.BINARY, body.skippedReason, scenario)
            // Chunked: no Content-Length, so this is the count of what the app read.
            assertEquals(TestServer.BINARY_BYTES.toLong(), body.totalBytes, scenario)
            assertEquals(0, body.capturedBytes, "$scenario: counted, never copied")
        }
    }

    @Test
    fun aBodyCutByTheConnectionFailsAndKeepsTheResponseAndWhatArrived() {
        for (scenario in listOf("okhttpBinaryDrop", "okhttpTextDrop", "urlBinaryDrop")) {
            store.close()
            store = HopiStore()
            val observed = runScenario(scenario, store, server.droppingBase)
            val record = store.awaitTerminal(1).single()
            assertEquals(200, record.responseCode, scenario)
            assertTrue(record.responseHeaders.any { it.first.equals("Content-Type", ignoreCase = true) }, scenario)
            val body = record.responseBody!!
            assertEquals(TestServer.DROPPED_AFTER.toLong(), body.totalBytes, "$scenario: the bytes that arrived")
            if (observed["error"] == null) {
                // The JDK's HttpURLConnection may end a short fixed-length body without an error.
                assertEquals(TransactionState.Complete, record.state, scenario)
                continue
            }
            assertEquals(TransactionState.Failed, record.state, scenario)
            if (scenario == "okhttpTextDrop") {
                assertEquals(null, body.skippedReason)
                assertTrue(body.bytes.all { it == 'x'.code.toByte() } && body.capturedBytes == TestServer.DROPPED_AFTER, "the partial body")
            } else {
                assertEquals(ResponseSkip.BINARY, body.skippedReason, scenario)
            }
        }
    }

    @Test
    fun aStreamStaysPendingWhileOpenThenCompletesWithItsExactSize() {
        for (scenario in listOf("okhttpSse", "urlSse")) {
            TestServer().use { sse ->
                store.close()
                val store = HopiStore().also { store = it }
                // The test reads the stream mid-flight as a viewer does (the UI, a bridge client).
                store.attachViewer()
                var midStream: TransactionState? = null
                val observed = runScenario(
                    scenario,
                    store,
                    sse.base,
                    extra = mapOf(
                        "midStream" to Runnable {
                            midStream = runBlocking { withTimeout(10_000) { store.transactions.first { it.isNotEmpty() } } }.single().state
                            sse.streamRelease.countDown()
                        },
                    ),
                )
                assertEquals(TransactionState.Pending, midStream, "$scenario: open after two events")
                assertTrue("data: last" in observed["rest"] as String, scenario)
                val record = store.awaitTerminal(1).single()
                assertEquals(TransactionState.Complete, record.state, scenario)
                assertEquals(ResponseSkip.STREAMING, record.responseBody!!.skippedReason, scenario)
                assertEquals(("data: event 0\n\n".length * 2 + "data: last\n\n".length).toLong(), record.responseBody!!.totalBytes, scenario)
            }
        }
    }

    @Test
    fun aStreamEndedByCancellingItsCallIsCompleteWithWhatWasRead() {
        // okhttp-sse's EventSource.cancel() and Ktor's SSE session on OkHttp end a stream this way.
        TestServer().use { sse ->
            val observed = runScenario("okhttpSseCancel", store, sse.base)
            assertNotNull(observed["error"], "the read after the cancel throws")
            val record = store.awaitTerminal(1).single()
            assertEquals(TransactionState.Complete, record.state)
            assertNull(record.error)
            assertEquals(200, record.responseCode)
            assertEquals(ResponseSkip.STREAMING, record.responseBody!!.skippedReason)
            assertEquals(("data: event 0\n\n".length * 2).toLong(), record.responseBody!!.totalBytes)
        }
    }

    @Test
    fun aBodyWhoseCallIsCancelledMidReadIsCancelledAndKeepsWhatArrived() {
        val observed = runScenario("okhttpCancelMidBody", store, server.base)
        assertEquals(listOf("first"), observed["read"])
        assertNotNull(observed["error"], "the read after the cancel throws")
        val record = store.awaitTerminal(1).single()
        assertEquals(TransactionState.Failed, record.state)
        assertEquals("cancelled", record.error, "as for a body closed early, not the cancel's exception text")
        assertEquals(200, record.responseCode)
        assertEquals("first\n", record.responseBody!!.bytes.decodeToString())
        assertEquals(6L, record.responseBody!!.totalBytes)
    }

    @Test
    fun aDisconnectFromAnotherThreadMidBodyIsCancelledAndLaterReadsChangeNothing() {
        val observed = runScenario("urlDisconnectMidBody", store, server.base)
        assertEquals("first\n", observed["first"])
        val record = store.awaitTerminal(1).single()
        assertEquals(TransactionState.Failed, record.state)
        assertEquals("cancelled", record.error)
        assertEquals(200, record.responseCode)
        val body = record.responseBody!!
        assertEquals("first\n", body.bytes.decodeToString())
        assertEquals(body.capturedBytes.toLong(), body.totalBytes, "bytes and count agree")
    }

    @Test
    fun aFollowedRedirectIsOneRecordWithTheFinalUrl() {
        runScenario("okhttpRedirect", store, server.base)
        val record = store.awaitTerminal(1).single()
        assertEquals(listOf("GET /redirect", "GET /json"), server.received.toList())
        assertEquals("${server.base}/redirect", record.url)
        assertEquals("${server.base}/json", record.finalUrl)
        assertEquals(200, record.responseCode)
    }

    @Test
    fun aFailureIsRecordedAndReachesTheAppUnchanged() {
        val observed = runScenario("okhttpRefused", store, server.base)
        assertEquals("java.net.ConnectException", observed["error"])
        val record = store.awaitTerminal(1).single()
        assertEquals(TransactionState.Failed, record.state)
        assertTrue(record.error!!.startsWith("ConnectException"), record.error)
        assertNull(record.responseCode)
    }

    @Test
    fun hopiReadsNothingAheadOfTheApp() {
        // The server sends one line and holds the rest until the app has read that line: a peek or
        // read-ahead in the interceptor would wait for the rest and never return the first line.
        val observed = runScenario("okhttpHold", store, server.base, extra = mapOf("release" to Runnable { server.release.countDown() }))
        assertEquals("first", observed["first"])
        assertEquals("rest\n", observed["rest"])
        assertEquals("first\nrest\n", store.awaitTerminal(1).single().responseBody!!.bytes.decodeToString())
    }

    @Test
    fun closingABodyEarlyIsCancelled() {
        runScenario("okhttpCloseEarly", store, server.base)
        val record = store.awaitTerminal(1).single()
        assertEquals(TransactionState.Failed, record.state)
        assertEquals("cancelled", record.error)
        assertEquals(200, record.responseCode)
    }

    @Test
    fun aBodyTheAppNeverReadsIsCompleteAndNotRead() {
        // An SDK ping that only looks at the status: the call succeeded, the app closed the body unread.
        runScenario("okhttpUnread", store, server.base)
        val record = store.awaitTerminal(1).single()
        assertEquals(TransactionState.Complete, record.state)
        assertEquals(200, record.responseCode)
        assertEquals("not read", record.responseBody!!.skippedReason)
        assertEquals(17L, record.responseBody!!.totalBytes)
    }

    @Test
    fun enqueueAndParallelCallsGetTheirOwnRecords() {
        val observed = runScenario("okhttpEnqueue", store, server.base)
        assertTrue((observed["thread"] as String).startsWith("OkHttp"), observed.toString())
        assertEquals(1, store.awaitTerminal(1).size)

        store.close()
        store = HopiStore()
        runScenario("okhttpParallel", store, server.base)
        val records = store.awaitTerminal(32)
        assertEquals(32, records.size)
        for (record in records) {
            val i = record.url.substringAfter("i=")
            assertEquals("body-$i".repeat(100), record.requestBody!!.bytes.decodeToString())
            assertEquals("body-$i".repeat(100), record.responseBody!!.bytes.decodeToString())
        }
    }

    @Test
    fun httpUrlConnectionCallSitesAreWrappedAndRecorded() {
        val get = runScenario("urlGet", store, server.base)
        assertEquals(HopiHttpURLConnection::class.java.name, get["class"])
        val record = store.awaitTerminal(1).single()
        assertEquals("urlconnection", record.source)
        assertEquals(200, record.responseCode)
        assertEquals(get["body"], record.responseBody!!.bytes.decodeToString())
        assertTrue("X-App" to "1" in record.requestHeaders, record.requestHeaders.toString())

        store.close()
        store = HopiStore()
        val post = runScenario("urlPost", store, server.base)
        val posted = store.awaitTerminal(1).single()
        assertEquals("POST", posted.method)
        assertEquals("""{"title":"stand"}""", posted.requestBody!!.bytes.decodeToString())
        assertEquals(post["body"], posted.responseBody!!.bytes.decodeToString())

        store.close()
        store = HopiStore()
        val missing = runScenario("urlMissing", store, server.base)
        assertEquals("java.io.FileNotFoundException", missing["exception"])
        val notFound = store.awaitTerminal(1).single()
        assertEquals(404, notFound.responseCode)
        assertEquals(missing["error"], notFound.responseBody!!.bytes.decodeToString())
        assertEquals(1, server.received.count { it == "GET /missing" })

        store.close()
        store = HopiStore()
        runScenario("urlOpenStream", store, server.base)
        assertEquals("""{"hello":"world"}""", store.awaitTerminal(1).single().responseBody!!.bytes.decodeToString())
    }

    @Test
    fun beforeStartNothingIsWrappedOrRecorded() {
        val observed = runScenario("urlGet", store, server.base, start = false)
        assertEquals(false, observed["started"])
        assertEquals("sun.net.www.protocol.http.HttpURLConnection", observed["class"])
        runScenario("okhttpGet", store, server.base, start = false)
        assertEquals(0, store.nextId() - 1)
    }

    @Test
    fun ktorsOkHttpEngineDefersOnlyWhenRealCallIsInstrumented() {
        val engine = "io.ktor.client.engine.okhttp.OkHttpEngine"
        runScenario("okhttpGet", store, server.base)
        assertTrue(PlatformCapture.covers(engine))

        PlatformCapture.resetForTests()
        // OkHttp left out of the rewrite: no annotation, so the Ktor plugin keeps recording.
        val observed = runScenario("okhttpGet", HopiStore(), server.base, options = HopiRewriter.Options(okhttp = false))
        assertEquals(false, observed["instrumented"])
        assertFalse(PlatformCapture.covers(engine))
    }

    @Test
    fun ktorsAndroidEngineIsRecordedOnceByTheWrapper() {
        val observed = runScenario("ktorAndroid", store, server.base, extra = mapOf("path" to "/json", "urlConnection" to true))
        assertTrue((observed["engine"] as String).startsWith(AndroidCapture.KTOR_ANDROID_ENGINE_PACKAGE), observed.toString())
        val record = store.awaitTerminal(1).single()
        assertEquals("urlconnection", record.source)
        assertEquals("""{"hello":"world"}""", record.responseBody!!.bytes.decodeToString())
        // One id allocated: the Ktor plugin deferred and allocated none.
        assertEquals(2, store.nextId())
        assertTrue(PlatformCapture.honorsPassThrough("io.ktor.client.engine.android.AndroidClientEngine"))
    }

    @Test
    fun withContentEncodingTheKtorPluginRecordsWhatKtorDecodedOnEitherEngine() {
        val cap = 256 * 1024
        for ((scenario, jars) in listOf("ktorAndroid" to emptyList(), "ktorOkHttp" to testJars("ktorOkhttp"))) {
            store.close()
            store = HopiStore()
            server.receivedHeaders.clear()
            val observed = runScenario(scenario, store, server.base, extra = mapOf("path" to "/gzip", "decode" to true, "urlConnection" to true), jars = jars)
            assertEquals(sha(TestServer.large()), observed["sha"], scenario)
            val record = store.awaitTerminal(1).single()
            assertEquals("ktor", record.source, scenario)
            // Decoded, as the app read it, not the gzip bytes the platform stack handed over.
            assertTrue(TestServer.large().copyOf(cap).contentEquals(record.responseBody!!.bytes), scenario)
            assertEquals(2, store.nextId(), "$scenario: one record")
            assertTrue(record.requestHeaders.none { it.first.equals(PlatformCapture.PASS_THROUGH_HEADER, ignoreCase = true) }, scenario)
            // The marker never reaches the network.
            assertTrue(server.receivedHeaders.single().none { it == PlatformCapture.PASS_THROUGH_HEADER.lowercase() }, "$scenario: ${server.receivedHeaders}")
        }
    }

    @Test
    fun ktorsOkHttpEngineWithoutContentEncodingIsRecordedOnceByTheInterceptor() {
        val observed = runScenario("ktorOkHttp", store, server.base, extra = mapOf("path" to "/json"), jars = testJars("ktorOkhttp"))
        assertEquals("io.ktor.client.engine.okhttp.OkHttpEngine", observed["engine"])
        val record = store.awaitTerminal(1).single()
        assertEquals("okhttp", record.source)
        assertEquals(2, store.nextId())
    }

    @Test
    fun aMarkedRequestPassesThroughUnrecordedAndWithoutTheMarker() {
        for (scenario in listOf("okhttpPassThrough", "urlPassThrough")) {
            store.close()
            store = HopiStore()
            server.receivedHeaders.clear()
            val observed = runScenario(scenario, store, server.base)
            assertEquals("""{"hello":"world"}""", observed["marked"], scenario)
            val record = store.awaitTerminal(1).single()
            assertEquals("${server.base}/json?plain", record.url, scenario)
            assertEquals(2, store.nextId(), "$scenario: the marked request has no record")
            assertTrue(server.receivedHeaders.none { PlatformCapture.PASS_THROUGH_HEADER.lowercase() in it }, "$scenario: ${server.receivedHeaders}")
        }
    }

    @Test
    fun okHttpBefore314IsLeftAloneAnd314IsRecorded() {
        // 3.12: RealCall is rewritten (the plugin cannot tell the version), but the runtime does not
        // link against it (no RequestBody.isDuplex()), so nothing joins its chain.
        val old = runScenario("okhttpLegacyPost", store, server.base, jars = testJars("okhttp312"))
        assertEquals("Unsupported", old["okHttpState"])
        assertEquals(false, old["instrumented"])
        assertEquals(200, old["code"])
        assertEquals("legacy body", old["body"])
        @Suppress("UNCHECKED_CAST")
        assertTrue((old["rewritten"] as List<String>).any { it.startsWith("okhttp3/RealCall: OkHttpClient.interceptors") }, old.toString())
        assertEquals(1, store.nextId(), "nothing recorded")
        assertFalse(PlatformCapture.covers("io.ktor.client.engine.okhttp.OkHttpEngine"))

        store.close()
        store = HopiStore()
        val last = runScenario("okhttpLegacyPost", store, server.base, jars = testJars("okhttp314"))
        assertEquals("Instrumented", last["okHttpState"])
        assertEquals("legacy body", last["body"])
        val record = store.awaitTerminal(1).single()
        assertEquals("okhttp", record.source)
        assertEquals("legacy body", record.requestBody!!.bytes.decodeToString())
        assertEquals("legacy body", record.responseBody!!.bytes.decodeToString())
    }

    @Test
    fun getErrorStreamAsTheFirstCallIsRecorded() {
        val observed = runScenario("urlErrorStreamFirst", store, server.base)
        assertEquals("""{"error":"missing"}""", observed["error"])
        val record = store.awaitTerminal(1).single()
        assertEquals(404, record.responseCode)
        assertEquals(observed["error"], record.responseBody!!.bytes.decodeToString())
        assertEquals(1, server.received.count { it == "GET /missing" })
    }

    @Test
    fun singleByteReadsAndWritesAreCaptured() {
        val observed = runScenario("urlSingleBytes", store, server.base)
        assertEquals("one byte at a time", observed["body"])
        val record = store.awaitTerminal(1).single()
        assertEquals("one byte at a time", record.requestBody!!.bytes.decodeToString())
        assertEquals("one byte at a time", record.responseBody!!.bytes.decodeToString())
        assertFalse(record.responseBody!!.truncated)
    }

    @Test
    fun theHooksHaveTheDescriptorsTheRewriteCalls() {
        val okHttpHooks = Class.forName(HookDescriptors.OKHTTP_HOOKS.replace('/', '.'))
        val okHttpHook = okHttpHooks.methods.single { it.name == HookDescriptors.INTERCEPTORS }
        assertEquals(HookDescriptors.OKHTTP_HOOK_DESCRIPTOR, Type.getMethodDescriptor(okHttpHook))
        val urlHooks = Class.forName(HookDescriptors.URL_HOOKS.replace('/', '.'))
        for (hook in HookDescriptors.URL_HOOK_METHODS) {
            val method = urlHooks.methods.singleOrNull { it.name == hook.name && Type.getMethodDescriptor(it) == hook.hookDescriptor }
            assertNotNull(method, "no $hook hook")
            assertTrue(java.lang.reflect.Modifier.isStatic(method.modifiers))
        }
        // The URL hooks never name an okhttp3 type, so an app without OkHttp loads them.
        assertTrue(urlHooks.declaredMethods.none { m -> (m.parameterTypes + m.returnType).any { it.name.startsWith("okhttp3.") } })
        assertEquals(HookDescriptors.INSTRUMENTED_ANNOTATION, Type.getDescriptor(dev.koga.hopi.android.internal.HopiInstrumented::class.java))
    }

    @Test
    fun theWrappersOverrideEveryMethodOfTheCompileSdksUrlConnections() {
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
        val http = declared(HopiHttpURLConnection::class.java)
        val https = declared(HopiHttpsURLConnection::class.java)
        val httpExpected = expected.getValue("java/net/URLConnection") + expected.getValue("java/net/HttpURLConnection")
        assertEquals(emptySet(), httpExpected - http, "HopiHttpURLConnection does not override")
        assertEquals(emptySet(), httpExpected + expected.getValue("javax/net/ssl/HttpsURLConnection") - https, "HopiHttpsURLConnection does not override")
    }

    @Test
    fun invalidCapsStartNothing() {
        val observed = runScenario("invalidCaps", store, server.base, start = false)
        assertEquals(false, observed["result"])
        assertEquals(false, observed["started"])
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
