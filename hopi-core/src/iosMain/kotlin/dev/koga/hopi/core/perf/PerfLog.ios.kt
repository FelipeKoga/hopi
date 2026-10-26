package dev.koga.hopi.core.perf

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.internal.oslog.hopi_os_log_perf_line
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
@HopiInternalApi
public actual fun emitPerfLogLine(line: String) {
    hopi_os_log_perf_line(line)
}
