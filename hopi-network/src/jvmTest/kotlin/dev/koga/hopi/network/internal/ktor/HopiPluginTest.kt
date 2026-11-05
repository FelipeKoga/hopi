package dev.koga.hopi.network.internal.ktor

import dev.koga.hopi.core.bridge.AgentBridgeHook
import dev.koga.hopi.core.curl.toCurl
import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ktor.HopiLogSink
import io.ktor.client.HttpClient
import io.ktor.client.call.replaceResponse
import io.ktor.client.plugins.onDownload
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.http.Headers
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.writer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writeFully
import java.io.IOException
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray

class HopiPluginTest {
    private val json = """{"users":[{"id":1,"name":"Ada"},{"id":2,"name":"Grace"}]}"""
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun getJsonWithoutContentLengthIsTeedAndAppReceivesTheSameBody() = runBlocking<Unit> {
        TestStore().use { t ->
            val client = mockClient(t.store) { respond(json, HttpStatusCode.OK, jsonHeaders) }
            assertEquals(json, client.get("https://api.example.com/users?page=2").bodyAsText())
            val tx = t.awaitTerminal().single()
            assertEquals(TransactionState.Complete, tx.state)
            assertEquals("GET", tx.method)
            assertEquals("https://api.example.com/users?page=2", tx.url)
            assertEquals(200, tx.responseCode)
            assertEquals("HTTP/1.1", tx.protocol)
            assertNull(tx.requestBody)
            val body = assertNotNull(tx.responseBody)
            assertContentEquals(json.encodeToByteArray(), body.bytes)
            assertEquals(json.length.toLong(), body.totalBytes)
            assertFalse(body.truncated)
            assertEquals("application/json", body.contentType)
            assertTrue(tx.durationMs!! >= 0)
        }
    }

    @Test
    fun getJsonWithContentLengthIsReadFromTheSavedBody() = runBlocking<Unit> {
        TestStore().use { t ->
            val headers = headersOf(
                HttpHeaders.ContentType to listOf("application/json"),
                HttpHeaders.ContentLength to listOf(json.length.toString()),
            )
            val client = mockClient(t.store) { respond(json, HttpStatusCode.OK, headers) }
            assertEquals(json, client.get("https://api.example.com/users").bodyAsText())
            val body = assertNotNull(t.awaitTerminal().single().responseBody)
            assertContentEquals(json.encodeToByteArray(), body.bytes)
            assertEquals(json.length.toLong(), body.totalBytes)
        }
    }

    @Test
    fun streamingResponseWithContentLengthIsTeedAfterSave() = runBlocking<Unit> {
        TestStore().use { t ->
            val headers = headersOf(
                HttpHeaders.ContentType to listOf("application/json"),
                HttpHeaders.ContentLength to listOf(json.length.toString()),
            )
            val client = mockClient(t.store) { respond(json, HttpStatusCode.OK, headers) }
            val received = client.prepareGet("https://api.example.com/users").execute { it.bodyAsChannel().toByteArray() }
            assertContentEquals(json.encodeToByteArray(), received)
            val body = assertNotNull(t.awaitTerminal().single().responseBody)
            assertContentEquals(json.encodeToByteArray(), body.bytes)
        }
    }

    @Test
    fun requestBodiesOfEveryKindReachTheServerIntactAndAreCaptured() = runBlocking<Unit> {
        TestStore().use { t ->
            val received = Collections.synchronizedList(mutableListOf<ByteArray>())
            val client = mockClient(t.store) { request ->
                received += request.body.toByteArray()
                respond("ok")
            }
            val text = "hello hopi"
            val bytes = asciiBytes(3000, seed = 1)
            val streamed = asciiBytes(100_000, seed = 2)
            val bodies: List<Pair<ByteArray, OutgoingContent>> = listOf(
                text.encodeToByteArray() to TextContent(text, ContentType.Text.Plain),
                bytes to ByteArrayContent(bytes, ContentType.Application.OctetStream),
                streamed to writeChannelContent(streamed),
                streamed to readChannelContent(streamed),
            )
            for ((_, content) in bodies) {
                client.post("https://api.example.com/upload") { setBody(content) }
            }
            val txs = t.awaitTerminal(bodies.size)
            assertEquals(bodies.size, received.size)
            bodies.forEachIndexed { index, (expected, _) ->
                assertContentEquals(expected, received[index], "server side of body $index")
                val captured = assertNotNull(txs[index].requestBody, "capture of body $index")
                assertContentEquals(expected, captured.bytes, "capture of body $index")
                assertEquals(expected.size.toLong(), captured.totalBytes)
                assertFalse(captured.truncated)
                assertNull(captured.skippedReason)
            }
            assertTrue(txs[0].requestBody!!.contentType!!.startsWith("text/plain"))
            assertTrue(txs[0].requestHeaders.any { it.first == HttpHeaders.ContentType })
        }
    }

