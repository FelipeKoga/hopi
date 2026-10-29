package dev.koga.hopi.api

import dev.koga.hopi.rules.HopiRulesRuntime
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * The runtimes a debug build registered: one write-once slot per kind, each read with one volatile
 * read. Nothing registers in a release build, so every slot stays empty there and the API that
 * reads it does nothing. A second registration is refused and writes nothing, since this module
 * has no log sink. For the rules slot that is the normal case: core's start registers on each of
 * its calls, and only the first succeeds. For a slot a different runtime could also claim, the
 * runtime refused writes the notice. The rules slot is typed with this module's own
 * [HopiRulesRuntime]; a slot whose interface names types this module cannot see (the Ktor hooks)
 * holds what the runtime registered, and the module that reads it knows the type.
 */
@HopiInternalApi
public object HopiRuntimes {
    private val ktor = AtomicReference<Any?>(null)
    private val rulesRuntime = AtomicReference<HopiRulesRuntime?>(null)
    private val dropped = AtomicInt(0)

    /**
     * The Ktor client hooks the network runtime registered, or null. `hopi-ktor` casts the value to
     * its own hooks interface, which names Ktor types this module cannot see.
     */
    public val ktorHooks: Any?
        get() = ktor.load()

    /** Registers [hooks] as the Ktor client hooks; false, changing nothing, when some are registered. */
    public fun registerKtorHooks(hooks: Any): Boolean = ktor.compareAndSet(null, hooks)

    /** The rules runtime `HopiRules` delegates to, or null: `hopi-core`'s, which its start sequence registers first. */
    public val rules: HopiRulesRuntime?
        get() = rulesRuntime.load()

    /** Registers [runtime] as the rules runtime; false, changing nothing, when one is registered. */
    public fun registerRules(runtime: HopiRulesRuntime): Boolean = rulesRuntime.compareAndSet(null, runtime)

    /**
     * The rules `HopiRules` dropped because no rules runtime was registered (`mock`, `fail`, `delay`
     * and `edit` calls, each of which returned `-1`): read once, by the registration that succeeds,
     * for its notice line.
     */
    public val droppedRules: Int
        get() = dropped.load()

    /** Counts one rule `HopiRules` dropped. */
    internal fun countDroppedRule() {
        dropped.incrementAndFetch()
    }

    /** Empties every slot and the dropped count, for tests that register a runtime of their own. */
    public fun resetForTests() {
        ktor.store(null)
        rulesRuntime.store(null)
        dropped.store(0)
    }
}
