package dev.koga.hopi.network.internal.ktor

import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.rules.CompiledRule
import dev.koga.hopi.core.rules.FailKind
import dev.koga.hopi.core.rules.MockSpec
import dev.koga.hopi.core.rules.RequestEdit
import dev.koga.hopi.core.rules.RequestEdits
import dev.koga.hopi.core.rules.ResponseEdit
import dev.koga.hopi.core.rules.ResponseEdits
import dev.koga.hopi.core.rules.RuleRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.HttpRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.close
import io.ktor.utils.io.discard
import java.net.SocketException
import java.net.UnknownHostException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Rules on the Ktor plugin (docs/design/rules.md, "Ktor plugin"). Everything here runs only when
// the rule state is not EMPTY; the plugin's no-rule path never reaches this file.

/**
 * A [RuleRequest] over the builder and its content, read as the engine will send them. The scheme
 * and the path (which the builder joins from its segments on every read) are computed once per
 * request, on the first rule that reads them.
 */
internal class KtorRuleRequest(private val request: HttpRequestBuilder, private val content: OutgoingContent) : RuleRequest {
    override val method: String get() = request.method.value

    private var lowerScheme: String? = null
    override val scheme: String get() = lowerScheme ?: request.url.protocol.name.lowercase().also { lowerScheme = it }
    override val host: String get() = request.url.host
    override val port: Int get() = request.url.port.takeIf { it != 0 } ?: request.url.protocol.defaultPort

    private var path: String? = null
    override val encodedPath: String get() = path ?: request.url.encodedPath.ifEmpty { "/" }.also { path = it }

    override fun queryValues(name: String): List<String> = request.url.parameters.getAll(name).orEmpty()

    override fun headerValues(name: String): List<String> {
        request.headers.getAll(name)?.let { return it }
        content.headers.getAll(name)?.let { return it }
        if (name.equals(HttpHeaders.ContentType, ignoreCase = true) && content !is OutgoingContent.NoContent) {
            content.contentType?.let { return listOf(it.toString()) }
        }
        return emptyList()
    }
}

/**
 * Applies [edit] to [request] (method, URL, headers) and returns the content to send in place of
 * [content]: the replacement body, or the same content under an edited `Content-Type`, or [content]
 * itself. Header names the builder cannot carry (`Content-Type`, which is the content's, and
 * `Content-Length`, which the engine computes) never reach its headers.
 */
internal fun applyRequestEdit(request: HttpRequestBuilder, content: OutgoingContent, edit: RequestEdit, redactor: Redactor): OutgoingContent {
    val originalType = content.takeIf { it !is OutgoingContent.NoContent }?.contentType
    val headers = ArrayList<Pair<String, String>>()
    for ((name, values) in request.headers.entries()) for (value in values) headers += name to value
    originalType?.let { headers += HttpHeaders.ContentType to it.toString() }
    val url = request.url.buildString()
    // Null only for a URL that is not absolute http(s), which a Ktor request never has; the
    // header edits still apply then.
    val edited = RequestEdits.apply(request.method.value, url, headers, edit, redactor)
    edit.method?.let { request.method = HttpMethod.parse(it) }
    if (edited != null && edited.url != url) {
        // takeFrom adds the new URL's parameters to the builder's: the edited query replaces them.
        request.url.encodedParameters.clear()
        request.url.takeFrom(edited.url)
    }
    var newType: ContentType? = null
    request.headers.clear()
    for ((name, value) in edited?.headers ?: RequestEdits.editHeaders(headers, edit, crossOrigin = null)) {
        when {
            name.equals(HttpHeaders.ContentType, ignoreCase = true) -> newType = runCatching { ContentType.parse(value) }.getOrNull()
            name.equals(HttpHeaders.ContentLength, ignoreCase = true) -> Unit
            else -> request.headers.append(name, value)
        }
    }
    val body = edit.body
    return when {
        body != null -> ByteArrayContent(body.bytes, newType)
        newType != originalType && content !is OutgoingContent.NoContent -> RetypedContent(content, newType)
        else -> content
    }
}

/** [delegate] under another `Content-Type`: Ktor keeps the type on the content, never in the builder's headers. */
internal class RetypedContent(delegate: OutgoingContent, private val type: ContentType?) : OutgoingContent.ContentWrapper(delegate) {
    override val contentType: ContentType? get() = type

    override fun copy(delegate: OutgoingContent): OutgoingContent.ContentWrapper = RetypedContent(delegate, type)
}

/**
 * Waits [ms] for a rule's delay. Ends early, throwing, at the caller's cancellation or when
 * [execution] completes ([HttpTimeout] ends a request by cancelling its execution context, which a
 * bare `delay` in the send pipeline would not see): the job's cause is rethrown, unwrapped from the
 * cancellation that carries it (`HttpRequestTimeoutException` for a request timeout).
 */
internal suspend fun ruleDelay(ms: Long, execution: Job) {
    if (ms <= 0) return
    val ended = CompletableDeferred<Throwable>()
    val handle = execution.invokeOnCompletion { cause -> ended.complete(cause ?: CancellationException("request ended")) }
    try {
        val cause = withTimeoutOrNull(ms) { ended.await() } ?: return
        val origin = cause.cause
        throw if (cause is CancellationException && origin != null && origin !is CancellationException) origin else cause
    } finally {
        handle.dispose()
    }
}

/** The exception a fail rule throws: each kind as this client's engine would throw it (see [platformFailure]). */
internal fun failure(kind: FailKind, client: HttpClient, request: HttpRequestBuilder): Throwable = when (kind) {
    FailKind.TIMEOUT -> if (client.pluginOrNull(HttpTimeout) != null) HttpRequestTimeoutException(request) else SocketTimeoutException("timeout")
    FailKind.CANCEL -> CancellationException("cancelled")
    else -> platformFailure(kind, request.url.host)
}

