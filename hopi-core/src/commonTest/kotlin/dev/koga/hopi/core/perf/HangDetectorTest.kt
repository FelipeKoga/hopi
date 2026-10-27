package dev.koga.hopi.core.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The hang watchdog's state machine on a fake clock (docs/design/perf-monitors.md, "Tests"). */
class HangDetectorTest {
    private class FakePlatform : HangPlatform {
        var now = 0L
        var debugger = false
        var stack = listOf("dev.koga.hopi.sample.PerfLab.hang(PerfLab.kt:41)", "android.os.Handler.dispatchMessage(Handler.java:102)")
        var onCapture: () -> Unit = {}
        var captures = 0
        var heartbeats = 0
        var names: ((List<String>) -> List<String>)? = null

        override fun nowMs(): Long = now

        override fun monotonicNs(): Long = now * 1_000_000

        override fun captureMainStack(): List<String> {
            captures++
            onCapture()
            return stack
        }

        override fun symbolicate(stack: List<String>): List<String>? = names?.invoke(stack)

        override fun debuggerAttached(): Boolean = debugger

        override fun postHeartbeat() {
            heartbeats++
        }
    }

    private val busy = BusyState()
    private val platform = FakePlatform()
    private val data = PerfData()
    private val logged = ArrayList<String>()
    private val detector = HangDetector(busy, platform, data, thresholdMs = 250)

    init {
        data.setLogSink(PerfLogSink(emit = { logged += it }, maxBytes = 1000, clockMs = { platform.now }))
    }

    private fun next(at: Long): HangWait {
        platform.now = at
        return detector.next(at)
    }

    /**
     * iOS names raw addresses with `dladdr`, which takes the dyld lock: only once the unit ended, so
     * the naming never holds the lock against the hung main thread finishing its unit.
     */
    @Test
    fun rawStacksAreRecordedAndLoggedFirstAndNamedOnlyOnceTheUnitEnded() {
        platform.stack = listOf("0x0000000100001000", "0x0000000100002000")
        var namings = 0
        platform.names = { raw -> namings++; raw.map { "app sym$it" } }
        busy.begin(1_000)
        next(1_000)
        next(1_250)
        // At detection: the raw line only; nothing named while the unit runs.
        assertEquals(1, logged.size, logged.toString())
        assertTrue(logged[0].contains("0x0000000100001000") && !logged[0].contains("sym"), logged[0])
        next(1_500)
        assertEquals(0, namings, "named while the hung unit still ran")
        assertTrue(data.hangs.since(0).single().stacks.all { frames -> frames.all { it.startsWith("0x") } })
        busy.end(1_600)
        next(1_600)
        val done = data.hangs.since(0).single()
        assertTrue(done.ended)
        assertEquals(600, done.durationMs)
        assertEquals(2, done.stacks.size)
        assertTrue(done.stacks.all { frames -> frames.all { it.startsWith("app sym") } }, done.stacks.toString())
        assertEquals(2, namings)
        // The named stack line, then the ended line.
        assertEquals(3, logged.size, logged.toString())
        assertTrue(logged[1].contains("app sym0x0000000100001000"), logged[1])
        assertTrue(logged[2].contains("\"state\":\"ended\""), logged[2])
    }

    @Test
    fun anIdleMainThreadDozesOnceThenSleepsUntimed() {
        assertEquals(HangWait.Until(250, busy = false), next(0))
        assertEquals(HangWait.Untimed, next(250))
        assertEquals(HangWait.Untimed, next(10_000))
        assertEquals(0, platform.captures)
    }

    @Test
    fun aUnitUnderTheThresholdIsNoHang() {
        busy.begin(1_000)
        assertEquals(HangWait.Until(1_250, busy = true), next(1_000))
        busy.end(1_200)
        // Nothing ran after it: no idle doze, straight to the untimed sleep.
        assertEquals(HangWait.Untimed, next(1_250))
        assertEquals(0, data.hangs.count)
        // A new unit is watched from its own start.
        busy.begin(1_400)
        assertEquals(HangWait.Until(1_650, busy = true), next(1_500))
    }

