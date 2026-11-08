package dev.koga.hopi.android.internal

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.body.ResponseSkip
import dev.koga.hopi.core.capture.PlatformCapture
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionSource
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import dev.koga.hopi.core.rules.FailKind
import dev.koga.hopi.core.rules.Glob
import dev.koga.hopi.core.rules.RequestEdit
import dev.koga.hopi.core.rules.RequestEdits
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleSource
import dev.koga.hopi.core.rules.RuleState
import dev.koga.hopi.core.rules.RuleRequest
import dev.koga.hopi.core.rules.UrlParts
import dev.koga.hopi.core.store.HopiStore
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/**
 * The record of one wrapped `HttpURLConnection` (docs/design/android-capture.md, "The
 * `URLConnection` wrapper"). The wrapper calls it around the app's own calls; it never calls a
 * delegate method the app did not call, except reading the request before the first connecting
 * call (which has no side effect then) and the response's status and headers once a call of the
 * app's has connected (the delegate has them cached by then).
 */
@HopiInternalApi
public class UrlConnectionRecorder(original: HttpURLConnection, private val proxy: Proxy? = null) : HopiStore.DeferredRecord {
    /**
     * The connection the wrapper forwards to: the app's own, until a rule replaces it at the first
     * connecting call (a mock's or a response edit's [RuledHttpConnection], or a new connection for
     * an edited URL). Written once, under this monitor, by the thread of that call. A plain field,
     * so a call without rules reads no volatile: `disconnect()` from another thread takes this
     * monitor before it reads it, and a connection is otherwise used by one thread at a time.
     */
    public var delegate: HttpURLConnection = original
        private set

    private var target: AndroidCapture.Target? = null
    private var id = 0L
    private var startedAtEpochMs = 0L
    private var startNanos = 0L
    private var method = ""
    // The URL as text, made on the store's thread from [requestUrl] (`URL.toString` builds it anew).
    private var urlText: String? = null
    private var requestUrl: java.net.URL? = null
    // The map the connection returned (a fresh unmodifiable copy), flattened on the store's thread.
    private var requestProperties: Map<String?, List<String>> = emptyMap()
    @Volatile
    private var requestCapture: BoundedCapture? = null
    private var requestLength = -1L
    private var chunkLength = 0
    private var passingThrough = false

    /** The rule applied to this connection, or null: then everything below is what it was before rules existed. */
    private var ruled: Ruled? = null
    private var mark: RuleRecordMark? = null

    private var responded = false
    private var code = 0
    // The response headers as [name, value, name, value, …], read by index: the platform's
    // `getHeaderFields()` builds a fresh sorted map on every call, the costliest read of a response.
    private var responseHeaderStrings: Array<String?> = NO_HEADERS
    private var responseHeaderCount = 0
    private var responseType: String? = null
    private var responseLength = -1L

    // Why the response body is only counted (binary, streaming), or null when it is copied up to the cap.
    private var responseSkip: String? = null
    // The connection's URL after a redirect it followed itself; compared and made text on the store's thread.
    private var responseUrl: URL? = null
    private var teeSource: InputStream? = null
    private var tee: InputStream? = null
    private val terminal = AtomicBoolean(false)

    // The end, written once (behind [terminal]) before the recorder is queued a second time: the
    // record is built from them on the store's thread.
    private var durationMs = 0L
    private var endError: String? = null
    private var endResponded = false
    private var endBody: CapturedBody? = null
    private var endTee: TeeInputStream? = null

    // The request body's size once [shedBodies] let go of its capture, else null.
    private var shedRequestBytes: Long? = null

    /**
     * Takes the request snapshot before the app's first connecting call, once; with rules, applies
     * the first matching rule there. [output] is true for `getOutputStream`.
     */
    @Synchronized
    public fun beforeConnect(output: Boolean = false) {
        if (target != null || passingThrough) {
            ruled?.again(output)
            return
        }
        // One read and a reference comparison: with no rule the connection takes exactly the path
        // it took before rules existed.
        val state = if (AndroidCapture.rules) RuleEngine.state else RuleState.EMPTY
        // Rules of other sources only: the path without rules.
        if (state !== RuleState.EMPTY && state.rulesFor(RuleSource.URL_CONNECTION).isNotEmpty()) {
            beforeConnectRuled(state, output)
            return
        }
        val target = AndroidCapture.target()
        this.target = target
        id = target.store.nextId()
        startedAtEpochMs = System.currentTimeMillis()
        startNanos = System.nanoTime()
        val requested = delegate.requestMethod ?: "GET"
        // The platform turns a GET with doOutput into a POST when it connects.
        method = if (requested == "GET" && delegate.doOutput) "POST" else requested
        val requestedUrl = delegate.url
        requestUrl = requestedUrl
        requestProperties = try {
            delegate.requestProperties.orEmpty()
        } catch (_: IllegalStateException) {
            emptyMap()
        }
        target.store.submitStart(this)
    }

