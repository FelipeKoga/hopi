package dev.koga.hopi.android.internal

import dev.koga.hopi.core.HopiInternalApi
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Proxy
import java.net.URL
import java.security.Permission
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * An app's `HttpsURLConnection`, recorded, so the app's casts and TLS settings work: the same
 * delegation as [HopiHttpURLConnection], plus HttpsURLConnection's own methods.
 */
@HopiInternalApi
public class HopiHttpsURLConnection(delegate: HttpsURLConnection, proxy: Proxy? = null) : HttpsURLConnection(delegate.url) {
    private val recorder = UrlConnectionRecorder(delegate, proxy)

    // The app's connection, or what a rule put in its place at the first connecting call.
    private val delegate: HttpsURLConnection get() = recorder.delegate as HttpsURLConnection

    // Connecting calls: the request snapshot first, then the app's call, unchanged.

    override fun connect() {
        recorder.beforeConnect()
        try {
            delegate.connect()
        } catch (e: IOException) {
            recorder.connectFailed(e)
            throw e
        }
    }

    override fun getOutputStream(): OutputStream {
        recorder.beforeConnect(output = true)
        recorder.replacingStream()?.let { return it }
        val stream = try {
            delegate.outputStream
        } catch (e: IOException) {
            recorder.connectFailed(e)
            throw e
        }
        return recorder.requestStream(stream)
    }

    override fun getInputStream(): InputStream {
        recorder.beforeConnect()
        val stream = try {
            delegate.inputStream
        } catch (e: IOException) {
            recorder.connectFailed(e, fromInputStream = true)
            throw e
        }
        recorder.responseArrived()
        return recorder.responseStream(stream) ?: stream
    }

    // A connecting call on Android (HttpURLConnectionImpl sends the request when nothing has), so
    // the snapshot comes first; null when the response has no error status.
    override fun getErrorStream(): InputStream? {
        recorder.beforeConnect()
        val stream = delegate.errorStream ?: return null
        recorder.responseArrived()
        return recorder.responseStream(stream)
    }

    override fun getContent(): Any? {
        val content = response { delegate.content }
        recorder.contentReadDirectly()
        return content
    }

    override fun getContent(classes: Array<out Class<*>>?): Any? {
        val content = response { delegate.getContent(classes) }
        recorder.contentReadDirectly()
        return content
    }

    override fun getResponseCode(): Int = response { delegate.responseCode }

    override fun getResponseMessage(): String? = response { delegate.responseMessage }

    override fun getHeaderField(name: String?): String? = response { delegate.getHeaderField(name) }

    override fun getHeaderField(n: Int): String? = response { delegate.getHeaderField(n) }

    override fun getHeaderFieldKey(n: Int): String? = response { delegate.getHeaderFieldKey(n) }

    override fun getHeaderFields(): MutableMap<String, MutableList<String>>? = response { delegate.headerFields }

    override fun getHeaderFieldInt(name: String?, default: Int): Int = response { delegate.getHeaderFieldInt(name, default) }

    override fun getHeaderFieldLong(name: String?, default: Long): Long = response { delegate.getHeaderFieldLong(name, default) }

    override fun getHeaderFieldDate(name: String?, default: Long): Long = response { delegate.getHeaderFieldDate(name, default) }

    override fun getContentLength(): Int = response { delegate.contentLength }

    override fun getContentLengthLong(): Long = response { delegate.contentLengthLong }

    override fun getContentType(): String? = response { delegate.contentType }

    override fun getContentEncoding(): String? = response { delegate.contentEncoding }

    override fun getDate(): Long = response { delegate.date }

    override fun getExpiration(): Long = response { delegate.expiration }

    override fun getLastModified(): Long = response { delegate.lastModified }

    override fun disconnect() {
        recorder.disconnected()
        delegate.disconnect()
    }

    // Everything else goes to the delegate as it is.

    override fun usingProxy(): Boolean = delegate.usingProxy()

    override fun getPermission(): Permission? = delegate.permission

    override fun getURL(): URL? = delegate.url

    override fun setConnectTimeout(timeout: Int) {
        delegate.connectTimeout = timeout
    }

    override fun getConnectTimeout(): Int = delegate.connectTimeout

