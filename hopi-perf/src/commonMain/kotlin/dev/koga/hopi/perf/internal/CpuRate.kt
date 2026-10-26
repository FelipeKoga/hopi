package dev.koga.hopi.perf.internal

import dev.koga.hopi.core.perf.CpuSample

/**
 * The CPU sample's arithmetic, shared by the samplers: the process's and Hopi's CPU time since the
 * previous read, over the wall time since it, in percent of one core. A tick with CPU off
 * ([skip]) forgets the baseline, so the first sample after CPU comes back (memory kept the sampler
 * running) is a new baseline, never a sample spanning all the time CPU was off. Sampler thread only.
 */
internal class CpuRate(cpuNs: Long, hopiNs: Long, wallMs: Long) {
    private var lastCpuNs = cpuNs
    private var lastHopiNs = hopiNs
    private var lastWallMs = wallMs
    private var primed = cpuNs >= 0

    /** A tick with CPU off. */
    fun skip() {
        primed = false
    }

    /** A tick with CPU on: the sample since the previous one, or null when this read is only the baseline. */
    fun sample(cpuNs: Long, hopiNs: Long, wallMs: Long, cores: Int): CpuSample? {
        val sample = if (primed && cpuNs >= 0) {
            val dtMs = (wallMs - lastWallMs).coerceAtLeast(1)
            CpuSample((cpuNs - lastCpuNs) / 1_000_000.0 * 100.0 / dtMs, (hopiNs - lastHopiNs) / 1_000_000.0 * 100.0 / dtMs, cores)
        } else {
            null
        }
        lastCpuNs = cpuNs
        lastHopiNs = hopiNs
        lastWallMs = wallMs
        primed = cpuNs >= 0
        return sample
    }
}
