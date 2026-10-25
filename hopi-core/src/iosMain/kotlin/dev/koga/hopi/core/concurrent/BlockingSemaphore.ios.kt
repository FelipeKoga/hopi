package dev.koga.hopi.core.concurrent

import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

/**
 * A dispatch semaphore: `wait` with `DISPATCH_TIME_FOREVER` blocks in the kernel with no timeout
 * (unlike a coroutine event loop parked on a Kotlin/Native worker, which wakes on a timer).
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun newBlockingSemaphore(): BlockingSemaphore = object : BlockingSemaphore {
    private val semaphore = dispatch_semaphore_create(0)

    override fun acquire() {
        dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER)
    }

    // Non-zero: the timeout passed without a signal.
    override fun tryAcquire(nanos: Long): Boolean = dispatch_semaphore_wait(semaphore, dispatch_time(DISPATCH_TIME_NOW, nanos)) == 0L

    override fun release() {
        dispatch_semaphore_signal(semaphore)
    }
}
