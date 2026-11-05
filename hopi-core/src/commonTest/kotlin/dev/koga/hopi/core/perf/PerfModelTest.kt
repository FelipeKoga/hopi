package dev.koga.hopi.core.perf

import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.json.parseJsonTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The perf model's pure parts: rings, histogram, frame classification and seconds, owners, log lines. */
class PerfModelTest {
    /** Off costs no ring: a model nothing wrote allocates its series and event slots at the first point. */
    @Test
    fun ringsAreAllocatedAtTheirFirstWriteOnly() {
        val data = PerfData()
        val rings = listOf(data.frames.allocated, data.cpu.allocated, data.memory.allocated, data.janks.allocated, data.stalls.allocated, data.hangs.allocated, data.memoryEvents.allocated)
        assertTrue(rings.none { it }, rings.toString())
        // Reads of an empty model allocate nothing either.
        assertEquals(0, data.cpu.last(10).size)
        assertTrue(data.eventsSince(0).isEmpty())
        data.summary()
        assertFalse(data.cpu.allocated)
        data.recordSample(1_000, CpuSample(5.0, 0.1, 8), null)
        assertTrue(data.cpu.allocated)
        assertFalse(data.memory.allocated, "only the series written")
        assertEquals(1, data.cpu.last(10).size)
        data.recordStall(StallEvent(data.nextSeq(), 5, 120, null))
        assertTrue(data.stalls.allocated)
        assertFalse(data.hangs.allocated)
        data.clear()
        assertEquals(0, data.stalls.count)
    }

    @Test
    fun aSeriesRingKeepsTheNewestPointsInPrimitiveSlots() {
        val ring = SeriesRing(capacity = 3, columns = 2)
        val row = FloatArray(2)
        for (i in 1..5) {
            row[0] = i.toFloat()
            row[1] = -i.toFloat()
            ring.add(i * 1000L, row)
        }
        assertEquals(3, ring.count)
        val points = ring.last(10)
        assertEquals(listOf(3000L, 4000L, 5000L), points.times.toList())
        assertEquals(3f, points.value(0, 0))
        assertEquals(-5f, points.value(2, 1))
        assertEquals(listOf(5000L), ring.last(1).times.toList())
    }

    private class E(override val seq: Long, val tag: String = "") : Sequenced

    @Test
    fun anEventRingEvictsByCountAndReplacesBySequence() {
        val ring = EventRing<E>(2)
        ring.add(E(1))
        ring.add(E(2))
        ring.add(E(3))
        assertEquals(listOf(2L, 3L), ring.since(0).map { it.seq })
        assertEquals(listOf(3L), ring.since(2).map { it.seq })
        assertTrue(ring.replace(E(3, "done")))
        assertEquals("done", ring.find(3)?.tag)
        assertFalse(ring.replace(E(1)), "evicted")
        assertEquals(listOf(3L), ring.since(0, limit = 1).map { it.seq })
    }

    @Test
    fun perKindRingsKeepHangsThroughAJankFlood() {
        val data = PerfData()
        val deadline = 16_666_667L
        repeat(PerfLimits.JANK_EVENTS + 50) { data.recordFrame(1000L + it / 60, it * deadline, 5 * deadline, deadline, false, 0, 0, null) }
        data.recordHang(hang(data.nextSeq()))
        assertEquals(PerfLimits.JANK_EVENTS, data.janks.count)
        assertEquals(1, data.hangs.count)
    }

    @Test
    fun histogramPercentiles() {
        val h = FrameHistogram()
        assertNull(h.percentile(0.5))
        repeat(90) { h.add(10.0) }
        repeat(9) { h.add(50.0) }
        h.add(450.0)
        assertEquals(11.0, h.percentile(0.5))
        assertEquals(11.0, h.percentile(0.9))
        assertEquals(51.0, h.percentile(0.99))
        assertEquals(450.0, h.percentile(1.0))
        h.add(5_000.0)
        assertEquals(5_000.0, h.maxMs)
    }

    @Test
    fun framesAreClassifiedAgainstTheirDeadline() {
        for (hz in listOf(60f, 90f, 120f)) {
            val deadline = FrameClassifier.deadlineNs(hz)
            assertEquals(FrameClass.NORMAL, FrameClassifier.classify(deadline - 1, deadline), "$hz")
            assertEquals(FrameClass.SLOW, FrameClassifier.classify(deadline + 1_000_000, deadline), "$hz")
            assertEquals(FrameClass.JANKY, FrameClassifier.classify(2 * deadline + 1, deadline), "$hz")
        }
        val at60 = FrameClassifier.deadlineNs(60f)
        // An injected 20 ms frame is slow, not janky, at 60 Hz; 100 ms is janky; over 700 ms frozen.
        assertEquals(FrameClass.SLOW, FrameClassifier.classify(20_000_000, at60))
        assertEquals(FrameClass.JANKY, FrameClassifier.classify(100_000_000, at60))
        assertEquals(FrameClass.FROZEN, FrameClassifier.classify(701_000_000, at60))
        // API 31's DEADLINE metric, per frame: a 120 Hz frame of 20 ms is janky.
        assertEquals(FrameClass.JANKY, FrameClassifier.classify(20_000_000, 8_333_333))
        assertEquals(0, FrameClassifier.missedVsyncs(0, 100, 16))
        assertEquals(0, FrameClassifier.missedVsyncs(1_000, 1_016, 16))
        assertEquals(2, FrameClassifier.missedVsyncs(1_000, 1_048, 16))
    }

