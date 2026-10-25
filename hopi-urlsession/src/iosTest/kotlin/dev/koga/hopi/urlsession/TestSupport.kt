package dev.koga.hopi.urlsession

import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionEvent
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.urlsession.fixtures.HopiFxCompletion
import dev.koga.hopi.urlsession.fixtures.HopiFxRecorder
import dev.koga.hopi.urlsession.fixtures.hopi_fx_bytes
import dev.koga.hopi.urlsession.fixtures.hopi_fx_class_name
import dev.koga.hopi.urlsession.fixtures.hopi_fx_responds
import dev.koga.hopi.urlsession.fixtures.hopi_fx_server_start
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDelegateProtocol
import platform.Foundation.NSURLSessionTask
import platform.Foundation.create
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue

/**
 * Process-wide test state. The first test that needs capture runs [BeforeStart]'s scenarios and
 * creates the "before start()" sessions and tasks, then starts capture, whatever the test order.
 */
@OptIn(ExperimentalForeignApi::class)
internal object Capture {
    val port: Int by lazy { hopi_fx_server_start() }
    private val counter = AtomicInt(0)

    /** A URL on the fixture server, unique per call so each record can be told apart. */
    fun url(path: String): String {
        val separator = if ('?' in path) '&' else '?'
        return "http://127.0.0.1:$port$path${separator}t=${counter.addAndFetch(1)}"
    }

    val store: HopiStore get() = HopiStore.default

    private var started = false

    fun ensureStarted() {
        if (started) return
        BeforeStart.prepare()
        HopiUrlSession.start {
            summaryLog = false
        }
        started = true
    }
}

/** Records for one URL: exactly one is expected; [single] waits for it, then a short grace for a second. */
internal class Watch(private val url: String) {
    private val records = Channel<HttpTransaction>(Channel.UNLIMITED)
    private val remove = Capture.store.addEventListener { event ->
        if (event is TransactionEvent.Completed && event.transaction.url == url) records.trySend(event.transaction)
    }

    fun single(timeoutMs: Long = 15_000, graceMs: Long = 300): HttpTransaction = runBlocking {
        try {
            val first = withTimeoutOrNull(timeoutMs) { records.receive() } ?: fail("no record for $url")
            val second = withTimeoutOrNull(graceMs) { records.receive() }
            assertEquals(null, second, "a second record for $url")
            first
        } finally {
            remove()
        }
    }

    /** All records that arrive within [windowMs]. */
    fun all(windowMs: Long): List<HttpTransaction> = runBlocking {
        val out = mutableListOf<HttpTransaction>()
        try {
            withTimeoutOrNull(windowMs) { while (true) out += records.receive() }
        } finally {
            remove()
        }
        out
    }

    fun none(windowMs: Long = 1_000) {
        assertEquals(emptyList(), all(windowMs), "records for $url")
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toBytes(): ByteArray = if (length.toInt() == 0) ByteArray(0) else bytes!!.readBytes(length.toInt())

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
internal fun expectedBytes(n: Int): ByteArray = hopi_fx_bytes(n.toLong())!!.toBytes()

internal fun nsUrl(url: String): NSURL = NSURL(string = url)

internal fun post(url: String, body: ByteArray? = null, contentType: String = "text/plain"): NSMutableURLRequest =
    NSMutableURLRequest(uRL = nsUrl(url)).apply {
        setHTTPMethod("POST")
        setValue(contentType, forHTTPHeaderField = "Content-Type")
        body?.let { setHTTPBody(it.toNSData()) }
    }

internal enum class SessionKind { Shared, Default, Ephemeral }

internal fun session(kind: SessionKind, delegate: NSURLSessionDelegateProtocol? = null, queue: NSOperationQueue? = null): NSURLSession = when (kind) {
    SessionKind.Shared -> NSURLSession.sharedSession
    SessionKind.Default -> NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.defaultSessionConfiguration, delegate, queue)
    SessionKind.Ephemeral -> NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration, delegate, queue)
}

internal fun HopiFxCompletion.awaitDone() = assertTrue(waitFor(15.0), "the completion handler never ran")

internal fun HopiFxRecorder.awaitDone() = assertTrue(waitFor(15.0), "the delegate never completed")

/** What an observer of the app could compare before and after start(). */
@OptIn(ExperimentalForeignApi::class)
internal fun appView(delegate: Any): String = hopi_fx_responds(delegate) ?: ""

@OptIn(ExperimentalForeignApi::class)
internal fun classOf(task: NSURLSessionTask): String = hopi_fx_class_name(task) ?: "?"
