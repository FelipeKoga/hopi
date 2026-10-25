package dev.koga.hopi.core.capture

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.bridge.AgentBridgeHook
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.log.applyBeforeProcessDeath
import dev.koga.hopi.core.log.emitPlatformLogLine
import dev.koga.hopi.core.log.launchSummaryLogSink
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.store.HopiStore
import kotlin.concurrent.atomics.AtomicBoolean

/**
 * What every recorder does once when it is installed (the Ktor plugin per client, URLSession
 * capture per process), so the store semantics are the same whichever installs first: the first
 * installation sets the caps, redaction is the union, the summary log sink and the agent bridge
 * start once per process, for the default store only.
 */
@HopiInternalApi
public object CaptureInstallation {
    /** The store a recorder writes to and its effective per-body cap. */
    public class Installed(public val store: HopiStore, public val maxBodyBytes: Int)

    public fun install(
        maxEntries: Int,
        maxTotalBytes: Long,
        maxBodyBytes: Long,
        redactedHeaders: List<String>,
        redactedQueryParameters: List<String>,
        redactionDisabled: Boolean,
        summaryLog: Boolean,
        agentBridge: Boolean,
        injectedStore: HopiStore? = null,
    ): Installed {
        invalidCaps(maxEntries, maxTotalBytes, maxBodyBytes)?.let { throw IllegalArgumentException(it) }
        val store = injectedStore ?: HopiStore.configureDefault(maxEntries, maxTotalBytes)
        store.includeRedaction(
            Redactor.create(
                extraHeaders = redactedHeaders,
                queryParameters = redactedQueryParameters,
                enabled = !redactionDisabled,
            ),
        )
        if (summaryLog && !store.redactor.enabled) announceRedactionDisabled()
        if (injectedStore == null) {
            if (summaryLog) startProcessSummarySink(store)
            if (agentBridge) AgentBridgeHook.requestStart(store)
        }
        return Installed(store, minOf(maxBodyBytes, store.maxTotalBytes / 2).toInt())
    }

    /** Why these caps would make [install] throw, or null when they are valid. */
    public fun invalidCaps(maxEntries: Int, maxTotalBytes: Long, maxBodyBytes: Long): String? = when {
        maxEntries <= 0 -> "maxEntries must be positive"
        maxTotalBytes <= 0 -> "maxTotalBytes must be positive"
        maxBodyBytes !in 0..Int.MAX_VALUE -> "maxBodyBytes must be in 0..${Int.MAX_VALUE}"
        else -> null
    }

    private val summarySinkStarted = AtomicBoolean(false)
    private val redactionNoticeEmitted = AtomicBoolean(false)

    private fun announceRedactionDisabled() {
        if (redactionNoticeEmitted.compareAndSet(false, true)) emitPlatformLogLine(redactionDisabledNotice())
    }

    private fun startProcessSummarySink(store: HopiStore) {
        if (summarySinkStarted.compareAndSet(false, true)) {
            launchSummaryLogSink(store)
            applyBeforeProcessDeath(store)
        }
    }

    private fun redactionDisabledNotice(): String = JsonWriter().beginObject()
        .field("hopi", 1)
        .field("notice", "redaction disabled by unsafeDisableRedaction()")
        .endObject()
        .toString()
}
