package dev.koga.hopi.perf.internal

import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfHook
import dev.koga.hopi.perf.HopiPerf
import dev.koga.hopi.perf.internal.native.hopi_perf_now_ms
import kotlin.concurrent.atomics.AtomicLong
import kotlin.test.assertNotNull
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** Keeps the burn loops from being optimised away. */
internal val burnSink = AtomicLong(0)

/** Burns the calling thread's CPU for [ms]: the function a hang's stack must name. */
internal fun hopiTestBurnMain(ms: Long) {
    val end = hopi_perf_now_ms() + ms
    var spins = 0L
    while (hopi_perf_now_ms() < end) spins++
    burnSink.store(spins)
}

/** Runs the main run loop (the observed one) for [seconds]; the tests run on the main thread. */
internal fun runMainLoop(seconds: Double) {
    CFRunLoopRunInMode(kCFRunLoopDefaultMode, seconds, false)
}

/** Runs the main run loop in short slices until [condition] holds or [timeoutMs] passed. */
internal fun runMainLoopUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = hopi_perf_now_ms() + timeoutMs
    while (hopi_perf_now_ms() < deadline) {
        if (condition()) return true
        runMainLoop(0.02)
    }
    return condition()
}

/** A block on the main queue, run by the main run loop inside one observed unit. */
internal fun onMain(block: () -> Unit) {
    dispatch_async(dispatch_get_main_queue(), block)
}

internal fun perfData(): PerfData = assertNotNull(PerfHook.controller, "a controller is registered").data

/** The names of this process's `hopi-perf-*` threads now. */
internal fun perfThreads(): List<String> = IosThreadScan().scan().map { it.name }.filter { it.startsWith("hopi-perf") }

/** Stops every monitor and waits (on the main run loop) until their threads ended. */
internal fun stopAll() {
    HopiPerf.stop()
    runMainLoopUntil(3_000) { perfThreads().isEmpty() }
}
