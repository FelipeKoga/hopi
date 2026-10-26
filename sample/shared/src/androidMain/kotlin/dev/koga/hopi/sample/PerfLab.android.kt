package dev.koga.hopi.sample

import android.graphics.Bitmap
import android.os.Debug
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private val main = Handler(Looper.getMainLooper())

internal actual fun labSleep(ms: Long) {
    Thread.sleep(ms)
}

internal actual fun labWallMs(): Long = System.currentTimeMillis()

internal actual fun labPostMain(block: () -> Unit) {
    main.post(block)
}

internal actual fun labBurnThreads(names: List<String>, ms: Long) {
    for (name in names) {
        thread(name = name, isDaemon = true) { PerfLab.burnOffMain(ms) }
    }
}

/**
 * A bitmap's pixels: malloc'd in the native heap since API 26 (the native heap and RSS grow, the
 * Java heap does not), written by `eraseColor`. Not a direct `ByteBuffer`, which ART allocates in
 * its non-movable Java space.
 */
internal actual fun labAllocNative(bytes: Int): Any {
    val side = sqrt(bytes / 4.0).toInt()
    return Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { it.eraseColor(0x01020304) }
}

/** Frees the pixels at once. */
internal actual fun labFreeNative(block: Any) {
    (block as Bitmap).recycle()
}

internal actual fun labMemoryWarning(): Boolean = false

internal actual val benchKinds: List<String> = listOf("empty", "lambda", "coroutine")

internal actual suspend fun benchChain(kind: String, count: Int): BenchChain = when (kind) {
    "coroutine" -> withContext(Dispatchers.Main) {
        val starts = LongArray(count + 1)
        val cpuBefore = Debug.threadCpuTimeNanos()
        for (i in 0..count) {
            starts[i] = System.nanoTime()
            // Dispatchers.Main resumes through Handler.post: one Looper message per resume.
            yield()
        }
        BenchChain(gaps(starts), Debug.threadCpuTimeNanos() - cpuBefore)
    }
    else -> suspendCancellableCoroutine { continuation ->
        val starts = LongArray(count + 1)
        val reused = kind == "empty"
        var index = 0
        var cpuBefore = 0L
        lateinit var step: Runnable
        fun unit() {
            if (index == 0) cpuBefore = Debug.threadCpuTimeNanos()
            starts[index++] = System.nanoTime()
            if (index <= count) {
                if (reused) main.post(step) else main.post { unit() }
            } else {
                continuation.resume(BenchChain(gaps(starts), Debug.threadCpuTimeNanos() - cpuBefore))
            }
        }
        step = Runnable { unit() }
        main.post(step)
    }
}

internal actual fun benchGcCount(): Long = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1

private fun gaps(starts: LongArray): LongArray = LongArray(starts.size - 1) { starts[it + 1] - starts[it] }

// ART's collector is concurrent; the lab reads no pause for it.
internal actual fun labLastGc(): Pair<Long, String>? = null
