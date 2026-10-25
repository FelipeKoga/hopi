package hopitest

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Authenticator
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * What an app does under rules, as app code loaded through the rewriter (see [Scenarios]): one
 * OkHttp call and one `HttpURLConnection`, each shaped by its arguments, returning everything the
 * app observed. The rules themselves are set by the test, on the engine both loaders share.
 */
internal object RuleScenarios {
    fun run(name: String, base: String, args: Map<String, Any?>, out: MutableMap<String, Any?>) {
        when (name) {
            "rule.okhttp" -> okhttp(base, args, out)
            "rule.url" -> url(base, args, out)
            else -> error("no scenario $name")
        }
    }

    private fun okhttp(base: String, args: Map<String, Any?>, out: MutableMap<String, Any?>) {
        val appSaw = mutableListOf<Int>()
        val networkCalls = AtomicInteger()
        val authenticatorCalls = AtomicInteger()
        val builder = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-App", "1").build()).also { appSaw += it.code }
            })
            .addNetworkInterceptor { chain ->
                networkCalls.incrementAndGet()
                chain.proceed(chain.request())
            }
            .authenticator(Authenticator { _, _ ->
                authenticatorCalls.incrementAndGet()
                null
            })
        (args["readTimeoutMs"] as? Long)?.let { builder.readTimeout(it, TimeUnit.MILLISECONDS) }
        (args["callTimeoutMs"] as? Long)?.let { builder.callTimeout(it, TimeUnit.MILLISECONDS) }
        (args["cache"] as? java.io.File)?.let { builder.cache(okhttp3.Cache(it, 1L shl 20)) }
        val tls = args["tlsFactory"] as? SSLSocketFactory
        if (tls != null) builder.sslSocketFactory(tls, args["tlsTrust"] as X509TrustManager).hostnameVerifier { _, _ -> true }
        (args["pin"] as? String)?.let { pin -> builder.certificatePinner(CertificatePinner.Builder().add(args["pinHost"] as String, pin).build()) }
        val client = builder.build()
        val url = (args["url"] as? String) ?: "$base${args["path"]}"
        val request = Request.Builder().url(url)
        (args["headers"] as? Map<*, *>)?.forEach { (name, value) -> request.header(name as String, value as String) }
        val body = (args["body"] as? String)?.toRequestBody(((args["contentType"] as? String) ?: "text/plain").toMediaType())
        request.method((args["method"] as? String) ?: "GET", body)
        val call = client.newCall(request.build())
        (args["cancelAfterMs"] as? Long)?.let { ms ->
            Thread {
                Thread.sleep(ms)
                call.cancel()
            }.apply { isDaemon = true }.start()
        }
        val started = System.nanoTime()
        try {
            call.execute().use { response ->
                out["code"] = response.code
                out["message"] = response.message
                out["headers"] = response.headers.toMultimap()
                out["handshakeNull"] = response.handshake == null
                out["protocol"] = response.protocol.toString()
                out["body"] = response.body!!.string()
            }
        } catch (e: IOException) {
            out["exception"] = e.javaClass.name
            out["exceptionMessage"] = e.message
        }
        out["elapsedMs"] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        out["isCanceled"] = call.isCanceled()
        out["appSaw"] = appSaw.toList()
        out["networkCalls"] = networkCalls.get()
        out["authenticatorCalls"] = authenticatorCalls.get()
        // A second, unruled call through the same client, when asked: the cache test reads it back.
        (args["secondPath"] as? String)?.let { path ->
            client.newCall(Request.Builder().url("$base$path").build()).execute().use { out["second"] = it.body!!.string() }
        }
    }

    private fun url(base: String, args: Map<String, Any?>, out: MutableMap<String, Any?>) {
        val url = (args["url"] as? String) ?: "$base${args["path"]}"
        val connection = URL(url).openConnection() as HttpURLConnection
        out["class"] = connection.javaClass.name
        (args["method"] as? String)?.let { connection.requestMethod = it }
        (args["headers"] as? Map<*, *>)?.forEach { (name, value) -> connection.setRequestProperty(name as String, value as String) }
        (args["readTimeoutMs"] as? Int)?.let { connection.readTimeout = it }
        (args["connectTimeoutMs"] as? Int)?.let { connection.connectTimeout = it }
        if (connection is HttpsURLConnection) {
            (args["tlsFactory"] as? SSLSocketFactory)?.let { connection.sslSocketFactory = it }
            (args["verifier"] as? HostnameVerifier)?.let { connection.hostnameVerifier = it }
        }
        (args["disconnectAfterMs"] as? Long)?.let { ms ->
            Thread {
                Thread.sleep(ms)
                connection.disconnect()
            }.apply { isDaemon = true }.start()
        }
        if (args["errorStreamFirst"] == true) out["errorBeforeConnect"] = connection.errorStream?.use { String(it.readBytes()) }
        val started = System.nanoTime()
        try {
            (args["body"] as? String)?.let { text ->
                connection.doOutput = true
                val bytes = text.toByteArray()
                if (args["fixedLength"] == true) connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            out["code"] = connection.responseCode
            out["message"] = connection.responseMessage
            out["contentLength"] = connection.contentLengthLong
            out["contentType"] = connection.contentType
            out["header0"] = connection.getHeaderField(0)
            out["headerX"] = connection.getHeaderField("X-Mock") ?: connection.getHeaderField("X-Edited")
            out["usingProxy"] = connection.usingProxy()
            // Before the body is read: the JDK's connection forgets its session once the body ends.
            if (connection is HttpsURLConnection) {
                try {
                    connection.serverCertificates
                    out["certificates"] = "present"
                } catch (e: IOException) {
                    out["certificates"] = e.javaClass.name + ": " + e.message
                }
            }
            try {
                out["body"] = connection.inputStream.use { String(it.readBytes()) }
            } catch (e: IOException) {
                out["inputException"] = e.javaClass.name
                out["error"] = connection.errorStream?.use { String(it.readBytes()) }
            }
        } catch (e: IOException) {
            out["exception"] = e.javaClass.name
            out["exceptionMessage"] = e.message
        }
        out["elapsedMs"] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        connection.disconnect()
    }
}
