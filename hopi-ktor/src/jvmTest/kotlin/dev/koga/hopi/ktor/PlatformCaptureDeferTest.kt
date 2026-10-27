package dev.koga.hopi.ktor

import dev.koga.hopi.core.capture.PlatformCapture
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionEvent
import dev.koga.hopi.core.model.TransactionSource
import dev.koga.hopi.core.model.TransactionState
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.utils.io.ByteReadChannel
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * One record per request: when a platform recorder covers the client's engine (URLSession capture
 * covers Darwin), the plugin records nothing for it and leaves the call untouched; otherwise it
 * records as before, marked `ktor`. MockEngine stands in for a covered engine here.
 */
class PlatformCaptureDeferTest {
    private val mockEngine = "io.ktor.client.engine.mock."

    @AfterTest
    fun reset() = PlatformCapture.resetForTests()

    @Test
    fun anUncoveredEngineIsRecordedAsKtor() = runBlocking<Unit> {
        TestStore().use { t ->
            val client = mockClient(t.store) { respond("ok", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain")) }
            client.get("https://api.example.com/plain").bodyAsText()
            assertEquals(TransactionSource.KTOR, t.awaitTerminal().single().source)
        }
    }

    @Test
    fun aCoveredEngineIsLeftToThePlatformRecorderAndTheCallIsUnchanged() = runBlocking<Unit> {
        TestStore().use { t ->
            val sent = mutableListOf<ByteArray>()
            val payload = asciiBytes(300_000)
            val client = mockClient(t.store) { request ->
                sent += request.body.toByteArray()
                respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
            }
            // Covered after the client was built: the check is per request.
            PlatformCapture.cover(mockEngine)
            assertTrue(PlatformCapture.covers(client.engine::class.qualifiedName))
            val body = "request body".encodeToByteArray()
            assertContentEquals(payload, client.post("https://api.example.com/covered") { setBody(body) }.bodyAsBytes())
            assertContentEquals(body, sent.single())
            delay(200)
            assertEquals(emptyList(), t.store.transactions.value, "the plugin recorded a covered request")
            // Another engine stays the plugin's.
            PlatformCapture.resetForTests()
            client.get("https://api.example.com/again").bodyAsBytes()
            assertEquals("https://api.example.com/again", t.awaitTerminal().single().url)
        }
    }

    @Test
    fun aRequestWithItsOwnAcceptEncodingIsRecordedHereAndMarkedForARecorderThatHonorsTheMarker() = runBlocking<Unit> {
        TestStore().use { t ->
            val markers = mutableListOf<String?>()
            val client = mockClient(t.store) { request ->
                markers += request.headers[PlatformCapture.PASS_THROUGH_HEADER]
                respond("ok", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
            }
            // Android's OkHttp capture: the platform stack decodes only a body it asked to be encoded.
            PlatformCapture.coverWithPassThrough(mockEngine)
            assertTrue(PlatformCapture.honorsPassThrough(client.engine::class.qualifiedName))
            client.get("https://api.example.com/decoded").bodyAsText()
            delay(200)
            assertEquals(emptyList(), t.store.transactions.value, "no Accept-Encoding: the platform's record")
            assertEquals(listOf<String?>(null), markers)

            // ContentEncoding (or the app) names Accept-Encoding: Ktor decodes, so the plugin records it.
            client.get("https://api.example.com/encoded") { header(HttpHeaders.AcceptEncoding, "gzip") }.bodyAsText()
            val record = t.awaitTerminal().single()
            assertEquals(TransactionSource.KTOR, record.source)
            assertEquals("https://api.example.com/encoded", record.url)
            assertTrue(record.requestHeaders.none { it.first.equals(PlatformCapture.PASS_THROUGH_HEADER, ignoreCase = true) }, record.requestHeaders.toString())
            assertEquals("1", markers.last())
        }
    }

    @Test
    fun aRecorderWithoutTheMarkerKeepsEveryRequestOfItsEngine() = runBlocking<Unit> {
        TestStore().use { t ->
            val markers = mutableListOf<String?>()
            val client = mockClient(t.store) { request ->
                markers += request.headers[PlatformCapture.PASS_THROUGH_HEADER]
                respond("ok", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
            }
            // URLSession capture: it records what URLSession decoded, Accept-Encoding or not, and
            // nothing would remove a marker from the wire.
            PlatformCapture.cover(mockEngine)
            client.get("https://api.example.com/encoded") { header(HttpHeaders.AcceptEncoding, "gzip") }.bodyAsText()
            delay(200)
            assertEquals(emptyList(), t.store.transactions.value)
            assertEquals(listOf<String?>(null), markers)
        }
    }

    @Test
    fun aRequestTheEngineHasWhenItsEngineBecomesCoveredIsWithdrawnNotRecordedTwice() = runBlocking<Unit> {
        TestStore().use { t ->
            val events = ConcurrentLinkedQueue<TransactionEvent>()
            t.store.addEventListener { events += it }
            val payload = asciiBytes(100_000)
            val client = mockClient(t.store) { request ->
                // URLSession capture's start() while the engine has the request: its hook may see it.
                if (request.url.encodedPath == "/in-flight") PlatformCapture.cover(mockEngine)
                // No Content-Length: the body is teed, and its end would come before the call returns.
                respond(ByteReadChannel(payload), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
            }
            assertContentEquals(payload, client.get("https://api.example.com/in-flight").bodyAsBytes())
            delay(200)
            assertEquals(emptyList(), t.store.transactions.value, "the plugin kept a record the platform recorder may also have")
            assertEquals(emptyList(), events.toList(), "nobody watched: nothing of the withdrawn record went out")

            // A failure before any response is withdrawn the same way.
            PlatformCapture.resetForTests()
            val failing = mockClient(t.store) {
                PlatformCapture.cover(mockEngine)
                throw IOException("connection reset")
            }
            assertFailsWith<Throwable> { failing.get("https://api.example.com/reset") }
            delay(200)
            assertEquals(emptyList(), t.store.transactions.value)

            // The next request of an engine still not covered is the plugin's, as before.
            PlatformCapture.resetForTests()
            client.get("https://api.example.com/after").bodyAsBytes()
            assertEquals("https://api.example.com/after", t.awaitTerminal().single().url)
        }
    }

    @Test
    fun aWithdrawnRequestAViewerShowedPendingEndsThereWithTheReason() = runBlocking<Unit> {
        TestStore().use { t ->
            t.store.attachViewer()
            val ends = Channel<HttpTransaction>(Channel.UNLIMITED)
            t.store.addEventListener { if (it is TransactionEvent.Completed) ends.trySend(it.transaction) }
            val client = mockClient(t.store) {
                // The viewer has shown the request pending before the platform recorder starts.
                withTimeout(10_000) { t.store.transactions.first { it.isNotEmpty() } }
                PlatformCapture.cover(mockEngine)
                respond("ok", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
            }
            assertEquals("ok", client.get("https://api.example.com/watched").bodyAsText())
            val end = withTimeout(10_000) { ends.receive() }
            assertEquals(TransactionState.Failed, end.state)
            assertEquals(WITHDRAWN, end.error)
            assertEquals(TransactionSource.KTOR, end.source)
            delay(200)
            assertEquals(emptyList(), t.store.transactions.value, "the withdrawn record is not kept")
            assertTrue(ends.tryReceive().isFailure, "one end only")
        }
    }

    @Test
    fun coverMatchesByPackagePrefixOnly() {
        PlatformCapture.cover("io.ktor.client.engine.darwin.")
        PlatformCapture.cover("io.ktor.client.engine.darwin.")
        assertTrue(PlatformCapture.covers("io.ktor.client.engine.darwin.DarwinClientEngine"))
        assertEquals(false, PlatformCapture.covers("io.ktor.client.engine.okhttp.OkHttpEngine"))
        assertEquals(false, PlatformCapture.covers(null))
    }
}
