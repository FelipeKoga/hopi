package dev.koga.hopi.core.concurrent

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch

/** A counting semaphore whose [acquire] blocks the calling thread with no timeout, so no timer wakes it. */
internal interface BlockingSemaphore {
    fun acquire()

    /** [acquire] for at most [nanos]; false when the time passed without a permit. */
    fun tryAcquire(nanos: Long): Boolean

    fun release()
}

/** A semaphore with no permits: `java.util.concurrent.Semaphore` on the JVM, a dispatch semaphore on iOS. */
internal expect fun newBlockingSemaphore(): BlockingSemaphore

/**
 * Wakes one waiting thread, with no polling: [await] blocks with no timer until [wake] or [mark]
 * was called since the last [await] or [doze] returned, and returns at once if one was. [wake] is
 * one atomic exchange while the waiter is awake, and a semaphore release (one futex or Mach wake)
 * only when it sleeps, so a producer that finds the consumer busy pays no system call.
 *
 * [doze] is the waiter's short, timed sleep after a busy stretch, during which [mark] only records
 * the work (one compare-and-set, no system call) and the waiter takes it when the time is up: a
 * burst of producers pays one wake, for its first item, instead of one per item. [mark] wakes a
 * waiter in [await] like [wake]; [wake] always ends a [doze] at once. One waiter thread per signal.
 */
internal class WakeSignal {
    private val state = AtomicInt(EMPTY)
    private val semaphore = newBlockingSemaphore()
    private val wakes = AtomicLong(0)
    private val dozes = AtomicLong(0)

    /** How many times [await] slept and was woken; a test checks it stays put while nothing happens. */
    val wakeups: Long get() = wakes.load()

    /** How many [doze]s slept until their time was up (work [mark]ed meanwhile or none). */
    val dozeTimeouts: Long get() = dozes.load()

    /** True while the waiter is blocked in [await], with no timer. */
    val sleeping: Boolean get() = state.load() == PARKED

    /** True while the waiter is in a [doze] (tests). */
    val dozing: Boolean get() = state.load().let { it == DOZING || it == MARKED }

    /** Wakes the waiter now, also out of a [doze]. */
    fun wake() {
        when (state.exchange(NOTIFIED)) {
            // A dozing waiter that was marked is still asleep: it needs the permit as much.
            PARKED, DOZING, MARKED -> semaphore.release()
        }
    }

    /**
     * Records work for the waiter: while it dozes, without waking it (it takes the work when the
     * doze ends); while it sleeps in [await], by waking it ([wake]); while it is awake, as [wake].
     */
    fun mark() {
        while (true) {
            when (val current = state.load()) {
                NOTIFIED, MARKED -> return
                DOZING -> if (state.compareAndSet(DOZING, MARKED)) return
                EMPTY -> if (state.compareAndSet(EMPTY, NOTIFIED)) return
                else -> {
                    // PARKED: the first item after the waiter went to sleep with no timer wakes it.
                    if (state.compareAndSet(current, NOTIFIED)) {
                        semaphore.release()
                        return
                    }
                }
            }
        }
    }

    fun await() {
        while (true) {
            when (state.load()) {
                NOTIFIED -> if (state.compareAndSet(NOTIFIED, EMPTY)) return
                EMPTY -> if (state.compareAndSet(EMPTY, PARKED)) {
                    // A wake that saw PARKED released exactly one permit; after it the state is NOTIFIED.
                    semaphore.acquire()
                    wakes.incrementAndFetch()
                }
                else -> semaphore.acquire()
            }
        }
    }

    /**
     * Sleeps at most [nanos], unless work is already there. Returns true when work arrived (a
     * [wake], which ends the doze at once, or a [mark], taken when the time is up), false when
     * none did: the waiter then calls [await], which sleeps with no timer.
     */
    fun doze(nanos: Long): Boolean {
        while (true) {
            when (state.load()) {
                NOTIFIED -> if (state.compareAndSet(NOTIFIED, EMPTY)) return true
                EMPTY -> if (state.compareAndSet(EMPTY, DOZING)) break
                // Only the waiter dozes, so nothing else is possible here.
                else -> return true
            }
        }
        if (semaphore.tryAcquire(nanos)) {
            // Only a wake that saw DOZING or MARKED released it, and left NOTIFIED.
            state.store(EMPTY)
            return true
        }
        dozes.incrementAndFetch()
        while (true) {
            when (state.load()) {
                DOZING -> if (state.compareAndSet(DOZING, EMPTY)) return false
                MARKED -> if (state.compareAndSet(MARKED, EMPTY)) return true
                else -> {
                    // NOTIFIED: a wake saw DOZING or MARKED after the time was up, and releases one
                    // permit; taken here, or the next [await] would return without a wake.
                    semaphore.acquire()
                    state.store(EMPTY)
                    return true
                }
            }
        }
    }

    private companion object {
        const val EMPTY = 0
        const val NOTIFIED = 1
        const val PARKED = 2
        const val DOZING = 3
        const val MARKED = 4
    }
}
