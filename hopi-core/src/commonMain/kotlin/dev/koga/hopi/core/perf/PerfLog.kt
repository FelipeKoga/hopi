package dev.koga.hopi.core.perf

import dev.koga.hopi.core.HopiInternalApi

/**
 * Writes one perf line where agents read them, apart from the HTTP lines: logcat tag `HopiPerf`
 * on Android (so `adb logcat -s Hopi:I` is unchanged), os_log subsystem `dev.koga.hopi` category
 * `perf` on iOS, stderr on the JVM. A perf line never carries `"id"`.
 */
@HopiInternalApi
public expect fun emitPerfLogLine(line: String)
