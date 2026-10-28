package dev.koga.hopi.start

import dev.koga.hopi.core.start.HopiTool
import dev.koga.hopi.core.urlsession.NetworkTool
import dev.koga.hopi.perf.internal.PerfEntry

/** URLSession capture first, so it records from the start on, then perf's tool unless [HopiStartConfig.perf] is false. */
internal actual fun tools(config: HopiStartConfig): List<HopiTool> {
    val network = NetworkTool(rules = config.rules)
    return if (config.perf) listOf(network, PerfEntry()) else listOf(network)
}
