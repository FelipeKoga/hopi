package dev.koga.hopi.urlsession

import dev.koga.hopi.core.urlsession.UrlSessionCapture

internal actual fun platformStart(config: HopiUrlSessionConfig): Boolean =
    UrlSessionCapture.start(
        UrlSessionCapture.Settings(
            maxEntries = config.maxEntries,
            maxTotalBytes = config.maxTotalBytes,
            maxBodyBytes = config.maxBodyBytes,
            redactedHeaders = config.redactedHeaders.toList(),
            redactedQueryParameters = config.redactedQueryParameters.toList(),
            redactionDisabled = config.redactionDisabled,
            summaryLog = config.summaryLog,
            agentBridge = config.agentBridge,
            rules = config.rules,
        ),
    ) != UrlSessionCapture.Mode.NotStarted