    /**
     * The first connecting call with rules (docs/design/rules.md, "`HttpURLConnection`"): the
     * first matching rule is claimed and its request edit applied before the snapshot (a new delegate when the URL changes or a header is removed), then the record is
     * started and the rule's before-request delay, mock, fail or body replacement run.
     */
    private fun beforeConnectRuled(state: RuleState, output: Boolean) {
        val requested = delegate.requestMethod ?: "GET"
        val requestMethod = if (requested == "GET" && delegate.doOutput) "POST" else requested
        val properties = try {
            delegate.requestProperties.orEmpty()
        } catch (_: IllegalStateException) {
            emptyMap()
        }
        val requestedUrl = delegate.url
        val target = AndroidCapture.target()
        val rule = state.firstMatch(RuleSource.URL_CONNECTION, UrlConnectionRuleRequest(requestMethod, requestedUrl, properties))
        val edit = rule?.requestEdit
        if (rule != null) {
            // Claimed: the rule applies, as far as this stack can, whatever happens below.
            ruled = Ruled(rule)
            edit?.let { applyRequestEdit(it, requestMethod, properties, target) }
            mark = RuleRecordMark(rule.id, rule.applied, mocked = rule.mock != null)
        }
        this.target = target
        id = target.store.nextId()
        startedAtEpochMs = System.currentTimeMillis()
        startNanos = System.nanoTime()
        if (edit == null) {
            // Nothing changed the request: the reads above are the snapshot.
            method = requestMethod
            requestUrl = requestedUrl
            requestProperties = properties
        } else {
            val method = delegate.requestMethod ?: "GET"
            this.method = if (method == "GET" && delegate.doOutput) "POST" else method
            val editedUrl = delegate.url
            requestUrl = editedUrl
            requestProperties = try {
                delegate.requestProperties.orEmpty()
            } catch (_: IllegalStateException) {
                emptyMap()
            }
        }
        target.store.submitStart(this)
        ruled?.first(output)
    }

    /**
     * Applies [edit] before anything connected. Method, header sets and adds go to the delegate;
     * a URL change, or a header removal (Android's connection keeps a header set to null and has no
     * remove), takes a new delegate, configured with everything read back from the old one, the
     * app's TLS socket factory and verifier and its proxy included; the old one never connects.
     */
    private fun applyRequestEdit(edit: RequestEdit, method: String, properties: Map<String?, List<String>>, target: AndroidCapture.Target) {
        val old = delegate
        val headers = flatten(properties)
        val edited = RequestEdits.apply(method, old.url.toString(), headers, edit, target.store.redactor) ?: return
        val removes = edited.originChanged || edit.removeHeaders.isNotEmpty()
        if (edited.url == old.url.toString() && !removes) {
            if (edit.method != null) old.requestMethod = edited.method
            for (name in edit.setHeaders.map { it.first }.distinct()) {
                val values = edit.setHeaders.filter { it.first == name }
                old.setRequestProperty(name, values.first().second)
                for (i in 1 until values.size) old.addRequestProperty(name, values[i].second)
            }
            for ((name, value) in edit.addHeaders) old.addRequestProperty(name, value)
        } else {
            val url = URL(edited.url)
            val next = (if (proxy != null) url.openConnection(proxy) else url.openConnection()) as HttpURLConnection
            next.requestMethod = edit.method ?: old.requestMethod ?: "GET"
            next.doInput = old.doInput
            next.doOutput = old.doOutput
            next.useCaches = old.useCaches
            next.ifModifiedSince = old.ifModifiedSince
            next.allowUserInteraction = old.allowUserInteraction
            next.instanceFollowRedirects = old.instanceFollowRedirects
            next.connectTimeout = old.connectTimeout
            next.readTimeout = old.readTimeout
            if (chunkLength > 0) next.setChunkedStreamingMode(chunkLength)
            if (requestLength >= 0 && edit.body == null) next.setFixedLengthStreamingMode(requestLength)
            if (old is HttpsURLConnection && next is HttpsURLConnection) {
                next.sslSocketFactory = old.sslSocketFactory
                next.hostnameVerifier = old.hostnameVerifier
            }
            for ((name, value) in edited.headers) next.addRequestProperty(name, value)
            delegate = next
        }
        edit.body?.let { body ->
            if (!delegate.doOutput) delegate.doOutput = true
            ruled?.pendingBody = body.bytes
        }
    }

