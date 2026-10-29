package dev.koga.hopi.network.internal

import dev.koga.hopi.api.HopiRuntimes
import dev.koga.hopi.core.rules.MatchShorthand
import dev.koga.hopi.core.rules.MockSpec
import dev.koga.hopi.core.rules.RuleBody
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleResult
import dev.koga.hopi.core.rules.RuleSpec
import dev.koga.hopi.core.start.HopiToolContext
import dev.koga.hopi.core.start.StartConfig
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ktor.Hopi
import dev.koga.hopi.ktor.HopiLogSink
import dev.koga.hopi.network.internal.ktor.KtorHooks
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The JVM network tool, which `hopi-start`'s `Hopi.start` lists: it registers the Ktor plugin's
 * recorder with the start's configuration and its own `rules`, and a client built through the slot
 * makes the start's installation first. One JVM runs every test class of this module, so the start
 * configuration keeps the summary log, the bridge and redaction as the moved plugin tests need them
 * unused (their once-per-process bridge request, summary sink and redaction notice), and the
 * clients log nothing (the default `Summary` would start the process's summary sink).
 */
class JvmNetworkToolTest {
    @BeforeTest
    @AfterTest
    fun reset() {
        // The slot is write-once: each start below needs it empty.
        HopiRuntimes.resetForTests()
        RuleEngine.resetForTests()
    }

    @Test
    fun theToolRegistersTheRecorderAndItsClientsGetTheStartsRedactionAndTheRules() = runBlocking<Unit> {
        assertEquals("ktor", NetworkTool(rules = true).start(HopiToolContext(START, platform = null)))
        val hooks = assertIs<KtorHooks>(HopiRuntimes.ktorHooks)
        assertSame(START, hooks.start)
        assertTrue(hooks.rules)
        assertTrue(HopiRuntimes.networkStarted)
        assertFalse(HopiRuntimes.okHttpInstrumented)

        // A client with no redaction lists of its own: the start's installation came first.
        HttpClient(MockEngine { respond("ok") }) { install(Hopi) { logSink = HopiLogSink.Off } }.use { client ->
            assertEquals("ok", client.get("https://api.example.com/a").bodyAsText())
        }
        val redactor = HopiStore.default.redactor
        assertTrue(redactor.isRedactedHeader("X-Start-Secret"))
        assertEquals("https://api.example.com/a?start_token=<redacted>&q=1", redactor.url("https://api.example.com/a?start_token=abc&q=1"))

        // And the rules apply, the tool's and the client's `rules` both true: the positive control
        // of the rules = false test below, through the production hooks.
        val added = RuleEngine.add(RuleSpec(MatchShorthand.parse("GET */x", null), mock = MockSpec(200, body = RuleBody.text("mocked"))))
        assertIs<RuleResult.Ok>(added)
        HttpClient(MockEngine { respond("real") }) { install(Hopi) { logSink = HopiLogSink.Off } }.use { client ->
            assertEquals("mocked", client.get("https://api.example.com/x").bodyAsText())
        }
    }

    @Test
    fun withRulesFalseTheRecorderAppliesNoRule() = runBlocking<Unit> {
        assertEquals("ktor", NetworkTool(rules = false).start(HopiToolContext(START, platform = null)))
        assertFalse(assertIs<KtorHooks>(HopiRuntimes.ktorHooks).rules)
        val added = RuleEngine.add(RuleSpec(MatchShorthand.parse("GET */x", null), mock = MockSpec(200, body = RuleBody.text("mocked"))))
        assertIs<RuleResult.Ok>(added)
        // The client's own `rules` is true (the default): the tool's false wins.
        HttpClient(MockEngine { respond("real") }) { install(Hopi) { logSink = HopiLogSink.Off } }.use { client ->
            assertEquals("real", client.get("https://api.example.com/x").bodyAsText())
        }
    }

    private companion object {
        val START = StartConfig(
            summaryLog = false,
            agentBridge = false,
            redactionDisabled = false,
            redactedHeaders = listOf("X-Start-Secret"),
            redactedQueryParameters = listOf("start_token"),
        )
    }
}