/** `reset` and `unknown-host` as the platform's own socket exceptions (`java.net` on the JVM and Android). */
internal fun platformFailure(kind: FailKind, host: String): Throwable = when (kind) {
    FailKind.UNKNOWN_HOST -> UnknownHostException(host)
    else -> SocketException("Connection reset")
}

/**
 * A call built by Hopi rather than by an engine: a mock, from request and response data, or an
 * edited response over [replaceWith]. Its `response` can be replaced once the receive pipeline ran
 * on it, as the client's own `Receive` phase does for an engine's call.
 */
@OptIn(InternalAPI::class)
internal class RuledCall : HttpClientCall {
    constructor(client: HttpClient, requestData: HttpRequestData, responseData: HttpResponseData) : super(client, requestData, responseData)

    constructor(client: HttpClient, request: HttpRequest) : super(client) {
        this.request = request
    }

    fun replaceWith(response: HttpResponse) {
        this.response = response
    }
}

/**
 * Builds the call a mock delivers (docs/design/rules.md, "Mechanics"): the request data from the
 * builder, with its execution context and attributes (so the plugin's exchange is found on it),
 * and response data whose call context is a new child of the request's execution job (a
 * `CompletableJob`, which `HttpStatement`'s cleanup completes) on the client's dispatcher.
 */
@OptIn(InternalAPI::class)
internal fun mockCall(client: HttpClient, request: HttpRequestBuilder, content: OutgoingContent, mock: MockSpec): RuledCall {
    val requestData = HttpRequestData(
        request.url.build(), request.method, request.headers.build(), content, request.executionContext, request.attributes,
    )
    val headers = HeadersBuilder()
    for ((name, value) in noStoreIfCached(client, ResponseEdits.mockHeaders(mock))) headers.append(name, value)
    val body = mock.body
    val channel = if (body == null || !ResponseEdits.hasBody(request.method.value, mock.status)) ByteReadChannel.Empty else ByteReadChannel(body.bytes)
    val callContext: CoroutineContext = Job(request.executionContext) + client.engine.dispatcher
    val responseData = HttpResponseData(
        HttpStatusCode.fromValue(mock.status), GMTDate(), headers.build(), HttpProtocolVersion.HTTP_1_1, channel, callContext,
    )
    return RuledCall(client, requestData, responseData)
}

/**
 * The response [edit] makes of [response], on a call of its own that shares the original request
 * (so its attributes, the plugin's exchange among them). A replaced body is served from the rule's
 * bytes and the original is cancelled, so the engine releases the connection.
 */
@OptIn(InternalAPI::class)
internal fun editedResponse(client: HttpClient, response: HttpResponse, edit: ResponseEdit): HttpResponse {
    val headers = ArrayList<Pair<String, String>>()
    for ((name, values) in response.headers.entries()) for (value in values) headers += name to value
    val built = HeadersBuilder()
    for ((name, value) in noStoreIfCached(client, ResponseEdits.editHeaders(headers, edit))) built.append(name, value)
    val body = edit.body
    val content = if (body != null) {
        response.rawContent.cancel(null)
        if (ResponseEdits.hasBody(response.call.request.method.value, edit.status ?: response.status.value)) ByteReadChannel(body.bytes) else ByteReadChannel.Empty
    } else {
        response.rawContent
    }
    val call = RuledCall(client, response.call.request)
    val edited = EditedResponse(call, response, edit.status?.let(HttpStatusCode::fromValue) ?: response.status, built.build(), content)
    call.replaceWith(edited)
    return edited
}

@OptIn(InternalAPI::class)
private class EditedResponse(
    override val call: HttpClientCall,
    private val original: HttpResponse,
    override val status: HttpStatusCode,
    override val headers: Headers,
    override val rawContent: ByteReadChannel,
) : HttpResponse() {
    override val version: HttpProtocolVersion get() = original.version
    override val requestTime: GMTDate get() = original.requestTime
    override val responseTime: GMTDate get() = original.responseTime
    override val coroutineContext: CoroutineContext get() = original.coroutineContext
}

/**
 * With [HttpCache] installed, which stores what passes the receive pipeline after Hopi, a rule's
 * response carries `Cache-Control: no-store` in place of any it gave ([ResponseEdits.noStore]), so
 * no cache keeps it after the rule is gone (docs/design/rules.md, "Mock"). Conditional here, unlike
 * the platform sources: the recorder sees its client.
 */
private fun noStoreIfCached(client: HttpClient, headers: List<Pair<String, String>>): List<Pair<String, String>> {
    if (client.pluginOrNull(HttpCache) == null) return headers
    return ResponseEdits.noStore(headers)
}

/**
 * Sends the app's request body nowhere, for a mock: a streaming body is written into a channel that
 * discards it (the capture wrapper keeps up to the cap for the record), so the app's writer
 * coroutine completes instead of waiting for a reader forever.
 */
internal suspend fun drainUnsent(content: OutgoingContent) {
    when (content) {
        is OutgoingContent.WriteChannelContent -> coroutineScope {
            val sink = ByteChannel()
            launch {
                try {
                    content.writeTo(sink)
                    sink.flushAndClose()
                } catch (cause: Throwable) {
                    sink.close(cause)
                }
            }
            sink.discard()
        }
        is OutgoingContent.ReadChannelContent -> content.readFrom().cancel(null)
        is OutgoingContent.ContentWrapper -> drainUnsent(content.delegate())
        else -> Unit
    }
}

/** What a ruled exchange carries to the response hooks: the rule, and whether its response is Hopi's own. */
internal class RuleApplication(val rule: CompiledRule, val mocked: Boolean, val execution: Job)