    /**
     * True when [key] is the Ktor plugin's pass-through marker ([PlatformCapture.PASS_THROUGH_HEADER]):
     * the plugin records that request itself, so this connection records nothing and the marker is
     * never set on the delegate.
     */
    public fun passThrough(key: String?): Boolean {
        if (key == null || !key.equals(PlatformCapture.PASS_THROUGH_HEADER, ignoreCase = true)) return false
        synchronized(this) {
            // After connect the platform refuses any request property: so does the marker, then.
            if (target != null) return false
            passingThrough = true
        }
        return true
    }

    /** The app's fixed-length streaming size: the request capture is sized for it once. */
    @Synchronized
    public fun requestLength(length: Long) {
        requestLength = length
    }

    /** The app's chunked streaming mode (there is no getter): what a rebuilt delegate sets again. */
    @Synchronized
    public fun chunked(length: Int) {
        chunkLength = if (length > 0) length else DEFAULT_CHUNK
    }

    /**
     * For `getOutputStream` under a body edit: writes the rule's body to the delegate and returns
     * the stream the app writes into, which discards what it gets; null without a body edit.
     */
    @Synchronized
    public fun replacingStream(): OutputStream? {
        val ruled = ruled ?: return null
        if (!ruled.replacesBody) return null
        ruled.writeReplacement()
        return DiscardingStream
    }

    /** A connecting call of the app's threw; a 4xx/5xx from `getInputStream` still has a response. */
    public fun connectFailed(error: IOException, fromInputStream: Boolean = false) {
        if (fromInputStream && error is FileNotFoundException) {
            responseArrived()
            return
        }
        failed(AndroidCapture.describe(error))
    }

    /** Reads the response's status and headers once, after an app call that connected. */
    @Synchronized
    public fun responseArrived() {
        if (responded || target == null || terminal.get()) return
        ruled?.beforeResponse()
        val status = try {
            delegate.responseCode
        } catch (e: IOException) {
            failed(AndroidCapture.describe(e))
            return
        }
        responded = true
        code = status
        readResponseHeaders()
        // The same URL object unless a redirect followed inside the connection.
        val current = delegate.url
        if (current !== requestUrl) responseUrl = current
        responseType = delegate.contentType
        responseLength = delegate.contentLengthLong
        if (!AndroidCapture.hasBody(method, code) || responseLength == 0L) {
            finish(null)
            return
        }
        // A binary or streaming body is counted as the app reads it, never copied; it ends with its stream.
        responseSkip = ResponseSkip.reason(responseType)
    }

    /**
     * The app's output stream, copying what it writes up to the cap; after the end (a connection whose
     * first connecting call failed, opened again) the stream itself: what the record holds, and what
     * the store counted for its end, change no more.
     */
    @Synchronized
    public fun requestStream(stream: OutputStream): OutputStream {
        if (terminal.get()) return stream
        val target = target ?: return stream
        val capture = requestCapture ?: BoundedCapture(target.maxBodyBytes, requestLength).also { requestCapture = it }
        return TeeOutputStream(stream, capture)
    }

    /** The app's input or error stream, copying what it reads up to the cap, or [stream] itself when nothing is captured. */
    @Synchronized
    public fun responseStream(stream: InputStream?): InputStream? {
        if (stream == null) return null
        if (!responded || terminal.get()) return stream
        if (stream === teeSource) return tee
        // One body per response: a second stream (getErrorStream after getInputStream, or the
        // reverse) passes through, so it can neither replace the record's tee nor end it twice.
        if (tee != null) return stream
        val cap = if (responseSkip == null) target?.maxBodyBytes ?: 0 else 0
        val wrapped = TeeInputStream(stream, BoundedCapture(cap, responseLength))
        teeSource = stream
        tee = wrapped
        return wrapped
    }

