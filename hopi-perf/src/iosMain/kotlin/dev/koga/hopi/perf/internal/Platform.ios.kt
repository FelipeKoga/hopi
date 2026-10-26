package dev.koga.hopi.perf.internal

import dev.koga.hopi.core.perf.PerfLock
import dev.koga.hopi.core.perf.PerfMonitor
import platform.Foundation.NSProcessInfo

internal actual fun createBackend(): MonitorBackend = IosBackend.instance

private val iosLock = PerfLock()

// One runtime lock per process, held only for owner bookkeeping and backend calls that return at once.
internal actual fun <T> perfLocked(lock: Any, block: () -> T): T = iosLock.locked(block)

/** `-hopiPerf frames,hangs` among the launch arguments (`xcrun simctl launch <udid> <id> -hopiPerf hangs`): one lookup. */
internal actual fun launchArgumentMonitors(): Set<PerfMonitor> {
    val arguments = NSProcessInfo.processInfo.arguments
    val index = arguments.indexOf(LAUNCH_ARGUMENT)
    if (index < 0) return emptySet()
    val value = arguments.getOrNull(index + 1) as? String ?: return emptySet()
    return parseLaunchMonitors(value)
}

internal fun parseLaunchMonitors(value: String): Set<PerfMonitor> =
    value.split(',').mapNotNullTo(LinkedHashSet()) { PerfMonitor.fromWire(it.trim().lowercase()) }

private const val LAUNCH_ARGUMENT = "-hopiPerf"
