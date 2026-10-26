package dev.koga.hopi.ui.perf

import dev.koga.hopi.core.perf.CpuSample
import dev.koga.hopi.core.perf.FrameSecond
import dev.koga.hopi.core.perf.JankEvent
import dev.koga.hopi.core.perf.MemorySample
import dev.koga.hopi.core.perf.PerfSample
import dev.koga.hopi.core.perf.StallEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The HUD's text (docs/design/perf-monitors.md, "In-app"): what it says, and that it changes only when the text does. */
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
}
