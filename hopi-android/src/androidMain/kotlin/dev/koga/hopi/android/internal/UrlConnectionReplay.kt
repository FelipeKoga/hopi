package dev.koga.hopi.android.internal

import dev.koga.hopi.core.model.TransactionSource
import dev.koga.hopi.core.rules.ReplayHandoff
import dev.koga.hopi.core.rules.ReplayRequest
import dev.koga.hopi.core.rules.ReplayTarget
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Replays an `HttpURLConnection` record (docs/design/rules.md, "Replay"): a new connection from the
 * hooks' `openConnection` (so it is recorded, under the hand-off's id), with the recorded method,
 * headers and body, the original's timeouts and streaming mode, and the `SSLSocketFactory`,
 * `HostnameVerifier` and `Proxy` the app gave it, held weakly; on a Hopi thread. A connection that
 * kept the platform's defaults shares one target, so a record costs no allocation for it.
 */
internal class UrlConnectionReplayTarget private constructor(
    private val connectTimeout: Int,
    private val readTimeout: Int,
    private val fixedLength: Long?,
    private val chunkLength: Int,
    sslSocketFactory: SSLSocketFactory?,
    hostnameVerifier: HostnameVerifier?,
    proxy: Proxy?,
) : ReplayTarget {
    private val sslSocketFactory = sslSocketFactory?.let(::WeakReference)
    private val hostnameVerifier = hostnameVerifier?.let(::WeakReference)
    private val proxy = proxy?.let(::WeakReference)

    override val source: String get() = TransactionSource.URL_CONNECTION

    override fun refusal(): String? = when {
        sslSocketFactory != null && sslSocketFactory.get() == null -> "the app's SSLSocketFactory for it was collected"
        hostnameVerifier != null && hostnameVerifier.get() == null -> "the app's HostnameVerifier for it was collected"
        proxy != null && proxy.get() == null -> "the app's Proxy for it was collected"
        else -> null
    }

    override fun send(request: ReplayRequest, handoff: ReplayHandoff) {
        refusal()?.let { throw IllegalStateException(it) }
        val factory = sslSocketFactory?.get()
        val verifier = hostnameVerifier?.get()
        val proxy = proxy?.get()
        val thread = Thread({ run(request, handoff, factory, verifier, proxy) }, "hopi-replay")
        thread.isDaemon = true
        thread.start()
    }

    private fun run(request: ReplayRequest, handoff: ReplayHandoff, factory: SSLSocketFactory?, verifier: HostnameVerifier?, proxy: Proxy?) {
        var error: String? = null
        var connection: HttpURLConnection? = null
        try {
            val url = URL(request.url)
            val opened = if (proxy != null) openConnection(url, proxy) else openConnection(url)
            connection = opened as HttpURLConnection
            when (opened) {
                is HopiHttpURLConnection -> opened.handOff(handoff)
                is HopiHttpsURLConnection -> opened.handOff(handoff)
            }
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeout
            connection.readTimeout = readTimeout
            if (connection is HttpsURLConnection) {
                factory?.let { connection.sslSocketFactory = it }
                verifier?.let { connection.hostnameVerifier = it }
            }
            for ((name, value) in request.headers) {
                if (name.equals("Content-Length", ignoreCase = true)) continue
                connection.addRequestProperty(name, value)
            }
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                if (chunkLength > 0) connection.setChunkedStreamingMode(chunkLength) else connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream: InputStream? = if (code >= 400) connection.errorStream else connection.inputStream
            stream?.use { s ->
                val buffer = ByteArray(8192)
                while (s.read(buffer) != -1) Unit
            }
        } catch (e: IOException) {
            error = AndroidCapture.describe(e)
        } catch (e: RuntimeException) {
            error = AndroidCapture.describe(e)
        } finally {
            connection?.disconnect()
            handoff.finish(error)
        }
    }

    companion object {
        private val DEFAULT = UrlConnectionReplayTarget(0, 0, null, 0, null, null, null)

        /** The target for a connection with these settings; the custom TLS objects and proxy only, never the defaults. */
        fun of(
            connectTimeout: Int,
            readTimeout: Int,
            fixedLength: Long?,
            chunkLength: Int,
            sslSocketFactory: SSLSocketFactory?,
            hostnameVerifier: HostnameVerifier?,
            proxy: Proxy?,
        ): UrlConnectionReplayTarget {
            val factory = sslSocketFactory?.takeIf { it !== HttpsURLConnection.getDefaultSSLSocketFactory() }
            val verifier = hostnameVerifier?.takeIf { it !== HttpsURLConnection.getDefaultHostnameVerifier() }
            if (connectTimeout == 0 && readTimeout == 0 && fixedLength == null && chunkLength == 0 && factory == null && verifier == null && proxy == null) {
                return DEFAULT
            }
            return UrlConnectionReplayTarget(connectTimeout, readTimeout, fixedLength, chunkLength, factory, verifier, proxy)
        }
    }
}
