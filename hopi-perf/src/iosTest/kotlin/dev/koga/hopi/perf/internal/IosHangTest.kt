package dev.koga.hopi.perf.internal

import dev.koga.hopi.core.perf.HangEvent
import dev.koga.hopi.core.perf.StallEvent
import dev.koga.hopi.perf.HopiMonitor
import dev.koga.hopi.perf.HopiPerf
import dev.koga.hopi.perf.internal.native.hopi_perf_observers_installed
import dev.koga.hopi.perf.internal.native.hopi_perf_watchdog_wakeups
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import platform.Foundation.NSThread
import platform.Foundation.NSTimer

/**
 * The iOS hang monitor and frames tier 1 on the simulator, on the real main run loop: a 600 ms
 * block is one hang whose stack names the blocking function, a 150 ms one is none, a main thread
 * that never sleeps through 1 s of 5 ms timer callbacks is none, an idle main thread wakes the
 * watchdog not at all while it sleeps, and nothing exists before a start or after a stop.
 */
class IosHangTest {
    private var since = 0L

    @BeforeTest
    fun setUp() {
        assertTrue(NSThread.isMainThread, "the tests drive the main run loop from the main thread")
        HopiPerf.install { summaryLog = false }
        since = perfData().lastSeq
    }

    @AfterTest
    fun tearDown() {
        stopAll()
    }

    private fun hangs(): List<HangEvent> = perfData().eventsSince(since).filterIsInstance<HangEvent>()

    private fun stalls(): List<StallEvent> = perfData().eventsSince(since).filterIsInstance<StallEvent>()

    @Test
    fun offHasNoThreadAndNoObserver() {
        assertEquals(emptyList(), perfThreads(), "no hopi-perf thread while every monitor is off")
        assertFalse(hopi_perf_observers_installed(), "no run-loop observer while every monitor is off")
        HopiPerf.start { hangs = true }
        assertTrue(hopi_perf_observers_installed())
        // NSThread names its pthread once it runs.
        assertTrue(runMainLoopUntil(1_000) { perfThreads().isNotEmpty() })
        assertEquals(listOf(IosHangMonitor.THREAD_NAME), perfThreads())
        stopAll()
        assertFalse(hopi_perf_observers_installed(), "the observers are removed at stop")
        assertEquals(emptyList(), perfThreads(), "the watchdog ended")
    }

    @Test
    fun aSixHundredMillisecondBlockIsOneHangNamingTheBlockingFunction() {
        HopiPerf.start { hangs = true }
        runMainLoop(0.1)
        onMain { hopiTestBurnMain(600) }
        assertTrue(runMainLoopUntil(3_000) { hangs().any { it.ended } }, "the hang ended: ${hangs().map { it.durationMs }}")
        val hang = hangs().single()
        assertFalse(hang.debugger)
        assertTrue(hang.durationMs in 550..900, "duration ${hang.durationMs}")
        assertTrue(hang.stacks.isNotEmpty(), "at least one stack (missed: ${hang.stackMissed})")
        assertTrue(hang.stacks.size <= 3)
        val stack = hang.stacks.first()
        assertTrue(stack.any { "hopiTestBurnMain" in it }, "the stack names the blocking function:\n${stack.joinToString("\n")}")
        assertTrue(hang.captureUs.all { it < 50_000 }, "suspensions ${hang.captureUs} µs")
    }

    @Test
    fun aHundredFiftyMillisecondBlockIsNone() {
        HopiPerf.start { hangs = true }
        runMainLoop(0.1)
        onMain { hopiTestBurnMain(150) }
        runMainLoop(0.6)
        assertEquals(emptyList(), hangs().map { it.durationMs })
    }

    @Test
    fun aMainThreadThatNeverSleepsThroughShortSourcesIsNoHang() {
        HopiPerf.start { hangs = true }
        val timer = NSTimer.scheduledTimerWithTimeInterval(0.005, repeats = true) { hopiTestBurnMain(5) }
        runMainLoop(1.0)
        timer.invalidate()
        runMainLoop(0.1)
        assertEquals(emptyList(), hangs().map { it.durationMs })
    }

    @Test
    fun anIdleMainThreadLeavesTheWatchdogAsleep() {
        HopiPerf.start { hangs = true }
        // Past the watchdog's one doze after the last busy unit.
        runMainLoop(0.6)
        val before = hopi_perf_watchdog_wakeups()
        // One iteration that sleeps 2 s: at most the begins around that sleep wake the watchdog.
        runMainLoop(2.0)
        val woken = hopi_perf_watchdog_wakeups() - before
        assertTrue(woken <= 3, "the watchdog woke $woken times while the main thread slept 2 s")
    }

    @Test
    fun framesReportUnitsOverTwiceTheDeadlineAsStalls() {
        HopiPerf.start { frames = true }
        assertTrue(hopi_perf_observers_installed(), "frames alone installs the observers")
        assertEquals(emptyList(), perfThreads(), "frames tier 1 runs no thread")
        runMainLoop(0.1)
        onMain { hopiTestBurnMain(80) }
        assertTrue(runMainLoopUntil(2_000) { stalls().isNotEmpty() }, "a stall")
        onMain { hopiTestBurnMain(5) }
        runMainLoop(0.2)
        val durations = stalls().map { it.durationMs }
        assertEquals(1, durations.size, "one stall: $durations")
        assertTrue(durations.single() in 75..200, "stall ${durations.single()} ms")
    }

    @Test
    fun stoppingHangsKeepsTheObserversWhileFramesRuns() {
        HopiPerf.start {
            frames = true
            hangs = true
        }
        HopiPerf.stop(HopiMonitor.Hangs)
        assertTrue(hopi_perf_observers_installed(), "frames still needs them")
        HopiPerf.stop(HopiMonitor.Frames)
        assertFalse(hopi_perf_observers_installed())
    }
}
