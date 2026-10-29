package hopitest

import dev.koga.hopi.android.internal.AndroidCapture
import dev.koga.hopi.android.internal.HopiHttpURLConnection
import dev.koga.hopi.android.internal.CACHED_CLIENTS
import dev.koga.hopi.android.internal.HopiOkHttpInterceptor
import dev.koga.hopi.android.internal.NetworkTool
import dev.koga.hopi.android.internal.interceptors
import dev.koga.hopi.core.capture.PlatformCapture
import dev.koga.hopi.core.start.HopiToolContext
import dev.koga.hopi.core.start.StartConfig
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ktor.Hopi
import dev.koga.hopi.ktor.HopiConfig
import dev.koga.hopi.ktor.HopiLogSink
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Function
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okio.BufferedSink

/**
 * What an app does, compiled as app code and loaded through the plugin's rewriter: its OkHttp is
 * the rewritten one and its `URL.openConnection()` calls go to Hopi's hooks. Each scenario returns
 * what the app observed, so the test can compare it with what Hopi recorded.
 */
class Scenarios : Function<Map<String, Any?>, Map<String, Any?>> {
    override fun apply(args: Map<String, Any?>): Map<String, Any?> {
        val store = args["store"] as HopiStore
        val base = args["base"] as String
        if (args["start"] == true) {
            val okHttp = NetworkTool.okHttpState(javaClass.classLoader)
            AndroidCapture.start(
                AndroidCapture.Settings(maxBodyBytes = args["maxBodyBytes"] as Long, summaryLog = false, rules = args["rules"] != false),
                okHttp,
                urlConnection = args["urlConnection"] == true,
                injectedStore = store,
            )
        }
        val out = linkedMapOf<String, Any?>(
            "started" to AndroidCapture.started,
            "instrumented" to AndroidCapture.okHttpInstrumented,
            "okHttpState" to NetworkTool.okHttpState(javaClass.classLoader).name,
        )
        when (val name = args["name"]) {
            "okhttpGet" -> okhttpGet(base, out)
            "okhttpPost" -> okhttpPost(base, out)
            "okhttpOneShot" -> okhttpOneShot(base, out)
            "okhttpLarge" -> okhttpRead(base, "/large", out)
            "okhttpChunked" -> okhttpRead(base, "/chunked", out)
            "okhttpGzip" -> okhttpRead(base, "/gzip", out)
            "okhttpImage" -> okhttpRead(base, "/image", out)
            "okhttpBinaryChunked" -> okhttpRead(base, "/binary-chunked", out)
            "okhttpBinaryDrop" -> okhttpReadFailing(base, "/binary-drop", out)
            "okhttpTextDrop" -> okhttpReadFailing(base, "/text-drop", out)
            "okhttpSse" -> okhttpSse(base, args, out)
            "urlBinaryChunked" -> urlRead(base, "/binary-chunked", out)
            "urlBinaryDrop" -> urlRead(base, "/binary-drop", out)
            "urlSse" -> urlSse(base, args, out)
            "okhttpEmpty" -> okhttpRead(base, "/empty", out)
            "okhttpRedirect" -> okhttpRead(base, "/redirect", out)
            "okhttpRefused" -> okhttpRefused(out)
            "okhttpHold" -> okhttpHold(base, args, out)
            "okhttpCloseEarly" -> okhttpCloseEarly(base, out)
            "okhttpSseCancel" -> okhttpCancelMidBody(base, "/sse", 2, out)
            "okhttpCancelMidBody" -> okhttpCancelMidBody(base, "/hold", 1, out)
            "urlDisconnectMidBody" -> urlDisconnectMidBody(base, out)
            "interceptorViews" -> interceptorViews(out)
            "okhttpUnread" -> client().newCall(Request.Builder().url("$base/json").build()).execute().use { out["code"] = it.code }
            "okhttpEnqueue" -> okhttpEnqueue(base, out)
            "okhttpParallel" -> okhttpParallel(base, out)
            "urlGet" -> urlGet(base, out)
            "urlPost" -> urlPost(base, out)
            "urlMissing" -> urlMissing(base, out)
            "networkTool" -> {
                // The start sequence's network tool, as the generated configuration lists it, with
                // the settings the test passes.
                val tool = NetworkTool(okhttp = args["okhttp"] as Boolean, urlConnection = args["urlConnection"] as Boolean, rules = args["rules"] as Boolean)
                out["note"] = tool.start(HopiToolContext(StartConfig(summaryLog = false), platform = null))
                out["started"] = AndroidCapture.started
                out["instrumented"] = AndroidCapture.okHttpInstrumented
                out["rules"] = AndroidCapture.rules
            }
            "invalidCaps" -> {
                out["result"] = AndroidCapture.start(AndroidCapture.Settings(maxEntries = 0, summaryLog = false), AndroidCapture.OkHttp.Absent, injectedStore = store)
                out["started"] = AndroidCapture.started
            }
            "networkToolInvalidCaps" -> {
                // The network tool with caps capture refuses: it must return no note and write no
                // network status.
                out["note"] = NetworkTool(okhttp = true, urlConnection = true, rules = true)
                    .start(HopiToolContext(StartConfig(maxEntries = 0, summaryLog = false), platform = null))
                out["started"] = AndroidCapture.started
            }
            "urlOpenStream" -> out["text"] = URL("$base/json").openStream().use { String(it.readBytes()) }
            "urlErrorStreamFirst" -> urlErrorStreamFirst(base, out)
            "urlSingleBytes" -> urlSingleBytes(base, out)
            "urlOutputAfterEnd" -> urlOutputAfterEnd(base, out)
            "urlReplacementAfterEnd" -> urlReplacementAfterEnd(base, out)
            "okhttpPassThrough" -> okhttpPassThrough(base, out)
            "urlPassThrough" -> urlPassThrough(base, out)
            "okhttpLegacyPost" -> okhttpLegacyPost(base, out)
            "ktorAndroid" -> ktor(HttpClient(Android) { hopi(store, args["decode"] == true) }, "$base${args["path"]}", out)
            "ktorOkHttp" -> {
                // Loaded by name from the scenario's own loader: the engine and its OkHttp 5 are the jars'.
                @Suppress("UNCHECKED_CAST")
                val okHttpEngine = javaClass.classLoader.loadClass("io.ktor.client.engine.okhttp.OkHttp").getField("INSTANCE").get(null) as HttpClientEngineFactory<HttpClientEngineConfig>
                ktor(HttpClient(okHttpEngine) { hopi(store, args["decode"] == true) }, "$base${args["path"]}", out)
            }
            else -> if (name is String && name.startsWith("rule.")) RuleScenarios.run(name, base, args, out) else error("no scenario $name")
        }
        return out
    }

