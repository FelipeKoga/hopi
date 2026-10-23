package dev.koga.hopi.ktor

import dev.koga.hopi.core.bridge.AgentBridgeHook
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.log.emitPlatformLogLine
import dev.koga.hopi.core.log.launchSummaryLogSink
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.store.HopiStore
import io.ktor.client.HttpClient
import io.ktor.client.call.replaceResponse
import io.ktor.client.plugins.api.ClientHook
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.isSaved
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.client.statement.HttpResponse
import io.ktor.client.request.HttpSendPipeline
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentLength
import io.ktor.util.date.getTimeMillis
import io.ktor.util.pipeline.PipelineContext
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.cancel
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.launch

/**
 * Records every exchange of the client it is installed in into the process-wide Hopi store,
 * for the in-app screen, the platform log and the agent bridge.
 */
public val Hopi: ClientPlugin<HopiConfig> = createClientPlugin("Hopi", ::HopiConfig) {
    val config = pluginConfig
    if (!config.enabled) return@createClientPlugin

    require(config.maxEntries > 0) { "maxEntries must be positive" }
    require(config.maxTotalBytes > 0) { "maxTotalBytes must be positive" }
    require(config.maxBodyBytes in 0..Int.MAX_VALUE) { "maxBodyBytes must be in 0..${Int.MAX_VALUE}" }

    val injectedStore = config.store
    val store = injectedStore ?: HopiStore.configureDefault(config.maxEntries, config.maxTotalBytes)
    store.includeRedaction(
        Redactor.create(
            extraHeaders = config.redactedHeaders.toList(),
            queryParameters = config.redactedQueryParameters.toList(),
            enabled = !config.redactionDisabled,
        ),
    )
    if (config.logSink == HopiLogSink.Summary && !store.redactor.enabled) announceRedactionDisabled()
    if (injectedStore == null) {
        if (config.logSink == HopiLogSink.Summary) startProcessSummarySink(store)
        if (config.agentBridge) AgentBridgeHook.requestStart(store)
    }

    val maxBodyBytes = minOf(config.maxBodyBytes, store.maxTotalBytes / 2).toInt()
    val recorder = Recorder(client, store, maxBodyBytes)
    on(MonitoringHook) { request, content -> recorder.send(this, request, content) }
    on(RawResponseHook) { response -> recorder.rawResponse(this, response) }
    on(SavedResponseHook) { response -> recorder.savedResponse(this, response) }
    on(DecodedResponseHook) { response -> recorder.decodedResponse(this, response) }
}

private val summarySinkStarted = AtomicBoolean(false)
private val redactionNoticeEmitted = AtomicBoolean(false)

private fun announceRedactionDisabled() {
    if (redactionNoticeEmitted.compareAndSet(false, true)) emitPlatformLogLine(redactionDisabledNotice())
}

private fun startProcessSummarySink(store: HopiStore) {
    if (summarySinkStarted.compareAndSet(false, true)) launchSummaryLogSink(store)
}

private fun redactionDisabledNotice(): String = JsonWriter().beginObject()
    .field("hopi", 1)
    .field("notice", "redaction disabled by unsafeDisableRedaction()")
    .endObject()
    .toString()

