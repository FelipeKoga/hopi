package dev.koga.hopi.api

import dev.koga.hopi.perf.HopiPerfRuntime
import dev.koga.hopi.rules.HopiRulesRuntime
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * The runtimes a debug build registered: one write-once slot per kind, each read with one volatile
 * read. Nothing registers in a release build, so every slot stays empty there and the API that
 * reads it does nothing. A second registration is refused and writes nothing, since this module
 * has no log sink. For the rules and perf slots that is the normal case: core's start registers
 * the rules runtime on each of its calls, perf's tool its runtime on each of its starts, and only
 * the first succeeds. For a slot a different runtime could also claim, the runtime refused writes
 * the notice. The rules and perf slots are typed with this module's own [HopiRulesRuntime] and
 * [HopiPerfRuntime]; a slot whose interface names types this module cannot see (the Ktor hooks)
 * holds what the runtime registered, and the module that reads it knows the type. The network
 * status ([networkStarted], [okHttpInstrumented]) is a slot of the same kind: `hopi-network`'s
 * network tool writes it once capture runs, on Android and iOS; a debug app's code may read it (the
 * sample prints it); nothing registers it in a release build, where both values are false.
 */
@HopiInternalApi
public object HopiRuntimes {
    private val ktor = AtomicReference<Any?>(null)
    private val rulesRuntime = AtomicReference<HopiRulesRuntime?>(null)
    private val dropped = AtomicInt(0)
    private val perfRuntime = AtomicReference<HopiPerfRuntime?>(null)
    private val droppedPerf = AtomicInt(0)
    private val pendingPerfScreen = AtomicReference<String?>(null)

    // The network status: 0 until a network tool registers, then NETWORK_STARTED, with
    // OKHTTP_INSTRUMENTED when the Android tool found this APK's OkHttp rewritten.
    private val network = AtomicInt(0)

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

    /** The perf runtime `HopiPerf` delegates to, or null: `hopi-perf`'s, which perf's tool registers when it starts. */
    public val perf: HopiPerfRuntime?
        get() = perfRuntime.load()

    /** Registers [runtime] as the perf runtime; false, changing nothing, when one is registered. */
    public fun registerPerf(runtime: HopiPerfRuntime): Boolean = perfRuntime.compareAndSet(null, runtime)

    /**
     * The `HopiPerf.start` calls dropped because no perf runtime was registered: read once, by the
     * registration that succeeds, for its notice line.
     */
    public val droppedPerfStarts: Int
        get() = droppedPerf.load()

    /** Counts one `HopiPerf.start` dropped. */
    internal fun countDroppedPerfStart() {
        droppedPerf.incrementAndFetch()
    }

    /**
     * The last label `HopiPerf.screen` was given (null: none, or none set), which the perf
     * runtime's registration applies: one value, written before `HopiPerf.screen` reads the slot.
     */
    public val perfScreen: String?
        get() = pendingPerfScreen.load()

    /** Keeps [name] as the last `HopiPerf.screen` label. */
    internal fun keepPerfScreen(name: String?) {
        pendingPerfScreen.store(name)
    }

    /**
     * True once a network tool of `hopi-network` started capture in this process (Android's or
     * iOS's, from the start sequence); false without a network runtime, so in every release build.
     */
    public val networkStarted: Boolean
        get() = network.load() != 0

    /**
     * True when the Android network tool found this APK's OkHttp instrumented (its `RealCall` carries
     * Hopi's annotation) as capture started; false on iOS and without a network runtime.
     */
    public val okHttpInstrumented: Boolean
        get() = network.load() and OKHTTP_INSTRUMENTED != 0

    /**
     * Records that a network tool started capture, and whether OkHttp is instrumented; false,
     * changing nothing, when a network tool registered already. One compare-and-set on a field this
     * object already holds: no class loads for it on Android's main thread.
     */
    public fun registerNetwork(okHttpInstrumented: Boolean): Boolean =
        network.compareAndSet(0, if (okHttpInstrumented) NETWORK_STARTED or OKHTTP_INSTRUMENTED else NETWORK_STARTED)

    /** Empties every slot, the dropped counts, the kept label and the network status, for tests that register a runtime of their own. */
    public fun resetForTests() {
        ktor.store(null)
        rulesRuntime.store(null)
        dropped.store(0)
        perfRuntime.store(null)
        droppedPerf.store(0)
        pendingPerfScreen.store(null)
        network.store(0)
    }

    private const val NETWORK_STARTED = 1
    private const val OKHTTP_INSTRUMENTED = 2
}
