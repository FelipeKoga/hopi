package dev.koga.hopi.start

import dev.koga.hopi.api.HopiRuntimes
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ktor.HopiLogSink
import dev.koga.hopi.rules.HopiRules
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
// An alias: an explicit import would outrank this package's own Hopi, the entry.
import dev.koga.hopi.ktor.Hopi as HopiKtor

/**
 * The JVM entry, end to end, in one test and in this order: one JVM runs every test class of this
 * module, and the first valid start is the process's. Its summary log stays on (the default), so
 * the process's summary lines, on stderr, start at the first client's installation.
 */
class JvmStartTest {
    @Test
    fun theStartRegistersTheRuntimesAndAClientBuiltAfterItIsRecorded() = runBlocking<Unit> {
        Hopi.start { enabled = false }
        assertFalse(Hopi.isStarted)
        // A disabled start does nothing at all, not even the rules runtime's registration.
        assertNull(HopiRuntimes.rules)
        // Invalid caps: the rules runtime is registered first, then nothing starts (a notice line).
        Hopi.start { maxEntries = 0 }
        assertNotNull(HopiRuntimes.rules)
        assertFalse(Hopi.isStarted)

        Hopi.start { redactQueryParameters("start_token") }
        assertTrue(Hopi.isStarted)
        assertNotNull(HopiRuntimes.rules)
        assertNotNull(HopiRuntimes.perf)
        assertNotNull(HopiRuntimes.ktorHooks)
        assertTrue(HopiRuntimes.networkStarted)
        assertTrue(HopiRules.mock("*/x") >= 0)

        // Built after the start: the plugin finds the recorder, which writes to the default store,
        // and the start's installation comes first, so the start's summary lines and redaction reach
        // a client that logs nothing itself and has no lists of its own.
        val marker = UUID.randomUUID().toString()
        val stderr = ByteArrayOutputStream()
        val previous = System.err
        System.setErr(PrintStream(stderr, true, "UTF-8"))
        val line = try {
            HttpClient(MockEngine { respond("ok") }) { install(HopiKtor) { logSink = HopiLogSink.Off } }.use { client ->
                assertEquals("ok", client.get("https://api.example.com/y?m=$marker&start_token=s3cr3t").bodyAsText())
            }
            val records = withTimeout(10_000) {
                HopiStore.default.transactions.first { list -> list.any { marker in it.url && it.state.isTerminal } }
            }.filter { marker in it.url }
            assertEquals(1, records.size)
            assertEquals(200, records.single().responseCode)
            // A store listener writes the line on another thread, right after that snapshot.
            withTimeout(10_000) {
                while (marker !in stderr.toString("UTF-8")) delay(10)
                stderr.toString("UTF-8").lines().first { marker in it }
            }
        } finally {
            System.setErr(previous)
        }
        assertTrue("start_token=<redacted>" in line, line)
        assertFalse("s3cr3t" in line, line)
    }
}
