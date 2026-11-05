package dev.koga.hopi.core.store

import kotlin.concurrent.atomics.AtomicReference

/**
 * The caps the process-wide store will be created with ([HopiStore.presetDefault]), apart from
 * [HopiStore]: the start sequence sets them on Android's main thread, and reaching the store's
 * companion there would load and initialize the store's class, which the first reader loads anyway,
 * on its own thread.
 */
internal object DefaultStoreCaps {
    class Caps(val maxEntries: Int, val maxTotalBytes: Long)

    private val preset = AtomicReference<Caps?>(null)

    /** The caps set, or null while none were. */
    val current: Caps? get() = preset.load()

    /** The first preset wins; a later one returns false and changes nothing. */
    fun preset(maxEntries: Int, maxTotalBytes: Long): Boolean = preset.compareAndSet(null, Caps(maxEntries, maxTotalBytes))

    fun clear() {
        preset.store(null)
    }
}
