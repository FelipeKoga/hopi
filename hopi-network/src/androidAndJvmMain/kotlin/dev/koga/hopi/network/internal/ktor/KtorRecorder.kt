package dev.koga.hopi.network.internal.ktor

import dev.koga.hopi.core.capture.FullBodies
import dev.koga.hopi.core.capture.CaptureInstallation
import dev.koga.hopi.core.capture.PlatformCapture
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionSource
import dev.koga.hopi.core.start.StartConfig
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.core.rules.CompiledRule
import dev.koga.hopi.core.rules.FailKind
import dev.koga.hopi.core.rules.RequestEdits
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleSource
import dev.koga.hopi.core.rules.RuleState
import dev.koga.hopi.ktor.HopiConfig
import dev.koga.hopi.ktor.HopiLogSink
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.call.replaceResponse
import io.ktor.client.plugins.api.ClientHook
import io.ktor.client.plugins.api.ClientPluginBuilder
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
import io.ktor.util.AttributeKey
import io.ktor.util.Attributes
import io.ktor.util.date.getTimeMillis
import io.ktor.util.pipeline.PipelineContext
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.cancel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.launch

/**
 * The Ktor plugin's recorder (docs/ARCHITECTURE.md, "Ktor plugin"): what `hopi-ktor`'s thin `Hopi`
 * installs on a client through the hooks the network tool registered ([KtorHooks]). It records
 * every exchange of that client into the process-wide Hopi store, for the platform log and the
 * agent bridge (the CLI and the desktop viewer), and applies the rules to them.
 */
internal object KtorRecorder {
    /**
     * Installs the recorder on one client, with [plugin]'s configuration. Every [HopiConfig] member
     * is read first, before anything is installed, so a `hopi-ktor` of another version fails here
     * (the plugin catches it) with no hook installed. With a [start] (the network tool's), the
     * start's installation comes first, the client's own then follows, with the store semantics of
     * [CaptureInstallation.install]: the store with the caps the start preset, redaction the union
     * of both, the summary sink and the bridge once per process. [rules] is the network tool's: a
     * request is ruled only when it and the client's own `rules` are both true. [injectedStore]
     * (tests) takes the client's records in place of the process-wide store.
     */
    fun install(plugin: ClientPluginBuilder<HopiConfig>, start: StartConfig?, rules: Boolean, injectedStore: HopiStore?) {
        val config = plugin.pluginConfig
        val maxEntries = config.maxEntries
        val maxTotalBytes = config.maxTotalBytes
        val maxBodyBytes = config.maxBodyBytes
        val redactedHeaders = config.redactedHeaders.toList()
        val redactedQueryParameters = config.redactedQueryParameters.toList()
        val redactionDisabled = config.redactionDisabled
        val summaryLog = config.logSink == HopiLogSink.Summary
        val agentBridge = config.agentBridge
        val ruled = config.rules && rules
        if (start != null) {
            CaptureInstallation.install(
                maxEntries = start.maxEntries,
                maxTotalBytes = start.maxTotalBytes,
                maxBodyBytes = start.maxBodyBytes,
                redactedHeaders = start.redactedHeaders,
                redactedQueryParameters = start.redactedQueryParameters,
                redactionDisabled = start.redactionDisabled,
                summaryLog = start.summaryLog,
                agentBridge = start.agentBridge,
            )
        }
        val installed = CaptureInstallation.install(
            maxEntries = maxEntries,
            maxTotalBytes = maxTotalBytes,
            maxBodyBytes = maxBodyBytes,
            redactedHeaders = redactedHeaders,
            redactedQueryParameters = redactedQueryParameters,
            redactionDisabled = redactionDisabled,
            summaryLog = summaryLog,
            agentBridge = agentBridge,
            injectedStore = injectedStore,
        )
        val client = plugin.client
        // Read once: an engine a platform recorder covers (OkHttp's and, rewritten, the Android
        // engine under Android capture) is recorded there, and the recorder defers to it request
        // by request (see Recorder.send).
        val engineClassName = client.engine::class.qualifiedName
        RuleEngine.recorderStarted(ruled)
        val recorder = Recorder(client, installed.store, installed.maxBodyBytes, engineClassName, ruled)
        plugin.on(MonitoringHook) { request, content -> recorder.send(this, request, content) }
        plugin.on(RawResponseHook) { response -> recorder.rawResponse(this, response) }
        plugin.on(SavedResponseHook) { response -> recorder.savedResponse(this, response) }
        plugin.on(DecodedResponseHook) { response -> recorder.decodedResponse(this, response) }
    }
}