private class Recorder(
    private val client: HttpClient,
    private val store: HopiStore,
    private val maxBodyBytes: Int,
) {
    suspend fun send(context: MonitoringHook.Context, request: HttpRequestBuilder, content: Any) {
        if (content !is OutgoingContent) return
        val started = TimeSource.Monotonic.markNow()
        val capture = captureRequest(content, maxBodyBytes)
        val pending = HttpTransaction.pending(
            id = store.nextId(),
            startedAtEpochMs = getTimeMillis(),
            method = request.method.value,
            url = request.url.buildString(),
            requestHeaders = requestHeaders(request, content),
            requestBody = capture.body,
        )
        val exchange = Exchange(store, pending, started, capture.streamed)
        request.attributes.put(ExchangeKey, exchange)
        store.submit(pending)
        try {
            context.proceedWith(capture.outgoing)
        } catch (cause: Throwable) {
            exchange.fail(cause)
            throw cause
        }
        exchange.callReturned()
    }

    /** Before `SaveBody` reads the body: the only point where a large body can be teed instead of copied. */
    suspend fun rawResponse(context: ResponseContext, response: HttpResponse) {
        val exchange = response.call.attributes.getOrNull(ExchangeKey) ?: return
        if (exchange.plan != ResponsePlan.None) return
        exchange.recordResponse(response)
        val contentType = response.headers[HttpHeaders.ContentType]
        val contentLength = response.contentLength()
        val skipReason = responseSkipReason(contentType)
        when {
            !hasBody(exchange.method, response.status) -> exchange.ready(null)
            skipReason != null -> exchange.ready(CapturedBody.skipped(skipReason, contentType, contentLength))
            isEncoded(response.headers[HttpHeaders.ContentEncoding]) -> exchange.decoded()
            contentLength != null && contentLength <= maxBodyBytes -> exchange.afterSave()
            else -> context.proceedWith(tee(response, exchange, contentType, contentLength))
        }
    }

    /** Right after `SaveBody`, before `BodyProgress` wraps the response at `After`. */
    @OptIn(InternalAPI::class)
    suspend fun savedResponse(context: ResponseContext, response: HttpResponse) {
        val exchange = response.call.attributes.getOrNull(ExchangeKey) ?: return
        if (exchange.plan != ResponsePlan.AfterSave) return
        val contentType = response.headers[HttpHeaders.ContentType]
        val contentLength = response.contentLength()
        if (!response.isSaved) {
            context.proceedWith(tee(response, exchange, contentType, contentLength))
            return
        }
        val buffer = BoundedBuffer(maxBodyBytes, contentLength)
        try {
            buffer.drain(response.rawContent)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            exchange.fail(cause)
            return
        }
        exchange.ready(buffer.body(contentType))
    }

    /** After a decoder at `State` (`ContentEncoding`) replaced the response, before `After`. */
    @OptIn(InternalAPI::class)
    suspend fun decodedResponse(context: ResponseContext, response: HttpResponse) {
        val exchange = response.call.attributes.getOrNull(ExchangeKey) ?: return
        if (exchange.plan != ResponsePlan.Decoded) return
        val contentType = response.headers[HttpHeaders.ContentType]
        if (!response.isSaved) {
            context.proceedWith(tee(response, exchange, contentType, sizeHint = null))
            return
        }
        // Reading a saved body is safe only when every access builds a new channel over the saved bytes.
        val own = response.rawContent
        val probe = response.rawContent
        if (own === probe) {
            context.proceedWith(tee(response, exchange, contentType, sizeHint = null, source = own))
            return
        }
        probe.cancel()
        exchange.teed()
        // Decoding is work proportional to the body; it runs off the call's coroutine.
        val job = client.launch {
            val buffer = BoundedBuffer(maxBodyBytes, sizeHint = null)
            try {
                buffer.drain(own)
            } catch (cause: Throwable) {
                exchange.fail(cause)
                if (cause is CancellationException) throw cause
                return@launch
            }
            exchange.complete(buffer.body(contentType))
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                own.cancel(cause)
                exchange.fail(cause)
            }
        }
    }

    /**
     * Replaces the response's body with a [TeedBody] over [source], which the app reads directly,
     * keeping the first [maxBodyBytes] of it as the app consumes them. A failure of [source]
     * reaches the app unchanged, and an app that stops reading before the end cancels [source] and
     * fails the transaction.
     */
    @OptIn(InternalAPI::class)
    private fun tee(
        response: HttpResponse,
        exchange: Exchange,
        contentType: String?,
        sizeHint: Long?,
        source: ByteReadChannel = response.rawContent,
    ): HttpResponse {
        val body = TeedBody(source, exchange, BoundedBuffer(maxBodyBytes, sizeHint), contentType)
        exchange.teed()
        return response.call.replaceResponse { body }.response
    }

    private fun hasBody(method: String, status: HttpStatusCode): Boolean =
        method != HttpMethod.Head.value &&
            status.value >= 200 &&
            status != HttpStatusCode.NoContent &&
            status != HttpStatusCode.NotModified
}

private object MonitoringHook : ClientHook<suspend MonitoringHook.Context.(HttpRequestBuilder, Any) -> Unit> {
    class Context(private val context: PipelineContext<Any, HttpRequestBuilder>) {
        suspend fun proceedWith(content: Any): Any = context.proceedWith(content)
    }

    override fun install(client: HttpClient, handler: suspend Context.(HttpRequestBuilder, Any) -> Unit) {
        client.sendPipeline.intercept(HttpSendPipeline.Monitoring) { content ->
            handler(Context(this), context, content)
        }
    }
}

private class ResponseContext(private val context: PipelineContext<HttpResponse, Unit>) {
    suspend fun proceedWith(response: HttpResponse): HttpResponse = context.proceedWith(response)
}

private object RawResponseHook : ClientHook<suspend ResponseContext.(HttpResponse) -> Unit> {
    override fun install(client: HttpClient, handler: suspend ResponseContext.(HttpResponse) -> Unit) {
        val phase = PipelinePhase("HopiRawResponse")
        client.receivePipeline.insertPhaseBefore(HttpReceivePipeline.Before, phase)
        client.receivePipeline.intercept(phase) { response -> handler(ResponseContext(this), response) }
    }
}

private object SavedResponseHook : ClientHook<suspend ResponseContext.(HttpResponse) -> Unit> {
    override fun install(client: HttpClient, handler: suspend ResponseContext.(HttpResponse) -> Unit) {
        val phase = PipelinePhase("HopiSavedResponse")
        client.receivePipeline.insertPhaseAfter(HttpReceivePipeline.Before, phase)
        client.receivePipeline.intercept(phase) { response -> handler(ResponseContext(this), response) }
    }
}

private object DecodedResponseHook : ClientHook<suspend ResponseContext.(HttpResponse) -> Unit> {
    override fun install(client: HttpClient, handler: suspend ResponseContext.(HttpResponse) -> Unit) {
        val phase = PipelinePhase("HopiDecodedResponse")
        client.receivePipeline.insertPhaseAfter(HttpReceivePipeline.State, phase)
        client.receivePipeline.intercept(phase) { response -> handler(ResponseContext(this), response) }
    }
}

private fun isEncoded(contentEncoding: String?): Boolean =
    contentEncoding != null && contentEncoding.split(',').any { coding ->
        val name = coding.trim()
        name.isNotEmpty() && !name.equals("identity", ignoreCase = true)
    }

/**
 * The cause a channel was failed with, for the transaction's error: reads and
 * [ByteReadChannel.closedCause] wrap a cause that cannot be copied in one more exception, once per
 * channel it crossed (a copy such as `bodyAsChannel`'s adds layers), so every such layer is removed.
 */
internal fun channelFailureOrigin(cause: Throwable): Throwable {
    var current = cause
    while (true) {
        val origin = current.cause ?: return current
        current = when {
            current is ClosedByteChannelException -> origin
            current is CancellationException && current.message == origin.message -> origin
            else -> return current
        }
    }
}