    @Test
    fun secondsCloseWithoutATimerAndNeverTwice() {
        val closed = ArrayList<FrameSecond>()
        val seconds = FrameSeconds { closed += it }
        seconds.add(100, 10, FrameClass.NORMAL, false, 0, 0, false, "A")
        seconds.add(100, 40_000_000, FrameClass.JANKY, false, 0, 2, false, "A")
        seconds.add(100, 10, FrameClass.NORMAL, true, 0, 0, false, "A")
        assertTrue(closed.isEmpty(), "the current second is never written")
        assertEquals(2, seconds.provisional()?.frames)
        seconds.closeBefore(100)
        assertTrue(closed.isEmpty(), "a reader in the same second closes nothing")
        seconds.closeBefore(101)
        assertEquals(1, closed.size)
        assertEquals(2, closed[0].frames)
        assertEquals(1, closed[0].janky)
        assertEquals(1, closed[0].firstFrames)
        assertEquals(2, closed[0].metricsLost)
        assertEquals(40.0, closed[0].worstMs)
        // A late frame of the closed second opens no second point for it.
        seconds.add(100, 10, FrameClass.NORMAL, false, 0, 0, false, "A")
        seconds.add(102, 10, FrameClass.NORMAL, false, 0, 0, false, "A")
        assertEquals(listOf(100L, 101L), closed.map { it.second })
        seconds.closeBefore(200)
        assertEquals(listOf(100L, 101L, 102L), closed.map { it.second })
    }

    @Test
    fun excludedFramesAreCountedApart() {
        val data = PerfData()
        val deadline = 16_666_667L
        // Two Hopi pauses of the main thread (a stack capture, a PSS read): a frame overlapping one is left out.
        data.markPause(10_000_000, 20_000_000)
        data.recordFrame(10, 0, 100_000_000, deadline, false, 0, 0, null)
        data.markPause(1_000_000_000, 1_010_000_000)
        data.recordFrame(10, 1_005_000_000, 100_000_000, deadline, false, 0, 0, null)
        data.recordFrame(10, 2_000_000_000, 100_000_000, deadline, false, 0, 0, longArrayOf(1, 2, 3, 4, 5, 6, 7, -1))
        val summary = data.summary()
        assertEquals(1, summary.frames)
        assertEquals(2, summary.excluded)
        assertEquals(1, data.janks.count)
        data.tick(11)
        val point = data.frames.last(1)
        assertEquals(1f, point.value(0, 0))
        assertEquals(2f, point.value(0, PerfData.FRAME_COLUMNS.indexOf("excluded")))
    }

    @Test
    fun busyStateIsPackedAndWrapsAround() {
        val packed = BusyState.pack(5, 123_456)
        assertEquals(5, BusyState.sequenceOf(packed))
        assertEquals(123_456, BusyState.startOf(packed))
        assertTrue(BusyState.isIdle(BusyState.pack(5, 0)))
        val state = BusyState()
        state.begin(0)
        assertFalse(BusyState.isIdle(state.load()), "a unit starting at clock 0 is still busy")
        state.end(1)
        assertEquals(1, BusyState.sequenceOf(state.load()))
        // The 24-bit sequence wraps to 0, and never spills into the start.
        val wrapped = BusyState.pack(BusyState.SEQ_MASK + 1, 77)
        assertEquals(0, BusyState.sequenceOf(wrapped))
        assertEquals(77, BusyState.startOf(wrapped))
        assertEquals(BusyState.SEQ_MASK, BusyState.sequenceOf(BusyState.pack(BusyState.SEQ_MASK, BusyState.START_MASK)))
        assertEquals(BusyState.START_MASK, BusyState.startOf(BusyState.pack(BusyState.SEQ_MASK, BusyState.START_MASK)))
        // The input bracket marks busy only when idle on entry.
        assertTrue(state.enterInput(20))
        val seq = BusyState.sequenceOf(state.load())
        assertFalse(state.enterInput(21), "nested input inside a unit")
        assertEquals(seq, BusyState.sequenceOf(state.load()))
        state.exitInput(22)
        assertTrue(BusyState.isIdle(state.load()))
    }

