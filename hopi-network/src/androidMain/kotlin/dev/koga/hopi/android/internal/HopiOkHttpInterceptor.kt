package dev.koga.hopi.android.internal

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.body.ResponseSkip
import dev.koga.hopi.core.capture.PlatformCapture
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionSource
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleState
import dev.koga.hopi.core.store.HopiStore
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Source
import okio.buffer

/**
 * Records every OkHttp call, as the last application interceptor (docs/design/android-capture.md,
 * "The interceptor"). Observe only: the request goes on unchanged except that a body is wrapped in
 * a [HopiRequestBody] that copies what OkHttp writes, and the response comes back unchanged except
 * that its body is wrapped in a [HopiResponseBody] that copies what the app reads. Hopi never
 * writes, reads or peeks a body itself, and an exception reaches the app as it was thrown. A
 * request carrying the Ktor plugin's pass-through marker is the plugin's to record (it decodes a
 * body OkHttp hands over encoded): it goes on unrecorded, without the marker.
 */
@HopiInternalApi
public object HopiOkHttpInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = intercept(chain, authenticated = false)

    /** Hopi's interceptor on a client with an `Authenticator`, which a 401 makes add a credential. */
    internal object WithAuthenticator : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = intercept(chain, authenticated = true)
    }

    private fun intercept(chain: Interceptor.Chain, authenticated: Boolean): Response {
        val request = chain.request()
        if (!AndroidCapture.started) return chain.proceed(request)
        // Only the Ktor plugin adds the marker, and it says so first: no header scan before that.
        if (PlatformCapture.passThroughMarked && request.header(PlatformCapture.PASS_THROUGH_HEADER) != null) {
            return chain.proceed(request.newBuilder().removeHeader(PlatformCapture.PASS_THROUGH_HEADER).build())
        }
        // One read and a reference comparison: with no rule the call takes exactly the path it took
        // before rules existed.
        val state = if (AndroidCapture.rules) RuleEngine.state else RuleState.EMPTY
        if (state !== RuleState.EMPTY) return OkHttpRules.intercept(chain, request, state, authenticated)
        return proceedRecorded(chain, request, OkHttpRecord(AndroidCapture.target(), request, chain.call()))
    }

    /**
     * Sends [request] with its body copied for [record], and returns the response with its body
     * copied. A rule's response side ([afterResponse]: its delay and edit) runs before the copy; a
     * rule that waited before the request has [started] the record already.
     */
    internal fun proceedRecorded(
        chain: Interceptor.Chain,
        request: Request,
        record: OkHttpRecord,
        afterResponse: ((Response) -> Response)? = null,
        started: Boolean = false,
    ): Response {
        val original = request.body
        val sent = when {
            original == null -> request
            original.isDuplex() -> {
                record.requestSkip = DUPLEX
                request
            }
            else -> {
                val body = HopiRequestBody(original, record)
                record.requestBody = body
                request.newBuilder().method(request.method, body).build()
            }
        }
        if (!started) record.started()
        val response = try {
            val received = chain.proceed(sent)
            if (afterResponse == null) received else afterResponse(received)
        } catch (e: Throwable) {
            record.failed(if (chain.call().isCanceled()) CANCELLED else AndroidCapture.describe(e))
            throw e
        }
        return record.responded(response)
    }

    internal const val CANCELLED = "cancelled"
    internal const val DUPLEX = "duplex, not captured"
    internal const val NOT_SENT = "not sent"
    internal const val INCOMPLETE = "incomplete"
    internal const val NOT_READ = "not read"
}

/**
 * One call's record: what the request thread saw, queued to the store twice (start and end), as
 * itself. The records are built on the store's consumer thread, from the fields kept here.
 */
