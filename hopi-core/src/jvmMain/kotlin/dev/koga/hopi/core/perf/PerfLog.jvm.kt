package dev.koga.hopi.core.perf

import dev.koga.hopi.api.HopiInternalApi

@HopiInternalApi
public actual fun emitPerfLogLine(line: String) {
    System.err.println(line)
}
