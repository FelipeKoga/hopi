package dev.koga.hopi.core.start

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.CLOCK_UPTIME_RAW
import platform.posix.clock_gettime_nsec_np

@OptIn(ExperimentalForeignApi::class)
internal actual fun monotonicNanos(): Long = clock_gettime_nsec_np(CLOCK_UPTIME_RAW.toUInt()).toLong()