    /**
     * A lone short unit costs the watchdog two wakes (its begin's, its deadline's), not three; units
     * that ran in between mean an active main thread, and the one idle doze absorbs their wakes.
     */
    @Test
    fun aLoneShortUnitSkipsTheIdleDozeAndABurstKeepsIt() {
        assertEquals(HangWait.Until(250, busy = false), next(0))
        assertEquals(HangWait.Untimed, next(250))
        busy.begin(1_000)
        assertEquals(HangWait.Until(1_250, busy = true), next(1_001))
        busy.end(1_005)
        assertEquals(HangWait.Untimed, next(1_250))
        // A burst: the unit watched ended, and two more ran before the deadline.
        busy.begin(2_000)
        assertEquals(HangWait.Until(2_250, busy = true), next(2_001))
        busy.end(2_005)
        busy.begin(2_100)
        busy.end(2_105)
        busy.begin(2_200)
        busy.end(2_205)
        assertEquals(HangWait.Until(2_500, busy = false), next(2_250))
        assertEquals(HangWait.Untimed, next(2_500))
        assertEquals(0, data.hangs.count)
    }

    @Test
    fun aHangIsCapturedAtOneTwoAndFourTimesTheThresholdThenCompleted() {
        busy.begin(1_000)
        next(1_000)
        assertEquals(HangWait.Until(1_500, busy = true), next(1_250))
        val first = data.hangs.since(0).single()
        assertFalse(first.ended)
        assertEquals(250, first.durationMs)
        assertEquals(1, first.stacks.size)
        assertTrue(logged.single().contains("\"state\":\"ongoing\""), logged.toString())
        assertTrue(logged.single().contains("PerfLab.hang"), logged.single())
        assertEquals(HangWait.Until(2_000, busy = true), next(1_500))
        assertEquals(HangWait.UntilEnd, next(2_000))
        assertEquals(3, data.hangs.since(0).single().stacks.size)
        assertEquals(3, platform.captures)
        busy.end(2_300)
        assertEquals(HangWait.Untimed, next(2_301))
        val done = data.hangs.since(0).single()
        assertTrue(done.ended)
        assertEquals(1_300, done.durationMs)
        assertEquals(first.seq, done.seq)
        assertEquals(2, logged.size, "one line at detection, one at the end")
        assertTrue(logged[1].contains("\"state\":\"ended\"") && logged[1].contains("\"ms\":1300"), logged[1])
        assertEquals(1, data.summary().hangs)
        assertEquals(1_300, data.summary().hangWorstMs)
    }

    @Test
    fun aUnitThatEndsDuringTheCaptureLeavesNoStack() {
        busy.begin(1_000)
        next(1_000)
        platform.onCapture = { busy.end(1_255) }
        // Detected (260 ms is over the threshold), but the stack is another unit's: discarded, and
        // the hang is complete in the same pass.
        assertEquals(HangWait.Untimed, next(1_260))
        val event = data.hangs.since(0).single()
        assertTrue(event.stackMissed)
        assertTrue(event.stacks.isEmpty())
        assertTrue(event.ended)
        assertEquals(1, detector.discardedCaptures)
    }

    @Test
    fun aStoppedProcessIsNoHang() {
        busy.begin(1_000)
        next(1_000)
        // The watchdog wakes 3.75 s late: the freezer, or a debugger that suspended every thread.
        assertEquals(HangWait.Until(5_250, busy = true), next(5_000))
        assertEquals(0, data.hangs.count)
        // Still running one threshold after the thaw: a hang, with the frozen span apart.
        next(5_250)
        val event = data.hangs.since(0).single()
        assertEquals(250, event.durationMs)
        assertEquals(3_750, event.frozenMs)
    }

    @Test
    fun aDetectionUnderADebuggerTakesNoStackAndIsNotCounted() {
        platform.debugger = true
        busy.begin(1_000)
        next(1_000)
        assertEquals(HangWait.UntilEnd, next(1_250))
        assertEquals(0, platform.captures)
        val event = data.hangs.since(0).single()
        assertTrue(event.debugger)
        assertFalse(event.stackMissed)
        busy.end(3_000)
        next(3_000)
        assertEquals(0, data.summary().hangs)
        assertEquals(1, data.summary().debuggerHangs)
    }

