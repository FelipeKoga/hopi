package dev.koga.hopi.core.capture

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.perf.PerfLock
import dev.koga.hopi.core.store.HopiStore
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference

/**
 * Full bodies while a host viewer asks for them (bridge `capture.set`): every capture that starts
 * then keeps up to [cap] bytes of a body instead of its installation's cap, and the store keeps
 * room for them. The viewer fetches each body as soon as its request ends, so the bytes end up on
 * the host, as with a proxy; the app's memory stays bounded, by a larger figure, and only while a
 * viewer holds the mode: the last [release] puts the caps back.
 */
@HopiInternalApi
public object FullBodies {
    /** Holders per store: one lock for the counts and the caps they set, so they never disagree. */
    private val lock = PerfLock()
    private val holders = mutableMapOf<HopiStore, Int>()
    private val current = AtomicInt(0)
    private val listeners = AtomicReference<List<(Int) -> Unit>>(emptyList())

    /**
     * What a body is cut back to once a viewer fetched it whole (`get` with `trim`): the largest
     * cap an installation chose, the default one before any did.
     */
    public val trimTo: Int get() = trimCap.load()

    private val trimCap = AtomicInt(256 * 1024)

    /** Records an installation's cap for [trimTo]. */
    public fun installed(maxBodyBytes: Int) {
        while (true) {
            val old = trimCap.load()
            if (maxBodyBytes <= old || trimCap.compareAndSet(old, maxBodyBytes)) return
        }
    }

    /** The cap a capture uses now, 0 while no viewer holds the mode. */
    public val cap: Int get() = current.load()

    /** The cap for a capture that starts now, under an installation whose own cap is [base]. */
    public fun bodyCap(base: Int): Int {
        val full = current.load()
        return if (full > base) full else base
    }

    /**
     * The mode for a viewer of [store]; the returned function gives it back (once). The first
     * holder raises the capture cap, and each store's first holder its byte cap; the last releases
     * put them back.
     */
    public fun acquire(store: HopiStore): () -> Unit {
        lock.locked {
            val before = holders[store] ?: 0
            if (holders.isEmpty()) setCap(platformFullBodyCap())
            holders[store] = before + 1
            if (before == 0) raise(store)
        }
        var released = false
        return {
            lock.locked {
                if (!released) {
                    released = true
                    val left = (holders[store] ?: 1) - 1
                    if (left > 0) {
                        holders[store] = left
                    } else {
                        holders.remove(store)
                        store.raiseByteCap(null)
                        if (holders.isEmpty()) setCap(0)
                    }
                }
            }
        }
    }

    /** Called with the new cap (0: back to the installations' own) when it changes: iOS's native capture. */
    public fun addListener(listener: (Int) -> Unit) {
        while (true) {
            val old = listeners.load()
            if (listeners.compareAndSet(old, old + listener)) return
        }
    }

    private fun setCap(cap: Int) {
        current.store(cap)
        for (listener in listeners.load()) listener(cap)
    }

    /** A request and its response at the cap, plus the history the store keeps anyway. */
    private fun raise(store: HopiStore) {
        store.raiseByteCap(store.baseMaxTotalBytes + 2L * current.load() * 4 / 3)
    }

    /** Tests: no holder, the installations' caps. */
    internal fun resetForTests(store: HopiStore?) {
        lock.locked {
            holders.clear()
            current.store(0)
        }
        store?.raiseByteCap(null)
    }
}

/** The body cap of full-bodies mode on this platform: a share of what the process may allocate. */
internal expect fun platformFullBodyCap(): Int

/** An eighth of a heap of [maxMemory] bytes, from 8 to 64 MiB. */
internal fun fullBodyCapForHeap(maxMemory: Long): Int =
    (maxMemory / 8).coerceIn(8L * 1024 * 1024, 64L * 1024 * 1024).toInt()
