package dev.koga.hopi.android.internal

import dev.koga.hopi.core.model.TransactionSource
import dev.koga.hopi.core.rules.CompiledRule
import dev.koga.hopi.core.rules.FailKind
import dev.koga.hopi.core.rules.Glob
import dev.koga.hopi.core.rules.MockSpec
import dev.koga.hopi.core.rules.ReplayHandoff
import dev.koga.hopi.core.rules.ReplayRequest
import dev.koga.hopi.core.rules.ReplayTarget
import dev.koga.hopi.core.rules.RequestEdit
import dev.koga.hopi.core.rules.RequestEdits
import dev.koga.hopi.core.rules.ResponseEdit
import dev.koga.hopi.core.rules.ResponseEdits
import dev.koga.hopi.core.rules.RuleRequest
import dev.koga.hopi.core.rules.RuleSource
import dev.koga.hopi.core.rules.RuleState
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.connection.RealCall
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource

/** What a record carries of the rule applied to it and of the replay it is (docs/design/rules.md, "How records are marked"). */
internal class RuleRecordMark(val ruleId: Long?, val applied: String?, val replayOf: Long?, val mocked: Boolean)

/**
 * Rules on OkHttp (docs/design/rules.md, "OkHttp"), at the interceptor's position: last among the
 * application interceptors, above OkHttp's retry, bridge, cache, connection and network
 * interceptors, so an edited request still goes through the client's own pinning, TLS settings,
 * proxy, DNS, cookie jar and cache, and a mock or a fail never opens a connection. Reached only
 * when the rule state is not EMPTY.
 */
internal object OkHttpRules {
    fun intercept(chain: Interceptor.Chain, request: Request, state: RuleState): Response {
        // A replay's hand-off rides only on a replay's own request, sent while the count is above 0.
        val handoff = if (state.replaysInFlight == 0) null else request.tag(ReplayHandoff::class.java)?.takeIf { it.take() }
        val rule = state.firstMatch(RuleSource.OKHTTP, OkHttpRuleRequest(request))
        val target = AndroidCapture.target()
        val call = chain.call()
        if (rule == null) {
            if (handoff == null) return HopiOkHttpInterceptor.proceedRecorded(chain, request, OkHttpRecord(target, request, call))
            val mark = RuleRecordMark(null, null, handoff.replayOf, mocked = false)
            return HopiOkHttpInterceptor.proceedRecorded(chain, request, OkHttpRecord(target, request, call, handoff.id, mark))
        }
        val sent = rule.requestEdit?.let { editRequest(request, it, target) } ?: request
        val mock = rule.mock
        val mark = RuleRecordMark(rule.id, rule.applied, handoff?.replayOf, mocked = mock != null)
        val record = OkHttpRecord(target, sent, call, handoff?.id ?: target.store.nextId(), mark)
        val delay = rule.delay
        val fail = rule.fail
        if (mock == null && fail == null) {
            if (delay != null && delay.beforeRequestMs > 0) {
                record.started()
                try {
                    wait(delay.beforeRequestMs, chain, timeoutMs = 0)
                } catch (e: IOException) {
                    record.failed(HopiOkHttpInterceptor.CANCELLED)
                    throw e
                }
                return proceedEdited(chain, sent, record, rule, alreadyStarted = true)
            }
            return proceedEdited(chain, sent, record, rule, alreadyStarted = false)
        }
        record.started()
        try {
            if (delay != null) wait(delay.beforeRequestMs, chain, timeoutMs = 0)
            if (fail != null) {
                if (delay != null) wait(delay.beforeResponseMs, chain, timeoutMs = 0)
                throw failure(fail, sent, call)
            }
        } catch (e: IOException) {
            record.failed(if (call.isCanceled()) HopiOkHttpInterceptor.CANCELLED else AndroidCapture.describe(e))
            throw e
        }
        var response = mockResponse(sent, mock!!)
        if (delay != null && delay.beforeResponseMs > 0) {
            try {
                wait(delay.beforeResponseMs, chain, chain.readTimeoutMillis().toLong())
            } catch (e: IOException) {
                record.failed(if (call.isCanceled()) HopiOkHttpInterceptor.CANCELLED else AndroidCapture.describe(e))
                throw e
            }
            response = response.newBuilder().receivedResponseAtMillis(System.currentTimeMillis()).build()
        }
        return record.responded(response)
    }