    /** The app read the body through `getContent`, from the delegate's own stream. */
    public fun contentReadDirectly() {
        finish(CapturedBody.skipped(READ_BY_GET_CONTENT, responseType, responseLength.takeIf { it >= 0 }))
    }

    /** The app disconnected: a response whose body was never opened is complete without it. */
    @Synchronized
    public fun disconnected() {
        ruled?.disconnect()
        if (target == null || terminal.get()) return
        val open = tee as TeeInputStream?
        when {
            open != null -> open.closedEarly()
            responded -> finish(CapturedBody.skipped(NOT_READ, responseType, responseLength.takeIf { it >= 0 }))
            else -> failed(HopiOkHttpInterceptor.CANCELLED)
        }
    }

    /**
     * Fails the record; after the response arrived it keeps the status, headers and what arrived of
     * the body in [partial]. A tee's body ([partial], [finish]'s [tee]) is built on the store's
     * thread, not the app's: a capture's join of its blocks never runs on the reader.
     */
    private fun failed(error: String, partial: TeeInputStream? = null) {
        val target = target ?: return
        if (!terminal.compareAndSet(false, true)) return
        requestCapture?.freeze()
        durationMs = (System.nanoTime() - startNanos) / 1_000_000
        endError = error
        endResponded = responded
        endTee = partial
        target.store.submitEnd(this)
    }

    /** Completes the record with [body], or, when [tee] is given, with what it captured. */
    private fun finish(body: CapturedBody?, tee: TeeInputStream? = null) {
        val target = target ?: return
        if (!terminal.compareAndSet(false, true)) return
        requestCapture?.freeze()
        durationMs = (System.nanoTime() - startNanos) / 1_000_000
        endBody = body
        endTee = tee
        target.store.submitEnd(this)
    }

    override fun terminalSnapshot(): HttpTransaction {
        val started = pendingSnapshot().copy(requestBody = requestBody())
        val finalUrl = responseUrl?.toString()?.takeIf { it != started.url }
        val error = endError
        val body = endBody ?: endTee?.captured()
        if (error == null) {
            return started.completed(code, responseHeaders(), body, durationMs, recordedProtocol()).copy(finalUrl = finalUrl)
        }
        if (!endResponded) return started.failed(error, durationMs)
        return started.failedAfterResponse(error, durationMs, code, responseHeaders(), body, recordedProtocol()).copy(finalUrl = finalUrl)
    }

    // Both captures froze before [finish] or [failed] queued the end, and none is made after it
    // ([requestStream] and [Ruled.writeReplacement] look at [terminal] first), so neither changes.
    override fun heldBytes(): Long = (requestCapture?.held ?: 0).toLong() + (endTee?.held ?: 0)

    /**
     * After the end, nothing reads the tee's fields again ([responseStream] and [disconnected] look
     * at [terminal] first), so they are let go with it; an app that keeps its stream keeps its copy.
     */
    override fun shedBodies() {
        requestCapture?.let { capture ->
            if (capture.held == 0) return@let
            shedRequestBytes = capture.total
            requestCapture = null
        }
        endTee?.let { stream ->
            if (stream.held == 0) return@let
            endBody = stream.withoutBytes()
            endTee = null
            tee = null
            teeSource = null
        }
    }

    private fun requestBody(): CapturedBody? {
        val capture = requestCapture
        val shed = shedRequestBytes
        if (capture == null && shed == null) return null
        val type = requestProperties.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()
        if (capture == null) return CapturedBody.skipped(HopiStore.SKIPPED_STORE_BACKLOG, type, shed)
        return CapturedBody(capture.bytes(), capture.total, capture.truncated, type, skippedReason = null)
    }

    private fun url(): String = urlText ?: requestUrl.toString().also { urlText = it }

    override fun pendingSnapshot(): HttpTransaction = HttpTransaction.pending(
        id, startedAtEpochMs, method, url(), flatten(requestProperties), requestBody = null, source = TransactionSource.URL_CONNECTION,
        ruleId = mark?.ruleId, ruleApplied = mark?.applied,
    )

    /** A mocked response crossed no protocol. */
    private fun recordedProtocol(): String? = if (mark?.mocked == true) null else protocol()