    private val appInterceptor = Interceptor { chain -> chain.proceed(chain.request().newBuilder().header("X-App", "1").build()) }

    private fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(appInterceptor).addNetworkInterceptor { it.proceed(it.request()) }.build()

    private fun okhttpGet(base: String, out: MutableMap<String, Any?>) {
        val client = client()
        val derived = client.newBuilder().addInterceptor { it.proceed(it.request()) }.build()
        client.newCall(Request.Builder().url("$base/json").build()).execute().use { out["body"] = it.body!!.string() }
        derived.newCall(Request.Builder().url("$base/json").build()).execute().use { it.body!!.string() }
        // What the app can read of its clients is what it built.
        out["interceptors"] = client.interceptors.size
        out["networkInterceptors"] = client.networkInterceptors.size
        out["derivedInterceptors"] = derived.interceptors.size
        out["builderInterceptors"] = client.newBuilder().interceptors().size
        out["interceptorClasses"] = (client.interceptors + derived.interceptors).map { it.javaClass.name }
    }

    /** What RealCall's rewritten call gets for clients whose calls interleave: one view per client, kept for four. */
    private fun interceptorViews(out: MutableMap<String, Any?>) {
        val api = client()
        val images = api.newBuilder().addInterceptor { it.proceed(it.request()) }.build()
        val apiView = interceptors(api)
        val imagesView = interceptors(images)
        out["interleavedSame"] = (1..3).all { interceptors(api) === apiView && interceptors(images) === imagesView }
        out["hopiLast"] = apiView.last() === HopiOkHttpInterceptor && imagesView.last() === HopiOkHttpInterceptor
        out["appFirst"] = apiView.dropLast(1) == api.interceptors && imagesView.dropLast(1) == images.interceptors
        val others = List(CACHED_CLIENTS - 1) { client() }
        for (other in others) interceptors(other)
        // The four newest are images and the three others: images' view is kept, api's was dropped.
        out["imagesKept"] = interceptors(images) === imagesView
        out["oldestRebuilt"] = interceptors(api).let { it !== apiView && it == apiView }
    }

