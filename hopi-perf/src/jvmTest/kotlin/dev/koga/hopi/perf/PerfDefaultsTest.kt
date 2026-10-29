package dev.koga.hopi.perf

import dev.koga.hopi.api.HopiRuntimes
import dev.koga.hopi.core.perf.PerfLimits
import dev.koga.hopi.core.perf.PerfMonitor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * `hopi-api` depends on nothing, so `HopiPerfConfig`'s defaults are literals there and `HopiMonitor`
 * is its own enum: this compares them with the runtime's, the defaults with `PerfLimits` and the
 * monitors, in order, with `PerfMonitor` (the runtime maps one onto the other, the plugin's bits
 * count by that order). The ABI dump holds signatures, not initializers, so only this sees a default
 * that changed on one side.
 */
class PerfDefaultsTest {
    @AfterTest
    fun tearDown() {
        // A start with no runtime registered is counted as dropped.
        HopiRuntimes.resetForTests()
    }

    @Test
    fun theApisDefaultsAreTheRuntimes() {
        var captured: HopiPerfConfig? = null
        HopiPerf.start { captured = this }
        val config = assertNotNull(captured, "the block runs, with or without a runtime")
        assertEquals(PerfLimits.DEFAULT_HANG_THRESHOLD_MS, config.hangThresholdMs)
        assertEquals(PerfLimits.DEFAULT_SAMPLE_INTERVAL_MS, config.sampleIntervalMs)
        assertFalse(config.hangHeartbeat)
        assertFalse(config.frames || config.hangs || config.cpu || config.memory, "every monitor off")
    }

    @Test
    fun theMonitorsAreTheRuntimesInTheSameOrder() {
        assertEquals(PerfMonitor.entries.map { it.wireName }, HopiMonitor.entries.map { it.name.lowercase() })
    }
}
