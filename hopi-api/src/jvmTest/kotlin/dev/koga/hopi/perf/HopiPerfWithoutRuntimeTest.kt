package dev.koga.hopi.perf

import dev.koga.hopi.api.HopiRuntimes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `HopiPerf` with no runtime registered, as in a release build: every call inert, the block still running. */
class HopiPerfWithoutRuntimeTest {
    @BeforeTest
    fun setUp() {
        HopiRuntimes.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        HopiRuntimes.resetForTests()
    }

    @Test
    fun everyCallIsInert() {
        var ran = false
        HopiPerf.start {
            frames = true
            hangs = true
            cpu = true
            memory = true
            ran = true
        }
        assertTrue(ran, "the block runs, as with a runtime")
        HopiPerf.screen("Checkout")
        assertEquals(emptySet(), HopiPerf.running)
        HopiPerf.stop(HopiMonitor.Frames)
        HopiPerf.stop()
        assertEquals(emptyList(), Thread.getAllStackTraces().keys.filter { it.name.startsWith("hopi-perf") })
    }

    @Test
    fun onlyTheStartsMadeWhileNoRuntimeIsRegisteredAreCounted() {
        assertEquals(0, HopiRuntimes.droppedPerfStarts)
        HopiPerf.start { cpu = true }
        HopiPerf.start {}
        // Nothing to undo before a start: none of these is counted.
        HopiPerf.stop()
        HopiPerf.stop(HopiMonitor.Cpu)
        HopiPerf.screen("A")
        assertEquals(emptySet(), HopiPerf.running)
        assertEquals(2, HopiRuntimes.droppedPerfStarts, "the two starts only")
        assertTrue(HopiRuntimes.registerPerf(FakePerfRuntime()))
        HopiPerf.start { memory = true }
        assertEquals(2, HopiRuntimes.droppedPerfStarts, "a start the runtime took is not counted")
    }

    @Test
    fun screenKeepsTheLastLabelForALaterRuntime() {
        assertNull(HopiRuntimes.perfScreen)
        HopiPerf.screen("Checkout")
        HopiPerf.screen("Cart")
        assertEquals("Cart", HopiRuntimes.perfScreen)
        HopiPerf.screen(null)
        assertNull(HopiRuntimes.perfScreen, "null is a label too: back to the activity's name")
    }

    @Test
    fun theConfigReadsBackWhatWasSetAndTheDefaultsWhenNot() {
        HopiPerf.start {
            assertFalse(frames || hangs || cpu || memory, "every monitor off by default")
            assertEquals(250L, hangThresholdMs)
            assertEquals(false, hangHeartbeat)
            assertEquals(1_000L, sampleIntervalMs)
            assertNull(hangThresholdMsSet)
            assertNull(hangHeartbeatSet)
            assertNull(sampleIntervalMsSet)
            assertEquals(emptySet(), monitors())
            hangThresholdMs = 300
            hangHeartbeat = true
            sampleIntervalMs = 500
            frames = true
            memory = true
            assertEquals(300L, hangThresholdMs)
            assertEquals(true, hangHeartbeat)
            assertEquals(500L, sampleIntervalMs)
            assertEquals(setOf(HopiMonitor.Frames, HopiMonitor.Memory), monitors())
            // No validation here: an out-of-range value is kept for the runtime to refuse.
            hangThresholdMs = 1
            assertEquals(1L, hangThresholdMsSet)
        }
    }
}
