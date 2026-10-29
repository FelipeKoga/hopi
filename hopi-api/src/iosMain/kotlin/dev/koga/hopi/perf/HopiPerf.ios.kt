package dev.koga.hopi.perf

import kotlin.concurrent.atomics.AtomicInt
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import platform.Foundation.NSLog

private val noticeLines = AtomicInt(0)

/** The lines [perfStartDroppedWithoutRuntime] wrote in this process, at most one; the iOS test reads it. */
internal val perfStartDroppedNoticeLines: Int
    get() = noticeLines.load()

/**
 * A Debug binary's first dropped start writes one line to the system log through `NSLog` (the
 * unified log, and the console of `simctl launch --console-pty`): an app that forgot
 * `Hopi.shared.start`, calls `HopiPerf.start` before it, or started Hopi with `perf = false`, would
 * otherwise see its monitors never start and never learn why. A Release binary writes nothing,
 * since there no runtime is the normal case.
 */
@OptIn(ExperimentalNativeApi::class)
internal actual fun perfStartDroppedWithoutRuntime() {
    // The line is NSLog's argument, never its format. A Kotlin string in a C vararg arrives as a C
    // string, not an object, so `%s` (`%@` would crash): the line is ASCII.
    if (Platform.isDebugBinary && noticeLines.compareAndSet(0, 1)) NSLog("%s", DROPPED_NOTICE)
}

/** A notice line of the summary log's form, ASCII; no monitor list, the same line for every start. */
private const val DROPPED_NOTICE =
    """{"hopi":1,"notice":"HopiPerf: no Hopi runtime is registered, so monitors started now do not start (each start is dropped); call Hopi.shared.start first thing in a Debug build, with its perf on"}"""