    override fun setReadTimeout(timeout: Int) {
        delegate.readTimeout = timeout
    }

    override fun getReadTimeout(): Int = delegate.readTimeout

    override fun setDoInput(doinput: Boolean) {
        delegate.doInput = doinput
    }

    override fun getDoInput(): Boolean = delegate.doInput

    override fun setDoOutput(dooutput: Boolean) {
        delegate.doOutput = dooutput
    }

    override fun getDoOutput(): Boolean = delegate.doOutput

    override fun setAllowUserInteraction(allowuserinteraction: Boolean) {
        delegate.allowUserInteraction = allowuserinteraction
    }

    override fun getAllowUserInteraction(): Boolean = delegate.allowUserInteraction

    override fun setUseCaches(usecaches: Boolean) {
        delegate.useCaches = usecaches
    }

    override fun getUseCaches(): Boolean = delegate.useCaches

    override fun setIfModifiedSince(ifmodifiedsince: Long) {
        delegate.ifModifiedSince = ifmodifiedsince
    }

    override fun getIfModifiedSince(): Long = delegate.ifModifiedSince

    override fun getDefaultUseCaches(): Boolean = delegate.defaultUseCaches

    override fun setDefaultUseCaches(defaultusecaches: Boolean) {
        delegate.defaultUseCaches = defaultusecaches
    }

    // The Ktor plugin's pass-through marker is Hopi's, never sent: it leaves this connection unrecorded.
    override fun setRequestProperty(key: String?, value: String?) {
        if (recorder.passThrough(key)) return
        delegate.setRequestProperty(key, value)
    }

    override fun addRequestProperty(key: String?, value: String?) {
        if (recorder.passThrough(key)) return
        delegate.addRequestProperty(key, value)
    }

    override fun getRequestProperty(key: String?): String? = delegate.getRequestProperty(key)

    override fun getRequestProperties(): MutableMap<String, MutableList<String>>? = delegate.requestProperties

    override fun setFixedLengthStreamingMode(contentLength: Int) {
        delegate.setFixedLengthStreamingMode(contentLength)
        recorder.requestLength(contentLength.toLong())
    }

    override fun setFixedLengthStreamingMode(contentLength: Long) {
        delegate.setFixedLengthStreamingMode(contentLength)
        recorder.requestLength(contentLength)
    }

    override fun setChunkedStreamingMode(chunklen: Int) {
        delegate.setChunkedStreamingMode(chunklen)
        recorder.chunked(chunklen)
    }

    override fun setInstanceFollowRedirects(followRedirects: Boolean) {
        delegate.instanceFollowRedirects = followRedirects
    }

    override fun getInstanceFollowRedirects(): Boolean = delegate.instanceFollowRedirects

    override fun setRequestMethod(method: String?) {
        delegate.requestMethod = method
    }

    override fun getRequestMethod(): String? = delegate.requestMethod

    override fun toString(): String = delegate.toString()

    /** A call that reads the response: the snapshot first, then the app's call, then the response's fields. */
    private inline fun <T> response(call: () -> T): T {
        recorder.beforeConnect()
        val result = try {
            call()
        } catch (e: IOException) {
            recorder.connectFailed(e)
            throw e
        }
        recorder.responseArrived()
        return result
    }

    // HttpsURLConnection's own methods.

    override fun getCipherSuite(): String? = delegate.cipherSuite

    override fun getLocalCertificates(): Array<Certificate>? = delegate.localCertificates

    override fun getServerCertificates(): Array<Certificate>? = delegate.serverCertificates

    override fun getPeerPrincipal(): Principal? = delegate.peerPrincipal

    override fun getLocalPrincipal(): Principal? = delegate.localPrincipal

    override fun setHostnameVerifier(v: HostnameVerifier?) {
        delegate.hostnameVerifier = v
    }

    override fun getHostnameVerifier(): HostnameVerifier? = delegate.hostnameVerifier

    override fun setSSLSocketFactory(sf: SSLSocketFactory?) {
        delegate.sslSocketFactory = sf
    }

    override fun getSSLSocketFactory(): SSLSocketFactory? = delegate.sslSocketFactory
}
