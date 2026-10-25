package dev.koga.hopi.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HopiNoopTest {
    @Test
    fun everySetterIsAcceptedAndTheClientIsUnchanged() = runTest {
        val requestBody = ByteArray(64 * 1024) { (it % 251).toByte() }
        val responseBody = """{"name":"Ada"}""".encodeToByteArray()
        var received: ByteArray? = null
        val client = HttpClient(
            MockEngine { request ->
                received = request.body.toByteArray()
                respond(responseBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            install(Hopi) {
                enabled = true
                maxEntries = 10
                maxTotalBytes = 1024
                maxBodyBytes = 16
                redactHeaders("X-Api-Key")
                redactQueryParameters("token")
                unsafeDisableRedaction()
                logSink = HopiLogSink.Off
                logSink = HopiLogSink.Summary
                agentBridge = true
            }
        }

        val response = client.post("https://api.example.com/upload?token=secret") {
            setBody(requestBody)
            headers.append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
        }

        assertNotNull(client.pluginOrNull(Hopi))
        assertContentEquals(requestBody, received)
        assertEquals(HttpStatusCode.OK, response.status)
        assertContentEquals(responseBody, response.bodyAsBytes())
        client.close()
    }

    @Test
    fun theConfigKeepsWhatWasSet() {
        val config = HopiConfig()
        assertEquals(500, config.maxEntries)
        assertEquals(16L * 1024 * 1024, config.maxTotalBytes)
        assertEquals(256L * 1024, config.maxBodyBytes)
        assertEquals(HopiLogSink.Summary, config.logSink)
        assertFalse(config.agentBridge)
        assertTrue(config.rules)

        config.maxEntries = 7
        config.rules = false
        assertFalse(config.rules)
        config.agentBridge = true
        assertEquals(7, config.maxEntries)
        assertEquals(true, config.agentBridge)
    }

    @Test
    fun hopiCoreIsNotOnTheRuntimeClasspath() {
        // There is no store to hold entries: the core classes cannot even be loaded.
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.koga.hopi.core.store.HopiStore") }
        val classpath = System.getProperty("java.class.path").split(java.io.File.pathSeparatorChar)
        val core = classpath.filter { "hopi-core" in it }
        assertEquals(emptyList(), core, "hopi-core on the classpath")
    }
}
