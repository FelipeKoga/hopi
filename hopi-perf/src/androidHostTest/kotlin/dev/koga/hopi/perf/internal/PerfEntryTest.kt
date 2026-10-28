package dev.koga.hopi.perf.internal

import dev.koga.hopi.core.perf.PerfHook
import dev.koga.hopi.core.start.HopiToolContext
import dev.koga.hopi.core.start.StartConfig
import dev.koga.hopi.perf.HopiPerf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Perf's tool in the start sequence, as the generated configuration lists it: with no `autoStart`
 * monitor it registers the controller with the `perf { }` block's settings and starts nothing.
 */
class PerfEntryTest {
    @Test
    fun withNoAutoStartThePerfToolRegistersTheControllerAndStartsNothing() {
        val tool = PerfEntry(autoStart = 0, hangThresholdMs = 300, hangHeartbeat = true)
        assertEquals("perf", tool.name)
        assertNull(tool.start(HopiToolContext(StartConfig(), platform = null)), "no note in the start line")
        val controller = assertNotNull(PerfRuntime.existing())
        assertSame(controller, PerfHook.controller)
        assertEquals(300L, controller.currentSettings.hangThresholdMs)
        assertEquals(true, controller.currentSettings.hangHeartbeat)
        assertEquals(emptySet(), HopiPerf.running)
    }
}
