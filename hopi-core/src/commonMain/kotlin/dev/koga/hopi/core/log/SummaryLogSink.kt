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
 *
 * Lines are written by a store event listener, on the store's consumer, right after the snapshot
 * that completes the transaction: no other thread is woken per request, and no line is lost to a
 * slow collector. With the platform log (no [emit]), each line is built in one reused builder,
 * measured only when it may be too long, and on iOS handed to os_log as bytes with no C string. [emit] (tests, a host tool)
 * receives each line as a string instead. Everything is subscribed before this returns, so no
 * event published afterwards is missed; cancelling the returned job removes the listener.
 * [redactor] is read per line, so redaction registered later still applies.
 */
@HopiInternalApi
public fun launchSummaryLogSink(
    store: HopiStore,
    redactor: () -> Redactor = { store.redactor },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    maxLineBytes: Int = platformLogLineMaxBytes,
    emit: ((String) -> Unit)? = null,
): Job {
    // Listener only, so one thread at a time.
    val line = StringBuilder(512)
    val remove = store.addEventListener { event ->
        if (event is TransactionEvent.Completed) {
            if (emit != null) {
                emit(TransactionJson.summaryLogLine(event.transaction, redactor(), maxLineBytes))
            } else {
                line.setLength(0)
                TransactionJson.appendSummaryLogLine(event.transaction, redactor(), maxLineBytes, line)
                emitPlatformLogLine(line)
            }
        }
    }
    val notice = emit ?: ::emitPlatformLogLine
    val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        store.droppedTerminalWrites.collect { dropped ->
            if (dropped > 0) notice(droppedNotice(dropped))
        }
    }
    job.invokeOnCompletion { remove() }
    return job
}

/** `{"hopi":1,"notice":"…","dropped":N}`, N counting every terminal write lost so far. */
@HopiInternalApi
public fun droppedNotice(dropped: Long): String = JsonWriter().beginObject()
    .field("hopi", TransactionJson.SUMMARY_VERSION)
    .field("notice", "transactions dropped under load; they stay pending and have no line")
    .field("dropped", dropped)
    .endObject()
    .toString()
