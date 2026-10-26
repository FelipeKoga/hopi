package dev.koga.hopi.core.perf

import java.util.concurrent.locks.ReentrantLock

/** A `ReentrantLock`: one compare-and-set when free, a park (no spin) when held. */
internal actual class PlatformLock actual constructor() {
    private val lock = ReentrantLock()

    actual fun lock() {
        lock.lock()
    }

    actual fun unlock() {
        lock.unlock()
    }
}
