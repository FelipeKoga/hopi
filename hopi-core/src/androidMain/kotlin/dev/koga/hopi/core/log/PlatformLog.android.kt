package dev.koga.hopi.core.log

import android.util.Log
import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.json.TransactionJson

private const val TAG = "Hopi"

@HopiInternalApi
public actual fun emitPlatformLogLine(line: String) {
    Log.i(TAG, line)
}

internal actual fun emitPlatformLogLine(line: StringBuilder) {
    Log.i(TAG, line.toString())
}

@HopiInternalApi
public actual val platformLogLineMaxBytes: Int = TransactionJson.MAX_SUMMARY_LINE_BYTES
