package dev.koga.hopi.sample

import android.os.Debug
import android.util.Log

internal actual fun sampleLog(line: String) {
    Log.i("HopiSample", line)
}

private val GC_STATS = listOf(
    "gcCount" to "art.gc.gc-count",
    "gcTimeMs" to "art.gc.gc-time",
    "blockingGcCount" to "art.gc.blocking-gc-count",
    "blockingGcTimeMs" to "art.gc.blocking-gc-time",
)

internal actual fun runtimeStats(): Map<String, Long> {
    val runtime = Runtime.getRuntime()
    val stats = LinkedHashMap<String, Long>()
    for ((key, name) in GC_STATS) stats[key] = Debug.getRuntimeStat(name)?.toLongOrNull() ?: -1
    stats["javaHeapUsedKb"] = (runtime.totalMemory() - runtime.freeMemory()) / 1024
    stats["nativeHeapKb"] = Debug.getNativeHeapAllocatedSize() / 1024
    return stats
}

internal actual fun collectGarbage() {
    Runtime.getRuntime().gc()
}
