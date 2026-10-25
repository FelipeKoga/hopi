package dev.koga.hopi.core.concurrent

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * [WakeSignal]'s doze: a [WakeSignal.mark] during it records the work without waking the waiter,
 * a [WakeSignal.wake] ends it at once, and no interleaving leaves a permit behind that would make a
 * later untimed [WakeSignal.await] return with nothing to do (the idle guarantee). Runs on the JVM
 * and on the iOS simulator.
 */
class WakeSignalTest {
    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        val mark = TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (mark.elapsedNow() > 10_000.milliseconds) fail("timed out waiting for $what")
            delay(1)
        }
    }

    /** Runs [body] on a thread of its own; [done] turns true when it returned. */
    private class Waiter(body: () -> Unit) {
        private val finished = AtomicBoolean(false)
        val done: Boolean get() = finished.load()

        init {
            startBackgroundThread("test-waiter") {
                body()
                finished.store(true)
            }
        }
    }

    @Test
    fun aDozeWithNothingMarkedEndsFalseAndLeavesNoPermit() {
        val signal = WakeSignal()
        assertFalse(signal.doze(1_000_000))
        assertEquals(1, signal.dozeTimeouts)
        val waiter = Waiter { signal.await() }
        waitFor("the untimed sleep") { signal.sleeping }
        runBlocking { delay(100) }
        assertFalse(waiter.done, "await returned with nothing to do")
        signal.wake()
        waitFor("the wake") { waiter.done }
        assertEquals(1, signal.wakeups)
    }

    @Test
    fun aMarkDuringADozeIsTakenWhenItEnds() {
        val signal = WakeSignal()
        val result = AtomicInt(-1)
        val elapsed = AtomicLong(0)
        val waiter = Waiter {
            val mark = TimeSource.Monotonic.markNow()
            result.store(if (signal.doze(300_000_000)) 1 else 0)
            elapsed.store(mark.elapsedNow().inWholeMilliseconds)
        }
        // The waiter is dozing once a mark finds it so; give it the moment to get there.
        runBlocking { delay(50) }
        signal.mark()
        signal.mark()
        waitFor("the doze to end") { waiter.done }
        assertEquals(1, result.load(), "the marked work was lost")
        assertTrue(elapsed.load() >= 250, "a mark ended the doze early: ${elapsed.load()} ms")
        assertEquals(0, signal.wakeups)
        // Taken: the next doze has nothing.
        assertFalse(signal.doze(1_000_000))
    }

    @Test
    fun aWakeEndsADozeAtOnce() {
        val signal = WakeSignal()
        val result = AtomicInt(-1)
        val waiter = Waiter { result.store(if (signal.doze(60_000_000_000)) 1 else 0) }
        runBlocking { delay(50) }
        signal.mark()
        signal.wake()
        waitFor("the doze to end") { waiter.done }
        assertEquals(1, result.load())
        assertEquals(0, signal.dozeTimeouts)
        assertFalse(signal.doze(1_000_000), "a permit was left behind")
    }

    @Test
    fun aMarkWakesAnUntimedSleep() {
        val signal = WakeSignal()
        val waiter = Waiter { signal.await() }
        waitFor("the untimed sleep") { signal.sleeping }
        signal.mark()
        waitFor("the wake") { waiter.done }
        assertEquals(1, signal.wakeups)
    }

    @Test
    fun workBeforeADozeEndsItAtOnce() {
        val signal = WakeSignal()
        signal.mark()
        assertTrue(signal.doze(60_000_000_000))
        signal.wake()
        assertTrue(signal.doze(60_000_000_000))
        assertEquals(0, signal.dozeTimeouts)
    }

    /**
     * Producers mark and wake while the waiter alternates short dozes and untimed sleeps, so wakes
     * land before, during and after each doze's timeout. Every item is seen, and afterwards the
     * signal holds no stray permit: an untimed sleep stays asleep.
     */
    @Test
    fun noInterleavingLosesWorkOrLeavesAPermit() {
        val signal = WakeSignal()
        val produced = AtomicLong(0)
        val consumed = AtomicLong(0)
        val stop = AtomicBoolean(false)
        val producers = 3
        val perProducer = 20_000
        repeat(producers) { p ->
            startBackgroundThread("test-producer-$p") {
                for (i in 0 until perProducer) {
                    produced.incrementAndFetch()
                    if ((i + p) % 7 == 0) signal.wake() else signal.mark()
                }
            }
        }
        val consumer = Waiter {
            while (!stop.load()) {
                signal.await()
                do {
                    consumed.store(produced.load())
                } while (!stop.load() && signal.doze(20_000))
            }
        }
        val total = producers.toLong() * perProducer
        waitFor("every item") { produced.load() == total && consumed.load() == total }
        stop.store(true)
        signal.wake()
        waitFor("the consumer to stop") { consumer.done }
        // The last wake may still be unconsumed (a state, which a doze takes); a stray permit may not.
        signal.doze(1_000_000)
        assertFalse(signal.doze(1_000_000), "work left after every item was consumed")
        // No stray permit: a fresh untimed sleep must stay asleep until woken.
        val after = Waiter { signal.await() }
        waitFor("the untimed sleep") { signal.sleeping || after.done }
        runBlocking { delay(100) }
        assertFalse(after.done, "a permit was left behind")
        signal.wake()
        waitFor("the last wake") { after.done }
    }
}
