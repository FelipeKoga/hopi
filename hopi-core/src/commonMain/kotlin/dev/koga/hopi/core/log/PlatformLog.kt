package dev.koga.hopi.core.log

import dev.koga.hopi.api.HopiInternalApi

/**
 * Writes one line to the platform log channel agents read: logcat tag `Hopi` on Android,
 * os_log subsystem `dev.koga.hopi` category `http` on iOS, stderr on the JVM.
 */
@HopiInternalApi
public expect fun emitPlatformLogLine(line: String)

/** Longest line, in UTF-8 bytes, that [emitPlatformLogLine] delivers without cutting it. */
@HopiInternalApi
public expect val platformLogLineMaxBytes: Int

/**
 * [emitPlatformLogLine] for a line held in a builder the caller reuses. On iOS the line is
 * encoded once, by the runtime's native encoder, and handed to os_log pinned with its length: no
 * C string and no copy into a native arena per line (measured: that conversion was most of the
 * line's cost). Android's `Log.i` and the JVM's stderr take a string.
 */
internal expect fun emitPlatformLogLine(line: StringBuilder)