internal class OkHttpRecord(
    private val target: AndroidCapture.Target,
    private val request: Request,
    private val call: Call,
    /** The rule's mark, or null: then the record is what it was before rules existed. */
    private val mark: RuleRecordMark? = null,
) : HopiStore.DeferredRecord {
    private val id = target.store.nextId()
    private val startedAtEpochMs = System.currentTimeMillis()
    private val startNanos = System.nanoTime()
    private val terminal = AtomicBoolean(false)

    var requestBody: HopiRequestBody? = null
    var requestSkip: String? = null
    private var code = 0
    private var headers: Headers? = null
    private var protocol: Protocol? = null
    private var finalUrl: String? = null

    // The end, written once (behind [terminal]) before the record is queued a second time.
    private var durationMs = 0L
    private var endError: String? = null
    private var endBody: CapturedBody? = null
    private var endCapture: BoundedCapture? = null

    // The request body's size once [shedBodies] let go of its capture, else null.
    private var shedRequestBytes: Long? = null

    // The raw header: OkHttp's `contentType()` parses a MediaType per response, which nothing here needs.
    private var responseType: String? = null

    /** Why the response body is only counted ([ResponseSkip]), or null when it is copied up to the cap. */
    @Volatile
    var responseSkip: String? = null
        private set

    val maxBodyBytes: Int get() = target.maxBodyBytes

    /** The cap of the response body's capture: 0 (count only) for a body that is not kept. */
    val responseCaptureCap: Int get() = if (responseSkip == null) target.maxBodyBytes else 0

    fun started() {
        target.store.submitStart(this)
    }

    /**
     * Takes the response's fields and wraps its body: copying up to the cap, or, for a binary or
     * streaming type, only counting what the app reads, so state, size and duration come from the
     * body's real end in both cases.
     */
    fun responded(response: Response): Response {
        code = response.code
        headers = response.headers
        protocol = response.protocol
        val responseUrl = response.request.url
        if (responseUrl != request.url) finalUrl = responseUrl.toString()
        val body: ResponseBody? = response.body
        if (body == null || !AndroidCapture.hasBody(request.method, code)) {
            finish(null, null)
            return response
        }
        val type = response.headers.get("Content-Type")
        responseType = type
        responseSkip = ResponseSkip.reason(type)
        return response.newBuilder().body(HopiResponseBody(body, this)).build()
    }

    fun bodyEnded(capture: BoundedCapture) {
        finish(null, capture)
    }

    /**
     * The app closed the body before a read returned the end. A body whose declared length was
     * read whole is complete (a JSON parser stops after the last byte without asking for more), and
     * so is a response whose body the app never read at all (an SDK ping that only looks at the
     * status): its body is recorded as not read. A stream (`text/event-stream`) ends when the app
     * closes it, so it is complete with what was read. Any other body cut short after some bytes
     * is cancelled, and keeps what arrived.
     */
    fun bodyClosed(capture: BoundedCapture, contentLength: Long) {
        when {
            contentLength >= 0 && capture.total >= contentLength -> finish(null, capture)
            capture.total == 0L -> finish(CapturedBody.skipped(HopiOkHttpInterceptor.NOT_READ, responseType, contentLength.takeIf { it >= 0 }), null)
            responseSkip == ResponseSkip.STREAMING -> finish(null, capture)
            else -> failed(HopiOkHttpInterceptor.CANCELLED, capture)
        }
    }

    /**
     * A read of the body threw. After `call.cancel()` (how okhttp-sse's `EventSource.cancel()` and
     * Ktor's SSE session on this engine end a stream, closing the socket under a blocked read) the
     * throw is the cancel's, not a failure: a stream is complete with what was read, like a stream
     * the app closed, and any other body is cancelled and keeps what arrived, as when it is closed
     * early. Otherwise the read's own error fails the record.
     */
    fun readFailed(error: Throwable, capture: BoundedCapture) {
        when {
            !call.isCanceled() -> failed(AndroidCapture.describe(error), capture)
            responseSkip == ResponseSkip.STREAMING -> finish(null, capture)
            else -> failed(HopiOkHttpInterceptor.CANCELLED, capture)
        }
    }

    /** Fails the record; after the response arrived it keeps the status, headers and the part of the body in [partial]. */
    fun failed(error: String, partial: BoundedCapture? = null) {
        if (!terminal.compareAndSet(false, true)) return
        durationMs = (System.nanoTime() - startNanos) / 1_000_000
        endError = error
        endCapture = partial
        target.store.submitEnd(this)
    }

    private fun finish(skipped: CapturedBody?, capture: BoundedCapture?) {
        if (!terminal.compareAndSet(false, true)) return
        durationMs = (System.nanoTime() - startNanos) / 1_000_000
        endBody = skipped
        endCapture = capture
        target.store.submitEnd(this)
    }

    override fun terminalSnapshot(): HttpTransaction {
        val started = pendingSnapshot().copy(requestBody = requestCaptured())
        val error = endError
        val body = endBody ?: endCapture?.let(::responseBody)
        if (error == null) {
            return started.completed(code, flatten(headers), body, durationMs, recordedProtocol()).copy(finalUrl = finalUrl)
        }
        if (code == 0) return started.failed(error, durationMs)
        return started.failedAfterResponse(error, durationMs, code, flatten(headers), body, recordedProtocol()).copy(finalUrl = finalUrl)
    }

    // Both captures are final by the time the end is queued: the request's was set (a volatile
    // write) before the response came, the response's froze before [finish] or [failed] ran.
    override fun heldBytes(): Long = (requestBody?.capture?.held ?: 0).toLong() + (endCapture?.held ?: 0)

    override fun shedBodies() {
        requestBody?.capture?.let { capture ->
            if (capture.held == 0) return@let
            shedRequestBytes = capture.total
            requestBody = null
        }
        endCapture?.let { capture ->
            if (capture.held == 0) return@let
            endBody = CapturedBody.skipped(HopiStore.SKIPPED_STORE_BACKLOG, responseType, capture.total)
            endCapture = null
        }
    }

    /** What [capture] holds of the response: its bytes, or, for a body only counted, its size and why. */
    private fun responseBody(capture: BoundedCapture): CapturedBody {
        val skip = responseSkip ?: return captured(capture, responseType)
        return CapturedBody.skipped(skip, responseType, capture.total)
    }

    /** A response Hopi built crossed no protocol. */
    private fun recordedProtocol(): String? = if (mark?.mocked == true) null else protocolName(protocol)

    override fun pendingSnapshot(): HttpTransaction {
        val body = request.body
        val requestHeaders = flatten(request.headers)
        // OkHttp's BridgeInterceptor adds the body's type later; the record shows it as sent.
        val type = body?.contentType()?.toString()
        val withType = if (type != null && request.header("Content-Type") == null) requestHeaders + ("Content-Type" to type) else requestHeaders
        return HttpTransaction.pending(
            id, startedAtEpochMs, request.method, request.url.toString(), withType,
            requestBody = null,
            source = TransactionSource.OKHTTP,
            ruleId = mark?.ruleId,
            ruleApplied = mark?.applied,
        )
    }

    private fun requestCaptured(): CapturedBody? {
        val body = request.body ?: return null
        val type = body.contentType()?.toString()
        requestSkip?.let { return CapturedBody.skipped(it, type, null) }
        shedRequestBytes?.let { return CapturedBody.skipped(HopiStore.SKIPPED_STORE_BACKLOG, type, it) }
        val wrapper = requestBody ?: return null
        val capture = wrapper.capture ?: return CapturedBody.skipped(wrapper.unfinishedReason, type, null)
        return captured(capture, type)
    }

    private fun captured(capture: BoundedCapture, type: String?): CapturedBody =
        CapturedBody(capture.bytes(), capture.total, capture.truncated, type, skippedReason = null)

    private companion object {
        fun flatten(headers: Headers?): List<Pair<String, String>> {
            if (headers == null) return emptyList()
            val out = ArrayList<Pair<String, String>>(headers.size)
            for (i in 0 until headers.size) out += headers.name(i) to headers.value(i)
            return out
        }

        /** As Ktor writes it: `HTTP/1.1`, `HTTP/2.0`. */
        fun protocolName(protocol: Protocol?): String? = when (val name = protocol?.toString()) {
            null -> null
            "http/1.0" -> "HTTP/1.0"
            "http/1.1" -> "HTTP/1.1"
            "h2", "h2_prior_knowledge" -> "HTTP/2.0"
            "h3", "quic" -> "HTTP/3.0"
            else -> name.uppercase()
        }
    }
}