    @Test
    fun requestBodiesBeyondTheCapAreTruncatedButSentWhole() = runBlocking<Unit> {
        TestStore().use { t ->
            val received = Collections.synchronizedList(mutableListOf<ByteArray>())
            val client = mockClient(t.store, { maxBodyBytes = 1024 }) { request ->
                received += request.body.toByteArray()
                respond("ok")
            }
            val payload = asciiBytes(50_000, seed = 3)
            client.post("https://api.example.com/a") { setBody(ByteArrayContent(payload, ContentType.Text.Plain)) }
            client.post("https://api.example.com/b") { setBody(writeChannelContent(payload)) }
            val txs = t.awaitTerminal(2)
            for ((index, tx) in txs.withIndex()) {
                assertContentEquals(payload, received[index])
                val captured = assertNotNull(tx.requestBody)
                assertContentEquals(payload.copyOf(1024), captured.bytes)
                assertTrue(captured.truncated)
                assertEquals(payload.size.toLong(), captured.totalBytes)
            }
        }
    }

    @Test
    fun multipartRequestIsRecordedAsSkipped() = runBlocking<Unit> {
        TestStore().use { t ->
            val client = mockClient(t.store) { request ->
                request.body.toByteArray()
                respond("ok")
            }
            client.submitFormWithBinaryData("https://api.example.com/form", formData { append("field", "value") })
            val captured = assertNotNull(t.awaitTerminal().single().requestBody)
            assertEquals(SkipReason.MULTIPART, captured.skippedReason)
            assertEquals(0, captured.bytes.size)
        }
    }

    @Test
    fun tenMegabyteResponsesReachTheAppIntactWhileOnlyTheCapIsRetained() = runBlocking<Unit> {
        TestStore().use { t ->
            val payload = asciiBytes(10 * 1024 * 1024, seed = 4)
            val withLength = headersOf(
                HttpHeaders.ContentType to listOf("text/plain"),
                HttpHeaders.ContentLength to listOf(payload.size.toString()),
            )
            val withoutLength = headersOf(HttpHeaders.ContentType, "text/plain")
            var call = 0
            val client = mockClient(t.store) {
                respond(payload, HttpStatusCode.OK, if (call++ == 0) withLength else withoutLength)
            }
            val saved = client.get("https://api.example.com/big").body<ByteArray>()
            val streamed = client.prepareGet("https://api.example.com/big").execute { it.bodyAsChannel().toByteArray() }
            assertContentEquals(payload, saved)
            assertContentEquals(payload, streamed)
            for (tx in t.awaitTerminal(2)) {
                assertEquals(TransactionState.Complete, tx.state)
                val body = assertNotNull(tx.responseBody)
                assertEquals(256 * 1024, body.bytes.size)
                assertContentEquals(payload.copyOf(256 * 1024), body.bytes)
                assertTrue(body.truncated)
                assertEquals(payload.size.toLong(), body.totalBytes)
            }
        }
    }

