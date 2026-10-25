package dev.koga.hopi.rules

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HopiRulesNoopTest {
    @Test
    fun everyCallIsInert() {
        var ran = false
        assertEquals(-1, HopiRules.mock("GET */users/*") {
            status = 500
            header("Content-Type", "application/json")
            body("{}")
            delay(beforeResponse = 2.seconds)
            times = 1
            ran = true
        })
        assertTrue(ran, "the block runs, as with the real module")
        assertEquals(-1, HopiRules.fail("*/a", HopiFailure.Timeout))
        assertEquals(-1, HopiRules.delay("*/a") { beforeRequest = 1.seconds })
        assertEquals(-1, HopiRules.edit("*/a") { request { path = "/b" } })
        assertEquals(emptyList(), HopiRules.list())
        assertFalse(HopiRules.setEnabled(1, true))
        assertFalse(HopiRules.remove(1))
        HopiRules.clear()
        assertEquals(-1, HopiRules.replay(1) { setHeader("X", "1") })
    }

    @Test
    fun builderPropertiesReadBackWhatWasSet() {
        HopiRules.mock("*/a") {
            status = 418
            label = "teapot"
            assertEquals(418, status)
            assertEquals("teapot", label)
        }
    }

    @Test
    fun hopiCoreIsNotOnTheRuntimeClasspath() {
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.koga.hopi.core.rules.RuleEngine") }
        val classpath = System.getProperty("java.class.path").split(File.pathSeparatorChar)
        assertEquals(emptyList(), classpath.filter { "hopi-core" in it }, "hopi-core on the classpath")
    }
}