/**
 * The app's request body, with what OkHttp writes copied up to the cap during the first `writeTo`
 * that completes. Everything else is the original's: type, length, one-shot and duplex. Hopi never
 * calls `writeTo` itself, so a one-shot body is still written exactly once, by OkHttp.
 */
internal class HopiRequestBody(private val original: RequestBody, private val record: OkHttpRecord) : RequestBody() {
    @Volatile
    var capture: BoundedCapture? = null
        private set

    @Volatile
    var unfinishedReason: String = HopiOkHttpInterceptor.NOT_SENT
        private set

    override fun contentType(): MediaType? = original.contentType()

    override fun contentLength(): Long = original.contentLength()

    override fun isOneShot(): Boolean = original.isOneShot()

    override fun isDuplex(): Boolean = original.isDuplex()

    override fun writeTo(sink: BufferedSink) {
        // A later writeTo (a redirect or retry resending a repeatable body) passes through.
        if (capture != null) {
            original.writeTo(sink)
            return
        }
        val length = try {
            original.contentLength()
        } catch (_: IOException) {
            -1L
        }
        val attempt = BoundedCapture(record.maxBodyBytes, length)
        val copying = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                attempt.take(source, 0, byteCount)
                super.write(source, byteCount)
            }
        }
        val buffered = copying.buffer()
        try {
            original.writeTo(buffered)
            buffered.emit()
        } catch (e: Throwable) {
            unfinishedReason = HopiOkHttpInterceptor.INCOMPLETE
            throw e
        }
        capture = attempt
    }
}

