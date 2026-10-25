package dev.koga.hopi.core.log

import dev.koga.hopi.core.store.HopiStore
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.invoke
import kotlinx.cinterop.staticCFunction
import platform.Foundation.NSGetUncaughtExceptionHandler
import platform.Foundation.NSSetUncaughtExceptionHandler
import platform.Foundation.NSUncaughtExceptionHandler
import platform.posix.atexit

private val exitStore = AtomicReference<HopiStore?>(null)

@OptIn(ExperimentalForeignApi::class)
private val previousHandler = AtomicReference<CPointer<NSUncaughtExceptionHandler>?>(null)

@OptIn(ExperimentalForeignApi::class)
internal actual fun applyBeforeProcessDeath(store: HopiStore) {
    if (!exitStore.compareAndSet(null, store)) return
    previousHandler.store(NSGetUncaughtExceptionHandler())
    NSSetUncaughtExceptionHandler(
        staticCFunction { exception ->
            applyForExit()
            previousHandler.load()?.invoke(exception)
        },
    )
    atexit(staticCFunction<Unit> { applyForExit() })
}

private fun applyForExit() {
    try {
        exitStore.load()?.awaitApplied(EXIT_APPLY_TIMEOUT_MS)
    } catch (_: Throwable) {
        // The process goes on dying whatever happens here.
    }
}