    /** The real send of a request a rule edited or delays: the recorded path, plus the rule's response side. */
    private fun proceedEdited(chain: Interceptor.Chain, sent: Request, record: OkHttpRecord, rule: CompiledRule, alreadyStarted: Boolean): Response {
        val responseDelay = rule.delay?.beforeResponseMs ?: 0
        val edit = rule.responseEdit
        val after: ((Response) -> Response)? = if (responseDelay <= 0 && edit == null) {
            null
        } else {
            { received ->
                if (responseDelay > 0) {
                    try {
                        wait(responseDelay, chain, chain.readTimeoutMillis().toLong())
                    } catch (e: IOException) {
                        received.close()
                        throw e
                    }
                }
                if (edit == null) received else editResponse(received, edit)
            }
        }
        return HopiOkHttpInterceptor.proceedRecorded(chain, sent, record, after, started = alreadyStarted)
    }

    /**
     * Waits [ms] in slices of at most [SLICE_MS], checking the call for a cancel after each: OkHttp
     * gives an interceptor no cancel callback, so this is the one polling wait in Hopi, and it
     * exists only while a delayed request waits. A cancel (the app's or `callTimeout`'s) throws
     * `IOException("Canceled")`, which `RealCall` reports as its own timeout when the cancel was
     * its. With [timeoutMs] non-zero and shorter than [ms], the wait ends with OkHttp's read
     * timeout, `SocketTimeoutException("timeout")`, at [timeoutMs].
     */
    private fun wait(ms: Long, chain: Interceptor.Chain, timeoutMs: Long) {
        if (ms <= 0) return
        val call = chain.call()
        val timesOut = timeoutMs in 1 until ms
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(if (timesOut) timeoutMs else ms)
        while (true) {
            if (call.isCanceled()) throw IOException("Canceled")
            val left = deadline - System.nanoTime()
            if (left <= 0) break
            LockSupport.parkNanos(minOf(left, TimeUnit.MILLISECONDS.toNanos(SLICE_MS)))
            if (Thread.interrupted()) throw java.io.InterruptedIOException("interrupted")
        }
        if (timesOut) throw SocketTimeoutException("timeout")
    }

    private fun failure(kind: FailKind, request: Request, call: Call): IOException = when (kind) {
        FailKind.TIMEOUT -> SocketTimeoutException("timeout")
        FailKind.RESET -> SocketException("Connection reset")
        FailKind.UNKNOWN_HOST -> UnknownHostException("Unable to resolve host \"${request.url.host}\": No address associated with hostname")
        FailKind.CANCEL -> {
            call.cancel()
            IOException("Canceled")
        }
    }

    /**
     * The mock as a response to [request] (the request as the app's interceptors left it): HTTP/1.1,
     * the standard reason phrase, no handshake (no TLS happened, so none is faked).
     */
    private fun mockResponse(request: Request, mock: MockSpec): Response {
        val now = System.currentTimeMillis()
        val headers = headersOf(ResponseEdits.mockHeaders(mock))
        val bytes = mock.body?.bytes?.takeIf { ResponseEdits.hasBody(request.method, mock.status) } ?: EMPTY
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(mock.status)
            .message(ResponseEdits.reasonPhrase(mock.status))
            .headers(headers)
            .body(BytesResponseBody(bytes, headers.get("Content-Type")?.toMediaTypeOrNull()))
            .sentRequestAtMillis(now)
            .receivedResponseAtMillis(now)
            .build()
    }

    /** [response] with [edit] applied; a replaced body closes the original first, so OkHttp releases or discards the connection. */
    private fun editResponse(response: Response, edit: ResponseEdit): Response {
        val pairs = ArrayList<Pair<String, String>>(response.headers.size)
        for (i in 0 until response.headers.size) pairs += response.headers.name(i) to response.headers.value(i)
        val headers = headersOf(ResponseEdits.editHeaders(pairs, edit))
        val builder = response.newBuilder().headers(headers)
        edit.status?.let { builder.code(it).message(ResponseEdits.reasonPhrase(it)) }
        val body = edit.body
        if (body != null) {
            response.body?.close()
            val status = edit.status ?: response.code
            val bytes = body.bytes.takeIf { ResponseEdits.hasBody(response.request.method, status) } ?: EMPTY
            builder.body(BytesResponseBody(bytes, headers.get("Content-Type")?.toMediaTypeOrNull()))
        }
        return builder.build()
    }

