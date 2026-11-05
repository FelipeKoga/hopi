package dev.koga.hopi.network.internal

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.api.HopiRuntimes
import dev.koga.hopi.core.start.HopiTool
import dev.koga.hopi.core.start.HopiToolContext
import dev.koga.hopi.network.internal.ktor.registerKtorHooks

/**
 * The Ktor plugin's recorder as a tool of the start sequence on the JVM: `hopi-start`'s `Hopi.start`
 * lists it with its own setting ([rules]), the global ones come from the start configuration. Its
 * start registers the recorder in `hopi-api`'s Ktor slot when the app has `hopi-ktor`, and makes no
 * installation: each client's install makes the start's installation first (the store, with the
 * caps the start sequence preset, the start's redaction, the summary sink with `summaryLog`), then
 * its own; the bridge with `agentBridge` is the start sequence's. Once the recorder is registered it
 * writes the network status to `hopi-api`'s registry (`HopiRuntimes.networkStarted`; OkHttp is
 * Android's, so never instrumented here). Only the Ktor plugin records on the JVM.
 */
@HopiInternalApi
public class NetworkTool(
    /** Rules (mock, delay, fail, edit) apply to the Ktor plugin's requests whose client's `rules` is true too; false: to none. */
    public val rules: Boolean,
) : HopiTool {
    override val name: String get() = "network"

    /** `ktor` once the recorder is registered; null without `hopi-ktor`, or when a recorder was registered already. */
    override fun start(context: HopiToolContext): String? {
        if (!registerKtorHooks(context.config, rules)) return null
        HopiRuntimes.registerNetwork(okHttpInstrumented = false)
        return "ktor"
    }
}
