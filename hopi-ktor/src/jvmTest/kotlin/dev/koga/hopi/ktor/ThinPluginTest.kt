package dev.koga.hopi.ktor

import dev.koga.hopi.api.HopiRuntimes
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.api.ClientPluginBuilder
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The thin plugin alone, with no network runtime: it installs whatever recorder hopi-api's Ktor
 * slot holds, once per client, and nothing when the slot is empty, so the client is then exactly as
 * without it. No core class is on this module's classpath.
 */
class ThinPluginTest {
    @BeforeTest
    fun emptyTheRegistry() {
        HopiRuntimes.resetForTests()
    }

    @AfterTest
    fun emptyTheRegistryAgain() {
        HopiRuntimes.resetForTests()
    }

    @Test
    fun withNothingRegisteredEverySetterIsAcceptedAndTheClientIsUnchanged() = runTest {
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
                // Invalid caps too: nothing reads them without a recorder.
                maxEntries = 0
                maxTotalBytes = -1
                maxBodyBytes = -1
                redactHeaders("X-Api-Key")
                redactQueryParameters("token")
                unsafeDisableRedaction()
                logSink = HopiLogSink.Off
                logSink = HopiLogSink.Summary
                agentBridge = true
                rules = false
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
        assertEquals(emptyList(), hopiPhases(client))
        client.close()
    }

    @Test
    fun theConfigKeepsWhatWasSet() {
        val config = HopiConfig()
        assertTrue(config.enabled)
        assertEquals(500, config.maxEntries)
        assertEquals(16L * 1024 * 1024, config.maxTotalBytes)
        assertEquals(256L * 1024, config.maxBodyBytes)
        assertEquals(HopiLogSink.Summary, config.logSink)
        assertFalse(config.agentBridge)
        assertTrue(config.rules)
        assertEquals(emptyList(), config.redactedHeaders)
        assertEquals(emptyList(), config.redactedQueryParameters)
        assertFalse(config.redactionDisabled)

        config.maxEntries = 7
        config.rules = false
        config.agentBridge = true
        config.redactHeaders("X-Api-Key")
        config.redactHeaders("X-Trace", "X-Session")
        config.redactQueryParameters("token")
        config.unsafeDisableRedaction()
        assertEquals(7, config.maxEntries)
        assertFalse(config.rules)
        assertTrue(config.agentBridge)
        assertEquals(listOf("X-Api-Key", "X-Trace", "X-Session"), config.redactedHeaders)
        assertEquals(listOf("token"), config.redactedQueryParameters)
        assertTrue(config.redactionDisabled)
    }

    @Test
    fun registeredHooksGetEachClientsBuilderOnceAndNeverWithEnabledFalse() = runTest {
        val hooks = RecordingHooks()
        assertTrue(HopiRuntimes.registerKtorHooks(hooks))
        val first = HttpClient(MockEngine { respond("a") }) { install(Hopi) { maxBodyBytes = 1 } }
        val second = HttpClient(MockEngine { respond("b") }) { install(Hopi) { maxBodyBytes = 2 } }
        val disabled = HttpClient(MockEngine { respond("c") }) { install(Hopi) { enabled = false; maxBodyBytes = 3 } }
        // Requests install nothing more: the hooks run at each client's install only.
        first.get("https://api.example.com/a")
        second.get("https://api.example.com/b")
        disabled.get("https://api.example.com/c")

        assertEquals(listOf(1L, 2L), hooks.configs.map { it.maxBodyBytes })
        assertSame(first, hooks.clients[0])
        assertSame(second, hooks.clients[1])
        assertNotNull(disabled.pluginOrNull(Hopi))
        listOf(first, second, disabled).forEach { it.close() }
    }

