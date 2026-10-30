package dev.koga.hopi.sample

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.model.TransactionEvent
import dev.koga.hopi.core.store.HopiStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Test support for `iosAppCaptureTests` (Swift, in the host app's process): watches the store for
 * the record of one URL. Compiled only with `-Phopi.enabled=true`, never into a build without the
 * runtimes.
 */
@OptIn(HopiInternalApi::class)
public object SampleCaptureProbe {
    /** Starts watching before the request is made, so its record cannot be missed. */
    public fun watch(url: String): SampleCaptureWatch = SampleCaptureWatch(url)
}

/** One URL's first terminal record, as `source|method|code|state|reqBytes|reqSkipped|resBytes|resKept|resSkipped`. */
@OptIn(HopiInternalApi::class)
public class SampleCaptureWatch internal constructor(private val url: String) {
    private val record = CompletableDeferred<String>()
    private val remove = HopiStore.default.addEventListener { event ->
        if (event is TransactionEvent.Completed && event.transaction.url == url) {
            val tx = event.transaction
            record.complete(
                listOf(
                    tx.source, tx.method, tx.responseCode, tx.state.wireName,
                    tx.requestBody?.reportedBytes, tx.requestBody?.skippedReason,
                    tx.responseBody?.reportedBytes, tx.responseBody?.bytes?.size, tx.responseBody?.skippedReason,
                ).joinToString("|") { it?.toString() ?: "-" },
            )
        }
    }

    /** Calls [done] with the record, or null after [timeoutMs]; once, on a background thread. */
    public fun await(timeoutMs: Long, done: (String?) -> Unit) {
        CoroutineScope(Dispatchers.Default).launch {
            val result = withTimeoutOrNull(timeoutMs) { record.await() }
            remove()
            done(result)
        }
    }
}
