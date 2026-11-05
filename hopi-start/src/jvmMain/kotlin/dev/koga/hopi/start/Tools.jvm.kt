package dev.koga.hopi.start

import dev.koga.hopi.core.start.HopiTool
import dev.koga.hopi.network.internal.NetworkTool
import dev.koga.hopi.perf.internal.PerfEntry

/**
 * The network tool first, which registers the Ktor plugin's recorder, then perf's tool (CPU and
 * memory), with [HopiStartConfig.perfRemoteControl], unless [HopiStartConfig.perf] is false.
 */
internal actual fun tools(config: HopiStartConfig): List<HopiTool> {
    val network = NetworkTool(rules = config.rules)
    return if (config.perf) listOf(network, PerfEntry(remoteControl = config.perfRemoteControl)) else listOf(network)
}
