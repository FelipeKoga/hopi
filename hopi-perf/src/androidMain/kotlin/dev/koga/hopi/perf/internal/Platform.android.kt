package dev.koga.hopi.perf.internal

import dev.koga.hopi.core.perf.PerfMonitor

internal actual fun createBackend(): MonitorBackend = AndroidBackend.instance

internal actual fun <T> perfLocked(lock: Any, block: () -> T): T = synchronized(lock, block)

internal actual fun launchArgumentMonitors(): Set<PerfMonitor> = emptySet()
