package dev.koga.hopi.rules

import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.rules.FailKind
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleSource
import dev.koga.hopi.core.rules.RuleState
import dev.koga.hopi.core.rules.UrlRuleRequest
import dev.koga.hopi.core.store.HopiStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class HopiRulesTest {
    @AfterTest
    fun tearDown() = HopiRules.clear()

    private fun first(source: RuleSource, method: String, url: String, headers: List<Pair<String, String>> = emptyList()) =
        RuleEngine.state.firstMatch(source, UrlRuleRequest(method, url, headers))

    @Test
    fun aMockIsLiveWhenTheCallReturns() {
        val id = HopiRules.mock("GET */users/*") {
            status = 500
            header("Content-Type", "application/json")
            body("""{"error":"boom"}""")
            delay(beforeResponse = 2.seconds)
            times = 1
            label = "users 500"
        }
        val rule = assertNotNull(first(RuleSource.OKHTTP, "GET", "https://api.example.com/users/42"))
        assertEquals(id, rule.id)
        assertEquals(500, rule.mock?.status)
        assertEquals("""{"error":"boom"}""", rule.mock?.body?.bytes?.decodeToString())
        assertEquals(2000, rule.delay?.beforeResponseMs)
        assertEquals("delay,mock", rule.applied)
        // times = 1: used up.
        assertSame(RuleState.EMPTY, RuleEngine.state)
        val info = HopiRules.list().single()
        assertTrue(info.exhausted)
        assertEquals("users 500", info.label)
        assertEquals("GET */users/*", info.match)
        assertTrue(info.action.startsWith("mock 500"), info.action)
    }

    @Test
    fun failDelayAndEditRules() {
        HopiRules.fail("POST */upload", HopiFailure.Timeout) { sources(HopiSource.Ktor) }
        HopiRules.delay("*/slow") { beforeRequest = 300.milliseconds }
        HopiRules.edit("*/v1/**") {
            request {
                path = "/v2/users"
                setHeader("X-Env", "staging")
            }
            response { status = 503 }
        }
        assertEquals(FailKind.TIMEOUT, first(RuleSource.KTOR, "POST", "https://h/upload")?.fail)
        assertEquals(null, first(RuleSource.OKHTTP, "POST", "https://h/upload"), "ktor only")
        assertEquals(300, first(RuleSource.OKHTTP, "GET", "https://h/slow")?.delay?.beforeRequestMs)
        val edit = assertNotNull(first(RuleSource.OKHTTP, "GET", "https://h/v1/x"))
        assertEquals("/v2/users", edit.requestEdit?.path)
        assertEquals(503, edit.responseEdit?.status)
        assertTrue(HopiRules.list().last().unsupported.single().startsWith("urlsession:"), "response edits are unsupported on URLSession")
    }

    @Test
    fun predicatesFromTheBuilder() {
        HopiRules.mock("*/search") {
            query("page", "2")
            noQuery("debug")
            matchHeader("X-Env", "stag*")
        }
        assertNotNull(first(RuleSource.KTOR, "GET", "https://h/search?page=2", listOf("X-Env" to "staging")))
        assertEquals(null, first(RuleSource.KTOR, "GET", "https://h/search?page=2&debug", listOf("X-Env" to "staging")))
        assertEquals(null, first(RuleSource.KTOR, "GET", "https://h/search?page=2"))
    }

    @Test
    fun enableRemoveClear() {
        val id = HopiRules.mock("*/a")
        assertTrue(HopiRules.setEnabled(id, false))
        assertSame(RuleState.EMPTY, RuleEngine.state)
        assertFalse(HopiRules.list().single().enabled)
        assertTrue(HopiRules.remove(id))
        assertFalse(HopiRules.remove(id))
        assertFalse(HopiRules.setEnabled(id, true))
        HopiRules.mock("*/b")
        HopiRules.clear()
        assertEquals(emptyList(), HopiRules.list())
    }

    @Test
    fun validationThrowsIllegalArgumentAndLimitsIllegalState() {
        assertFailsWith<IllegalArgumentException> { HopiRules.mock("ftp://h/a") }
        assertFailsWith<IllegalArgumentException> { HopiRules.mock("*/a") { header("Set-Cookie", "a=b") } }
        assertFailsWith<IllegalArgumentException> { HopiRules.delay("*/a") {} }
        assertFailsWith<IllegalArgumentException> { HopiRules.mock("*/a") { status = 204; body("x") } }
        assertFailsWith<IllegalStateException> { HopiRules.mock("*/a") { body(ByteArray(1024 * 1024 + 1)) } }
        repeat(64) { HopiRules.fail("*/r$it", HopiFailure.Reset) }
        assertFailsWith<IllegalStateException> { HopiRules.fail("*/r64", HopiFailure.Reset) }
    }

    @Test
    fun mockFromARecordedResponse() {
        val store = HopiStore.default
        val id = store.nextId()
        store.submit(
            HttpTransaction.pending(id, 0, "GET", "https://h/a", emptyList(), null)
                .completed(201, listOf("Content-Type" to "text/plain", "Set-Cookie" to "s=1"), CapturedBody("hi".encodeToByteArray(), 2, false, "text/plain", null), 3, null),
        )
        runBlocking { withTimeout(10_000) { store.transactions.first { list -> list.any { it.id == id } } } }
        HopiRules.mock("*/a") { from(id) }
        val mock = assertNotNull(first(RuleSource.KTOR, "GET", "https://h/a")?.mock)
        assertEquals(201, mock.status)
        assertEquals(listOf("Content-Type" to "text/plain"), mock.headers)
        assertEquals("hi", mock.body?.bytes?.decodeToString())
        assertFailsWith<IllegalArgumentException> { HopiRules.mock("*/a") { from(Long.MAX_VALUE) } }
    }
}
