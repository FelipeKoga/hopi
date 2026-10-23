package dev.koga.hopi.core.log

import dev.koga.hopi.core.HopiInternalApi

/**
 * Writes one line to the platform log channel agents read: logcat tag `Hopi` on Android,
 * os_log subsystem `dev.koga.hopi` category `http` on iOS, stderr on the JVM.
 */
@HopiInternalApi
public expect fun emitPlatformLogLine(line: String)

/** Longest line, in UTF-8 bytes, that [emitPlatformLogLine] delivers without cutting it. */
@HopiInternalApi
public expect val platformLogLineMaxBytes: Int