    @Test
    fun theThresholdCanChangeWhileRunning() {
        detector.thresholdMs = 1_000
        busy.begin(1_000)
        assertEquals(HangWait.Until(2_000, busy = true), next(1_000))
    }

    @Test
    fun aBeginWakesTheWatchdogOnlyWhenItSleepsUntimed() {
        // Awake or in a timed sleep: no wake at all.
        busy.begin(10)
        busy.end(11)
        assertFalse(busy.signal.doze(1_000_000), "nothing woke a timed sleep")
        // Parked with no timeout: the idle-to-busy transition wakes it, once.
        assertTrue(busy.parkWatchdog())
        busy.begin(20)
        assertTrue(busy.signal.doze(1_000_000))
        busy.end(21)
        busy.begin(22)
        assertFalse(busy.signal.doze(1_000_000), "the flag was consumed by the first begin")
        busy.end(23)
        // Busy when it tries to park: it does not.
        busy.begin(30)
        assertFalse(busy.parkWatchdog())
    }

    @Test
    fun theHeartbeatTreatsAnUnrunBeatAsBusy() {
        val heartbeat = HangDetector(busy, platform, data, thresholdMs = 250, heartbeat = true)
        platform.now = 1_000
        // Posted; the watchdog looks again when the next beat is due, not at this one's deadline.
        assertEquals(HangWait.Until(1_125, busy = true), heartbeat.next(1_000))
        assertEquals(1, platform.heartbeats)
        // The beat ran; the next one is posted half a threshold after the last.
        busy.end(1_010)
        platform.now = 1_125
        assertEquals(HangWait.Until(1_250, busy = true), heartbeat.next(1_125))
        assertEquals(2, platform.heartbeats)
        // This beat does not run: none is posted behind it, and the watchdog sleeps to its deadline...
        platform.now = 1_250
        assertEquals(HangWait.Until(1_375, busy = true), heartbeat.next(1_250))
        assertEquals(2, platform.heartbeats)
        // ...where it is a hang, flagged as the heartbeat's.
        platform.now = 1_375
        heartbeat.next(1_375)
        assertTrue(data.hangs.since(0).single().heartbeat)
    }

    /** The documented cadence: a beat every half threshold while main is idle, 8 a second at 250 ms on each thread. */
    @Test
    fun anIdleMainThreadGetsABeatEveryHalfThreshold() {
        val heartbeat = HangDetector(busy, platform, data, thresholdMs = 250, heartbeat = true)
        var now = 1_000L
        var wakes = 0
        while (now < 2_000) {
            platform.now = now
            val wait = assertIs<HangWait.Until>(heartbeat.next(now))
            wakes++
            // Main runs the beat at once.
            busy.end(now + 1)
            now = wait.deadlineMs
        }
        assertEquals(8, platform.heartbeats, "beats in one second")
        assertEquals(8, wakes, "watchdog wakes in one second")
        assertEquals(0, data.hangs.count)
    }

    /** An input unit ends without a wake too: one seen when the watchdog looks never holds the next beat back to its deadline. */
    @Test
    fun anInputUnitSeenAtACheckDoesNotDelayTheNextBeat() {
        val heartbeat = HangDetector(busy, platform, data, thresholdMs = 250, heartbeat = true)
        platform.now = 1_000
        assertEquals(HangWait.Until(1_125, busy = true), heartbeat.next(1_000))
        busy.end(1_010)
        // A tap is being dispatched when the watchdog looks: it looks again in half a threshold,
        // not at the tap's deadline (1_350).
        assertTrue(busy.enterInput(1_100))
        platform.now = 1_125
        assertEquals(HangWait.Until(1_250, busy = true), heartbeat.next(1_125))
        assertEquals(1, platform.heartbeats)
        busy.exitInput(1_130)
        // Main is idle again: the next beat goes out then.
        platform.now = 1_250
        assertEquals(HangWait.Until(1_375, busy = true), heartbeat.next(1_250))
        assertEquals(2, platform.heartbeats)
        assertEquals(0, data.hangs.count)
    }
}