private class Recorder(
    private val client: HttpClient,
    private val store: HopiStore,
    private val baseMaxBodyBytes: Int,
    private val engineClassName: String?,
    private val rules: Boolean,
) {
    /** The cap for a capture that starts now: the installation's, or full bodies' while a viewer holds them. */
    private val maxBodyBytes: Int get() = FullBodies.bodyCap(baseMaxBodyBytes)

    suspend fun send(context: MonitoringHook.Context, request: HttpRequestBuilder, content: Any) {
        if (content !is OutgoingContent) return
        // One record per request: the platform recorder has this one. Without an exchange in the
        // call's attributes, every response hook below passes the call through untouched too.
        // Except when the request names its own Accept-Encoding: the platform stack then hands the
        // body over encoded and Ktor decodes it (ContentEncoding), so this plugin records what the
        // app reads, and marks the request for a recorder that honors the marker to let it pass.
        // The recorder of a request is the only layer that applies rules to it, so a covered
        // request returns here before the rule state is read too.
        val covered = PlatformCapture.covers(engineClassName)
        if (covered && (request.headers[HttpHeaders.AcceptEncoding] == null || !PlatformCapture.honorsPassThrough(engineClassName))) return
        // One volatile read and a reference comparison: with no rule the request takes exactly the
        // path it took before rules existed.
        val state = if (rules) RuleEngine.state else RuleState.EMPTY
        if (state !== RuleState.EMPTY) return sendRuled(context, request, content, covered, state)
        val started = TimeSource.Monotonic.markNow()
        val capture = captureRequest(content, maxBodyBytes)
        val pending = HttpTransaction.pending(
            id = store.nextId(),
            startedAtEpochMs = getTimeMillis(),
            method = request.method.value,
            url = request.url.buildString(),
            requestHeaders = requestHeaders(request, content),
            requestBody = capture.body,
            source = TransactionSource.KTOR,
        )
        // Added after the snapshot: the marker is Hopi's, never part of the record.
        if (covered) {
            PlatformCapture.markPassThrough()
            request.headers.append(PlatformCapture.PASS_THROUGH_HEADER, "1")
        }
        // Uncovered here: the coverage is read again when the engine answers or fails, in case a
        // platform recorder started meanwhile (Exchange.withdrawnForPlatform).
        val exchange = Exchange(store, pending, started, capture.streamed, uncoveredEngine = if (covered) null else engineClassName)
        request.attributes.put(ExchangeKey, exchange)
        store.submit(pending)
        try {
            context.proceedWith(capture.outgoing)
        } catch (cause: Throwable) {
            if (!exchange.withdrawnForPlatform()) exchange.fail(cause)
            throw cause
        }
        exchange.callReturned()
    }

    /**
     * [send] with rules (docs/design/rules.md, "Ktor plugin"): the first matching rule is claimed,
     * its request edit applied before the record's snapshot, then the
     * before-request delay, and the mock, the fail or the real send. The response side of the rule
     * (its delay and edit) runs in [rawResponse].
     */
    @OptIn(InternalAPI::class)
    private suspend fun sendRuled(context: MonitoringHook.Context, request: HttpRequestBuilder, content: OutgoingContent, covered: Boolean, state: RuleState) {
        val started = TimeSource.Monotonic.markNow()
        // Ktor's Auth plugin answers a 401 by sending the request again with a fresh token, to
        // whatever host it went: an edit to another origin is refused on such a client.
        val refused: ((CompiledRule) -> Boolean)? = if (!client.hasAuthPlugin()) null else { rule ->
            rule.requestEdit?.let { RequestEdits.changesOrigin(request.url.buildString(), it) } == true
        }
        val rule = state.firstMatch(RuleSource.KTOR, KtorRuleRequest(request, content), refused)
        val sent = rule?.requestEdit?.let { applyRequestEdit(request, content, it, store.redactor) } ?: content
        val capture = captureRequest(sent, maxBodyBytes)
        val pending = HttpTransaction.pending(
            id = store.nextId(),
            startedAtEpochMs = getTimeMillis(),
            method = request.method.value,
            url = request.url.buildString(),
            requestHeaders = requestHeaders(request, sent),
            requestBody = capture.body,
            source = TransactionSource.KTOR,
            ruleId = rule?.id,
            ruleApplied = rule?.applied,
        )
        if (covered) {
            PlatformCapture.markPassThrough()
            request.headers.append(PlatformCapture.PASS_THROUGH_HEADER, "1")
        }
        val application = rule?.let { RuleApplication(it, mocked = it.mock != null, request.executionContext) }
        // A mock or a fail never reaches the engine, so no platform recorder can see it.
        val engine = if (covered || rule?.mock != null || rule?.fail != null) null else engineClassName
        val exchange = Exchange(store, pending, started, capture.streamed, application, uncoveredEngine = engine)
        request.attributes.put(ExchangeKey, exchange)
        store.submit(pending)
        try {
            rule?.delay?.let { ruleDelay(it.beforeRequestMs, request.executionContext) }
            val mock = rule?.mock
            val fail = rule?.fail
            when {
                mock != null -> {
                    // The engine is never called: the app's body is drained for the record, and the
                    // call runs through the receive pipeline as an engine's call does at `Receive`.
                    drainUnsent(capture.outgoing)
                    val call = mockCall(client, request, capture.outgoing, mock)
                    call.replaceWith(client.receivePipeline.execute(Unit, call.response))
                    context.finishWith(call)
                }
                fail != null -> {
                    rule.delay?.let { ruleDelay(it.beforeResponseMs, request.executionContext) }
                    if (fail == FailKind.CANCEL) drainUnsent(capture.outgoing)
                    throw failure(fail, client, request)
                }
                else -> context.proceedWith(capture.outgoing)
            }
        } catch (cause: Throwable) {
            if (!exchange.withdrawnForPlatform()) exchange.fail(cause)
            throw cause
        }
        exchange.callReturned()
    }

    /** Before `SaveBody` reads the body: the only point where a large body can be teed instead of copied. */
    suspend fun rawResponse(context: ResponseContext, response: HttpResponse) {
        val exchange = response.call.attributes.getOrNull(ExchangeKey) ?: return
        if (exchange.plan != ResponsePlan.None) return
        // A rule's response side: its delay, then its edit, before anything below reads the response.
        val received = exchange.application?.let { applyResponseRule(it, response) } ?: response
        if (exchange.withdrawnForPlatform()) {
            // A platform recorder started while the engine had the request: the call goes on unrecorded.
            if (received !== response) context.proceedWith(received)
            return
        }
        exchange.recordResponse(received)
        val contentType = received.headers[HttpHeaders.ContentType]
        val contentLength = received.contentLength()
        val skipReason = responseSkipReason(contentType)
        when {
            !hasBody(exchange.method, received.status) -> exchange.ready(null)
            skipReason != null -> exchange.ready(CapturedBody.skipped(skipReason, contentType, contentLength))
            isEncoded(received.headers[HttpHeaders.ContentEncoding]) -> exchange.decoded()
            contentLength != null && contentLength <= maxBodyBytes -> exchange.afterSave()
            else -> {
                context.proceedWith(tee(received, exchange, contentType, contentLength))
                return
            }
        }
        if (received !== response) context.proceedWith(received)
    }

    /** The before-response delay and the response edit of [application]'s rule; [response] itself when it has neither. */
    private suspend fun applyResponseRule(application: RuleApplication, response: HttpResponse): HttpResponse {
        val rule = application.rule
        rule.delay?.let { ruleDelay(it.beforeResponseMs, application.execution) }
        val edit = rule.responseEdit ?: return response
        return editedResponse(client, response, edit)
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
            exchange.fail(cause, buffer.body(contentType))
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
                exchange.fail(cause, buffer.body(contentType))
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

        /**
         * Ends the send pipeline with [call], a call Hopi built: `proceedWith` would hand it to the
         * `Engine` phase, which takes only an `OutgoingContent`; the pipeline returns its subject.
         */
        fun finishWith(call: HttpClientCall) {
            context.subject = call
            context.finish()
        }
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

/**
 * Ktor's registry of a client's installed plugins (`PLUGIN_INSTALLED_LIST`, internal to Ktor):
 * an attribute key equals another of the same name and type.
 */
private val InstalledPlugins = AttributeKey<Attributes>("ApplicationPluginRegistry")

/** Whether Ktor's `Auth` plugin (ktor-client-auth, which Hopi does not link) is installed on this client. */
private fun HttpClient.hasAuthPlugin(): Boolean =
    attributes.getOrNull(InstalledPlugins)?.allKeys?.any { it.name == "Auth" } == true
