package dev.koga.hopi.sample

import kotlin.coroutines.resume
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSThread
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.CLOCK_THREAD_CPUTIME_ID
import platform.posix.CLOCK_UPTIME_RAW
import platform.posix.clock_gettime_nsec_np
import platform.posix.usleep

internal actual fun labSleep(ms: Long) {
    // usleep takes at most a second's worth on some systems: sleep in slices.
    var left = ms
    while (left > 0) {
        val slice = minOf(left, 500L)
        usleep((slice * 1_000).toUInt())
        left -= slice
    }
}

internal actual fun labWallMs(): Long = (NSDate().timeIntervalSince1970 * 1_000).toLong()

internal actual fun labPostMain(block: () -> Unit) {
    dispatch_async(dispatch_get_main_queue(), block)
}

/** NSThreads named before they start, so the per-thread scan (`task_threads`) lists them by name. */
internal actual fun labBurnThreads(names: List<String>, ms: Long) {
    for (name in names) {
        val thread = NSThread { PerfLab.burnOffMain(ms) }
        thread.name = name
        thread.start()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun labAllocNative(bytes: Int): Any {
    val block = nativeHeap.allocArray<ByteVar>(bytes)
    for (offset in 0 until bytes step 4096) block[offset] = 1
    return NativeBlock(block)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun labFreeNative(block: Any) {
    nativeHeap.free((block as NativeBlock).pointer)
}

@OptIn(ExperimentalForeignApi::class)
private class NativeBlock(val pointer: CPointer<ByteVar>)

/** The notification UIKit posts on a memory warning; the lab posts it itself, so it is not OS pressure. */
internal actual fun labMemoryWarning(): Boolean {
    NSNotificationCenter.defaultCenter.postNotificationName(UIApplicationDidReceiveMemoryWarningNotification, null)
    return true
}

internal actual val benchKinds: List<String> = listOf("mainQueue", "coroutine")

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun benchChain(kind: String, count: Int): BenchChain = when (kind) {
    "coroutine" -> withContext(Dispatchers.Main) {
        val starts = LongArray(count + 1)
        val cpuBefore = threadCpuNs()
        for (i in 0..count) {
            starts[i] = nowNs()
            yield()
        }
        BenchChain(gaps(starts), threadCpuNs() - cpuBefore)
    }
    else -> suspendCancellableCoroutine { continuation ->
        val starts = LongArray(count + 1)
        var index = 0
        var cpuBefore = 0L
        fun unit() {
            if (index == 0) cpuBefore = threadCpuNs()
            starts[index++] = nowNs()
            if (index <= count) {
                dispatch_async(dispatch_get_main_queue()) { unit() }
            } else {
                continuation.resume(BenchChain(gaps(starts), threadCpuNs() - cpuBefore))
            }
        }
        dispatch_async(dispatch_get_main_queue()) { unit() }
    }
}

internal actual fun benchGcCount(): Long = -1

@OptIn(ExperimentalForeignApi::class)
private fun nowNs(): Long = clock_gettime_nsec_np(CLOCK_UPTIME_RAW.toUInt()).toLong()

@OptIn(ExperimentalForeignApi::class)
private fun threadCpuNs(): Long = clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID.toUInt()).toLong()

private fun gaps(starts: LongArray): LongArray = LongArray(starts.size - 1) { starts[it + 1] - starts[it] }

@OptIn(kotlin.native.runtime.NativeRuntimeApi::class, ExperimentalStdlibApi::class)
internal actual fun labLastGc(): Pair<Long, String>? {
    val info = kotlin.native.runtime.GC.lastGCInfo ?: return null
    // The GC's times are the runtime's steady clock (CLOCK_UPTIME_RAW on Darwin): onto the wall clock.
    val offsetMs = labWallMs() - nowNs() / 1_000_000
    fun wall(ns: Long?): String = ns?.let { (it / 1_000_000 + offsetMs).toString() } ?: "null"
    fun pause(request: Long?, start: Long?, end: Long?): String =
        "{\"requested\":${wall(request)},\"from\":${wall(start)},\"to\":${wall(end)},\"ms\":${if (request != null && end != null) (end - request) / 1_000_000.0 else "null"}}"
    val first = pause(info.firstPauseRequestTimeNs, info.firstPauseStartTimeNs, info.firstPauseEndTimeNs)
    val second = pause(info.secondPauseRequestTimeNs, info.secondPauseStartTimeNs, info.secondPauseEndTimeNs)
    return info.epoch to "{\"epoch\":${info.epoch},\"from\":${wall(info.startTimeNs)},\"to\":${wall(info.endTimeNs)},\"pauses\":[$first,$second]}"
}
