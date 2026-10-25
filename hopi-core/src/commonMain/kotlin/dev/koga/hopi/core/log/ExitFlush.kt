package dev.koga.hopi.core.log

import dev.koga.hopi.core.store.HopiStore

/**
 * Makes the process apply what [store] still holds before it dies, so a request that ended in the
 * store's batch window (no viewer attached) still gets its summary line when the app crashes or
 * exits right after it: the case a developer debugging a crash-causing response needs most.
 *
 * Android and the JVM: an uncaught-exception handler that waits for the store, then calls the one
 * it replaced (the platform's, or a crash reporter's); and a shutdown hook, for `exitProcess`.
 * iOS: an uncaught Objective-C exception handler chained the same way, and an `atexit` handler.
 * Nothing runs on a request's path or while the app is idle. What no process can intercept (a
 * `SIGKILL`, `abort()`, a Swift trap, Kotlin/Native's own termination on an uncaught Kotlin
 * exception) can still lose the records of the last [HopiStore.batchWindowMillis]. Installed once
 * per process, with the summary log, for the default store.
 */
internal expect fun applyBeforeProcessDeath(store: HopiStore)

/** The most a dying process waits for the store's pass: the pass itself takes well under a millisecond. */
internal const val EXIT_APPLY_TIMEOUT_MS: Long = 200