    /**
     * The rule claimed at the first connecting call, applied as this connection's calls come: its
     * delays wait on this recorder's monitor, which `disconnect()` notifies, so nothing polls.
     */
    private inner class Ruled(val rule: dev.koga.hopi.core.rules.CompiledRule) {
        var pendingBody: ByteArray? = null
        private var failure: IOException? = null
        private var waitedForResponse = false
        private var disconnectRequested = false

        /** Whether the rule replaces the body: the app's own writes are then discarded, however late they come. */
        val replacesBody: Boolean get() = rule.requestEdit?.body != null

        /** At the first connecting call, after the record started. */
        fun first(output: Boolean) {
            try {
                rule.delay?.let { wait(it.beforeRequestMs, delegate.connectTimeout, "connect timed out") }
                val fail = rule.fail
                if (fail != null) {
                    val responseDelay = rule.delay?.beforeResponseMs ?: 0
                    if (responseDelay > 0) wait(responseDelay, delegate.readTimeout, "Read timed out")
                    throw failureOf(fail, timedOutReading = responseDelay > 0)
                }
                val mock = rule.mock
                val edit = rule.responseEdit
                if (mock != null || edit != null) {
                    val original = delegate
                    val response = mock?.let { RuledResponse.of(it, method) }
                    val core = RuledCore(original, response, edit)
                    delegate = if (original is HttpsURLConnection) RuledHttpsConnection(core) else RuledHttpConnection(core)
                }
                if (!output && pendingBody != null) writeReplacement()
            } catch (e: IOException) {
                failure = e
                failed(AndroidCapture.describe(e))
                throw e
            }
        }

        /** A later connecting call: a failed connection fails again, as the platform's would. */
        fun again(output: Boolean) {
            failure?.let { throw it }
            if (!output && pendingBody != null) writeReplacement()
        }

        /** The replacement body, written once to the delegate at the size it has. */
        fun writeReplacement() {
            val bytes = pendingBody ?: return
            pendingBody = null
            val delegate = delegate
            if (chunkLength == 0) delegate.setFixedLengthStreamingMode(bytes.size)
            try {
                delegate.outputStream.use { it.write(bytes) }
            } catch (e: IOException) {
                connectFailed(e)
                throw e
            }
            // Sent after the end (a disconnect landed first): the record's request stays as it ended.
            if (terminal.get()) return
            val capture = BoundedCapture(target?.maxBodyBytes ?: 0, bytes.size.toLong())
            capture.take(bytes, 0, bytes.size)
            requestCapture = capture
        }

        /** Before the first response value reaches the app: the rule's before-response delay. */
        fun beforeResponse() {
            if (waitedForResponse) return
            waitedForResponse = true
            val ms = rule.delay?.beforeResponseMs ?: 0
            if (ms <= 0 || rule.fail != null) return
            try {
                wait(ms, delegate.readTimeout, "Read timed out")
            } catch (e: IOException) {
                failed(AndroidCapture.describe(e))
                throw e
            }
        }

        fun disconnect() {
            disconnectRequested = true
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (this@UrlConnectionRecorder as Object).notifyAll()
        }

        /**
         * Waits [ms] on the recorder's monitor (held by the caller, released while waiting). A
         * `disconnect()` ends it with `SocketException("Socket closed")`, as a disconnect from
         * another thread ends a blocked connect or read; a non-zero [timeoutMs] shorter than [ms]
         * ends it at [timeoutMs] with `SocketTimeoutException(timeoutMessage)`.
         */
        private fun wait(ms: Long, timeoutMs: Int, timeoutMessage: String) {
            if (ms <= 0) return
            val timesOut = timeoutMs in 1 until ms
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(if (timesOut) timeoutMs.toLong() else ms)
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            val monitor = this@UrlConnectionRecorder as Object
            while (!disconnectRequested) {
                val left = deadline - System.nanoTime()
                if (left <= 0) break
                try {
                    monitor.wait(maxOf(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(left)))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("interrupted")
                }
            }
            if (disconnectRequested) throw SocketException("Socket closed")
            if (timesOut) throw SocketTimeoutException(timeoutMessage)
        }

        private fun failureOf(kind: FailKind, timedOutReading: Boolean): IOException = when (kind) {
            FailKind.TIMEOUT -> SocketTimeoutException(if (timedOutReading) "Read timed out" else "connect timed out")
            FailKind.RESET -> SocketException("Connection reset")
            FailKind.UNKNOWN_HOST -> UnknownHostException("Unable to resolve host \"${delegate.url.host}\": No address associated with hostname")
            FailKind.CANCEL -> SocketException("Socket closed")
        }
    }

