package dev.koga.hopi.core.concurrent

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

internal actual fun newBlockingSemaphore(): BlockingSemaphore = object : BlockingSemaphore {
    private val semaphore = Semaphore(0)

    override fun acquire() = semaphore.acquireUninterruptibly()

    // An interrupt (Hopi never interrupts its threads) counts as the timeout.
    override fun tryAcquire(nanos: Long): Boolean = try {
        semaphore.tryAcquire(nanos, TimeUnit.NANOSECONDS)
    } catch (_: InterruptedException) {
        false
    }

    override fun release() = semaphore.release()
}
