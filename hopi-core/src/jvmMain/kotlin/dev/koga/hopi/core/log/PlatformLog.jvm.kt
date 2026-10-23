package dev.koga.hopi.core.log

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.json.TransactionJson

@HopiInternalApi
public actual fun emitPlatformLogLine(line: String) {
    System.err.println(line)
}

@HopiInternalApi
public actual val platformLogLineMaxBytes: Int = TransactionJson.MAX_SUMMARY_LINE_BYTES