    @Test
    fun binaryAndEventStreamResponsesAreNotCaptured() = runBlocking<Unit> {
        TestStore().use { t ->
            val image = ByteArray(4096) { it.toByte() }
            val events = "data: one\n\ndata: two\n\n"
            val client = mockClient(t.store) { request ->
                if (request.url.encodedPath == "/image") {
                    respond(image, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                } else {
                    respond(events, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                }
            }
            assertContentEquals(image, client.get("https://api.example.com/image").body<ByteArray>())
            val streamed = client.prepareGet("https://api.example.com/events").execute { it.bodyAsText() }
            assertEquals(events, streamed)
            val (imageTx, eventsTx) = t.awaitTerminal(2)
            assertEquals(SkipReason.BINARY, imageTx.responseBody!!.skippedReason)
            assertEquals(0, imageTx.responseBody!!.bytes.size)
            assertEquals("image/png", imageTx.responseBody!!.contentType)
            assertEquals(SkipReason.STREAMING, eventsTx.responseBody!!.skippedReason)
            assertEquals(TransactionState.Complete, eventsTx.state)
        }
    }

    @Test
    fun headAndNoContentResponsesHaveNoBody() = runBlocking<Unit> {
        TestStore().use { t ->
            val client = mockClient(t.store) { request ->
                if (request.url.encodedPath == "/empty") {
                    respond("", HttpStatusCode.NoContent)
                } else {
                    respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1234"))
                }
            }
            client.head("https://api.example.com/file")
            client.get("https://api.example.com/empty")
            val txs = t.awaitTerminal(2)
            assertEquals(listOf(200, 204), txs.map { it.responseCode })
            assertTrue(txs.all { it.responseBody == null && it.state == TransactionState.Complete })
        }
    }

    @Test
    fun engineFailureProducesAFailedTransaction() = runBlocking<Unit> {
        TestStore().use { t ->
            val client = mockClient(t.store) { throw IOException("connection reset") }
            assertFailsWith<IOException> { client.get("https://api.example.com/users") }
            val tx = t.awaitTerminal().single()
            assertEquals(TransactionState.Failed, tx.state)
            assertEquals("IOException: connection reset", tx.error)
            assertNull(tx.responseCode)
            assertNotNull(tx.durationMs)
        }
    }

    @Test
    fun cancelledCallBecomesFailedCancelled() = runBlocking<Unit> {
        TestStore().use { t ->
            val entered = CompletableDeferred<Unit>()
            val client = mockClient(t.store) {
                entered.complete(Unit)
                awaitCancellation()
            }
            val job = launch { client.get("https://api.example.com/slow") }
            withTimeout(10_000) { entered.await() }
            job.cancel()
            job.join()
            val tx = t.awaitTerminal().single()
            assertEquals(TransactionState.Failed, tx.state)
            assertEquals("cancelled", tx.error)
        }
    }

    @Test
    fun responseBodyAbandonedMidStreamBecomesFailedCancelled() = runBlocking<Unit> {
        TestStore().use { t ->
            val payload = asciiBytes(8 * 1024 * 1024, seed = 5)
            val body = ByteChannel()
            val abandoned = CompletableDeferred<Unit>()
            // The network has delivered the first 64 KiB when the app leaves; the rest is still to come.
            val producer = async(Dispatchers.Default) {
                body.writeFully(payload, 0, 64 * 1024)
                body.flush()
                abandoned.await()
                runCatching {
                    body.writeFully(payload, 64 * 1024, payload.size)
                    body.flushAndClose()
                }.isFailure
            }
            try {
                val client = mockClient(t.store) { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain")) }
                val head = withTimeout(10_000) {
                    client.prepareGet("https://api.example.com/big").execute { response ->
                        response.bodyAsChannel().readRemaining(10).readByteArray()
                    }
                }
                abandoned.complete(Unit)
                assertContentEquals(payload.copyOf(10), head)
                val tx = t.awaitTerminal().single()
                assertEquals(TransactionState.Failed, tx.state)
                assertEquals("cancelled", tx.error)
                // The response had arrived: its status and headers stay, with the part of the body that came.
                assertEquals(200, tx.responseCode)
                assertEquals("text/plain", tx.responseHeaders.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second)
                val partial = assertNotNull(tx.responseBody)
                assertTrue(partial.capturedBytes in 10..64 * 1024, "partial body: ${partial.capturedBytes}")
                assertContentEquals(payload.copyOf(partial.capturedBytes), partial.bytes)
                assertNotNull(body.closedCause, "the source is cancelled, so the engine can release the connection")
                assertTrue(withTimeout(10_000) { producer.await() }, "the rest of the body is refused")
            } finally {
                producer.cancel()
            }
        }
    }

    @Test
    fun redactionAppliesToExportsNotToTheWireOrTheStore() = runBlocking<Unit> {
        TestStore().use { t ->
            val sent = CompletableDeferred<io.ktor.http.Headers>()
            val client = mockClient(t.store, {
                redactHeaders("X-Api-Key")
                redactQueryParameters("token")
            }) { request ->
                sent.complete(request.headers)
                respond(json, HttpStatusCode.OK, jsonHeaders)
            }
            client.get("https://api.example.com/users?token=s3cr3t&page=2") {
                header(HttpHeaders.Authorization, "Bearer abc.def")
                header("X-Api-Key", "key-123")
            }
            val wire = sent.await()
            assertEquals("Bearer abc.def", wire[HttpHeaders.Authorization])
            assertEquals("key-123", wire["X-Api-Key"])

            val tx = t.awaitTerminal().single()
            assertTrue(HttpHeaders.Authorization to "Bearer abc.def" in tx.requestHeaders)
            assertTrue("X-Api-Key" to "key-123" in tx.requestHeaders)
            assertEquals("https://api.example.com/users?token=s3cr3t&page=2", tx.url)

            val curl = toCurl(tx, t.store.redactor)
            assertFalse("abc.def" in curl || "key-123" in curl || "s3cr3t" in curl, curl)
            assertTrue("'Authorization: <redacted>'" in curl && "'X-Api-Key: <redacted>'" in curl, curl)
            val line = TransactionJson.summaryLogLine(tx, t.store.redactor)
            assertTrue("token=<redacted>&page=2" in line, line)
            assertFalse("s3cr3t" in line, line)
        }
    }

    @Test
    fun disabledPluginInstallsNoHooksAndRecordsNothing() = runBlocking<Unit> {
        TestStore().use { t ->
            val client = mockClient(t.store, { enabled = false }) { respond(json, HttpStatusCode.OK, jsonHeaders) }
            assertEquals(json, client.get("https://api.example.com/users").bodyAsText())
            assertEquals(1, t.store.nextId(), "no id was allocated")
            assertFalse(client.receivePipeline.items.any { it.name == "HopiRawResponse" })
            val enabled = mockClient(t.store) { respond("") }
            assertTrue(enabled.receivePipeline.items.any { it.name == "HopiRawResponse" })
        }
    }

    @Test
    fun clientsShareOneStore() = runBlocking<Unit> {
        TestStore().use { t ->
            val first = mockClient(t.store) { respond("a") }
            val second = mockClient(t.store) { respond("b") }
            assertEquals("a", first.get("https://one.example.com/").bodyAsText())
            assertEquals("b", second.get("https://two.example.com/").bodyAsText())
            assertEquals("a", first.get("https://one.example.com/again").bodyAsText())
            val txs = t.awaitTerminal(3)
            assertEquals(listOf(1L, 2L, 3L), txs.map { it.id })
            assertEquals(
                listOf("https://one.example.com/", "https://two.example.com/", "https://one.example.com/again"),
                txs.map { it.url },
            )
        }
    }

    @Test
    fun twoHundredConcurrentRequestsAreAllRecordedWithUniqueIds() = runBlocking<Unit> {
        TestStore(channelCapacity = 1024).use { t ->
            val client = mockClient(t.store) { request -> respond(request.url.encodedPath, HttpStatusCode.OK) }
            val bodies = (0 until 200).map { i ->
                async(kotlinx.coroutines.Dispatchers.Default) { client.get("https://api.example.com/item/$i").bodyAsText() }
            }.awaitAll()
            assertEquals((0 until 200).map { "/item/$it" }, bodies)
            val txs = t.awaitTerminal(200)
            assertEquals(200, txs.size)
            assertEquals(200, txs.map { it.id }.toSet().size)
            assertTrue(txs.all { it.state == TransactionState.Complete })
            assertEquals(
                (0 until 200).map { "/item/$it" }.toSet(),
                txs.map { it.responseBody!!.bytes.decodeToString() }.toSet(),
            )
        }
    }

    @Test
    fun agentBridgeStartsOnlyWhenRequestedAndWithTheDefaultStore() = runBlocking<Unit> {
        // The once-per-process flag makes this the only test that may request the bridge; the
        // starter stands in for the platform bridge, so no socket is bound in the test JVM.
        val started = CompletableDeferred<HopiStore>()
        AgentBridgeHook.register { store -> started.complete(store) }
        HttpClient(MockEngine { respond("") }) {
            hopi(store = null) {
                logSink = HopiLogSink.Off
            }
        }
        HttpClient(MockEngine { respond("") }) {
            hopi(HopiStore()) {
                logSink = HopiLogSink.Off
                agentBridge = true
            }
        }
        assertFalse(started.isCompleted, "the bridge started without agentBridge = true on the default store")
        HttpClient(MockEngine { respond("") }) {
            hopi(store = null) {
                logSink = HopiLogSink.Off
                agentBridge = true
            }
        }
        assertSame(HopiStore.default, withTimeout(1_000) { started.await() })
    }

    @Test
    fun streamedRequestBodiesReachTheWireAsTheAppFlushesThem() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val original = object : OutgoingContent.WriteChannelContent() {
            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeFully("first".encodeToByteArray())
                channel.flush()
                gate.await()
                channel.writeFully("second".encodeToByteArray())
            }
        }
        val live = ByteChannel()
        val readOriginal = object : OutgoingContent.ReadChannelContent() {
            override fun readFrom(): ByteReadChannel = live
        }
        for (content in listOf<OutgoingContent>(original, readOriginal)) {
            val streamed = StreamedRequestBody(content, limit = 1024)
            // Engines hand writeTo a channel that does not auto-flush.
            val wire = ByteChannel()
            val writer = launch(Dispatchers.Default) {
                streamed.writeTo(wire)
                wire.flushAndClose()
            }
            if (content === readOriginal) {
                live.writeFully("first".encodeToByteArray())
                live.flush()
            }
            assertTrue(withTimeout(5_000) { wire.awaitContent(5) }, "the first chunk reaches the wire before the body ends")
            if (content === readOriginal) {
                live.writeFully("second".encodeToByteArray())
                live.flushAndClose()
            } else {
                gate.complete(Unit)
            }
            writer.join()
            assertEquals("firstsecond", wire.readRemaining().readByteArray().decodeToString())
            assertEquals("firstsecond", streamed.body().bytes.decodeToString())
        }
    }

    @Test
    fun encodedResponsesAreCapturedAsTheDecoderHandsThemToTheApp() = runBlocking<Unit> {
        TestStore().use { t ->
            val gzipped = gzip(json.encodeToByteArray())
            val chunked = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ContentEncoding to listOf("gzip"))
            val sized = headersOf(
                HttpHeaders.ContentType to listOf("application/json"),
                HttpHeaders.ContentEncoding to listOf("gzip"),
                HttpHeaders.ContentLength to listOf(gzipped.size.toString()),
            )
            val client = mockClient(t.store) { request ->
                respond(gzipped, HttpStatusCode.OK, if (request.url.encodedPath == "/sized") sized else chunked)
            }
            installGunzip(client)
            assertEquals(json, client.get("https://api.example.com/chunked").bodyAsText())
            assertEquals(json, client.get("https://api.example.com/sized").bodyAsText())
            assertEquals(json, client.prepareGet("https://api.example.com/streamed").execute { it.bodyAsText() })
            val txs = t.awaitTerminal(3)
            for (tx in txs) {
                val body = assertNotNull(tx.responseBody, tx.url)
                assertEquals(json, body.bytes.decodeToString(), tx.url)
                assertEquals(json.length.toLong(), body.totalBytes, tx.url)
                assertFalse(body.truncated)
                assertTrue(HttpHeaders.ContentEncoding to "gzip" in tx.responseHeaders, "raw headers are kept")
            }
        }
    }