/**
 * The app's response body, whose source copies what the app reads, up to the cap, and counts the
 * rest; a binary or streaming body is only counted (a cap of 0: no copy, no array). It reads
 * nothing the app did not read: no peek, no read-ahead, and per read only a count and, below the
 * cap, a copy.
 */
internal class HopiResponseBody(private val original: ResponseBody, private val record: OkHttpRecord) : ResponseBody() {
    private var source: BufferedSource? = null

    override fun contentType(): MediaType? = original.contentType()

    override fun contentLength(): Long = original.contentLength()

    private var closedUnopened = false

    @Synchronized
    override fun source(): BufferedSource =
        source ?: original.contentLength().let { length ->
            TeeSource(original.source(), record, BoundedCapture(record.responseCaptureCap, length), length).buffer().also { source = it }
        }

    /**
     * `ResponseBody.close()` is `source().closeQuietly()`: a body the app never opened is closed
     * through the original, with no tee built for it, and recorded as not read.
     */
    override fun close() {
        val opened = synchronized(this) {
            if (source == null) {
                if (closedUnopened) return
                closedUnopened = true
            }
            source
        }
        if (opened != null) {
            super.close()
            return
        }
        record.bodyClosed(BoundedCapture(0), original.contentLength())
        original.close()
    }

    private class TeeSource(
        delegate: Source,
        private val record: OkHttpRecord,
        private val capture: BoundedCapture,
        private val contentLength: Long,
    ) : ForwardingSource(delegate) {
        // The capture's freeze is the end: the first of a read's end, its throw and a close (maybe
        // on another thread) ends the record, and no later read changes what it holds.
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = try {
                super.read(sink, byteCount)
            } catch (e: Throwable) {
                if (capture.freeze()) record.readFailed(e, capture)
                throw e
            }
            if (read == -1L) {
                if (capture.freeze()) record.bodyEnded(capture)
                return -1
            }
            capture.take(sink, sink.size - read, read)
            return read
        }

        override fun close() {
            if (capture.freeze()) record.bodyClosed(capture, contentLength)
            super.close()
        }
    }
}