    /** [request] with [edit] applied (docs/design/rules.md, "Edit"); the scheme never changes. */
    private fun editRequest(request: Request, edit: RequestEdit, target: AndroidCapture.Target): Request {
        val pairs = ArrayList<Pair<String, String>>(request.headers.size)
        for (i in 0 until request.headers.size) pairs += request.headers.name(i) to request.headers.value(i)
        val originalBody = request.body
        val originalType = originalBody?.contentType()
        if (originalType != null && request.header("Content-Type") == null) pairs += "Content-Type" to originalType.toString()
        val url = request.url.toString()
        val edited = RequestEdits.apply(request.method, url, pairs, edit, target.store.redactor) ?: return request
        val builder = request.newBuilder()
        if (edited.url != url) builder.url(edited.url)
        builder.headers(headersOf(edited.headers))
        val type = edited.headers.lastOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second?.toMediaTypeOrNull()
        val replaced = edited.body
        var body: RequestBody? = when {
            replaced != null -> BytesRequestBody(replaced.bytes, type)
            originalBody != null && type?.toString() != originalType?.toString() -> RetypedRequestBody(originalBody, type)
            else -> originalBody
        }
        val method = edited.method
        if (method == "GET" || method == "HEAD") body = null
        if (body == null && method in BODY_REQUIRED) body = BytesRequestBody(EMPTY, type)
        builder.method(method, body)
        return builder.build()
    }

    private fun headersOf(pairs: List<Pair<String, String>>): Headers {
        val builder = Headers.Builder()
        for ((name, value) in pairs) builder.add(name, value)
        return builder.build()
    }

    private const val SLICE_MS = 50L
    private val EMPTY = ByteArray(0)
    private val BODY_REQUIRED = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
}

/** A rule's bytes as a request body: a fresh read of the shared array per write, never handed out. */
internal class BytesRequestBody(private val bytes: ByteArray, private val type: MediaType?) : RequestBody() {
    override fun contentType(): MediaType? = type

    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }
}

/** The app's request body under another `Content-Type`. */
private class RetypedRequestBody(private val original: RequestBody, private val type: MediaType?) : RequestBody() {
    override fun contentType(): MediaType? = type

    override fun contentLength(): Long = original.contentLength()

    override fun isOneShot(): Boolean = original.isOneShot()

    override fun writeTo(sink: BufferedSink) {
        original.writeTo(sink)
    }
}

/** A rule's bytes as a response body: each response gets its own buffer over the shared array. */
private class BytesResponseBody(private val bytes: ByteArray, private val type: MediaType?) : ResponseBody() {
    private val source by lazy { Buffer().write(bytes) }

    override fun contentType(): MediaType? = type

    override fun contentLength(): Long = bytes.size.toLong()

    override fun source(): BufferedSource = source
}

/**
 * The request a rule is matched against, read from OkHttp's own `HttpUrl` and `Headers`. The path
 * (a substring OkHttp computes on every read) and the decoded query (OkHttp's own decoding) are
 * computed once per request, on the first rule that reads them, not once per rule.
 */
internal class OkHttpRuleRequest(private val request: Request) : RuleRequest {
    override val method: String get() = request.method
    override val scheme: String get() = request.url.scheme
    override val host: String get() = request.url.host
    override val port: Int get() = request.url.port

    private var path: String? = null
    override val encodedPath: String get() = path ?: request.url.encodedPath.also { path = it }

    private var query: Map<String, List<String>>? = null

    override fun queryValues(name: String): List<String> {
        val url = request.url
        if (url.querySize == 0) return emptyList()
        val decoded = query ?: HashMap<String, MutableList<String>>().also { map ->
            for (i in 0 until url.querySize) map.getOrPut(url.queryParameterName(i)) { ArrayList(1) } += url.queryParameterValue(i).orEmpty()
            query = map
        }
        return decoded[name].orEmpty()
    }

