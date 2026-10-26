package dev.koga.hopi.core.perf

import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The watchdog on a real thread and clock: a stall over the threshold is one hang with the
 * "main" thread's stack, a short one none, and an idle main thread leaves the watchdog asleep
 * with no timer (no wake at all).
 */
class HangWatchdogTest {
    private val busy = BusyState()
    private val data = PerfData()
    private lateinit var main: Thread
    private val platform = object : HangPlatform {
        override fun nowMs(): Long = System.nanoTime() / 1_000_000

        override fun monotonicNs(): Long = System.nanoTime()

        override fun captureMainStack(): List<String> = main.stackTrace.map { it.toString() }

        override fun debuggerAttached(): Boolean = false

        override fun postHeartbeat() = Unit
    }
    private val detector = HangDetector(busy, platform, data, thresholdMs = 150)
    private val watchdog = HangWatchdog(busy, detector, platform::nowMs)
    private val watchdogThread = thread(name = "hopi-perf-hang") { watchdog.run() }

    @AfterTest
    fun tearDown() {
        watchdog.stop()
        watchdogThread.join(2_000)
    }

    private fun unit(ms: Long) {
        busy.begin(platform.nowMs())
        stall(ms)
        busy.end(platform.nowMs())
    }

    private fun stall(ms: Long) = Thread.sleep(ms)

    @Test
    fun aStallOverTheThresholdIsOneHangWithTheMainStack() {
        main = Thread.currentThread()
        unit(50)
        Thread.sleep(400)
        assertEquals(0, data.hangs.count, "50 ms is no hang")
        // Idle long enough for the one doze: the watchdog sleeps with no timer.
        val deadline = System.nanoTime() + 2_000_000_000
        while (!busy.signal.sleeping && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(busy.signal.sleeping)
        val wakes = busy.signal.wakeups
        Thread.sleep(300)
        assertEquals(wakes, busy.signal.wakeups, "an idle main thread wakes nothing")

        unit(700)
        val end = System.nanoTime() + 2_000_000_000
        while ((data.hangs.since(0).singleOrNull()?.ended != true) && System.nanoTime() < end) Thread.sleep(5)
        val hang = data.hangs.since(0).single()
        assertTrue(hang.ended)
        assertTrue(hang.durationMs in 650..1_500, "${hang.durationMs}")
        assertEquals(3, hang.stacks.size, "stacks at 150, 300 and 600 ms")
        assertTrue(hang.stacks.all { stack -> stack.any { "stall" in it } }, hang.stacks.toString())
    }
}

/**
 * A lease in force (a CLI `perf start hangs` gives one of 600 s) bounds the idle sleep but must not
 * blind it: the watchdog still parks, so the idle-to-busy transition of a hang wakes it.
 */
class LeasedHangWatchdogTest {
    private val busy = BusyState()
    private val data = PerfData()
    private val platform = object : HangPlatform {
        override fun nowMs(): Long = System.nanoTime() / 1_000_000

        override fun monotonicNs(): Long = System.nanoTime()

        override fun captureMainStack(): List<String> = listOf("lease.test")

        override fun debuggerAttached(): Boolean = false

        override fun postHeartbeat() = Unit
    }
    private val lease = object : PerfTimer {
        override fun msUntilDue(): Long = 600_000

        override fun fire() = Unit
    }
    private val detector = HangDetector(busy, platform, data, thresholdMs = 150)
    private val watchdog = HangWatchdog(busy, detector, platform::nowMs, lease)
    private val watchdogThread = thread(name = "hopi-perf-hang") { watchdog.run() }

    @AfterTest
    fun tearDown() {
        watchdog.stop()
        watchdogThread.join(2_000)
    }

    @Test
    fun aHangUnderALeaseIsDetectedAfterAnIdleStretch() {
        busy.begin(platform.nowMs())
        busy.end(platform.nowMs())
        // Idle past the deadline and any doze: the watchdog is in its lease-bounded sleep, parked.
        Thread.sleep(600)
        assertTrue(busy.signal.dozing, "the watchdog sleeps, bounded by the lease")

        busy.begin(platform.nowMs())
        Thread.sleep(500)
        assertTrue(data.hangs.count == 1, "a 500 ms unit under a lease was not detected")
        busy.end(platform.nowMs())
        val end = System.nanoTime() + 2_000_000_000
        while (data.hangs.since(0).single().ended.not() && System.nanoTime() < end) Thread.sleep(5)
        val hang = data.hangs.since(0).single()
        assertTrue(hang.ended && hang.durationMs in 450..1_500, "$hang")
    }
}
