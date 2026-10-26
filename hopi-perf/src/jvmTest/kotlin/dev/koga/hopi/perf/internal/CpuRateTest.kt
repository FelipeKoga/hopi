package dev.koga.hopi.perf.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The samplers' CPU arithmetic (Android and iOS share it): a sample never spans a stretch CPU was off. */
class CpuRateTest {
    @Test
    fun aSampleIsTheCpuSinceThePreviousReadOverTheWallTime() {
        val rate = CpuRate(cpuNs = 0, hopiNs = 0, wallMs = 0)
        val sample = rate.sample(cpuNs = 50_000_000, hopiNs = 1_000_000, wallMs = 1_000, cores = 8)!!
        assertEquals(5.0, sample.processPercent, 1e-9)
        assertEquals(0.1, sample.hopiPercent, 1e-9)
        assertEquals(8, sample.cores)
    }

    @Test
    fun cpuTurnedOnWhileMemoryRanStartsFromANewBaseline() {
        // The sampler was created for memory at 0; CPU stays off for five minutes at 5 % of a core.
        val rate = CpuRate(cpuNs = 0, hopiNs = 0, wallMs = 0)
        for (second in 1..300) rate.skip()
        // CPU on: the first tick is the baseline, not 15 s of CPU over one interval (1500 %).
        assertNull(rate.sample(cpuNs = 15_000_000_000, hopiNs = 300_000_000, wallMs = 300_000, cores = 8))
        val sample = rate.sample(cpuNs = 15_050_000_000, hopiNs = 301_000_000, wallMs = 301_000, cores = 8)!!
        assertEquals(5.0, sample.processPercent, 1e-9)
        assertEquals(0.1, sample.hopiPercent, 1e-9)
        // Off and on again: the same.
        rate.skip()
        assertNull(rate.sample(cpuNs = 20_000_000_000, hopiNs = 400_000_000, wallMs = 400_000, cores = 8))
    }

    @Test
    fun anUnreadableClockGivesNoSample() {
        val rate = CpuRate(cpuNs = -1, hopiNs = 0, wallMs = 0)
        assertNull(rate.sample(cpuNs = 10_000_000, hopiNs = 0, wallMs = 1_000, cores = 1))
        assertNull(rate.sample(cpuNs = -1, hopiNs = 0, wallMs = 2_000, cores = 1))
        assertNull(rate.sample(cpuNs = 30_000_000, hopiNs = 0, wallMs = 3_000, cores = 1))
        assertEquals(1.0, rate.sample(cpuNs = 40_000_000, hopiNs = 0, wallMs = 4_000, cores = 1)!!.processPercent, 1e-9)
    }
}
