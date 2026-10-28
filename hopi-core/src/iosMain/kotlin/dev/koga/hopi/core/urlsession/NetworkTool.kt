package dev.koga.hopi.core.urlsession

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.start.HopiTool
import dev.koga.hopi.core.start.HopiToolContext

/**
 * Automatic URLSession capture as a tool of the start sequence: `hopi-start`'s `Hopi.start` lists
 * it with its own setting ([rules]), the global ones come from the start configuration. Its start
 * is [UrlSessionCapture.start], which keeps the order the native library needs: the installation
 * (the store, with the caps the start sequence preset), the store as the callbacks' target, the
 * Ktor Darwin cover, the hooks, `RuleEngine.recorderStarted`, then the rules' hooks. Its notices
 * and its first-call guard stay [UrlSessionCapture]'s, because `HopiUrlSession.start` still calls
 * it too (until the runtime-split plan's stage 9 moves both into `hopi-network`).
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
        return "urlsession ${mode.name.lowercase()}"
    }
}