    override fun hasQueryValue(name: String, value: Glob?): Boolean {
        val url = request.url
        for (i in 0 until url.querySize) {
            if (url.queryParameterName(i) == name && (value == null || value.matches(url.queryParameterValue(i).orEmpty()))) return true
        }
        return false
    }

    /** [headerValues] read by index in place: no list per predicate. */
    override fun hasHeaderValue(name: String, value: Glob?): Boolean {
        val headers = request.headers
        var named = false
        for (i in 0 until headers.size) {
            if (!headers.name(i).equals(name, ignoreCase = true)) continue
            if (value == null || value.matches(headers.value(i))) return true
            named = true
        }
        if (named || !name.equals("Content-Type", ignoreCase = true)) return false
        val type = request.body?.contentType() ?: return false
        return value == null || value.matches(type.toString())
    }

    override fun headerValues(name: String): List<String> {
        val values = request.headers(name)
        if (values.isNotEmpty() || !name.equals("Content-Type", ignoreCase = true)) return values
        return request.body?.contentType()?.let { listOf(it.toString()) }.orEmpty()
    }
}

/**
 * The replay targets of OkHttp records (docs/design/rules.md, "Replay"): one per client, found from
 * the call (`RealCall.client`, OkHttp 4.x and 5.x) when a record is built on the store's consumer
 * thread, and kept in a map whose entries go with their client. OkHttp 3.14, whose `RealCall` is
 * not public, gets a target that refuses with the reason.
 */
internal object OkHttpReplay {
    private val targets = WeakHashMap<OkHttpClient, OkHttpReplayTarget>()

    @Volatile
    private var callClientUnreachable = false

    fun targetFor(call: Call): ReplayTarget? {
        if (callClientUnreachable) return Unsupported
        val client = try {
            (call as? RealCall)?.client
        } catch (_: LinkageError) {
            callClientUnreachable = true
            return Unsupported
        } ?: return null
        return synchronized(targets) { targets.getOrPut(client) { OkHttpReplayTarget(client) } }
    }

    private object Unsupported : ReplayTarget {
        override val source: String get() = TransactionSource.OKHTTP

        override fun refusal(): String = "this OkHttp (before 4.0) does not expose a call's client"

        override fun send(request: ReplayRequest, handoff: ReplayHandoff) {
            throw UnsupportedOperationException(refusal())
        }
    }
}

/**
 * Re-sends a request through the client that sent it, from Hopi's position: a copy of the client
 * with its application interceptors cleared (the recorded request is the one they produced, and
 * running them again would add their headers twice or re-sign it), sharing everything below
 * (dispatcher, pool, pinner, TLS, verifier, proxy, DNS, cookie jar, cache, authenticator, network
 * interceptors). `RealCall`'s rewritten interceptor list gives the copy Hopi's interceptor, which
 * records the replay under the hand-off's id.
 */
internal class OkHttpReplayTarget(client: OkHttpClient) : ReplayTarget {
    private val client = WeakReference(client)

    override val source: String get() = TransactionSource.OKHTTP

    override fun refusal(): String? = if (client.get() == null) "the client was collected" else null

    override fun send(request: ReplayRequest, handoff: ReplayHandoff) {
        val original = client.get() ?: throw IllegalStateException("the client was collected")
        val builder = Request.Builder().url(request.url)
        for ((name, value) in request.headers) {
            if (name.equals("Content-Length", ignoreCase = true)) continue
            builder.addHeader(name, value)
        }
        val type = request.contentType?.toMediaTypeOrNull()
        builder.method(request.method, request.body?.let { BytesRequestBody(it, type) })
        builder.tag(ReplayHandoff::class.java, handoff)
        val replayClient = original.newBuilder().apply { interceptors().clear() }.build()
        replayClient.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handoff.finish(AndroidCapture.describe(e))
            }

            override fun onResponse(call: Call, response: Response) {
                // Read to the end so the record completes with its body, then release the connection.
                val error = try {
                    response.use { r ->
                        val source = r.body?.source()
                        val sink = Buffer()
                        if (source != null) while (source.read(sink, 8192) != -1L) sink.clear()
                    }
                    null
                } catch (e: IOException) {
                    AndroidCapture.describe(e)
                }
                handoff.finish(error)
            }
        })
    }
}
