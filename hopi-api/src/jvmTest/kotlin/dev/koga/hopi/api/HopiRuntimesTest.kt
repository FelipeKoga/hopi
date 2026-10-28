package dev.koga.hopi.api

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HopiRuntimesTest {
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
    fun resetEmptiesTheSlot() {
        HopiRuntimes.registerKtorHooks(Any())
        HopiRuntimes.resetForTests()
        assertNull(HopiRuntimes.ktorHooks)
        assertTrue(HopiRuntimes.registerKtorHooks(Any()))
    }

    @Test
    fun hopiCoreIsNotOnTheRuntimeClasspath() {
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.koga.hopi.core.rules.RuleEngine") }
        val classpath = System.getProperty("java.class.path").split(File.pathSeparatorChar)
        assertEquals(emptyList(), classpath.filter { "hopi-core" in it }, "hopi-core on the classpath")
    }
}
