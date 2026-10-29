package dev.koga.hopi.urlsession

import dev.koga.hopi.core.model.TransactionSource
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.ktor.Hopi
import dev.koga.hopi.ktor.HopiLogSink
import dev.koga.hopi.network.internal.UrlSessionCapture
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * Ktor's Darwin engine sends through URLSession: with the plugin installed and capture started,
 * each request is recorded once, by URLSession capture (the plugin defers to it).
 */
class KtorDarwinTest {
    init {
        Capture.ensureStarted()
    }

    private fun client() = HttpClient(Darwin) {
        install(Hopi) { logSink = HopiLogSink.Off }
    }

    @Test
    fun oneRecordPerRequestFromUrlSessionCapture() = runBlocking {
        client().use { client ->
            val url = Capture.url("/bytes?n=12345")
            val watch = Watch(url)
            assertContentEquals(expectedBytes(12345), client.get(url).bodyAsBytes())
            val tx = watch.single()
            assertEquals(TransactionSource.URL_SESSION, tx.source)
            assertContentEquals(expectedBytes(12345), tx.responseBody!!.bytes)
        }
    }

    @Test
    fun concurrentPostsKeepTheirOwnBodies() = runBlocking {
        client().use { client ->
            val urls = List(2) { Capture.url("/echo") }
            val watches = urls.map { Watch(it) }
            val bodies = List(2) { i -> "{\"post\":$i,\"pad\":\"${"x".repeat(1000 * (i + 1))}\"}".encodeToByteArray() }
            urls.mapIndexed { i, url ->
                async {
                    client.post(url) {
                        contentType(ContentType.Application.Json)
                        setBody(bodies[i])
                    }.bodyAsBytes()
                }
            }.awaitAll().forEachIndexed { i, echoed -> assertContentEquals(bodies[i], echoed) }
            watches.forEachIndexed { i, watch ->
                val tx = watch.single()
                assertEquals(TransactionSource.URL_SESSION, tx.source)
                assertContentEquals(bodies[i], tx.requestBody!!.bytes)
                assertContentEquals(bodies[i], tx.responseBody!!.bytes)
            }
        }
    }

    @Test
    fun aChannelBodyTravelsAsAStreamAndIsNotRead() = runBlocking {
        client().use { client ->
            val url = Capture.url("/echo")
            val watch = Watch(url)
            val body = expectedBytes(20000)
            val echoed = client.post(url) { setBody(ByteReadChannel(body)) }.bodyAsBytes()
            assertContentEquals(body, echoed)
            assertEquals(UrlSessionCapture.SKIPPED_STREAM, watch.single().requestBody!!.skippedReason)
        }
    }

    @Test
    fun redirectsAreRecordedPerHop() = runBlocking {
        client().use { client ->
            // Ktor's Darwin delegate stops URLSession's own redirect and HttpRedirect sends the next
            // hop as a task of its own: one record per hop, as with the plugin.
            val target = Capture.url("/bytes?n=11")
            val url = Capture.url("/redirect?to=" + target.substringAfter(Capture.port.toString()).replace("?", "%3F").replace("&", "%26").replace("=", "%3D"))
            val first = Watch(url)
            val second = Watch(target)
            assertContentEquals(expectedBytes(11), client.get(url).bodyAsBytes())
            val hop = first.single()
            assertEquals(TransactionState.Complete, hop.state)
            assertEquals(302, hop.responseCode)
            assertEquals(null, hop.finalUrl)
            val final = second.single()
            assertEquals(200, final.responseCode)
            assertContentEquals(expectedBytes(11), final.responseBody!!.bytes)
        }
    }
}
