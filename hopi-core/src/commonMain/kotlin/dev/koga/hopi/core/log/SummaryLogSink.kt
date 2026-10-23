package dev.koga.hopi.core.log

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.model.TransactionEvent
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.store.HopiStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Emits one summary NDJSON line per completed or failed transaction of [store], at most
 * [maxLineBytes] UTF-8 bytes each, and a notice line whenever the store's
 * [HopiStore.droppedTerminalWrites] grows, so a reader knows transactions are missing.
 * The collectors are subscribed before this returns, so no event published afterwards is missed.
 * [redactor] is read per line, so redaction registered later still applies.
 */
@HopiInternalApi
public fun launchSummaryLogSink(
    store: HopiStore,
    redactor: () -> Redactor = { store.redactor },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    maxLineBytes: Int = platformLogLineMaxBytes,
    emit: (String) -> Unit = ::emitPlatformLogLine,
): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
    launch(start = CoroutineStart.UNDISPATCHED) {
        store.droppedTerminalWrites.collect { dropped ->
            if (dropped > 0) emit(droppedNotice(dropped))
        }
    }
    store.events.collect { event ->
        if (event is TransactionEvent.Completed) {
            emit(TransactionJson.summaryLogLine(event.transaction, redactor(), maxLineBytes))
        }
    }
}

/** `{"hopi":1,"notice":"…","dropped":N}`, N counting every terminal write lost so far. */
@HopiInternalApi
public fun droppedNotice(dropped: Long): String = JsonWriter().beginObject()
    .field("hopi", TransactionJson.SUMMARY_VERSION)
    .field("notice", "transactions dropped under load; they stay pending and have no line")
    .field("dropped", dropped)
    .endObject()
    .toString()
