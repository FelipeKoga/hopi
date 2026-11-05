package dev.koga.hopi.core.start

import dev.koga.hopi.api.HopiRuntimes
import dev.koga.hopi.core.rules.ApiRules
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.rules.HopiRules
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The start sequence with fake tools and its notice lines read from a list. No test turns
 * `agentBridge` on: that would create the process's default store, which [HopiStore]'s own test
 * creates (the device check covers the bridge at start). Each test starts with no rules runtime
 * registered and none dropped, and adds no rule once one is.
 */
class HopiStartTest {
    private val lines = ArrayList<String>()

    /** Records each start, in [order] when given, and returns [note]. */
    private class FakeTool(override val name: String, private val note: String? = null, private val order: MutableList<String>? = null) : HopiTool {
        val contexts = ArrayList<HopiToolContext>()

        override fun start(context: HopiToolContext): String? {
            contexts.add(context)
            order?.add(name)
            return note
        }
    }

    private class ThrowingTool : HopiTool {
        override val name: String = "broken"

        override fun start(context: HopiToolContext): String? = throw IllegalStateException("boom")
    }

    @BeforeTest
    fun setUp() {
        HopiStart.resetForTests()
        HopiRuntimes.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        HopiStart.resetForTests()
        HopiRuntimes.resetForTests()
    }

    private fun start(config: StartConfig, vararg tools: HopiTool, platform: Any? = null): List<ToolStart>? =
        HopiStart.start(config, tools.toList(), platform, lines::add)

    private fun notice(text: String) = """{"hopi":1,"notice":"$text"}"""

    @Test
    fun toolsStartInListOrderWithTheConfigurationAndThePlatform() {
        val order = ArrayList<String>()
        val first = FakeTool("first", note = "one", order = order)
        val second = FakeTool("second", order = order)
        val config = StartConfig(maxEntries = 7, maxTotalBytes = 1024, summaryLog = false)
        val started = assertNotNull(start(config, first, second, platform = "the platform"))
        assertEquals(listOf("first", "second"), order)
        assertEquals(listOf("first", "second"), started.map { it.name })
        assertEquals(listOf("one", null), started.map { it.note })
        assertTrue(started.all { it.nanos >= 0 }, started.map { it.nanos }.toString())
        val context = first.contexts.single()
        assertSame(config, context.config)
        assertEquals("the platform", context.platform)
        assertSame(context, second.contexts.single(), "one context for every tool")
        assertSame(config, HopiStart.config)
        assertEquals(emptyList(), lines)
    }

    @Test
    fun anEmptyToolListStillStarts() {
        assertEquals(emptyList(), start(StartConfig(summaryLog = false)))
        assertNotNull(HopiStart.config)
    }

    @Test
    fun disabledStartsNothingWritesNothingAndLeavesTheFirstCall() {
        val tool = FakeTool("tool")
        assertNull(start(StartConfig(enabled = false), tool))
        assertEquals(0, tool.contexts.size)
        assertEquals(emptyList(), lines)
        assertNull(HopiStart.config)
        assertNotNull(start(StartConfig(summaryLog = false), tool))
        assertEquals(1, tool.contexts.size)
    }

    @Test
    fun invalidCapsWriteOneNoticeWhateverSummaryLogSaysAndLeaveTheFirstCall() {
        val tool = FakeTool("tool")
        assertNull(start(StartConfig(maxEntries = 0, summaryLog = false), tool))
        assertNull(start(StartConfig(maxBodyBytes = -1, summaryLog = true), tool))
        assertEquals(
            listOf(
                notice("not started, invalid configuration: maxEntries must be positive"),
                notice("not started, invalid configuration: maxBodyBytes must be in 0..${Int.MAX_VALUE}"),
            ),
            lines,
        )
        assertEquals(0, tool.contexts.size)
        assertNull(HopiStart.config)
        val valid = StartConfig(summaryLog = false)
        assertNotNull(start(valid, tool))
        assertEquals(1, tool.contexts.size)
        assertSame(valid, HopiStart.config)
    }

    @Test
    fun theFirstValidCallWinsAndALaterDifferentOneWritesOneNotice() {
        val tool = FakeTool("tool")
        val first = StartConfig()
        assertNotNull(start(first, tool))
        // The same configuration again: nothing, silently.
        assertNull(start(StartConfig(), tool))
        assertEquals(emptyList(), lines)
        // Another one: one notice, nothing started, the first one kept.
        assertNull(start(StartConfig(maxEntries = 7), tool))
        assertEquals(listOf(notice("already started; this call's configuration is ignored")), lines)
        // The notice follows the later call's summaryLog.
        assertNull(start(StartConfig(maxEntries = 9, summaryLog = false), tool))
        assertEquals(1, lines.size)
        assertEquals(1, tool.contexts.size)
        assertSame(first, HopiStart.config)
    }

    @Test
    fun aThrowingToolWritesOneNoticeAndTheNextOneStillStarts() {
        val next = FakeTool("next")
        val started = assertNotNull(start(StartConfig(summaryLog = false), ThrowingTool(), next))
        assertEquals(listOf("next"), started.map { it.name })
        assertEquals(1, next.contexts.size)
        assertEquals(listOf(notice("broken not started: IllegalStateException: boom")), lines)
    }

    @Test
    fun theRulesRuntimeRegistersEvenWithInvalidCapsButNotWhenDisabled() {
        assertNull(start(StartConfig(enabled = false)))
        assertNull(HopiRuntimes.rules, "a disabled start registers nothing")
        assertNull(start(StartConfig(maxEntries = 0, summaryLog = false)))
        assertSame(ApiRules, HopiRuntimes.rules, "invalid caps still register, so HopiRules works")
        assertNotNull(start(StartConfig(summaryLog = false)))
        assertSame(ApiRules, HopiRuntimes.rules)
        assertEquals(listOf(notice("not started, invalid configuration: maxEntries must be positive")), lines)
    }

    @Test
    fun theRegistrationWritesOneNoticeWithTheDroppedRuleWhateverSummaryLogSays() {
        assertEquals(-1, HopiRules.mock("GET */a?token=secret"))
        // A disabled start neither registers nor writes: the count waits for the start.
        assertNull(start(StartConfig(enabled = false)))
        assertEquals(emptyList(), lines)
        assertNotNull(start(StartConfig(summaryLog = false)))
        val expected = notice("HopiRules: 1 rule added before Hopi started was dropped (its call returned -1)")
        assertEquals(listOf(expected), lines, "the count, never the match")
        // Later calls register nothing, so they never write it again.
        assertNull(start(StartConfig(summaryLog = false)))
        assertNull(start(StartConfig(maxEntries = 7)))
        assertEquals(listOf(expected, notice("already started; this call's configuration is ignored")), lines)
    }

    @Test
    fun theNoticeCountsEveryDroppedRule() {
        HopiRules.mock("*/a")
        HopiRules.edit("*/b") { request { path = "/c" } }
        // Not counted: only the four adds are.
        HopiRules.clear()
        assertNull(start(StartConfig(maxEntries = 0, summaryLog = false)))
        assertEquals(
            listOf(
                notice("HopiRules: 2 rules added before Hopi started were dropped (each call returned -1)"),
                notice("not started, invalid configuration: maxEntries must be positive"),
            ),
            lines,
            "the registration comes before the caps check",
        )
    }

    @Test
    fun theFirstCallPresetsTheDefaultStoresCaps() {
        assertNotNull(start(StartConfig(maxEntries = 7, maxTotalBytes = 1024, summaryLog = false)))
        // The start's preset came first: a later one changes nothing.
        assertFalse(HopiStore.presetDefault(1, 1))
    }
}