    @Test
    fun hooksRegisteredAfterAClientsInstallDoNotReachThatClient() = runTest {
        val client = HttpClient(MockEngine { respond("real") }) { install(Hopi) }
        val hooks = RecordingHooks()
        assertTrue(HopiRuntimes.registerKtorHooks(hooks))
        assertContentEquals("real".encodeToByteArray(), client.get("https://api.example.com/x").bodyAsBytes())
        assertEquals(emptyList(), hooks.configs)
        assertEquals(emptyList(), hopiPhases(client))
        client.close()
    }

    @Test
    fun aSlotHoldingSomethingElseInstallsNothing() = runTest {
        assertTrue(HopiRuntimes.registerKtorHooks("not the hooks"))
        val client = HttpClient(MockEngine { respond("real") }) { install(Hopi) }
        assertContentEquals("real".encodeToByteArray(), client.get("https://api.example.com/x").bodyAsBytes())
        assertNotNull(client.pluginOrNull(Hopi))
        assertEquals(emptyList(), hopiPhases(client))
        client.close()
    }

    @Test
    fun hooksOfAnotherVersionLeaveTheClientUnrecordedInsteadOfFailingIt() = runTest {
        // What a hopi-network of another version throws at its first missing member.
        assertTrue(HopiRuntimes.registerKtorHooks(SkewedHooks()))
        val client = HttpClient(MockEngine { respond("real") }) { install(Hopi) }
        assertContentEquals("real".encodeToByteArray(), client.get("https://api.example.com/x").bodyAsBytes())
        assertNotNull(client.pluginOrNull(Hopi))
        assertEquals(emptyList(), hopiPhases(client))
        client.close()
    }

    @Test
    fun hooksWhoseInitializerThrewFailTheClientInsteadOfHidingIt() {
        // A bug, not a version skew: the catch must not turn it into a silently unrecorded client.
        assertTrue(HopiRuntimes.registerKtorHooks(BrokenHooks()))
        val thrown = assertFails { HttpClient(MockEngine { respond("real") }) { install(Hopi) } }
        assertTrue(generateSequence(thrown) { it.cause }.any { it is ExceptionInInitializerError }, thrown.toString())
    }

    @Test
    fun noCoreClassIsOnTheClasspath() {
        // The plugin has no store to hold entries: the core classes cannot even be loaded.
        assertFailsWith<ClassNotFoundException> { Class.forName(CORE_PACKAGE + ".store.HopiStore") }
        val classpath = System.getProperty("java.class.path").split(java.io.File.pathSeparatorChar)
        assertEquals(emptyList(), classpath.filter { "hopi-core" in it || "hopi-network" in it }, "a runtime on the classpath")
    }

    /** The pipeline phases the recorder would have added (`HopiRawResponse` and its two siblings). */
    private fun hopiPhases(client: HttpClient): List<String> =
        listOf(client.requestPipeline.items, client.sendPipeline.items, client.receivePipeline.items, client.responsePipeline.items)
            .flatten().map { it.name }.filter { it.startsWith("Hopi") }

    /** Every install it got: the client's configuration and the client. */
    private class RecordingHooks : HopiKtorHooks {
        val configs = mutableListOf<HopiConfig>()
        val clients = mutableListOf<HttpClient>()

        override fun install(plugin: ClientPluginBuilder<HopiConfig>) {
            configs += plugin.pluginConfig
            clients += plugin.client
        }
    }

    private class SkewedHooks : HopiKtorHooks {
        override fun install(plugin: ClientPluginBuilder<HopiConfig>) {
            throw AbstractMethodError("dev.koga.hopi.ktor.HopiConfig.getRedactedHeaders")
        }
    }

    /** What a recorder whose static initializer threw an exception surfaces at its first use. */
    private class BrokenHooks : HopiKtorHooks {
        override fun install(plugin: ClientPluginBuilder<HopiConfig>) {
            throw ExceptionInInitializerError(IllegalStateException("the recorder's initializer threw"))
        }
    }

    private companion object {
        /** Spelled in two parts: nothing in this module names core's package. */
        const val CORE_PACKAGE = "dev.koga.hopi" + ".core"
    }
}
