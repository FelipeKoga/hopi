package dev.koga.hopi.android

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.instrument.HopiRewriter
import java.io.File
import java.net.InetAddress
import java.net.URLClassLoader
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Loads OkHttp, Okio, Ktor's OkHttp and Android engines, Hopi's Android runtime and the `hopitest`
 * scenarios child-first, each class passed through the plugin's own rewriter, so a scenario runs
 * the bytecode an instrumented app runs (RealCall's rewritten interceptors call and annotation,
 * rewritten URL call sites). Kotlin, Ktor's core and hopi-core come from the test's own loader, so
 * the test and the scenario share one store class. With [jars], OkHttp, Okio and Ktor's OkHttp
 * engine come from those jars only (another OkHttp), never from the test's own classpath.
 */
class RewritingClassLoader(
    parent: ClassLoader,
    private val options: HopiRewriter.Options = HopiRewriter.Options(),
    jars: List<File> = emptyList(),
) : ClassLoader(parent) {
    val rewritten = ConcurrentLinkedQueue<String>()
    private val jarLoader = if (jars.isEmpty()) null else URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), null)

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
        findLoadedClass(name)?.let { return it }
        if (CHILD_FIRST.none { name.startsWith(it) }) return super.loadClass(name, resolve)
        val resource = name.replace('.', '/') + ".class"
        // With jars, OkHttp is theirs alone: a class they lack does not exist (no 4.x RealCall in a 3.x app).
        val source = if (jarLoader != null && JAR_OWNED.any { name.startsWith(it) }) jarLoader else parent
        val bytes = source.getResourceAsStream(resource)?.use { it.readAllBytes() }
            ?: if (source === jarLoader) throw ClassNotFoundException(name) else return super.loadClass(name, resolve)
        val out = HopiRewriter.rewrite(bytes, options) { className, what -> rewritten += "$className: $what" }
        defineClass(name, out, 0, out.size).also { if (resolve) resolveClass(it) }
    }

    private companion object {
        val JAR_OWNED = listOf("okhttp3.", "okio.", "io.ktor.client.engine.okhttp.")
        val CHILD_FIRST = listOf("okhttp3.", "okio.", "io.ktor.client.engine.okhttp.", "io.ktor.client.engine.android.", "dev.koga.hopi.android.", "hopitest.")
    }
}

/** The jars the build resolved for [name] (`hopi.<name>`: okhttp312, okhttp314, ktorOkhttp). */
fun testJars(name: String): List<File> = System.getProperty("hopi.$name").split(File.pathSeparator).map(::File)

/** Runs the scenario [name] of `hopitest.Scenarios` in a fresh rewriting loader. */
fun runScenario(
    name: String,
    store: HopiStore,
    base: String,
    maxBodyBytes: Long = 256L * 1024,
    start: Boolean = true,
    options: HopiRewriter.Options = HopiRewriter.Options(),
    extra: Map<String, Any?> = emptyMap(),
    jars: List<File> = emptyList(),
): Map<String, Any?> {
    val loader = RewritingClassLoader(TestServer::class.java.classLoader, options, jars)
    @Suppress("UNCHECKED_CAST")
    val scenario = loader.loadClass("hopitest.Scenarios").getDeclaredConstructor().newInstance() as java.util.function.Function<Map<String, Any?>, Map<String, Any?>>
    return scenario.apply(mapOf("name" to name, "store" to store, "base" to base, "maxBodyBytes" to maxBodyBytes, "start" to start) + extra) +
        ("rewritten" to loader.rewritten.toList())
}

/** Waits, event-driven, until the store holds [count] terminal transactions; returns them in id order. */
fun HopiStore.awaitTerminal(count: Int): List<HttpTransaction> = runBlocking {
    withTimeout(10_000) { transactions.first { list -> list.count { it.state.isTerminal } >= count } }.filter { it.state.isTerminal }
}

