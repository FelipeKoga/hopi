package dev.koga.hopi.core.rules

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RuleEngineTest {
    @BeforeTest
    @AfterTest
    fun reset() = RuleEngine.resetForTests()

    private fun mock(match: String, status: Int = 500, priority: Int = 0, times: Int? = null, sources: Set<RuleSource>? = null, enabled: Boolean = true) =
        RuleSpec(MatchShorthand.parse(match, sources), priority = priority, times = times, enabled = enabled, mock = MockSpec(status))

    private fun add(spec: RuleSpec): RuleInfo = assertIs<RuleResult.Ok>(RuleEngine.add(spec), "add").rule

    private fun request(method: String = "GET", url: String = "https://api.example.com/users/42") = UrlRuleRequest(method, url, emptyList())

    @Test
    fun noRuleIsTheEmptyStateByReference() {
        assertSame(RuleState.EMPTY, RuleEngine.state)
        val id = add(mock("GET */users/*")).id
        assertNotSame(RuleState.EMPTY, RuleEngine.state)
        assertTrue(RuleEngine.remove(id))
        assertSame(RuleState.EMPTY, RuleEngine.state)
    }

    @Test
    fun disabledRulesKeepTheStateEmpty() {
        val id = add(mock("*/users/*", enabled = false)).id
        assertSame(RuleState.EMPTY, RuleEngine.state)
        assertIs<RuleResult.Ok>(RuleEngine.update(id, RulePatch(enabled = true)))
        assertNotSame(RuleState.EMPTY, RuleEngine.state)
        assertIs<RuleResult.Ok>(RuleEngine.update(id, RulePatch(enabled = false)))
        assertSame(RuleState.EMPTY, RuleEngine.state)
        assertEquals(1, RuleEngine.list().size)
    }

    @Test
    fun higherPriorityFirstThenNewestFirst() {
        val old = add(mock("*/users/*", status = 501)).id
        val newer = add(mock("*/users/*", status = 502)).id
        assertEquals(newer, RuleEngine.state.firstMatch(RuleSource.OKHTTP, request())?.id)
        val high = add(mock("*/users/*", status = 503, priority = 5)).id
        assertEquals(high, RuleEngine.state.firstMatch(RuleSource.OKHTTP, request())?.id)
        RuleEngine.update(old, RulePatch(priority = 9))
        assertEquals(old, RuleEngine.state.firstMatch(RuleSource.OKHTTP, request())?.id)
    }

    @Test
    fun firstMatchOnlyAndNonMatchingRulesAreSkipped() {
        val users = add(mock("GET */users/*")).id
        add(mock("POST */users/*"))
        assertEquals(users, RuleEngine.state.firstMatch(RuleSource.KTOR, request())?.id)
        assertNull(RuleEngine.state.firstMatch(RuleSource.KTOR, request("DELETE")))
        assertEquals(1, RuleEngine.get(users)?.hits)
    }

    @Test
    fun sourcesLimitTheArrays() {
        add(mock("*/users/*", sources = setOf(RuleSource.KTOR)))
        val state = RuleEngine.state
        assertEquals(1, state.rulesFor(RuleSource.KTOR).size)
        assertEquals(0, state.rulesFor(RuleSource.OKHTTP).size)
        assertNull(state.firstMatch(RuleSource.OKHTTP, request()))
    }

    @Test
    fun aSourceThatCannotApplyARuleNeverSeesItAndAddSaysSo() {
        // Every source named: a rule that names none is checked against the platform's sources only
        // (aRuleNamingNoSourcesIsCheckedAgainstThePlatformsOwn).
        val all = RuleSource.entries.toSet()
        val spec = RuleSpec(MatchShorthand.parse("*/users/*", all), edit = EditSpec(response = ResponseEdit(status = 503)))
        val info = add(spec)
        val unsupported = info.unsupported.single()
        assertEquals(RuleSource.URL_SESSION, unsupported.source)
        assertTrue(unsupported.excluded)
        assertEquals(0, RuleEngine.state.rulesFor(RuleSource.URL_SESSION).size)
        assertEquals(1, RuleEngine.state.rulesFor(RuleSource.OKHTTP).size)
        val redirect = add(mock("*/old", status = 302, sources = all))
        assertEquals(setOf(RuleSource.OKHTTP, RuleSource.URL_CONNECTION, RuleSource.URL_SESSION), redirect.unsupported.map { it.source }.toSet())
        assertTrue(redirect.unsupported.none { it.excluded }, "a mocked 302 still applies")
        assertEquals(1, RuleEngine.state.rulesFor(RuleSource.URL_SESSION).size)
    }

    @Test
    fun aRuleNamingNoSourcesIsCheckedAgainstThePlatformsOwn() {
        // Only the Ktor plugin records on a plain JVM, and it can apply both rules in full.
        assertEquals(setOf(RuleSource.KTOR), platformSources)
        val responseEdit = EditSpec(response = ResponseEdit(status = 503))
        assertEquals(emptyList(), add(RuleSpec(MatchShorthand.parse("*/users/*"), edit = responseEdit)).unsupported)
        assertEquals(emptyList(), add(mock("*/old", status = 302)).unsupported)
        // The arrays still cover every source: URLSession gets the mock, never the response edit.
        assertEquals(1, RuleEngine.state.rulesFor(RuleSource.URL_SESSION).size)
        // A source the rule names is reported whatever the platform.
        val named = add(RuleSpec(MatchShorthand.parse("*/v1/*", setOf(RuleSource.URL_SESSION)), edit = responseEdit))
        assertEquals(listOf(RuleSource.URL_SESSION), named.unsupported.map { it.source })
    }

    @Test
    fun timesExhaustsTheRuleAndTheStateReturnsToEmpty() {
        val id = add(mock("*/users/*", times = 2)).id
        val state = RuleEngine.state
        assertNotNull(state.firstMatch(RuleSource.KTOR, request()))
        assertNotNull(state.firstMatch(RuleSource.KTOR, request()))
        // A reader holding the old state: the claim fails, so the rule does not match.
        assertNull(state.firstMatch(RuleSource.KTOR, request()))
        assertSame(RuleState.EMPTY, RuleEngine.state)
        val info = assertNotNull(RuleEngine.get(id))
        assertTrue(info.exhausted)
        assertEquals(2, info.hits)
        // Resetting the hits brings it back.
        assertIs<RuleResult.Ok>(RuleEngine.update(id, RulePatch(resetHits = true)))
        assertNotNull(RuleEngine.state.firstMatch(RuleSource.KTOR, request()))
    }

    @Test
    fun anExhaustedRuleLetsTheScanGoOnToTheNext() {
        val once = add(mock("*/users/*", times = 1, priority = 1)).id
        val always = add(mock("*/users/*")).id
        val state = RuleEngine.state
        assertEquals(once, state.firstMatch(RuleSource.OKHTTP, request())?.id)
        assertEquals(always, state.firstMatch(RuleSource.OKHTTP, request())?.id)
    }

    @Test
    fun timesUnderSixteenThreadsClaimsExactlyTimesAndRepublishesOnce() {
        val times = 1_000
        val id = add(mock("*/users/*", times = times)).id
        val revisions = AtomicInteger()
        val remove = RuleEngine.addListener { revisions.incrementAndGet() }
        val claimed = AtomicInteger()
        val start = CountDownLatch(1)
        val threads = (1..16).map {
            thread {
                start.await()
                val req = request()
                repeat(500) { if (RuleEngine.state.firstMatch(RuleSource.OKHTTP, req) != null) claimed.incrementAndGet() }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        remove()
        assertEquals(times, claimed.get())
        assertEquals(times.toLong(), RuleEngine.get(id)?.hits)
        assertEquals(1, revisions.get(), "one republish, by the claim that reached times")
        assertSame(RuleState.EMPTY, RuleEngine.state)
    }

    @Test
    fun readersNeverSeeAHalfBuiltSetWhileRulesChange() {
        val stop = AtomicBoolean(false)
        val bad = AtomicInteger()
        val readers = (1..8).map {
            thread {
                val req = request()
                while (!stop.get()) {
                    val s = RuleEngine.state
                    // Every published state has the same rules in every source (all rules name every source).
                    val sizes = s.bySource.map { it.size }.toSet()
                    if (sizes.size > 1) bad.incrementAndGet()
                    s.firstMatch(RuleSource.KTOR, req)
                }
            }
        }
        val writers = (1..4).map { w ->
            thread {
                repeat(300) { i ->
                    val result = RuleEngine.add(mock("*/w$w/$i"))
                    if (result is RuleResult.Ok) RuleEngine.remove(result.rule.id)
                }
            }
        }
        writers.forEach { it.join() }
        stop.set(true)
        readers.forEach { it.join() }
        assertEquals(0, bad.get())
        assertSame(RuleState.EMPTY, RuleEngine.state)
        assertEquals(0, RuleEngine.list().size)
    }

    @Test
    fun ruleCountAndBodyLimits() {
        repeat(RuleLimits.MAX_RULES) { add(mock("*/r$it")) }
        val error = assertIs<RuleResult.Failed>(RuleEngine.add(mock("*/one-too-many"))).error
        assertEquals(RuleErrorCode.LIMIT, error.code)
        assertEquals(RuleLimits.MAX_RULES, RuleEngine.clear())
        val big = RuleBody(ByteArray(RuleLimits.MAX_BODY_BYTES))
        repeat(8) { add(RuleSpec(MatchShorthand.parse("*/b$it"), mock = MockSpec(200, body = big))) }
        val overTotal = assertIs<RuleResult.Failed>(RuleEngine.add(RuleSpec(MatchShorthand.parse("*/b9"), mock = MockSpec(200, body = RuleBody(ByteArray(1))))))
        assertEquals(RuleErrorCode.LIMIT, overTotal.error.code)
        val tooBig = assertIs<RuleResult.Failed>(RuleEngine.add(RuleSpec(MatchShorthand.parse("*/c"), mock = MockSpec(200, body = RuleBody(ByteArray(RuleLimits.MAX_BODY_BYTES + 1))))))
        assertEquals(RuleErrorCode.LIMIT, tooBig.error.code)
    }

    @Test
    fun idsAreMonotonicAndNeverReused() {
        val a = add(mock("*/a")).id
        RuleEngine.remove(a)
        val b = add(mock("*/b")).id
        assertTrue(b > a)
        assertFalse(RuleEngine.remove(a))
        assertEquals(RuleErrorCode.NOT_FOUND, assertIs<RuleResult.Failed>(RuleEngine.update(a, RulePatch(enabled = false))).error.code)
    }

    @Test
    fun updateChangesTimesAndKeepsHits() {
        val id = add(mock("*/users/*", times = 1)).id
        assertNotNull(RuleEngine.state.firstMatch(RuleSource.KTOR, request()))
        assertSame(RuleState.EMPTY, RuleEngine.state)
        assertIs<RuleResult.Ok>(RuleEngine.update(id, RulePatch(times = 3)))
        assertNotNull(RuleEngine.state.firstMatch(RuleSource.KTOR, request()))
        assertEquals(2, RuleEngine.get(id)?.hits)
        assertIs<RuleResult.Ok>(RuleEngine.update(id, RulePatch(clearTimes = true)))
        assertNull(RuleEngine.get(id)?.spec?.times)
        assertEquals(RuleErrorCode.BAD_REQUEST, assertIs<RuleResult.Failed>(RuleEngine.update(id, RulePatch(times = 0))).error.code)
    }

    @Test
    fun rulesAvailableUnlessEveryStartedRecorderTurnedThemOff() {
        assertTrue(RuleEngine.rulesAvailable)
        RuleEngine.recorderStarted(rules = false)
        assertFalse(RuleEngine.rulesAvailable)
        RuleEngine.recorderStarted(rules = true)
        assertTrue(RuleEngine.rulesAvailable)
    }

    @Test
    fun listenersRunAfterEachPublishAndRevisionAdvances() {
        val seen = mutableListOf<RuleState>()
        val remove = RuleEngine.addListener { seen += it }
        val before = RuleEngine.revision.value
        val id = add(mock("*/a")).id
        RuleEngine.remove(id)
        remove()
        add(mock("*/b"))
        assertEquals(2, seen.size)
        assertSame(RuleState.EMPTY, seen.last())
        assertEquals(before + 3, RuleEngine.revision.value)
    }

    @Test
    fun theMarkListsWhatTheRuleDoesInTheFixedOrder() {
        val spec = RuleSpec(MatchShorthand.parse("*/a"), delay = DelaySpec(beforeResponseMs = 10), mock = MockSpec(200))
        assertEquals("delay,mock", CompiledRule(1, spec, RuleHits()).applied)
        val edit = RuleSpec(MatchShorthand.parse("*/a"), edit = EditSpec(RequestEdit(method = "POST"), ResponseEdit(status = 201)))
        assertEquals("edit-request,edit-response", CompiledRule(1, edit, RuleHits()).applied)
        assertEquals("delay,edit-request,edit-response", RuleMark.of(listOf("edit-response", "delay", "edit-request")))
        assertNull(RuleMark.of(emptyList()))
    }
}
