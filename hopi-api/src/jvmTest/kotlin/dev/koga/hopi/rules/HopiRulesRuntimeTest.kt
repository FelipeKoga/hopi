package dev.koga.hopi.rules

import dev.koga.hopi.api.HopiRuntimes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** `HopiRules` over a registered runtime: a fake that records what each call handed it. */
class HopiRulesRuntimeTest {
    private val runtime = FakeRulesRuntime()

    @BeforeTest
    fun setUp() {
        HopiRuntimes.resetForTests()
        assertTrue(HopiRuntimes.registerRules(runtime))
    }

    @AfterTest
    fun tearDown() {
        HopiRuntimes.resetForTests()
    }

    @Test
    fun everyAddReachesTheRuntimeWithTheBuilderItsBlockFilled() {
        assertEquals(1, HopiRules.mock("GET */a") {
            status = 418
            body(byteArrayOf(1))
        })
        assertEquals(2, HopiRules.fail("*/a", HopiFailure.Timeout) { sources(HopiSource.OkHttp) })
        assertEquals(3, HopiRules.delay("*/a") { beforeResponse = 2.seconds })
        assertEquals(4, HopiRules.edit("*/a") { request { path = "/b" } })
        assertEquals(listOf("mock GET */a", "fail */a Timeout", "delay */a", "edit */a"), runtime.calls)
        val mock = assertIs<HopiMock>(runtime.rules[0])
        assertEquals(418, mock.status)
        assertContentEquals(byteArrayOf(1), mock.bodyBytes)
        assertEquals(listOf(HopiSource.OkHttp), runtime.rules[1].ruleSources)
        assertEquals(2.seconds, assertIs<HopiDelay>(runtime.rules[2]).beforeResponse)
        assertEquals("/b", assertIs<HopiEdit>(runtime.rules[3]).requestEdit?.path)
        assertEquals(0, HopiRuntimes.droppedRules, "nothing dropped")
    }

    @Test
    fun listSetEnabledRemoveAndClearAnswerWhatTheRuntimeAnswers() {
        val rules = HopiRules.list()
        assertEquals(listOf(7L, 8L), rules.map { it.id })
        assertEquals("#7 GET */a  mock 418  3/5", rules[0].toString())
        assertEquals("#8 * */b  fail timeout  2 hits", rules[1].toString())
        assertEquals(listOf("urlsession: no"), rules[1].unsupported)
        assertTrue(HopiRules.setEnabled(7, false))
        assertFalse(HopiRules.setEnabled(9, true))
        assertTrue(HopiRules.remove(7))
        assertFalse(HopiRules.remove(9))
        HopiRules.clear()
        assertEquals(listOf("setEnabled 7 false", "setEnabled 9 true", "remove 7", "remove 9", "clear"), runtime.calls)
    }

    @Test
    fun whatTheRuntimeThrowsReachesTheCaller() {
        HopiRuntimes.resetForTests()
        HopiRuntimes.registerRules(object : HopiRulesRuntime by runtime {
            override fun mock(match: String, rule: HopiMock): Long = throw IllegalArgumentException("match: empty")
        })
        val error = assertFailsWith<IllegalArgumentException> { HopiRules.mock(" ") }
        assertEquals("match: empty", error.message)
    }
}
