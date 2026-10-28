package dev.koga.hopi.core.start

import android.os.SystemClock

// The clock the provider times its own parts with.
internal actual fun monotonicNanos(): Long = SystemClock.elapsedRealtimeNanos()
