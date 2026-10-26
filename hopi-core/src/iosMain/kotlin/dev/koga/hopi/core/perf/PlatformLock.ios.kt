package dev.koga.hopi.core.perf

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.darwin.os_unfair_lock_lock
import platform.darwin.os_unfair_lock_s
import platform.darwin.os_unfair_lock_unlock

/**
 * An `os_unfair_lock`: one compare-and-set when free; when held, the waiter sleeps in the kernel
 * and lends its priority to the holder. Its 4 bytes live on the native heap, freed with the lock.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal actual class PlatformLock actual constructor() {
    private val native = nativeHeap.alloc<os_unfair_lock_s>().also { it._os_unfair_lock_opaque = 0u }

    @Suppress("unused")
    private val cleaner = createCleaner(native) { nativeHeap.free(it) }

    actual fun lock() {
        os_unfair_lock_lock(native.ptr)
    }

    actual fun unlock() {
        os_unfair_lock_unlock(native.ptr)
    }
}
