package dev.koga.hopi.rules

import kotlin.concurrent.atomics.AtomicInt
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import platform.Foundation.NSLog

private val noticeLines = AtomicInt(0)

/** The lines [ruleDroppedWithoutRuntime] wrote in this process, at most one; the iOS test reads it. */
internal val ruleDroppedNoticeLines: Int
    get() = noticeLines.load()

/**
 * A Debug binary's first dropped rule writes one line to the system log through `NSLog` (the
 * unified log, and the console of `simctl launch --console-pty`): an app that forgot
 * `HopiSetup.shared.start()`, or calls `HopiRules` before it, would otherwise see its rules do
 * nothing and never learn why. A Release binary writes nothing, since there no runtime is the
 * normal case.
 */
@OptIn(ExperimentalNativeApi::class)
internal actual fun ruleDroppedWithoutRuntime() {
    // The line is NSLog's argument, never its format. A Kotlin string in a C vararg arrives as a C
    // string, not an object, so `%s` (`%@` would crash): the line is ASCII.
    if (Platform.isDebugBinary && noticeLines.compareAndSet(0, 1)) NSLog("%s", DROPPED_NOTICE)
}

/** A notice line of the summary log's form, ASCII; no match in it, since a match can carry a secret. */
private const val DROPPED_NOTICE =
    """{"hopi":1,"notice":"HopiRules: no Hopi runtime is registered, so rules added now are dropped (each call returns -1); call HopiSetup.shared.start() first thing"}"""