    @Test
    fun capturingASavedBodyDoesNotFireTheAppsDownloadListener() = runBlocking<Unit> {
        TestStore().use { t ->
            val payload = asciiBytes(10_000)
            val headers = headersOf(
                HttpHeaders.ContentType to listOf("text/plain"),
                HttpHeaders.ContentLength to listOf(payload.size.toString()),
            )
            val client = mockClient(t.store) { respond(payload, HttpStatusCode.OK, headers) }
            val progress = Collections.synchronizedList(mutableListOf<Long>())
            val received = client.get("https://api.example.com/file") {
                onDownload { sent, _ -> progress += sent }
            }.body<ByteArray>()
            assertContentEquals(payload, received)
            assertEquals(1, progress.count { it == payload.size.toLong() }, progress.toString())
            assertTrue(progress.zipWithNext().all { (a, b) -> a <= b }, "progress never restarts: $progress")
            assertContentEquals(payload, assertNotNull(t.awaitTerminal().single().responseBody).bytes)
        }
    }

    @Test
    fun aMidBodyFailureReachesTheAppWithTheSameCauseChainAsWithoutThePlugin() = runBlocking<Unit> {
        suspend fun failure(client: HttpClient): Throwable =
            assertFailsWith<Throwable> { client.get("https://api.example.com/chunked").bodyAsText() }
        fun engine() = MockEngine {
            val body = ByteChannel()
            body.writeFully("{\"partial\":".encodeToByteArray())
            body.flush()
            body.cancel(IOException("connection reset"))
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        val baseline = failure(HttpClient(engine()))
        TestStore().use { t ->
            val withPlugin = failure(
                HttpClient(engine()) {
                    hopi(t.store) {
                        logSink = HopiLogSink.Off
                    }
                },
            )
            fun chain(e: Throwable) = generateSequence(e) { it.cause }.map { it::class }.toList()
            assertEquals(chain(baseline), chain(withPlugin))
            assertEquals("connection reset", generateSequence(withPlugin) { it.cause }.last().message)
            val tx = t.awaitTerminal().single()
            assertEquals(TransactionState.Failed, tx.state)
            assertEquals("IOException: connection reset", tx.error)
        }
    }

    @Test
    fun maxBodyBytesIsClampedToHalfTheStoreByteCap() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = HopiStore(maxTotalBytes = 1000, scope = scope)
        try {
            val payload = asciiBytes(5000)
            val client = mockClient(store, { maxBodyBytes = 10_000 }) {
                respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
            }
            assertContentEquals(payload, client.get("https://api.example.com/big").body<ByteArray>())
            val tx = withTimeout(10_000) { store.transactions.first { list -> list.any { it.state.isTerminal } } }.single()
            val body = assertNotNull(tx.responseBody)
            assertEquals(500, body.capturedBytes)
            assertTrue(body.truncated)
            assertEquals(5000L, body.totalBytes)
        } finally {
            store.close()
            scope.cancel()
        }
    }

