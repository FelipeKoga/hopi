package dev.koga.hopi.api

import kotlin.concurrent.atomics.AtomicReference

/**
 * The runtimes a debug build registered: one write-once slot per kind, each read with one volatile
 * read. This module names no runtime type, so a slot holds what the runtime registered, and the
 * module that reads it knows the type. Nothing registers in a release build, so every slot stays
 * empty there and the API that reads it does nothing. A second registration is refused; the
 * runtime that tried writes the notice, since this module has no log sink.
 */
@HopiInternalApi
public object HopiRuntimes {
    private val ktor = AtomicReference<Any?>(null)

    /**
     * The Ktor client hooks the network runtime registered, or null. `hopi-ktor` casts the value to
     * its own hooks interface, which names Ktor types this module cannot see.
     */
    public val ktorHooks: Any?
        get() = ktor.load()

    /** Registers [hooks] as the Ktor client hooks; false, changing nothing, when some are registered. */
    public fun registerKtorHooks(hooks: Any): Boolean = ktor.compareAndSet(null, hooks)

    /** Empties every slot, for tests that register a runtime of their own. */
    public fun resetForTests() {
        ktor.store(null)
    }
}
