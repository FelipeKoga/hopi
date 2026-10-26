package dev.koga.hopi.core.perf

import android.util.Log
import dev.koga.hopi.core.HopiInternalApi

private const val TAG = "HopiPerf"

@HopiInternalApi
public actual fun emitPerfLogLine(line: String) {
    Log.i(TAG, line)
}