    @Test
    fun ownersKeepAMonitorWhileAnyHoldsIt() {
        val owners = PerfOwners()
        assertTrue(owners.add(PerfMonitor.HANGS, PerfOwner.Code, 0))
        assertFalse(owners.add(PerfMonitor.HANGS, PerfOwner.Lease(10), 0))
        assertFalse(owners.add(PerfMonitor.HANGS, PerfOwner.Tail(1), 0))
        assertEquals(listOf("code", "lease", "tail:1"), owners.describe(PerfMonitor.HANGS))
        assertEquals(emptySet(), owners.expire(10_000), "code still holds it")
        assertEquals(emptySet(), owners.removeTail(1))
        assertTrue(owners.held(PerfMonitor.HANGS))

        assertTrue(owners.add(PerfMonitor.CPU, PerfOwner.Lease(10), 0))
        // A second start extends the lease to the later of the two.
        owners.add(PerfMonitor.CPU, PerfOwner.Lease(5), 1_000)
        assertEquals(10_000, owners.leaseUntil(PerfMonitor.CPU))
        owners.add(PerfMonitor.CPU, PerfOwner.Lease(20), 1_000)
        assertEquals(21_000, owners.nextExpiry())
        assertEquals(emptySet(), owners.expire(20_999))
        assertEquals(setOf(PerfMonitor.CPU), owners.expire(21_000))

        owners.add(PerfMonitor.FRAMES, PerfOwner.Tail(2), 0)
        owners.add(PerfMonitor.FRAMES, PerfOwner.Tail(3), 0)
        assertEquals(emptySet(), owners.removeTail(2), "another client holds it")
        assertEquals(setOf(PerfMonitor.FRAMES), owners.removeTail(3))

        owners.add(PerfMonitor.MEMORY, PerfOwner.Lease(0), 0)
        assertEquals(listOf("lease:no-expiry"), owners.describe(PerfMonitor.MEMORY))
        assertNull(owners.nextExpiry())
        assertEquals(emptySet(), owners.expire(Long.MAX_VALUE - 1))
        // perf.stop removes every owner, code included.
        assertTrue(owners.clear(PerfMonitor.HANGS))
        assertFalse(owners.held(PerfMonitor.HANGS))
    }

    @Test
    fun logLinesNeverCarryAnIdAndFitTheirBudget() {
        val event = hang(12, stack = List(64) { "dev.koga.hopi.sample.PerfLab.method$it(PerfLab.kt:$it)" })
        val line = assertNotNull(PerfJson.logLine(event, maxBytes = 1000))
        assertTrue(TransactionJson.utf8Length(line) <= 1000, line)
        val fields = assertNotNull(parseJsonTree(line, Int.MAX_VALUE, 4), line)
        assertNull(fields["id"])
        assertEquals("hang", fields["perf"])
        assertEquals("ongoing", fields["state"])
        val stack = fields["stack"] as List<*>
        assertTrue(stack.size in 1 until 64, "cut at a frame boundary: ${stack.size}")
        assertEquals("dev.koga.hopi.sample.PerfLab.method0(PerfLab.kt:0)", stack[0])
        val ended = assertNotNull(PerfJson.logLine(event.copy(ended = true, durationMs = 612), 1000))
        assertEquals(612L, parseJsonTree(ended)?.get("ms"))
        // The 2x and 4x captures are not logged.
        assertNull(PerfJson.logLine(event.copy(captureUs = listOf(10, 20)), 1000))
        val jank = FrameSecond(1729850000, 57, 9, 4, 0, 118.0, 0, 0, 0, 0, "Checkout")
        val jankLine = assertNotNull(PerfJson.logLine(jank, 1000))
        assertNull(parseJsonTree(jankLine)?.get("id"))
        assertNull(PerfJson.logLine(FrameSecond(1, 60, 0, 0, 0, 10.0, 0, 0, 0, 0, null), 1000), "no line for a second without jank")
    }

    @Test
    fun theLogSinkCapsLinesPerSecondAndCountsTheRest() {
        val lines = ArrayList<String>()
        var now = 10_000L
        val sink = PerfLogSink(emit = { lines += it }, maxBytes = 1000, clockMs = { now })
        repeat(8) { sink.onItem(MemoryEvent(it.toLong(), now, "trim", 15)) }
        assertEquals(5, lines.size)
        now += 1_000
        sink.onItem(MemoryEvent(9, now, "trim", 15))
        assertEquals("""{"hopi":1,"perf":"notice","suppressed":3}""", lines[5])
        assertTrue(lines[6].contains("\"seq\":9"))
        // Samples: a line every 10 s.
        lines.clear()
        repeat(15) {
            sink.onItem(PerfSample(now, CpuSample(12.5, 0.1, 8), null))
            now += 1_000
        }
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("""{"hopi":1,"perf":"sample","cpu":12.5"""), lines[0])
    }

    private fun hang(seq: Long, stack: List<String> = listOf("a.B.c(B.kt:1)")) = HangEvent(
        seq = seq, atMs = 0, thresholdMs = 250, ended = false, durationMs = 250, screen = "S",
        stacks = listOf(stack), captureUs = listOf(40), stackMissed = false, debugger = false, frozenMs = 0, heartbeat = false,
    )
}
