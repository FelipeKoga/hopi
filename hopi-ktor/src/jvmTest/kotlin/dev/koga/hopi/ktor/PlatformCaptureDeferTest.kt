package dev.koga.hopi.ktor

import dev.koga.hopi.core.capture.PlatformCapture
import dev.koga.hopi.core.model.TransactionSource
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

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
    fun coverMatchesByPackagePrefixOnly() {
        PlatformCapture.cover("io.ktor.client.engine.darwin.")
        PlatformCapture.cover("io.ktor.client.engine.darwin.")
        assertTrue(PlatformCapture.covers("io.ktor.client.engine.darwin.DarwinClientEngine"))
        assertEquals(false, PlatformCapture.covers("io.ktor.client.engine.okhttp.OkHttpEngine"))
        assertEquals(false, PlatformCapture.covers(null))
    }
}
