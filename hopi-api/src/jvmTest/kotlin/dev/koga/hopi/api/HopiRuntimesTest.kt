package dev.koga.hopi.api

import dev.koga.hopi.rules.FakeRulesRuntime
import dev.koga.hopi.rules.HopiRules
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HopiRuntimesTest {
    @BeforeTest
    fun setUp() {
        HopiRuntimes.resetForTests()
    }

    @AfterTest
    fun reset() {
        HopiRuntimes.resetForTests()
    }

    @Test
    fun theKtorSlotIsEmptyUntilARuntimeRegisters() {
        assertNull(HopiRuntimes.ktorHooks)
    }

    @Test
    fun theKtorSlotIsWrittenOnce() {
        val first = Any()
        assertTrue(HopiRuntimes.registerKtorHooks(first))
        assertFalse(HopiRuntimes.registerKtorHooks(Any()), "a second registration is refused")
        assertSame(first, HopiRuntimes.ktorHooks)
    }

    @Test
    fun theRulesSlotIsWrittenOnce() {
        assertNull(HopiRuntimes.rules)
        val first = FakeRulesRuntime()
        assertTrue(HopiRuntimes.registerRules(first))
        assertFalse(HopiRuntimes.registerRules(FakeRulesRuntime()), "a second registration is refused")
        assertSame(first, HopiRuntimes.rules)
    }

    @Test
    fun resetEmptiesTheSlotsAndTheDroppedCount() {
        HopiRuntimes.registerKtorHooks(Any())
        HopiRules.mock("*/a")
        HopiRuntimes.registerRules(FakeRulesRuntime())
        assertEquals(1, HopiRuntimes.droppedRules)
        HopiRuntimes.resetForTests()
        assertNull(HopiRuntimes.ktorHooks)
        assertNull(HopiRuntimes.rules)
        assertEquals(0, HopiRuntimes.droppedRules)
        assertTrue(HopiRuntimes.registerKtorHooks(Any()))
        assertTrue(HopiRuntimes.registerRules(FakeRulesRuntime()))
    }

    @Test
    fun hopiCoreIsNotOnTheRuntimeClasspath() {
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.koga.hopi.core.rules.RuleEngine") }
        val classpath = System.getProperty("java.class.path").split(File.pathSeparatorChar)
        assertEquals(emptyList(), classpath.filter { "hopi-core" in it }, "hopi-core on the classpath")
    }
}
