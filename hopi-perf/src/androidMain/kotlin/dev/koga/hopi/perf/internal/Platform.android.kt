package dev.koga.hopi.perf.internal

import dev.koga.hopi.core.perf.PerfMonitor

internal actual fun createBackend(): MonitorBackend = AndroidBackend.instance

internal actual fun <T> perfLocked(lock: Any, block: () -> T): T = synchronized(lock, block)

// The JDK's empty set: perf's tool reads it on the main thread, where Kotlin's would load its classes.
internal actual fun launchArgumentMonitors(): Set<PerfMonitor> = java.util.Collections.emptySet()
