package dev.koga.hopi.sample

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/** stdout: `xcrun simctl launch --console-pty` shows it. */
internal actual fun sampleLog(line: String) {
    println("HopiSample: $line")
}

internal actual fun runtimeStats(): Map<String, Long> = emptyMap()

@OptIn(NativeRuntimeApi::class, ExperimentalNativeApi::class)
internal actual fun collectGarbage() {
    GC.collect()
}