/** A loopback HTTP server (127.0.0.1 only) with the routes the scenarios use; counts what it received. */
class TestServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64)
    private val executor = Executors.newCachedThreadPool()
    val received = ConcurrentLinkedQueue<String>()
    val receivedBodies = ConcurrentLinkedQueue<ByteArray>()

    /** The request header names each request arrived with, lower case. */
    val receivedHeaders = ConcurrentLinkedQueue<Set<String>>()

    /** Released by the test to let `/hold` send the rest of its body. */
    val release = CountDownLatch(1)

    /** Released by the test to let `/sse` send its last event and end the stream. */
    val streamRelease = CountDownLatch(1)

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = executor
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val body = exchange.requestBody.readAllBytes()
            received += "${exchange.requestMethod} ${exchange.requestURI}"
            receivedHeaders += exchange.requestHeaders.keys.mapTo(mutableSetOf()) { it.lowercase() }
            receivedBodies += body
            val path = exchange.requestURI.path
            when (path) {
                "/json" -> send(exchange, 200, """{"hello":"world"}""".toByteArray(), "application/json")
                // The request's own headers, one `name: value` line each, names in lower case.
                "/headers" -> send(
                    exchange, 200,
                    exchange.requestHeaders.entries.flatMap { (name, values) -> values.map { "${name.lowercase()}: $it" } }.sorted().joinToString("\n").toByteArray(),
                    "text/plain",
                )
                "/cacheable" -> {
                    exchange.responseHeaders.add("Cache-Control", "max-age=600")
                    send(exchange, 200, "cacheable ${received.size}".toByteArray(), "text/plain")
                }
                "/echo" -> send(exchange, 200, body, exchange.requestHeaders.getFirst("Content-Type") ?: "text/plain")
                "/missing" -> send(exchange, 404, """{"error":"missing"}""".toByteArray(), "application/json")
                "/large" -> send(exchange, 200, large(), "application/json")
                "/chunked" -> send(exchange, 200, large(), "application/json", chunked = true)
                "/gzip" -> {
                    exchange.responseHeaders.add("Content-Encoding", "gzip")
                    val zipped = java.io.ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(large()) } }.toByteArray()
                    send(exchange, 200, zipped, "application/json")
                }
                "/image" -> send(exchange, 200, ByteArray(4096) { it.toByte() }, "image/png")
                "/empty" -> {
                    exchange.sendResponseHeaders(204, -1)
                }
                "/redirect" -> {
                    exchange.responseHeaders.add("Location", "/json")
                    exchange.sendResponseHeaders(302, -1)
                }
                "/hold" -> {
                    exchange.responseHeaders.add("Content-Type", "text/plain")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write("first\n".toByteArray())
                    exchange.responseBody.flush()
                    release.await(10, TimeUnit.SECONDS)
                    exchange.responseBody.write("rest\n".toByteArray())
                }
                "/binary-chunked" -> send(exchange, 200, ByteArray(BINARY_BYTES) { it.toByte() }, "application/octet-stream", chunked = true)
                "/sse" -> {
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    repeat(2) { n ->
                        exchange.responseBody.write("data: event $n\n\n".toByteArray())
                        exchange.responseBody.flush()
                    }
                    streamRelease.await(10, TimeUnit.SECONDS)
                    exchange.responseBody.write("data: last\n\n".toByteArray())
                }
                else -> send(exchange, 404, ByteArray(0), "text/plain")
            }
        }
    }

    private fun send(exchange: HttpExchange, code: Int, bytes: ByteArray, type: String, chunked: Boolean = false) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(code, if (chunked) 0 else if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (!chunked) {
            exchange.responseBody.write(bytes)
            return
        }
        for (offset in bytes.indices step 8192) {
            exchange.responseBody.write(bytes, offset, minOf(8192, bytes.size - offset))
            exchange.responseBody.flush()
        }
    }

    /**
     * A raw server whose every response declares [BINARY_BYTES] and drops the connection after
     * [DROPPED_AFTER]: `/binary-drop` as `application/octet-stream`, anything else as `text/plain`.
     * (The JDK's server keeps a short fixed-length exchange's connection open instead.)
     */
    private val dropping = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())

    val droppingBase: String get() = "http://127.0.0.1:${dropping.localPort}"

    init {
        executor.execute {
            while (!dropping.isClosed) {
                val socket = try {
                    dropping.accept()
                } catch (_: java.io.IOException) {
                    break
                }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val requestLine = reader.readLine().orEmpty()
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val type = if ("/binary-drop" in requestLine) "application/octet-stream" else "text/plain"
                    val out = it.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: $BINARY_BYTES\r\n\r\n".toByteArray())
                    out.write(ByteArray(DROPPED_AFTER) { 'x'.code.toByte() })
                    out.flush()
                }
            }
        }
    }

    override fun close() {
        release.countDown()
        streamRelease.countDown()
        dropping.close()
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        const val LARGE_BYTES = 1024 * 1024
        const val BINARY_BYTES = 65_536
        const val DROPPED_AFTER = 16_384

        /** The same 1 MiB every time, so a test can compare digests. */
        fun large(): ByteArray = ByteArray(LARGE_BYTES) { i -> ('a' + i % 26).code.toByte() }
    }
}

/**
 * A loopback HTTPS server (127.0.0.1 only) with a self-signed certificate made by `keytool` for this
 * run, for the tests that must show a rule never weakens TLS: [trustManager] and [socketFactory]
 * trust exactly that certificate, and [pin] is its OkHttp `CertificatePinner` pin.
 */
class TlsServer : AutoCloseable {
    private val directory = java.nio.file.Files.createTempDirectory("hopi-tls").toFile()
    private val keystore = File(directory, "server.p12")
    private val password = "hopi-test".toCharArray()
    val received = ConcurrentLinkedQueue<String>()
    val sslContext: javax.net.ssl.SSLContext
    val trustManager: javax.net.ssl.X509TrustManager
    val pin: String
    private val server: com.sun.net.httpserver.HttpsServer
    private val executor = Executors.newCachedThreadPool()

    init {
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2",
            "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1", "-storetype", "PKCS12",
            "-keystore", keystore.path, "-storepass", String(password), "-keypass", String(password),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor() == 0) { "keytool failed: $output" }
        val store = java.security.KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, password) } }
        val keys = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        trustManager = trust.trustManagers.single() as javax.net.ssl.X509TrustManager
        sslContext = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf(trustManager), null) }
        val certificate = store.getCertificate("server")
        pin = "sha256/" + java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded))
        server = com.sun.net.httpserver.HttpsServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
        server.httpsConfigurator = com.sun.net.httpserver.HttpsConfigurator(sslContext)
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.use {
                exchange.requestBody.readAllBytes()
                received += "${exchange.requestMethod} ${exchange.requestURI}"
                val bytes = "tls ${exchange.requestURI.path}".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/plain")
                // No keep-alive: every request makes its own TLS connection, through whichever socket factory it was given.
                exchange.responseHeaders.add("Connection", "close")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
    }

    val base: String get() = "https://127.0.0.1:${server.address.port}"

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        directory.deleteRecursively()
    }
}
