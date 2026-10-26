package dev.koga.hopi.core.bridge

import dev.koga.hopi.core.perf.PerfLimits
import dev.koga.hopi.core.perf.PerfMonitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PerfCommandTest {
    private fun parse(line: String): BridgeCommand = assertIs<BridgeParseResult.Ok>(BridgeCommand.parse(line), line).command

    private fun invalid(line: String) = assertIs<BridgeParseResult.Invalid>(BridgeCommand.parse(line), line)

    @Test
    fun perfCommandsParseWithTheirDefaults() {
        assertEquals(
            BridgeCommand.PerfStart(setOf(PerfMonitor.FRAMES, PerfMonitor.HANGS), hangMs = 300, intervalMs = null, ttlSec = PerfLimits.DEFAULT_LEASE_SEC),
            parse("""{"cmd":"perf.start","monitors":["frames","hangs"],"hangMs":300}"""),
        )
        assertEquals(BridgeCommand.PerfStart(setOf(PerfMonitor.CPU), null, 500, 0), parse("""{"cmd":"perf.start","monitors":["cpu"],"intervalMs":500,"ttlSec":0}"""))
        assertEquals(BridgeCommand.PerfStop(emptySet()), parse("""{"cmd":"perf.stop"}"""))
        assertEquals(BridgeCommand.PerfStatus, parse("""{"cmd":"perf.status"}"""))
        assertEquals(
            BridgeCommand.PerfSnapshot(emptyList(), BridgeCommand.DEFAULT_PERF_POINTS, events = true, since = 0, threads = false, pss = false, fullStacks = false),
            parse("""{"cmd":"perf.snapshot"}"""),
        )
        assertEquals(BridgeCommand.PerfEvent(12), parse("""{"cmd":"perf.event","seq":12}"""))
        assertEquals(
            BridgeCommand.PerfTail(since = 3, samples = true, fps = false, threads = false, start = setOf(PerfMonitor.HANGS)),
            parse("""{"cmd":"perf.tail","since":3,"samples":true,"start":["hangs"]}"""),
        )
        assertTrue(parse("""{"cmd":"perf.status"}""").isWrite, "accepted only after hello")
    }

    @Test
    fun malformedPerfCommandsAreRejected() {
        invalid("""{"cmd":"perf.start","monitors":["gpu"]}""")
        invalid("""{"cmd":"perf.start","monitors":"hangs"}""")
        invalid("""{"cmd":"perf.start","monitors":["hangs"],"hangMs":"x"}""")
        invalid("""{"cmd":"perf.start","monitors":["hangs"],"ttlSec":-1}""")
        invalid("""{"cmd":"perf.event"}""")
        invalid("""{"cmd":"perf.snapshot","series":[1]}""")
        invalid("""{"cmd":"perf.tail","samples":1}""")
    }
}