    private fun okhttpPost(base: String, out: MutableMap<String, Any?>) {
        val body = """{"title":"keyboard","price":129}""".toRequestBody("application/json".toMediaType())
        client().newCall(Request.Builder().url("$base/echo").post(body).build()).execute().use { out["body"] = it.body!!.string() }
    }

    private fun okhttpOneShot(base: String, out: MutableMap<String, Any?>) {
        var writes = 0
        val body = object : RequestBody() {
            override fun contentType() = "text/plain".toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                if (++writes > 1) throw IllegalStateException("one-shot body written twice")
                sink.writeUtf8("streamed once")
            }
        }
        client().newCall(Request.Builder().url("$base/echo").post(body).build()).execute().use { out["body"] = it.body!!.string() }
        out["writes"] = writes
    }

    private fun okhttpRead(base: String, path: String, out: MutableMap<String, Any?>) {
        client().newCall(Request.Builder().url("$base$path").build()).execute().use { response ->
            val bytes = response.body!!.bytes()
            out["code"] = response.code
            out["sha"] = sha(bytes)
            out["size"] = bytes.size
        }
    }

    private fun okhttpReadFailing(base: String, path: String, out: MutableMap<String, Any?>) {
        try {
            client().newCall(Request.Builder().url("$base$path").build()).execute().use { it.body!!.bytes() }
        } catch (e: IOException) {
            out["error"] = e.javaClass.name
        }
    }

    /** Reads two events, lets the test look at the record mid-stream, then reads the stream to its end. */
    private fun okhttpSse(base: String, args: Map<String, Any?>, out: MutableMap<String, Any?>) {
        client().newCall(Request.Builder().url("$base/sse").build()).execute().use { response ->
            val source = response.body!!.source()
            val events = mutableListOf<String>()
            while (events.size < 2) source.readUtf8Line()?.takeIf { it.startsWith("data:") }?.let { events += it }
            (args["midStream"] as? Runnable)?.run()
            out["rest"] = source.readUtf8()
            out["events"] = events
        }
    }

    private fun urlRead(base: String, path: String, out: MutableMap<String, Any?>) {
        val connection = URL("$base$path").openConnection() as HttpURLConnection
        try {
            out["size"] = connection.inputStream.use { it.readBytes().size }
        } catch (e: IOException) {
            out["error"] = e.javaClass.name
        }
    }

    private fun urlSse(base: String, args: Map<String, Any?>, out: MutableMap<String, Any?>) {
        val connection = URL("$base/sse").openConnection() as HttpURLConnection
        connection.inputStream.bufferedReader().use { reader ->
            val events = mutableListOf<String>()
            while (events.size < 2) reader.readLine()?.takeIf { it.startsWith("data:") }?.let { events += it }
            (args["midStream"] as? Runnable)?.run()
            out["rest"] = reader.readText()
            out["events"] = events
        }
    }

    private fun okhttpRefused(out: MutableMap<String, Any?>) {
        try {
            client().newCall(Request.Builder().url("http://127.0.0.1:1/json").build()).execute().close()
        } catch (e: IOException) {
            out["error"] = e.javaClass.name
        }
    }

    /** Reads the first line while the server still holds the rest: proves Hopi reads nothing ahead. */
    private fun okhttpHold(base: String, args: Map<String, Any?>, out: MutableMap<String, Any?>) {
        client().newCall(Request.Builder().url("$base/hold").build()).execute().use { response ->
            val source = response.body!!.source()
            out["first"] = source.readUtf8Line()
            (args["release"] as? Runnable)?.run()
            out["rest"] = source.readUtf8()
        }
    }

    private fun okhttpCloseEarly(base: String, out: MutableMap<String, Any?>) {
        client().newCall(Request.Builder().url("$base/chunked").build()).execute().use { response ->
            out["firstByte"] = response.body!!.source().readByte().toInt()
        }
    }

    /**
     * Reads [lines] lines of [path], then ends the call as okhttp-sse's `EventSource.cancel()` does:
     * `call.cancel()` from another thread, which closes the socket under the reader; the reader's
     * next read throws.
     */
    private fun okhttpCancelMidBody(base: String, path: String, lines: Int, out: MutableMap<String, Any?>) {
        val call = client().newCall(Request.Builder().url("$base$path").build())
        call.execute().use { response ->
            val source = response.body!!.source()
            val read = mutableListOf<String>()
            while (read.size < lines) source.readUtf8Line()?.takeIf { it.isNotEmpty() }?.let { read += it }
            out["read"] = read
            Thread { call.cancel() }.apply { start() }.join()
            try {
                out["rest"] = source.readUtf8()
            } catch (e: IOException) {
                out["error"] = e.javaClass.name
            }
        }
    }

    /** Reads the first line of `/hold`, then disconnects from another thread, as an app aborting a blocked read does; reads again after. */
    private fun urlDisconnectMidBody(base: String, out: MutableMap<String, Any?>) {
        val connection = URL("$base/hold").openConnection() as HttpURLConnection
        val stream = connection.inputStream
        val first = ByteArray(6)
        var n = 0
        while (n < first.size) {
            val read = stream.read(first, n, first.size - n)
            if (read == -1) break
            n += read
        }
        out["first"] = String(first, 0, n)
        Thread { connection.disconnect() }.apply { start() }.join()
        try {
            out["after"] = stream.read(ByteArray(64))
        } catch (e: IOException) {
            out["error"] = e.javaClass.name
        }
    }

    private fun okhttpEnqueue(base: String, out: MutableMap<String, Any?>) {
        val done = CountDownLatch(1)
        client().newCall(Request.Builder().url("$base/json").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                out["error"] = e.toString()
                done.countDown()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { out["body"] = it.body!!.string() }
                out["thread"] = Thread.currentThread().name
                done.countDown()
            }
        })
        check(done.await(10, TimeUnit.SECONDS))
    }

    private fun okhttpParallel(base: String, out: MutableMap<String, Any?>) {
        val client = client()
        val pool = Executors.newFixedThreadPool(8)
        val results = (0 until 32).map { i ->
            pool.submit<String> {
                val body = "body-$i".repeat(100).toRequestBody("text/plain".toMediaType())
                client.newCall(Request.Builder().url("$base/echo?i=$i").post(body).build()).execute().use { it.body!!.string() }
            }
        }.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        out["echoed"] = results
    }

    private fun urlGet(base: String, out: MutableMap<String, Any?>) {
        val connection = URL("$base/json").openConnection() as HttpURLConnection
        out["class"] = connection.javaClass.name
        connection.setRequestProperty("X-App", "1")
        out["code"] = connection.responseCode
        out["body"] = connection.inputStream.use { String(it.readBytes()) }
        connection.disconnect()
    }

    private fun urlPost(base: String, out: MutableMap<String, Any?>) {
        val connection = URL("$base/echo").openConnection() as HttpURLConnection
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val payload = """{"title":"stand"}""".toByteArray()
        connection.setFixedLengthStreamingMode(payload.size)
        connection.outputStream.use { it.write(payload) }
        out["code"] = connection.responseCode
        out["body"] = connection.inputStream.use { String(it.readBytes()) }
    }

    private fun urlMissing(base: String, out: MutableMap<String, Any?>) {
        val connection = URL("$base/missing").openConnection() as HttpURLConnection
        try {
            connection.inputStream.close()
        } catch (e: IOException) {
            out["exception"] = e.javaClass.name
        }
        out["code"] = connection.responseCode
        out["error"] = connection.errorStream?.use { String(it.readBytes()) }
    }

    /** The first call is getErrorStream, which sends the request on Android. */
    private fun urlErrorStreamFirst(base: String, out: MutableMap<String, Any?>) {
        // Not through the rewritten call site: the platform connection itself, as Android's would be.
        val real = URL::class.java.getMethod("openConnection").invoke(URL("$base/missing")) as HttpURLConnection
        val connection = HopiHttpURLConnection(AndroidLikeConnection(real))
        out["error"] = connection.errorStream?.use { String(it.readBytes()) }
        out["code"] = connection.responseCode
    }

    /**
     * A connection whose first `getOutputStream` fails and whose second succeeds (a third-party one:
     * Android's rethrows its first failure). The record ended with the failure; what the app writes
     * after it reaches the connection and changes nothing counted for that end.
     */
    private fun urlOutputAfterEnd(base: String, out: MutableMap<String, Any?>) {
        val delegate = InMemoryOutputConnection(URL("$base/echo"))
        var opened = 0
        delegate.beforeOutput = { if (opened++ == 0) throw IOException("connect failed") }
        val connection = HopiHttpURLConnection(delegate)
        connection.doOutput = true
        try {
            connection.outputStream
        } catch (e: IOException) {
            out["exception"] = e.message
        }
        out["heldAtEnd"] = heldBytes(connection)
        connection.outputStream.use { it.write("""{"title":"late"}""".toByteArray()) }
        out["written"] = delegate.written.toString(Charsets.UTF_8)
        out["heldAfter"] = heldBytes(connection)
    }

    /**
     * Under a rule's body edit, a `disconnect()` that lands before the rule's body is recorded (from
     * another thread it can land between the snapshot and the write; here the connection makes it as
     * the body goes out): the record ends there, and the body sent after it changes nothing counted
     * for that end.
     */
    private fun urlReplacementAfterEnd(base: String, out: MutableMap<String, Any?>) {
        val delegate = InMemoryOutputConnection(URL("$base/echo"))
        val connection = HopiHttpURLConnection(delegate)
        delegate.beforeOutput = {
            connection.disconnect()
            out["heldAtEnd"] = heldBytes(connection)
        }
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/plain")
        connection.outputStream.use { it.write("the app's own body".toByteArray()) }
        out["written"] = delegate.written.toString(Charsets.UTF_8)
        out["heldAfter"] = heldBytes(connection)
    }

    /** What the store counts for [connection]'s record while its end is queued (`DeferredRecord.heldBytes`). */
    private fun heldBytes(connection: HopiHttpURLConnection): Long =
        (HopiHttpURLConnection::class.java.getDeclaredField("recorder").apply { isAccessible = true }.get(connection) as HopiStore.DeferredRecord).heldBytes()

    /** One byte at a time each way: `write(int)` and `read()`. */
    private fun urlSingleBytes(base: String, out: MutableMap<String, Any?>) {
        val connection = URL("$base/echo").openConnection() as HttpURLConnection
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/plain")
        val payload = "one byte at a time".toByteArray()
        connection.setFixedLengthStreamingMode(payload.size.toLong())
        connection.outputStream.use { stream -> payload.forEach { stream.write(it.toInt()) } }
        out["body"] = connection.inputStream.use { stream ->
            val read = java.io.ByteArrayOutputStream()
            while (true) {
                val b = stream.read()
                if (b == -1) break
                read.write(b)
            }
            read.toString(Charsets.UTF_8)
        }
    }

    /** A request carrying the Ktor plugin's marker, then a plain one: only the plain one is recorded. */
    private fun okhttpPassThrough(base: String, out: MutableMap<String, Any?>) {
        val client = client()
        // As the Ktor plugin does before it adds the marker: until then the interceptor scans for none.
        PlatformCapture.markPassThrough()
        client.newCall(Request.Builder().url("$base/json?marked").header(PlatformCapture.PASS_THROUGH_HEADER, "1").build()).execute().use { out["marked"] = it.body!!.string() }
        client.newCall(Request.Builder().url("$base/json?plain").build()).execute().use { it.body!!.string() }
    }

    private fun urlPassThrough(base: String, out: MutableMap<String, Any?>) {
        val marked = URL("$base/json?marked").openConnection() as HttpURLConnection
        marked.addRequestProperty(PlatformCapture.PASS_THROUGH_HEADER, "1")
        out["markedProperty"] = marked.getRequestProperty(PlatformCapture.PASS_THROUGH_HEADER)
        out["marked"] = marked.inputStream.use { String(it.readBytes()) }
        marked.disconnect()
        val plain = URL("$base/json?plain").openConnection() as HttpURLConnection
        plain.inputStream.use { it.readBytes() }
    }

    /**
     * A POST through whichever OkHttp the loader has (3.x included), with only API every version
     * has, so the same bytecode links against 3.12, 3.14 and 4.x.
     */
    private fun okhttpLegacyPost(base: String, out: MutableMap<String, Any?>) {
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = null

            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("legacy body")
            }
        }
        val response = OkHttpClient().newCall(Request.Builder().url("$base/echo").post(body).build()).execute()
        try {
            out["code"] = response.code
            out["body"] = response.body!!.string()
        } finally {
            response.close()
        }
    }

    private fun <T : HttpClientEngineConfig> HttpClientConfig<T>.hopi(store: HopiStore, decode: Boolean) {
        if (decode) {
            install(ContentEncoding) {
                gzip()
                deflate()
            }
        }
        install(Hopi) {
            // HopiConfig.store is internal to hopi-ktor (its tests' hook): set through its setter.
            HopiConfig::class.java.methods.single { it.name.startsWith("setStore$") }.invoke(this, store)
            logSink = HopiLogSink.Off
        }
    }

    private fun ktor(client: HttpClient, url: String, out: MutableMap<String, Any?>) = runBlocking {
        client.use {
            val response = it.get(url)
            val bytes = response.bodyAsBytes()
            out["code"] = response.status.value
            out["sha"] = sha(bytes)
            out["size"] = bytes.size
            out["engine"] = it.engine::class.qualifiedName
        }
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * A third-party connection that never touches the network: its output stream is in memory
 * ([written]), and [beforeOutput] runs first in every `getOutputStream`, where it may throw (a
 * failed connect) or disconnect the wrapper.
 */
private class InMemoryOutputConnection(url: URL) : HttpURLConnection(url) {
    val written = java.io.ByteArrayOutputStream()
    var beforeOutput: () -> Unit = {}

    override fun getOutputStream(): java.io.OutputStream {
        beforeOutput()
        return written
    }

    override fun connect() = Unit

    override fun disconnect() = Unit

    override fun usingProxy(): Boolean = false
}

/**
 * A platform connection that behaves as Android's `HttpURLConnectionImpl` where the JDK's does
 * not: `getErrorStream()` sends the request when nothing has yet. The rest is the JDK's.
 */
private class AndroidLikeConnection(private val real: HttpURLConnection) : HttpURLConnection(real.url) {
    override fun getErrorStream(): InputStream? {
        real.responseCode
        return real.errorStream
    }

    override fun connect() = real.connect()

    override fun disconnect() = real.disconnect()

    override fun usingProxy(): Boolean = real.usingProxy()

    override fun getInputStream(): InputStream = real.inputStream

    override fun getResponseCode(): Int = real.responseCode

    override fun getHeaderFields(): MutableMap<String?, MutableList<String>> = real.headerFields

    override fun getHeaderField(name: String?): String? = real.getHeaderField(name)

    override fun getContentType(): String? = real.contentType

    override fun getContentLengthLong(): Long = real.contentLengthLong

    override fun getRequestProperties(): MutableMap<String, MutableList<String>> = real.requestProperties

    override fun getURL(): URL = real.url

    override fun getRequestMethod(): String = real.requestMethod

    override fun getDoOutput(): Boolean = real.doOutput
}
