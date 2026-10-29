package dev.koga.hopi.rules

import dev.koga.hopi.api.HopiRuntimes
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The forgotten-start line of an iOS Debug binary, which a test binary is: the first add that
 * finds no runtime writes it, once per process, and an add a runtime took writes nothing. Its
 * count is read here; the line itself goes to the system log, which the sample app's simulator
 * check reads. The count is never reset, so the runtime's part runs first: in this binary no other
 * test drops a rule before it.
 */
class RuleDroppedNoticeTest {
    @AfterTest
    fun tearDown() {
        HopiRuntimes.resetForTests()
    }

    @OptIn(ExperimentalNativeApi::class)
    @Test
    fun theFirstDroppedRuleWritesOneLineAndARegisteredRuntimeNone() {
        assertTrue(Platform.isDebugBinary, "a debug test binary")
        HopiRuntimes.resetForTests()
        val before = ruleDroppedNoticeLines
        val runtime = FakeRulesRuntime()
        assertTrue(HopiRuntimes.registerRules(runtime))
        HopiRules.mock("*/a")
        HopiRules.fail("*/a", HopiFailure.Cancel)
        HopiRules.delay("*/a") {}
        HopiRules.edit("*/a") {}
        assertEquals(4, runtime.rules.size)
        assertEquals(before, ruleDroppedNoticeLines, "an add the runtime took writes nothing")

        HopiRuntimes.resetForTests()
        assertEquals(-1, HopiRules.mock("*/a"))
        assertEquals(1, ruleDroppedNoticeLines, "the first dropped rule writes the line")
        assertEquals(-1, HopiRules.edit("*/a") {})
        assertEquals(-1, HopiRules.fail("*/a", HopiFailure.Timeout))
        assertEquals(1, ruleDroppedNoticeLines, "once per process")
        assertEquals(3, HopiRuntimes.droppedRules)
    }
}
