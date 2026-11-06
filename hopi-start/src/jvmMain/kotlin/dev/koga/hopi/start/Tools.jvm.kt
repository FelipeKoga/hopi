package dev.koga.hopi.start

import dev.koga.hopi.core.start.HopiTool
import dev.koga.hopi.network.internal.NetworkTool
import dev.koga.hopi.perf.internal.PerfEntry

/**
 * The network tool first, which registers the Ktor plugin's recorder, then perf's tool (CPU and
 * memory), with [HopiStartConfig.perfRemoteControl], each unless [HopiStartConfig.network] or [HopiStartConfig.perf] is false.
 */
internal actual fun tools(config: HopiStartConfig): List<HopiTool> {
    return listOfNotNull(
        if (config.network) NetworkTool(rules = config.rules) else null,
        if (config.perf) PerfEntry(remoteControl = config.perfRemoteControl) else null,
    )
}
