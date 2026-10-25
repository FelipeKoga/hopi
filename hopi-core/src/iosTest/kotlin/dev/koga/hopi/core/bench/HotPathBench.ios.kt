package dev.koga.hopi.core.bench

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun hotPathBenchmarkEnabled(): Boolean = getenv("HOPI_HOTPATH") != null