    private val savedResponse = asciiBytes(300)

    // A Content-Length within the cap: the body is read after SaveBody, and the end is queued before the call returns.
    private fun savingClient(store: HopiStore, handler: suspend () -> Unit = {}) = mockClient(store) {
        handler()
        respond(savedResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("text/plain"), HttpHeaders.ContentLength to listOf("300")))
    }

    private val posted = List(3) { asciiBytes(100, seed = it) }

    @Test
    fun aPostWhoseEndWentInWithoutItsBodiesKeepsTheRequestBodyItsStartHolds() = runBlocking<Unit> {
        HeldStore(maxTotalBytes = 1000).use { held ->
            val client = savingClient(held.store)
            // Held before the first start: the starts wait in their lane, so each end counts its request
            // body too (400 bytes), and the third goes in without its bodies.
            held.hold()
            for ((i, body) in posted.withIndex()) client.post("https://api.example.com/items?i=$i") { setBody(ByteArrayContent(body, ContentType.Text.Plain)) }
            held.release()
            val records = held.ends(3)
            // The plugin copied each body once, at the start: the record keeps the one its start holds.
            for ((record, body) in records.zip(posted)) assertContentEquals(body, record.requestBody?.bytes, record.url)
            assertContentEquals(savedResponse, records[1].responseBody?.bytes)
            assertEquals(HopiStore.SKIPPED_STORE_BACKLOG, records[2].responseBody?.skippedReason)
        }
    }

    @Test
    fun postsWhoseStartsAreInTheStoreCountOnlyTheirResponsesBehindIt() = runBlocking<Unit> {
        HeldStore(maxTotalBytes = 1000).use { held ->
            held.store.attachViewer()
            val answer = CompletableDeferred<Unit>()
            val client = savingClient(held.store) { answer.await() }
            val calls = posted.mapIndexed { i, body ->
                async(Dispatchers.Default) { client.post("https://api.example.com/items?i=$i") { setBody(ByteArrayContent(body, ContentType.Text.Plain)) } }
            }
            // A viewer shows the three starts: they are in the store when the consumer falls behind.
            withTimeout(10_000) { held.store.transactions.first { it.size == 3 } }
            held.hold()
            answer.complete(Unit)
            calls.awaitAll()
            held.release()
            // 900 bytes queued, not 1200: every end kept both bodies.
            for (record in held.ends(3)) {
                assertContentEquals(posted[record.url.substringAfter("i=").toInt()], record.requestBody?.bytes, record.url)
                assertContentEquals(savedResponse, record.responseBody?.bytes, record.url)
            }
        }
    }

    @Test
    fun redactionNoticeIsWrittenOnlyWithTheSummarySinkAndOncePerProcess() = runBlocking<Unit> {
        val captured = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(captured, true, "UTF-8"))
        try {
            TestStore().use { t ->
                mockClient(t.store, { unsafeDisableRedaction() }) { respond("") }
                assertEquals("", captured.toString("UTF-8"), "logSink = Off writes nothing")
                mockClient(t.store) { respond("") }
                mockClient(t.store, { logSink = HopiLogSink.Summary; unsafeDisableRedaction() }) { respond("") }
                assertEquals("", captured.toString("UTF-8"), "another installation keeps redaction on")
            }
            TestStore().use { t ->
                mockClient(t.store, { logSink = HopiLogSink.Summary; unsafeDisableRedaction() }) { respond("") }
                mockClient(t.store, { logSink = HopiLogSink.Summary; unsafeDisableRedaction() }) { respond("") }
            }
        } finally {
            System.setErr(original)
        }
        assertEquals(
            listOf("{\"hopi\":1,\"notice\":\"redaction disabled by unsafeDisableRedaction()\"}"),
            captured.toString("UTF-8").lines().filter { it.isNotEmpty() },
        )
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** Decodes gzip at `HttpReceivePipeline.State`, the way `ContentEncoding` does. */
    @OptIn(DelicateCoroutinesApi::class, InternalAPI::class)
    private fun installGunzip(client: HttpClient) {
        client.receivePipeline.intercept(HttpReceivePipeline.State) { response ->
            if (response.headers[HttpHeaders.ContentEncoding] != "gzip") return@intercept
            val headers = Headers.build {
                response.headers.forEach { name, values ->
                    if (!name.equals(HttpHeaders.ContentEncoding, true) && !name.equals(HttpHeaders.ContentLength, true)) appendAll(name, values)
                }
            }
            val decoded = response.call.replaceResponse(headers) {
                val source = rawContent
                GlobalScope.writer(Dispatchers.IO) {
                    val compressed = source.toByteArray()
                    channel.writeFully(GZIPInputStream(ByteArrayInputStream(compressed)).readBytes())
                }.channel
            }
            proceedWith(decoded.response)
        }
    }

    private fun writeChannelContent(bytes: ByteArray) = object : OutgoingContent.WriteChannelContent() {
        override val contentType = ContentType.Text.Plain
        override suspend fun writeTo(channel: ByteWriteChannel) {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(bytes.size, offset + 7_000)
                channel.writeFully(bytes, offset, end)
                channel.flush()
                offset = end
            }
        }
    }

    private fun readChannelContent(bytes: ByteArray) = object : OutgoingContent.ReadChannelContent() {
        override val contentLength = bytes.size.toLong()
        override fun readFrom(): ByteReadChannel = ByteReadChannel(bytes)
    }
}