    /** Android names the negotiated protocol in a header of its own. */
    private fun protocol(): String? =
        responseHeaders().firstOrNull { it.first.equals(SELECTED_PROTOCOL, ignoreCase = true) }?.second?.let(::protocolName)

    /**
     * Reads the response headers by index, on the app's thread, without the map: index `i` until
     * the value is null; a null name is the status line (the JDK's index 0), skipped.
     */
    private fun readResponseHeaders() {
        var strings = arrayOfNulls<String>(32)
        var count = 0
        var index = 0
        while (true) {
            val value = delegate.getHeaderField(index) ?: break
            val name = delegate.getHeaderFieldKey(index)
            index++
            if (name == null) continue
            if (count + 2 > strings.size) strings = strings.copyOf(strings.size * 2)
            strings[count++] = name
            strings[count++] = value
        }
        if (count == 0) {
            // A connection class that implements only the map (an app's own subclass, say).
            for ((name, values) in delegate.headerFields.orEmpty()) {
                if (name == null) continue
                for (value in values) {
                    if (count + 2 > strings.size) strings = strings.copyOf(strings.size * 2)
                    strings[count++] = name
                    strings[count++] = value
                }
            }
        }
        responseHeaderStrings = strings
        responseHeaderCount = count
    }

    /** The response headers as pairs, built on the store's thread. */
    private fun responseHeaders(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(responseHeaderCount / 2)
        var i = 0
        while (i < responseHeaderCount) {
            out += responseHeaderStrings[i]!! to responseHeaderStrings[i + 1]!!
            i += 2
        }
        return out
    }

    /** Forwards every write unchanged and copies it, up to the cap. */
    private class TeeOutputStream(private val out: OutputStream, private val capture: BoundedCapture) : OutputStream() {
        override fun write(b: Int) {
            out.write(b)
            capture.takeByte(b)
        }

        override fun write(b: ByteArray) {
            write(b, 0, b.size)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            capture.take(b, off, len)
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            out.close()
        }
    }

    /**
     * Forwards every read unchanged and copies what it returned, up to the cap; the end completes
     * the record. The capture's freeze is the end: the first of a read's end, its throw, a close and
     * a `disconnect()` (often from another thread, while this one is blocked in a read) ends the
     * record, and no later read changes what it holds.
     */
    private inner class TeeInputStream(private val source: InputStream, private val capture: BoundedCapture) : InputStream() {
        override fun read(): Int {
            val b = guarded { source.read() }
            if (b == -1) ended() else capture.takeByte(b)
            return b
        }

        override fun read(b: ByteArray): Int = read(b, 0, b.size)

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = guarded { source.read(b, off, len) }
            if (n == -1) ended() else capture.take(b, off, n)
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = guarded { source.skip(n) }
            capture.skipped(skipped)
            return skipped
        }

        override fun available(): Int = source.available()

        override fun close() {
            closedEarly()
            source.close()
        }

        override fun mark(readlimit: Int) {
            source.mark(readlimit)
        }

        override fun reset() {
            source.reset()
            capture.replay()
        }

        override fun markSupported(): Boolean = source.markSupported()

        fun closedEarly() {
            if (!capture.freeze()) return
            // As for OkHttp: a body read whole by its declared length is complete, one never read is
            // complete without its body, a stream ends when the app closes it, any other body cut
            // short after some bytes is cancelled and keeps what arrived.
            when {
                responseLength >= 0 && capture.total >= responseLength -> finish(null, this)
                capture.total == 0L -> finish(CapturedBody.skipped(NOT_READ, responseType, responseLength.takeIf { it >= 0 }))
                responseSkip == ResponseSkip.STREAMING -> finish(null, this)
                else -> failed(HopiOkHttpInterceptor.CANCELLED, this)
            }
        }

        private fun ended() {
            if (capture.freeze()) finish(null, this)
        }

        /** What the capture holds, on the store's thread. */
        fun captured(): CapturedBody {
            val skip = responseSkip ?: return CapturedBody(capture.bytes(), capture.total, capture.truncated, responseType, skippedReason = null)
            return CapturedBody.skipped(skip, responseType, capture.total)
        }

        /** Bytes the capture's blocks take ([BoundedCapture.held]). */
        val held: Int get() = capture.held

