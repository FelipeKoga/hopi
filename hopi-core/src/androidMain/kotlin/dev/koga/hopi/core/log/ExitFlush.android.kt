package dev.koga.hopi.core.log

import dev.koga.hopi.core.store.HopiStore

internal actual fun applyBeforeProcessDeath(store: HopiStore) {
    Thread.setDefaultUncaughtExceptionHandler(ApplyingExceptionHandler(store, Thread.getDefaultUncaughtExceptionHandler()))
    // Created now, started only by the runtime as the process exits (System.exit, exitProcess).
    Runtime.getRuntime().addShutdownHook(Thread({ store.awaitApplied(EXIT_APPLY_TIMEOUT_MS) }, "hopi-exit"))
}

/**
 * Waits for [store] to apply what it holds, then hands the exception to [previous] (the platform's
 * handler, or a crash reporter's), or, with none, prints it as the JVM's default handler does.
 */
internal class ApplyingExceptionHandler(
    private val store: HopiStore,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            store.awaitApplied(EXIT_APPLY_TIMEOUT_MS)
        } catch (_: Throwable) {
            // The crash goes on to the app's handler whatever happens here.
        }
        if (previous != null) {
            previous.uncaughtException(thread, error)
        } else {
            System.err.print("Exception in thread \"${thread.name}\" ")
            error.printStackTrace()
        }
    }
}
