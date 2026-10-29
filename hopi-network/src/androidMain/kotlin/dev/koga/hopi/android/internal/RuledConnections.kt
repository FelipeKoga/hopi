package dev.koga.hopi.android.internal

import dev.koga.hopi.core.rules.MockSpec
import dev.koga.hopi.core.rules.ResponseEdit
import dev.koga.hopi.core.rules.ResponseEdits
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.security.Permission
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocketFactory

/**
 * A response a rule decided: a mock's, or a real one after a response edit. Answers the way
 * Android's `HttpURLConnectionImpl` does: the status line at header index 0 with a null key, the
 * last value of a name by name, the body from `getInputStream` below 400 and from
 * `getErrorStream` at 400 and above, where `getInputStream` throws `FileNotFoundException`.
 */
internal class RuledResponse(
    val code: Int,
    val message: String,
    val headers: List<Pair<String, String>>,
    /** Null when the body is the real one (a response edit that keeps it). */
    val body: ByteArray?,
) {
    fun headerField(name: String?): String? {
        if (name == null) return statusLine()
        return headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    fun headerField(n: Int): String? = if (n == 0) statusLine() else headers.getOrNull(n - 1)?.second

    fun headerFieldKey(n: Int): String? = if (n == 0) null else headers.getOrNull(n - 1)?.first

    fun headerFields(): Map<String?, List<String>> {
        val out = LinkedHashMap<String?, MutableList<String>>()
        out[null] = mutableListOf(statusLine())
        for ((name, value) in headers) {
            val key = out.keys.firstOrNull { it != null && it.equals(name, ignoreCase = true) } ?: name
            out.getOrPut(key) { ArrayList(1) } += value
        }
        return java.util.Collections.unmodifiableMap(out.mapValues { java.util.Collections.unmodifiableList(it.value) })
    }

    private fun statusLine(): String = "HTTP/1.1 $code $message".trimEnd()

    companion object {
        /** [mock] as the app receives it, under `Cache-Control: no-store` ([ResponseEdits.noStore]). */
        fun of(mock: MockSpec, method: String): RuledResponse = RuledResponse(
            mock.status,
            ResponseEdits.reasonPhrase(mock.status),
            ResponseEdits.noStore(ResponseEdits.mockHeaders(mock)),
            mock.body?.bytes?.takeIf { ResponseEdits.hasBody(method, mock.status) } ?: ByteArray(0),
        )
    }
}

/**
 * What the delegate of a recorded connection becomes once a mock or a response edit applies
 * (docs/design/rules.md, "`HttpURLConnection`"): the request side stays on [original], which holds
 * everything the app set; the response side answers from the rule. A mock never connects
 * [original]; a response edit connects it as the app's calls ask, and rewrites what it answers.
 * [RuledHttpConnection] and [RuledHttpsConnection] are its two faces.
 */
internal class RuledCore(
    val original: HttpURLConnection,
    private val mock: RuledResponse?,
    private val edit: ResponseEdit?,
) {
    val mocked: Boolean get() = mock != null

    /** Whether an app call connected (a mock's `getErrorStream` is null before one, as on an unconnected delegate). */
    @Volatile
    private var connected = false

    private var edited: RuledResponse? = null

    fun connect() {
        connected = true
        if (mock == null) original.connect()
    }

    fun outputStream(): OutputStream {
        if (mock == null) return original.outputStream
        // As the platform refuses a body for a connection without doOutput.
        if (!original.doOutput) throw ProtocolException("method does not support a request body: ${original.requestMethod}")
        connected = true
        return Discarding
    }

    /**
     * The response the app sees: the mock, or the real one through the edit, read once; either
     * carries `Cache-Control: no-store` in place of the rule's or the server's.
     */
    fun response(): RuledResponse {
        connected = true
        mock?.let { return it }
        edited?.let { return it }
        val edit = checkNotNull(edit)
        val code = original.responseCode
        val pairs = ArrayList<Pair<String, String>>()
        var index = 0
        while (true) {
            val value = original.getHeaderField(index) ?: break
            val name = original.getHeaderFieldKey(index)
            index++
            if (name != null) pairs += name to value
        }
        val status = edit.status ?: code
        val message = if (edit.status != null) ResponseEdits.reasonPhrase(status) else original.responseMessage.orEmpty()
        val body = edit.body?.bytes?.let { bytes ->
            // The real body is never read: close it so the connection is released.
            closeQuietly { if (code >= 400) original.errorStream else original.inputStream }
            bytes.takeIf { ResponseEdits.hasBody(original.requestMethod ?: "GET", status) } ?: ByteArray(0)
        }
        return RuledResponse(status, message, ResponseEdits.noStore(ResponseEdits.editHeaders(pairs, edit)), body).also { edited = it }
    }

    fun inputStream(): InputStream {
        val response = response()
        if (response.code >= 400) throw FileNotFoundException(original.url.toString())
        response.body?.let { return ByteArrayInputStream(it) }
        return realBody() ?: ByteArrayInputStream(ByteArray(0))
    }

    fun errorStream(): InputStream? {
        if (mock != null && !connected) return null
        val response = response()
        if (response.code < 400) return null
        response.body?.let { return ByteArrayInputStream(it) }
        return realBody()
    }

    /** The real body under an edit that kept it, from whichever stream the real status serves it on. */
    private fun realBody(): InputStream? = if (original.responseCode >= 400) original.errorStream else original.inputStream

    fun usingProxy(): Boolean = mock == null && original.usingProxy()

    private inline fun closeQuietly(stream: () -> InputStream?) {
        try {
            stream()?.close()
        } catch (_: IOException) {
        }
    }

    private object Discarding : OutputStream() {
        override fun write(b: Int) = Unit

        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }
}

/**
 * The ruled delegate of a [HopiHttpURLConnection]. Overrides every method a response or a
 * connection decides, and every request-side method (to [RuledCore.original]); the rest
 * (`getContentLength`, `getContentType`, `getDate`, ... `getContent`) are `URLConnection`'s own,
 * which read the header methods overridden here.
 */
internal class RuledHttpConnection(private val core: RuledCore) : HttpURLConnection(core.original.url) {
    private val original get() = core.original

    override fun connect() = core.connect()
    override fun getOutputStream(): OutputStream = core.outputStream()
    override fun getInputStream(): InputStream = core.inputStream()
    override fun getErrorStream(): InputStream? = core.errorStream()
    override fun getResponseCode(): Int = core.response().code
    override fun getResponseMessage(): String? = core.response().message
    override fun getHeaderField(name: String?): String? = core.response().headerField(name)
    override fun getHeaderField(n: Int): String? = core.response().headerField(n)
    override fun getHeaderFieldKey(n: Int): String? = core.response().headerFieldKey(n)
    override fun getHeaderFields(): MutableMap<String?, MutableList<String>> = toMutable(core.response().headerFields())
    override fun usingProxy(): Boolean = core.usingProxy()
    override fun disconnect() = original.disconnect()
    override fun getURL(): URL? = original.url
    override fun getPermission(): Permission? = original.permission
    override fun setConnectTimeout(timeout: Int) { original.connectTimeout = timeout }
    override fun getConnectTimeout(): Int = original.connectTimeout
    override fun setReadTimeout(timeout: Int) { original.readTimeout = timeout }
    override fun getReadTimeout(): Int = original.readTimeout
    override fun setDoInput(doinput: Boolean) { original.doInput = doinput }
    override fun getDoInput(): Boolean = original.doInput
    override fun setDoOutput(dooutput: Boolean) { original.doOutput = dooutput }
    override fun getDoOutput(): Boolean = original.doOutput
    override fun setAllowUserInteraction(allowuserinteraction: Boolean) { original.allowUserInteraction = allowuserinteraction }
    override fun getAllowUserInteraction(): Boolean = original.allowUserInteraction
    override fun setUseCaches(usecaches: Boolean) { original.useCaches = usecaches }
    override fun getUseCaches(): Boolean = original.useCaches
    override fun setIfModifiedSince(ifmodifiedsince: Long) { original.ifModifiedSince = ifmodifiedsince }
    override fun getIfModifiedSince(): Long = original.ifModifiedSince
    override fun setRequestProperty(key: String?, value: String?) = original.setRequestProperty(key, value)
    override fun addRequestProperty(key: String?, value: String?) = original.addRequestProperty(key, value)
    override fun getRequestProperty(key: String?): String? = original.getRequestProperty(key)
    override fun getRequestProperties(): MutableMap<String, MutableList<String>>? = original.requestProperties
    override fun setInstanceFollowRedirects(followRedirects: Boolean) { original.instanceFollowRedirects = followRedirects }
    override fun getInstanceFollowRedirects(): Boolean = original.instanceFollowRedirects
    override fun setRequestMethod(method: String?) { original.requestMethod = method }
    override fun getRequestMethod(): String? = original.requestMethod
    // The streaming mode is the request side's: a body edit sizes the real connection's stream.
    override fun setFixedLengthStreamingMode(contentLength: Int) = original.setFixedLengthStreamingMode(contentLength)
    override fun setFixedLengthStreamingMode(contentLength: Long) = original.setFixedLengthStreamingMode(contentLength)
    override fun setChunkedStreamingMode(chunklen: Int) = original.setChunkedStreamingMode(chunklen)
}

/** [RuledHttpConnection] for an `HttpsURLConnection`; a mock has no peer, so its certificate getters throw. */
internal class RuledHttpsConnection(private val core: RuledCore) : HttpsURLConnection(core.original.url) {
    private val original get() = core.original as HttpsURLConnection

    override fun connect() = core.connect()
    override fun getOutputStream(): OutputStream = core.outputStream()
    override fun getInputStream(): InputStream = core.inputStream()
    override fun getErrorStream(): InputStream? = core.errorStream()
    override fun getResponseCode(): Int = core.response().code
    override fun getResponseMessage(): String? = core.response().message
    override fun getHeaderField(name: String?): String? = core.response().headerField(name)
    override fun getHeaderField(n: Int): String? = core.response().headerField(n)
    override fun getHeaderFieldKey(n: Int): String? = core.response().headerFieldKey(n)
    override fun getHeaderFields(): MutableMap<String?, MutableList<String>> = toMutable(core.response().headerFields())
    override fun usingProxy(): Boolean = core.usingProxy()
    override fun disconnect() = original.disconnect()
    override fun getURL(): URL? = original.url
    override fun getPermission(): Permission? = original.permission
    override fun setConnectTimeout(timeout: Int) { original.connectTimeout = timeout }
    override fun getConnectTimeout(): Int = original.connectTimeout
    override fun setReadTimeout(timeout: Int) { original.readTimeout = timeout }
    override fun getReadTimeout(): Int = original.readTimeout
    override fun setDoInput(doinput: Boolean) { original.doInput = doinput }
    override fun getDoInput(): Boolean = original.doInput
    override fun setDoOutput(dooutput: Boolean) { original.doOutput = dooutput }
    override fun getDoOutput(): Boolean = original.doOutput
    override fun setAllowUserInteraction(allowuserinteraction: Boolean) { original.allowUserInteraction = allowuserinteraction }
    override fun getAllowUserInteraction(): Boolean = original.allowUserInteraction
    override fun setUseCaches(usecaches: Boolean) { original.useCaches = usecaches }
    override fun getUseCaches(): Boolean = original.useCaches
    override fun setIfModifiedSince(ifmodifiedsince: Long) { original.ifModifiedSince = ifmodifiedsince }
    override fun getIfModifiedSince(): Long = original.ifModifiedSince
    override fun setRequestProperty(key: String?, value: String?) = original.setRequestProperty(key, value)
    override fun addRequestProperty(key: String?, value: String?) = original.addRequestProperty(key, value)
    override fun getRequestProperty(key: String?): String? = original.getRequestProperty(key)
    override fun getRequestProperties(): MutableMap<String, MutableList<String>>? = original.requestProperties
    override fun setInstanceFollowRedirects(followRedirects: Boolean) { original.instanceFollowRedirects = followRedirects }
    override fun getInstanceFollowRedirects(): Boolean = original.instanceFollowRedirects
    override fun setRequestMethod(method: String?) { original.requestMethod = method }
    override fun getRequestMethod(): String? = original.requestMethod
    // The streaming mode is the request side's: a body edit sizes the real connection's stream.
    override fun setFixedLengthStreamingMode(contentLength: Int) = original.setFixedLengthStreamingMode(contentLength)
    override fun setFixedLengthStreamingMode(contentLength: Long) = original.setFixedLengthStreamingMode(contentLength)
    override fun setChunkedStreamingMode(chunklen: Int) = original.setChunkedStreamingMode(chunklen)

    override fun getCipherSuite(): String? = if (core.mocked) throw noPeer() else original.cipherSuite
    override fun getLocalCertificates(): Array<Certificate>? = if (core.mocked) null else original.localCertificates
    override fun getServerCertificates(): Array<Certificate>? = if (core.mocked) throw noPeer() else original.serverCertificates
    override fun getPeerPrincipal(): Principal? = if (core.mocked) throw noPeer() else original.peerPrincipal
    override fun getLocalPrincipal(): Principal? = if (core.mocked) null else original.localPrincipal
    override fun setHostnameVerifier(v: HostnameVerifier?) { original.hostnameVerifier = v }
    override fun getHostnameVerifier(): HostnameVerifier? = original.hostnameVerifier
    override fun setSSLSocketFactory(sf: SSLSocketFactory?) { original.sslSocketFactory = sf }
    override fun getSSLSocketFactory(): SSLSocketFactory? = original.sslSocketFactory

    // No certificate is invented: an app that pins by reading the server's certificates fails closed.
    private fun noPeer() = SSLPeerUnverifiedException("No peer certificate (response mocked by Hopi)")
}

@Suppress("UNCHECKED_CAST")
private fun toMutable(map: Map<String?, List<String>>): MutableMap<String?, MutableList<String>> = map as MutableMap<String?, MutableList<String>>
