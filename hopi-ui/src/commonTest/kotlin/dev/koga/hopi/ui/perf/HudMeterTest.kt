package dev.koga.hopi.ui.perf

import dev.koga.hopi.core.perf.CpuSample
import dev.koga.hopi.core.perf.FrameSecond
import dev.koga.hopi.core.perf.JankEvent
import dev.koga.hopi.core.perf.MemorySample
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfSample
import dev.koga.hopi.core.perf.StallEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The HUD's text (docs/design/perf-monitors.md, "In-app"): what it says, that it changes only when
 * the text does, and that a stopped monitor's figures go, a sample racing the stop included.
 */
class HudMeterTest {
    private fun second(at: Long, frames: Int, janky: Int = 0) = FrameSecond(at, frames, janky, janky, 0, 40.0, 0, 0, 0, 0, null)

    private fun sample(atMs: Long, cpu: Double = 12.4, residentMb: Long = 142) = PerfSample(
        atMs,
        CpuSample(cpu, 0.2, 8),
        MemorySample(null, null, null, residentMb * 1024 * 1024, null, null, null, perturbed = false),
    )

    @Test
    fun framesAloneReadFpsWhileDrawingAndAQuietSecondIdle() {
        val meter = HudMeter(ios = false)
        assertEquals("perf: waiting for data", meter.text())
        assertEquals("58 fps while drawing · 4 jank", meter.onItem(second(100, 58, 4)))
        assertNull(meter.onItem(second(101, 58, 4)), "the same text draws nothing")
        assertEquals("idle", meter.onItem(second(102, 2)))
        assertNull(meter.onItem(JankEvent(1, 0, 120.0, 16.7, false, null, null)), "jank events never reach the HUD's text")
    }

    @Test
    fun withTheSamplerAnEmptySecondReadsIdle() {
        val meter = HudMeter(ios = false)
        meter.onItem(second(100, 60))
        assertEquals("60 fps · 0 jank · CPU 12% · 142 MB", meter.onItem(sample(100_500)))
        // A tick two seconds later with no frame between: nothing drew.
        assertEquals("idle · CPU 12% · 142 MB", meter.onItem(sample(102_000)))
        assertNull(meter.onItem(sample(103_000)), "an idle app stops changing the text")
        assertEquals("idle · CPU 30% · 142 MB", meter.onItem(sample(104_000, cpu = 30.0)))
    }

    @Test
    fun iosShowsStallsNeverFps() {
        val meter = HudMeter(ios = true)
        assertEquals("worst stall 84 ms · 1 stall/10 s", meter.onItem(StallEvent(1, 50_000, 84, null)))
        assertEquals("worst stall 120 ms · 2 stalls/10 s", meter.onItem(StallEvent(2, 52_000, 120, null)))
        assertEquals("no stall/10 s · CPU 12% · 142 MB", meter.onItem(sample(70_000)))
        // Tier 2: display-link callbacks, labelled cadence.
        assertEquals("cadence 120/s · no stall/10 s · CPU 12% · 142 MB", meter.onItem(second(70, 120)))
    }

    @Test
    fun aStoppedMonitorsFiguresGoAtOnceAndNoneRunningReadsOff() {
        val meter = HudMeter(ios = false)
        assertEquals("perf: waiting for data", meter.onRunning(setOf(PerfMonitor.FRAMES, PerfMonitor.CPU, PerfMonitor.MEMORY)))
        meter.onItem(second(100, 60))
        assertEquals("60 fps · 0 jank · CPU 12% · 142 MB", meter.onItem(sample(100_500)))
        // CPU stops, memory goes on: the CPU figure goes now, not at the next tick.
        assertEquals("60 fps · 0 jank · 142 MB", meter.onRunning(setOf(PerfMonitor.FRAMES, PerfMonitor.MEMORY)))
        // Memory stops too: frames alone again reads "while drawing", and a sample under way is ignored.
        assertEquals("60 fps while drawing · 0 jank", meter.onRunning(setOf(PerfMonitor.FRAMES)))
        assertNull(meter.onItem(sample(101_000)))
        // Nothing runs: no item will change the text again, so it says so; a second closed after the stop is ignored.
        assertEquals("perf: off", meter.onRunning(emptySet()))
        assertNull(meter.onItem(second(101, 60)))
        // A restart starts from nothing, never from the figures before the stop.
        assertEquals("perf: waiting for data", meter.onRunning(setOf(PerfMonitor.CPU)))
        assertEquals("CPU 12%", meter.onItem(sample(200_000)))
    }

    @Test
    fun aSampleRacingAStopNeverEndsOnScreen() = runBlocking {
        val published = ArrayList<String>()
        val storing = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val meter = HudMeter(ios = false) { text ->
            if (text == "CPU 12% · 142 MB") {
                // The sampler's thread is preempted between working out its text and storing it.
                storing.complete(Unit)
                runBlocking { resume.await() }
            }
            published += text
        }
        meter.onRunning(setOf(PerfMonitor.CPU, PerfMonitor.MEMORY))
        val sampler = launch(Dispatchers.Default) { meter.onItem(sample(100_000)) }
        withTimeoutOrNull(10_000) { storing.await() } ?: fail("timed out waiting for the sample's text")
        // A CLI's `perf stop` on the bridge thread meanwhile: it waits for that store, so its text lands last.
        val stop = launch(Dispatchers.Default) { meter.onRunning(emptySet()) }
        delay(200)
        resume.complete(Unit)
        joinAll(sampler, stop)
        assertEquals(listOf("perf: waiting for data", "CPU 12% · 142 MB", "perf: off"), published)
    }

    @Test
    fun framesStoppedIsNotReadAsAnIdleApp() {
        val meter = HudMeter(ios = false)
        meter.onRunning(setOf(PerfMonitor.FRAMES, PerfMonitor.CPU, PerfMonitor.MEMORY))
        meter.onItem(second(100, 60))
        meter.onItem(sample(100_500))
        assertEquals("CPU 12% · 142 MB", meter.onRunning(setOf(PerfMonitor.CPU, PerfMonitor.MEMORY)))
        // The sampler's tick closes the second frames left open, and later ticks see no frame: still no frames figure.
        assertNull(meter.onItem(second(101, 30)))
        assertNull(meter.onItem(sample(103_000)), "frames is off, not the app idle")
    }

    @Test
    fun iosDropsTheStallsWhenFramesStops() {
        val meter = HudMeter(ios = true)
        meter.onRunning(setOf(PerfMonitor.FRAMES, PerfMonitor.CPU))
        meter.onItem(StallEvent(1, 50_000, 84, null))
        assertEquals("worst stall 84 ms · 1 stall/10 s · CPU 12%", meter.onItem(sample(51_000)))
        // Not "no stall/10 s": frames is off.
        assertEquals("CPU 12%", meter.onRunning(setOf(PerfMonitor.CPU)))
        assertNull(meter.onItem(StallEvent(2, 52_000, 120, null)))
    }
}
