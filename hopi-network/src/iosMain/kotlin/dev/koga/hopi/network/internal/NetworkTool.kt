package dev.koga.hopi.network.internal

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.api.HopiRuntimes
import dev.koga.hopi.core.start.HopiTool
import dev.koga.hopi.core.start.HopiToolContext

/**
 * Automatic URLSession capture as a tool of the start sequence: `hopi-start`'s `Hopi.start` lists
 * it with its own setting ([rules]), the global ones come from the start configuration. Its start
 * is [UrlSessionCapture.start], which keeps the order the native library needs: the installation
 * (the store, with the caps the start sequence preset), the store as the callbacks' target, the
 * Ktor Darwin cover, the hooks, `RuleEngine.recorderStarted`, then the rules' hooks. It is that
 * start's only caller; once capture runs it writes the network status to `hopi-api`'s registry
 * (`HopiRuntimes.networkStarted`; OkHttp is Android's, so never instrumented here).
 */
@HopiInternalApi
public class NetworkTool(
    /** Rules (mock, delay, fail, request edit) apply to URLSession tasks; false installs no rule hook. */
    public val rules: Boolean,
) : HopiTool {
    override val name: String get() = "network"

    /** `urlsession bytes` (or `metadata`) once capture runs; null when it did not start. */
    override fun start(context: HopiToolContext): String? {
        val config = context.config
        val mode = UrlSessionCapture.start(
            UrlSessionCapture.Settings(
                maxEntries = config.maxEntries,
                maxTotalBytes = config.maxTotalBytes,
                maxBodyBytes = config.maxBodyBytes,
                redactedHeaders = config.redactedHeaders,
                redactedQueryParameters = config.redactedQueryParameters,
                redactionDisabled = config.redactionDisabled,
                summaryLog = config.summaryLog,
                agentBridge = config.agentBridge,
                rules = rules,
            ),
        )
        if (mode == UrlSessionCapture.Mode.NotStarted) return null
        HopiRuntimes.registerNetwork(okHttpInstrumented = false)
        return "urlsession ${mode.name.lowercase()}"
    }
}