        /** The body by its size alone, for [shedBodies]. */
        fun withoutBytes(): CapturedBody = CapturedBody.skipped(HopiStore.SKIPPED_STORE_BACKLOG, responseType, capture.total)

        private inline fun <T> guarded(read: () -> T): T = try {
            read()
        } catch (e: IOException) {
            if (capture.freeze()) failed(AndroidCapture.describe(e), this)
            throw e
        }
    }

    /** What the app writes under a body edit: the rule's body went to the delegate instead. */
    private object DiscardingStream : OutputStream() {
        override fun write(b: Int) = Unit

        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private companion object {
        const val SELECTED_PROTOCOL = "X-Android-Selected-Protocol"

        /** `setChunkedStreamingMode`'s default chunk when the app passes 0 or less. */
        const val DEFAULT_CHUNK = 4096
        val NO_HEADERS = arrayOfNulls<String>(0)
        const val NOT_READ = HopiOkHttpInterceptor.NOT_READ
        const val READ_BY_GET_CONTENT = "read by getContent, not captured"

        fun flatten(fields: Map<String?, List<String>>): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            for ((name, values) in fields) {
                // The status line's entry has no name.
                if (name == null) continue
                for (value in values) out += name to value
            }
            return out
        }

        fun protocolName(selected: String): String = when (selected.lowercase()) {
            "http/1.0" -> "HTTP/1.0"
            "http/1.1" -> "HTTP/1.1"
            "h2", "h2_prior_knowledge" -> "HTTP/2.0"
            "h3", "quic" -> "HTTP/3.0"
            else -> selected.uppercase()
        }
    }
}

/**
 * The request a rule is matched against, read from the connection's own `java.net.URL` (already
 * split) and the request properties the recorder read once: nothing is parsed or flattened unless
 * a rule reaches the field (the query on the first query predicate, the headers never copied), and
 * each field is read from the URL once per request, on the first rule that reads it, not per rule.
 */
internal class UrlConnectionRuleRequest(
    override val method: String,
    private val url: URL,
    private val properties: Map<String?, List<String>>,
) : RuleRequest {
    private var lowerScheme: String? = null
    override val scheme: String get() = lowerScheme ?: url.protocol.lowercase().also { lowerScheme = it }

    private var bareHost: String? = null
    override val host: String get() = bareHost ?: bare(url.host).also { bareHost = it }

    private var effectivePort = UNKNOWN_PORT
    override val port: Int get() = if (effectivePort != UNKNOWN_PORT) effectivePort else url.port.let { if (it >= 0) it else url.defaultPort }.also { effectivePort = it }

    private var path: String? = null
    override val encodedPath: String get() = path ?: url.path.ifEmpty { "/" }.also { path = it }

    private var query: List<Pair<String, String>>? = null

    private fun decodedQuery(): List<Pair<String, String>> = query ?: UrlParts.decodeQuery(url.query).also { query = it }

    override fun queryValues(name: String): List<String> = decodedQuery().filter { it.first == name }.map { it.second }

    override fun hasQueryValue(name: String, value: Glob?): Boolean {
        if (url.query == null) return false
        val decoded = decodedQuery()
        for (i in decoded.indices) {
            val (key, text) = decoded[i]
            if (key == name && (value == null || value.matches(text))) return true
        }
        return false
    }

    override fun hasHeaderValue(name: String, value: Glob?): Boolean {
        for ((key, list) in properties) {
            if (key == null || !key.equals(name, ignoreCase = true)) continue
            if (value == null) {
                if (list.isNotEmpty()) return true
                continue
            }
            for (i in list.indices) if (value.matches(list[i])) return true
        }
        return false
    }

    override fun headerValues(name: String): List<String> {
        var values: MutableList<String>? = null
        for ((key, list) in properties) {
            if (key != null && key.equals(name, ignoreCase = true)) (values ?: ArrayList<String>(list.size).also { values = it }).addAll(list)
        }
        return values ?: emptyList()
    }

    private companion object {
        /** No port read yet: a URL's effective port is never negative. */
        const val UNKNOWN_PORT = Int.MIN_VALUE

        /** The host without an IPv6 literal's brackets, as `removePrefix("[").removeSuffix("]")`, with no scan. */
        fun bare(host: String): String {
            val from = if (host.startsWith('[')) 1 else 0
            val to = if (host.length > from && host.endsWith(']')) host.length - 1 else host.length
            return if (from == 0 && to == host.length) host else host.substring(from, to)
        }
    }
}
