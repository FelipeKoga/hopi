package dev.koga.hopi.core.log

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.internal.oslog.hopi_os_log_line
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
@HopiInternalApi
public actual fun emitPlatformLogLine(line: String) {
    hopi_os_log_line(line)
}

/** The unified logging system cuts a message's dynamic string at about 1 KiB. */
@HopiInternalApi
public actual val platformLogLineMaxBytes: Int = 1000
