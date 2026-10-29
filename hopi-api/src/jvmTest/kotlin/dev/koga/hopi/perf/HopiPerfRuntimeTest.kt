package dev.koga.hopi.perf

import dev.koga.hopi.api.HopiRuntimes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `HopiPerf` over a registered runtime: a fake that records what each call handed it. */
class HopiPerfRuntimeTest {
    private val runtime = FakePerfRuntime()

    @BeforeTest
    fun setUp() {
        HopiRuntimes.resetForTests()
        assertTrue(HopiRuntimes.registerPerf(runtime))
    }

    @AfterTest
    fun tearDown() {
        HopiRuntimes.resetForTests()
    }

    @Test
    fun aStartHandsOverTheMonitorsSetTrueAndOnlyTheSettingsSet() {
        HopiPerf.start {
            hangs = true
            cpu = true
            frames = false
            hangThresholdMs = 300
        }
        // Each setting set, also to its default value, and null when the block left it alone.
        HopiPerf.start {
            memory = true
            hangHeartbeat = false
            sampleIntervalMs = 1_000
        }
        HopiPerf.start {}
        val (first, second, third) = runtime.starts
        assertEquals(setOf(HopiMonitor.Hangs, HopiMonitor.Cpu), first.monitors)
        assertEquals(300L, first.hangThresholdMs)
        assertNull(first.hangHeartbeat)
        assertNull(first.sampleIntervalMs)
        assertEquals(setOf(HopiMonitor.Memory), second.monitors)
        assertNull(second.hangThresholdMs)
        assertEquals(false, second.hangHeartbeat)
        assertEquals(1_000L, second.sampleIntervalMs)
        assertEquals(emptySet(), third.monitors)
        assertNull(third.hangThresholdMs)
        assertNull(third.hangHeartbeat)
        assertNull(third.sampleIntervalMs)
        assertEquals(0, HopiRuntimes.droppedPerfStarts, "nothing dropped")
    }

    @Test
    fun stopRunningAndScreenReachTheRuntime() {
        HopiPerf.stop()
        HopiPerf.stop(HopiMonitor.Frames, HopiMonitor.Hangs)
        assertEquals(listOf(emptySet(), setOf(HopiMonitor.Frames, HopiMonitor.Hangs)), runtime.stops, "stop() passes an empty set: all")
        runtime.runningNow = setOf(HopiMonitor.Cpu)
        assertEquals(setOf(HopiMonitor.Cpu), HopiPerf.running)
        HopiPerf.screen("Checkout")
        HopiPerf.screen(null)
        assertEquals(listOf("Checkout", null), runtime.screens)
        assertNull(HopiRuntimes.perfScreen, "the registry keeps the last label too")
    }

    @Test
    fun theSlotIsWrittenOnce() {
        assertFalse(HopiRuntimes.registerPerf(FakePerfRuntime()), "a second registration is refused")
        assertSame(runtime, HopiRuntimes.perf)
        HopiPerf.start { frames = true }
        assertEquals(1, runtime.starts.size, "the first runtime keeps the slot")
    }
}
