package dev.koga.hopi.core.perf

import dev.koga.hopi.core.concurrent.startBackgroundThread
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** [PerfLock] excludes (a JVM monitor; `os_unfair_lock` on iOS) and is released when its block throws. */
class PerfLockTest {
    private class Counter {
        var value = 0
    }

    @Test
    fun threadsTakingTheLockNeverInterleave() {
        val lock = PerfLock()
        val counter = Counter()
        val done = AtomicInt(0)
        val threads = 4
        val rounds = 20_000
        repeat(threads) { t ->
            startBackgroundThread("perf-lock-$t") {
                repeat(rounds) { lock.locked { counter.value = counter.value + 1 } }
                done.incrementAndFetch()
            }
        }
        val start = TimeSource.Monotonic.markNow()
        while (done.load() < threads) if (start.elapsedNow() > 20.seconds) fail("timed out")
        assertEquals(threads * rounds, lock.locked { counter.value })
    }

    @Test
    fun aBlockThatThrowsReleasesTheLock() {
        val lock = PerfLock()
        assertFailsWith<IllegalStateException> { lock.locked { error("boom") } }
        assertEquals(1, lock.locked { 1 })
    }
}
