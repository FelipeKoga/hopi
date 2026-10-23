package dev.koga.hopi.ktor

import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.ClosedReadChannelException
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.cancel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.discard
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writeFully
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray

/** The delegating tee: the app reads the source itself, and the capture costs nothing past the cap. */
@OptIn(InternalAPI::class)
class TeedBodyTest {
    private val plain = headersOf(HttpHeaders.ContentType, "text/plain")

    @Test
    fun bytesBeyondTheCapCauseNoCaptureWork() = runBlocking<Unit> {
        TestStore().use { t ->
            val payload = asciiBytes(10 * 1024 * 1024, seed = 11)
            val cap = 256 * 1024
            val chunk = 3_001
            // The source hands out one chunk per wait without suspending, so both arms do exactly
            // the same reads and only this thread's allocations are compared.
            fun client(plugin: Boolean) = if (plugin) {
                mockClient(t.store) { respond(ChunkedSource(payload, chunk), HttpStatusCode.OK, plain) }
            } else {
                HttpClient(MockEngine { respond(ChunkedSource(payload, chunk), HttpStatusCode.OK, plain) })
            }
            class Run(val allocated: Long, val copiesAtCap: Int?, val copiesPastCap: Int?)
            suspend fun run(plugin: Boolean): Run = client(plugin).prepareGet("https://api.example.com/big").execute { response ->
                // The teed body itself, as SaveBody, BodyProgress or bodyAsChannel's copy read it.
                val channel = response.rawContent
                val teed = if (plugin) assertIs<TeedBody>(channel) else null
                val scratch = ByteArray(16 * 1024)
                suspend fun readUpTo(limit: Long, from: Long): Long {
                    var read = from
                    while (read < limit) {
                        val n = channel.readAvailable(scratch, 0, minOf(scratch.size.toLong(), limit - read).toInt())
                        assertTrue(n > 0)
                        read += n
                    }
                    return read
                }
                var read = readUpTo(1024 * 1024, 0)
                val atCap = teed?.capture?.copies
                // From 1 MB to 9 MB: past the cap, and before the end completes the transaction.
                val allocatedBefore = threadAllocatedBytes()
                read = readUpTo(9L * 1024 * 1024, read)
                val allocated = threadAllocatedBytes() - allocatedBefore
                val pastCap = teed?.capture?.copies
                readUpTo(payload.size.toLong(), read)
                assertEquals(-1, channel.readAvailable(scratch))
                Run(allocated, atCap, pastCap)
            }
            repeat(3) { run(plugin = false); run(plugin = true) } // warm-up
            val baseline = run(plugin = false)
            val teed = run(plugin = true)
            assertEquals(teed.copiesAtCap, teed.copiesPastCap, "no copy past the cap")
            assertTrue(teed.copiesAtCap!! in 1..(cap / chunk + 1), "one copy per read until the cap: ${teed.copiesAtCap}")
            assertTrue(
                teed.allocated - baseline.allocated < 16 * 1024,
                "reading 8 MB past the cap allocated ${teed.allocated} B on the reader, ${baseline.allocated} B without the plugin",
            )
            val txs = t.awaitTerminal(4)
            for (tx in txs) assertCaptured(tx, payload, cap)
        }
    }

    @Test
    fun fromTheFirstByteToTheEndTheReaderPaysAtMostOneCopyOfTheCap() = runBlocking<Unit> {
        // No Content-Length, so the capture cannot be sized up front: it grows in blocks, without
        // copying, and the join at the end runs off the reader once it exceeds 64 KB.
        for (cap in intArrayOf(64 * 1024, 8 * 1024 * 1024)) {
            for (size in intArrayOf(200_000, 9 * 1024 * 1024)) {
                TestStore().use { t ->
                    val payload = asciiBytes(size, seed = 18)
                    fun client(plugin: Boolean) = if (plugin) {
                        mockClient(t.store, { maxBodyBytes = cap.toLong() }) {
                            respond(ChunkedSource(payload, 3_001), HttpStatusCode.OK, plain)
                        }
                    } else {
                        HttpClient(MockEngine { respond(ChunkedSource(payload, 3_001), HttpStatusCode.OK, plain) })
                    }
                    suspend fun run(plugin: Boolean): Long = client(plugin).prepareGet("https://api.example.com/chunked").execute { response ->
                        val channel = response.rawContent
                        if (plugin) assertIs<TeedBody>(channel)
                        val scratch = ByteArray(16 * 1024)
                        val before = threadAllocatedBytes()
                        var read = 0L
                        while (true) {
                            val n = channel.readAvailable(scratch)
                            if (n < 0) break
                            read += n
                        }
                        // The -1 settled the transaction on this thread.
                        val allocated = threadAllocatedBytes() - before
                        assertEquals(payload.size.toLong(), read)
                        allocated
                    }
                    repeat(3) { run(plugin = false); run(plugin = true) } // warm-up
                    val baseline = run(plugin = false)
                    val teed = run(plugin = true)
                    val kept = minOf(cap, size)
                    assertTrue(
                        teed - baseline < kept + 128 * 1024,
                        "cap $cap, body $size: the reader allocated $teed B with the plugin, $baseline B without",
                    )
                    for (tx in t.awaitTerminal(4)) assertCaptured(tx, payload, cap)
                }
            }
        }
    }

    @Test
    fun theAppMayReadWithEveryChannelApi() = runBlocking<Unit> {
        val lines = buildString {
            var i = 0
            while (length < 300_000) append("line ").append(i++).append(" of the body\n")
        }.encodeToByteArray()
        val readers: List<Pair<String, suspend (ByteReadChannel) -> ByteArray>> = listOf(
            "readAvailable" to { channel ->
                val out = ByteArrayOutputStream()
                val scratch = ByteArray(4096)
                while (true) {
                    val n = channel.readAvailable(scratch)
                    if (n < 0) break
                    out.write(scratch, 0, n)
                }
                out.toByteArray()
            },
            "readRemaining" to { channel -> channel.readRemaining().readByteArray() },
            "readRemaining(max)" to { channel ->
                val out = ByteArrayOutputStream()
                while (!channel.isClosedForRead) out.write(channel.readRemaining(30_011).readByteArray())
                out.toByteArray()
            },
            "readBuffer()" to { channel -> channel.readBuffer().readByteArray() },
            "toByteArray" to { channel -> channel.toByteArray() },
            "readUTF8Line" to { channel ->
                val out = StringBuilder()
                while (true) {
                    @Suppress("DEPRECATION")
                    val line = channel.readUTF8Line() ?: break
                    out.append(line).append('\n')
                }
                out.toString().encodeToByteArray()
            },
            "copyTo" to { channel ->
                val sink = ByteChannel()
                val collected = CoroutineScope(Dispatchers.Default).async { sink.toByteArray() }
                channel.copyTo(sink)
                sink.flushAndClose()
                collected.await()
            },
            "readByte" to { channel ->
                val out = ByteArrayOutputStream()
                while (true) {
                    val byte = try {
                        channel.readByte()
                    } catch (_: EOFException) {
                        break
                    }
                    out.write(byte.toInt())
                }
                out.toByteArray()
            },
            "readFully" to { channel ->
                val out = ByteArray(lines.size)
                var offset = 0
                while (offset < out.size) {
                    val end = minOf(out.size, offset + 10_007)
                    channel.readFully(out, offset, end)
                    offset = end
                }
                out
            },
            "awaitContent(min) + readBuffer" to { channel ->
                val out = ByteArrayOutputStream()
                while (channel.awaitContent(50_000)) {
                    out.write(channel.readBuffer.readByteArray(50_000))
                }
                out.write(channel.readRemaining().readByteArray())
                out.toByteArray()
            },
            "readBuffer + awaitContent loop" to { channel ->
                val out = ByteArrayOutputStream()
                while (!channel.isClosedForRead) {
                    out.write(channel.readBuffer.readByteArray())
                    channel.awaitContent()
                }
                out.toByteArray()
            },
        )
        for ((name, read) in readers) {
            for (cap in intArrayOf(64 * 1024, 1024 * 1024)) {
                TestStore().use { t ->
                    val client = liveClient(t, lines, chunks = intArrayOf(1, 777, 8_192, 65_539), cap = cap)
                    val received = client.prepareGet("https://api.example.com/lines").execute { read(assertIs<TeedBody>(it.rawContent)) }
                    assertContentEquals(lines, received, "$name, cap $cap: the app's bytes")
                    assertCaptured(t.awaitTerminal().single(), lines, cap, name)
                }
            }
        }
        // discard reads nothing out, yet the capture and the count are those of the whole body.
        TestStore().use { t ->
            val client = liveClient(t, lines, chunks = intArrayOf(5_000, 70_000))
            val discarded = client.prepareGet("https://api.example.com/lines").execute { it.rawContent.discard() }
            assertEquals(lines.size.toLong(), discarded)
            assertCaptured(t.awaitTerminal().single(), lines, 256 * 1024, "discard")
        }
    }

    @Test
    fun partialReadThenCancelFailsAndCancelsTheSource() = runBlocking<Unit> {
        TestStore().use { t ->
            val payload = asciiBytes(4 * 1024 * 1024, seed = 12)
            val source = ByteChannel()
            val cancelled = CompletableDeferred<Unit>()
            val producer = launch(Dispatchers.Default) {
                runCatching {
                    source.writeFully(payload, 0, 512 * 1024)
                    source.flush()
                    // The rest is still on the network when the app cancels.
                    cancelled.await()
                    source.writeFully(payload, 512 * 1024, payload.size)
                    source.flushAndClose()
                }
            }
            val client = mockClient(t.store) { respond(source, HttpStatusCode.OK, plain) }
            val channel = client.prepareGet("https://api.example.com/big").body<ByteReadChannel>()
            val head = ByteArray(300 * 1024)
            channel.readFully(head)
            assertContentEquals(payload.copyOf(head.size), head)
            channel.cancel()
            cancelled.complete(Unit)
            val tx = t.awaitTerminal().single()
            assertEquals(TransactionState.Failed, tx.state)
            assertEquals("cancelled", tx.error)
            assertNotNull(source.closedCause, "the source is cancelled")
            withTimeout(10_000) { producer.join() }
        }
    }

    @Test
    fun readingEveryByteThenLeavingCompletesWithoutSeeingTheEnd() = runBlocking<Unit> {
        TestStore().use { t ->
            val payload = asciiBytes(700_000, seed = 13)
            val client = mockClient(t.store) { respond(payload, HttpStatusCode.OK, plain) }
            // readFully of exactly the body never asks past it; the cleanup's cancel finds the end.
            client.prepareGet("https://api.example.com/exact").execute { it.rawContent.readFully(ByteArray(payload.size)) }
            assertCaptured(t.awaitTerminal().single(), payload, 256 * 1024)
        }
    }

    @Test
    fun aSourceFailureMidBodyReachesTheAppUnchangedAndFailsTheTransaction() = runBlocking<Unit> {
        val readers: List<Pair<String, suspend (ByteReadChannel) -> Unit>> = listOf(
            "readAvailable" to { channel ->
                val scratch = ByteArray(8192)
                while (channel.readAvailable(scratch) >= 0) Unit
                // readAvailable reports a failed channel as its end, as without the plugin.
                channel.closedCause?.let { throw it }
            },
            "readRemaining" to { channel -> channel.readRemaining().readByteArray() },
            "readFully" to { channel -> channel.readFully(ByteArray(1024 * 1024)) },
        )
        val scope = this
        for ((name, read) in readers) {
            for (failAt in intArrayOf(10_000, 300_000)) {
                for (direct in listOf(true, false)) {
                    // The reset comes before the app reads on, or while it waits for more. In the
                    // second case the resetter runs on this test's own thread, so it can run only
                    // once the reader has suspended in its wait for bytes that never come, and the
                    // ByteChannel resumes that wait with the failure (a reset just before the wait
                    // suspends ends it with false instead: aSourceThatFailsDuringAWaitEndsItAsAByteChannelDoes).
                    for (whileWaiting in listOf(false, true)) {
                        // With one byte left unread, the app's channel still holds bytes when the source
                        // fails, which a failed ByteChannel no longer hands out either.
                        for (unread in intArrayOf(0, 1)) {
                            for (progress in if (unread == 0) listOf(false, true) else listOf(false)) {
                                val label = "$name, failing at $failAt, ${if (direct) "rawContent" else "bodyAsChannel"}" +
                                    "${if (whileWaiting) ", while waiting" else ""}, $unread unread" +
                                    if (progress) ", onDownload" else ""
                                sourceFailure(scope, label, read, failAt - unread, failAt, direct, whileWaiting, progress)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun sourceFailure(
        scope: CoroutineScope,
        label: String,
        read: suspend (ByteReadChannel) -> Unit,
        head: Int,
        failAt: Int,
        direct: Boolean,
        whileWaiting: Boolean,
        progress: Boolean,
    ) {
        // The connection resets after failAt bytes, once the app has read the first head of them.
        suspend fun failure(newClient: (MockEngine) -> HttpClient): Throwable {
            val headRead = CompletableDeferred<Unit>()
            val reset = CompletableDeferred<Unit>()
            val engine = MockEngine {
                val body = ByteChannel()
                body.writeFully(asciiBytes(failAt, seed = 14))
                body.flush()
                val resetter = if (whileWaiting) scope else CoroutineScope(Dispatchers.Default)
                resetter.launch {
                    headRead.await()
                    body.cancel(IOException("connection reset"))
                    reset.complete(Unit)
                }
                respond(body, HttpStatusCode.OK, plain)
            }
            return assertFailsWith<Throwable>(label) {
                newClient(engine).prepareGet("https://api.example.com/reset") {
                    // BodyProgress's writer holds what it copied until the end or 1 MB, so the app
                    // cannot read the head first; the writer itself has read it once it reports it.
                    if (progress) onDownload { sent, _ -> if (sent >= failAt) headRead.complete(Unit) }
                }.execute { response ->
                    val channel = if (direct) response.rawContent else response.bodyAsChannel()
                    if (!progress) {
                        channel.readFully(ByteArray(head))
                        headRead.complete(Unit)
                    }
                    if (!whileWaiting) reset.await()
                    read(channel)
                }
            }
        }
        fun chain(e: Throwable) = generateSequence(e) { it.cause }.map { it::class to it.message }.toList()
        // A hang fails the test by its label rather than passing as the expected failure.
        suspend fun noHang(arm: String, block: suspend () -> Throwable): Throwable =
            withTimeoutOrNull(10_000) { block() }.let { e -> check(e != null && e !is TimeoutCancellationException) { "$label, $arm: hung" }; e }
        val baseline = noHang("without the plugin") { failure { engine -> HttpClient(engine) } }
        TestStore().use { t ->
            val withPlugin = noHang("with the plugin") {
                failure { engine ->
                    HttpClient(engine) {
                        install(Hopi) {
                            store = t.store
                            logSink = HopiLogSink.Off
                        }
                    }
                }
            }
            // Through bodyAsChannel the app reads Ktor's own copy, and with onDownload BodyProgress's,
            // whose failure varies from run to run without the plugin too (3 or 4 wrapping layers, or
            // an EOFException for readFully, as the copy closes before or after the app's next
            // read), so the exact chain is compared on the body itself. Through a copy, the app
            // still sees only what a copy of the failed source shows: the source's own failure
            // wrapped as a closed channel (a read or a closed-read one, as the copy closes before
            // or after the app's check), or the copy's end.
            if (direct && !progress) {
                assertEquals(chain(baseline), chain(withPlugin), label)
            } else {
                for (e in listOf(baseline, withPlugin)) assertCopiedFailure(e, label)
            }
            val tx = t.awaitTerminal().single()
            assertEquals(TransactionState.Failed, tx.state, label)
            assertEquals("IOException: connection reset", tx.error, label)
        }
    }

    @Test
    fun aSourceThatFailsDuringAWaitEndsItAsAByteChannelDoes() = runBlocking<Unit> {
        // A ByteChannel cancelled after its wait checked the close cause, and before it suspended,
        // ends that wait with false instead of throwing: the reader's next call then sees the end
        // or the failure. FailingSource fails exactly there, on its first wait for more bytes.
        val readers: List<Pair<String, suspend (ByteReadChannel) -> Unit>> = listOf(
            "readAvailable" to { channel ->
                val scratch = ByteArray(8192)
                while (channel.readAvailable(scratch) >= 0) Unit
                channel.closedCause?.let { throw it }
            },
            "readRemaining" to { channel -> channel.readRemaining().readByteArray() },
            "readFully" to { channel -> channel.readFully(ByteArray(1024 * 1024)) },
            // A failed ByteChannel's read buffer throws although bytes remain in it.
            "awaitContent, then readBuffer" to { channel ->
                if (!channel.awaitContent(2)) channel.readBuffer.readByteArray()
            },
            "awaitContent twice" to { channel ->
                channel.awaitContent(2)
                channel.awaitContent(1)
            },
        )
        fun chain(e: Throwable?) = generateSequence(e) { it.cause }.map { it::class to it.message }.toList()
        // (unread, onDownload): with onDownload the app reads BodyProgress's channel, whose writer
        // reads the body with readAvailable and holds what it copied until the body ends, so the
        // app reads nothing before the failure there.
        val cases = listOf(0 to false, 1 to false, 0 to true)
        for ((name, read) in readers) {
            for ((unread, progress) in cases) {
                val failAt = 10_000
                val label = "$name, $unread unread${if (progress) ", onDownload" else ""}"
                suspend fun outcome(client: HttpClient): Throwable? = runCatching {
                    client.prepareGet("https://api.example.com/reset") {
                        if (progress) onDownload { _, _ -> }
                    }.execute { response ->
                        val channel = response.rawContent
                        if (!progress) channel.readFully(ByteArray(failAt - unread))
                        read(channel)
                    }
                }.exceptionOrNull()
                val baseline = outcome(HttpClient(MockEngine { respond(FailingSource(failAt), HttpStatusCode.OK, plain) }))
                assertNotNull(baseline, label)
                TestStore().use { t ->
                    val withPlugin = outcome(mockClient(t.store) { respond(FailingSource(failAt), HttpStatusCode.OK, plain) })
                    if (progress) {
                        // BodyProgress's writer runs on its own dispatcher, so whether it closed its
                        // channel before the app's check varies; it must close it with the source's
                        // failure, not fail with a read error of its own.
                        for (e in listOf(baseline, withPlugin)) assertCopiedFailure(e, label)
                    } else {
                        assertEquals(chain(baseline), chain(withPlugin), label)
                    }
                    val tx = t.awaitTerminal().single()
                    assertEquals(TransactionState.Failed, tx.state, label)
                    assertEquals("IOException: connection reset", tx.error, label)
                }
            }
        }
    }

    @Test
    fun aBodyNobodyReadsStaysPendingUntilItIsReadOrCancelled() = runBlocking<Unit> {
        TestStore().use { t ->
            val payload = asciiBytes(1024 * 1024, seed = 15)
            val client = mockClient(t.store) { request ->
                if (request.url.encodedPath == "/other") respond("ok") else respond(payload, HttpStatusCode.OK, plain)
            }
            // Nothing reads the teed body while the block waits: the transaction stays pending.
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val call = async {
                client.prepareGet("https://api.example.com/held").execute { response ->
                    assertIs<TeedBody>(response.rawContent)
                    held.complete(Unit)
                    release.await()
                }
            }
            withTimeout(10_000) { held.await() }
            assertEquals("ok", client.get("https://api.example.com/other").bodyAsText())
            val snapshot = t.awaitTerminal(1)
            assertEquals(TransactionState.Pending, snapshot.single { it.url.endsWith("/held") }.state)
            // Leaving execute {} cancels the unread body; it had arrived whole, so it is captured.
            release.complete(Unit)
            call.await()
            assertCaptured(t.awaitTerminal(2).single { it.url.endsWith("/held") }, payload, 256 * 1024)

            // Through body<ByteReadChannel>(), Ktor's own copy reads the teed body into its 1 MB
            // buffer, so a body that fits completes although the app never reads it.
            val small = payload.copyOf(500_000)
            val smallClient = mockClient(t.store) { respond(small, HttpStatusCode.OK, plain) }
            val unread = smallClient.prepareGet("https://api.example.com/copied").body<ByteReadChannel>()
            assertCaptured(t.awaitTerminal(3).single { it.url.endsWith("/copied") }, small, 256 * 1024)
            assertContentEquals(small, unread.toByteArray())
        }
    }

    @Test
    fun anUnreadBodyLeftInExecuteCompletesIfItHadArrivedAndFailsIfNot() = runBlocking<Unit> {
        TestStore().use { t ->
            val small = asciiBytes(5_000, seed = 16)
            val live = ByteChannel()
            val client = mockClient(t.store) { request ->
                if (request.url.encodedPath == "/arrived") respond(small, HttpStatusCode.OK, plain) else respond(live, HttpStatusCode.OK, plain)
            }
            assertEquals(HttpStatusCode.OK, client.prepareGet("https://api.example.com/arrived").execute { it.status })
            live.writeFully(small)
            live.flush()
            assertEquals(HttpStatusCode.OK, client.prepareGet("https://api.example.com/arriving").execute { it.status })
            val txs = t.awaitTerminal(2)
            assertCaptured(txs.single { it.url.endsWith("/arrived") }, small, 256 * 1024)
            val arriving = txs.single { it.url.endsWith("/arriving") }
            assertEquals(TransactionState.Failed, arriving.state)
            assertEquals("cancelled", arriving.error)
            assertNotNull(live.closedCause, "the source is cancelled")
        }
    }

    @Test
    fun theBodyMayBeReadFromAnyDispatcherAndConcurrently() = runBlocking<Unit> {
        TestStore(channelCapacity = 1024).use { t ->
            val payload = asciiBytes(5 * 1024 * 1024, seed = 17)
            val client = liveClient(t, payload, chunks = intArrayOf(32 * 1024 + 1, 100_003))
            val received = client.prepareGet("https://api.example.com/io").execute { response ->
                withContext(Dispatchers.IO) { response.bodyAsChannel().toByteArray() }
            }
            assertContentEquals(payload, received)
            // BodyProgress reads the teed body from a writer coroutine of its own.
            val progress = AtomicLong()
            val observed = client.prepareGet("https://api.example.com/progress") {
                onDownload { sent, _ -> progress.set(sent) }
            }.execute { it.bodyAsChannel().toByteArray() }
            assertContentEquals(payload, observed)
            assertEquals(payload.size.toLong(), progress.get())
            val parallel = (0 until 24).map { i ->
                async(Dispatchers.Default) {
                    client.prepareGet("https://api.example.com/parallel/$i").execute { response ->
                        val channel = response.bodyAsChannel()
                        val out = ByteArrayOutputStream()
                        val scratch = ByteArray(1 + i * 997)
                        while (true) {
                            val n = channel.readAvailable(scratch)
                            if (n < 0) break
                            out.write(scratch, 0, n)
                        }
                        out.toByteArray()
                    }
                }
            }.awaitAll()
            assertTrue(parallel.all { it.contentEquals(payload) })
            val txs = t.awaitTerminal(26)
            assertEquals(26, txs.size)
            for (tx in txs) assertCaptured(tx, payload, 256 * 1024, tx.url)
        }
    }

    /**
     * A body whose next [chunk] of [payload] has always just arrived: like a `ByteChannel` with
     * flushed bytes, its read buffer takes them in when drained, and a wait never suspends.
     */
    private class ChunkedSource(private val payload: ByteArray, private val chunk: Int) : ByteReadChannel {
        private val buffer = kotlinx.io.Buffer()
        private var offset = 0

        override val closedCause: Throwable? get() = null
        override val isClosedForRead: Boolean get() = buffer.exhausted() && offset == payload.size
        override val readBuffer: kotlinx.io.Source
            get() {
                if (buffer.exhausted()) arrive()
                return buffer
            }

        override suspend fun awaitContent(min: Int): Boolean {
            while (buffer.size < min && offset < payload.size) arrive()
            return buffer.size >= min
        }

        private fun arrive() {
            val end = minOf(payload.size, offset + chunk)
            buffer.write(payload, offset, end)
            offset = end
        }

        override fun cancel(cause: Throwable?) = Unit
    }

    /**
     * Holds [size] bytes and fails, as a `ByteChannel` does, on its first wait for more: the wait
     * returns false, then the read buffer throws [ClosedReadChannelException] and the close cause
     * is a [ClosedByteChannelException] over the reset.
     */
    private class FailingSource(size: Int) : ByteReadChannel {
        private val buffer = kotlinx.io.Buffer().apply { write(asciiBytes(size, seed = 19)) }
        private var reset: IOException? = null

        override val closedCause: Throwable? get() = reset?.let(::ClosedByteChannelException)
        override val isClosedForRead: Boolean get() = reset != null
        override val readBuffer: kotlinx.io.Source
            get() {
                reset?.let { throw ClosedReadChannelException(it) }
                return buffer
            }

        override suspend fun awaitContent(min: Int): Boolean {
            closedCause?.let { throw it }
            if (buffer.size >= min) return true
            reset = IOException("connection reset")
            return false
        }

        override fun cancel(cause: Throwable?) {
            if (reset == null) reset = IOException(cause?.message ?: "Channel was cancelled")
        }
    }

    /** A client whose every response is [payload], written live from another dispatcher in [chunks] (cycled). */
    private fun liveClient(t: TestStore, payload: ByteArray, chunks: IntArray, cap: Int? = null): HttpClient =
        mockClient(t.store, { if (cap != null) maxBodyBytes = cap.toLong() }) {
            val body = ByteChannel()
            CoroutineScope(Dispatchers.Default + Job()).launch {
                var offset = 0
                var index = 0
                runCatching {
                    while (offset < payload.size) {
                        val end = minOf(payload.size, offset + chunks[index++ % chunks.size])
                        body.writeFully(payload, offset, end)
                        body.flush()
                        offset = end
                    }
                    body.flushAndClose()
                }
            }
            respond(body, HttpStatusCode.OK, plain)
        }

    /**
     * What an app reading a copy of a body whose source reset sees: the copy's end, or the source's
     * failure wrapped once per channel it crossed, where only the app's own read of the copy may
     * add a closed-read layer. A copy that failed on a read error of its own shows that error inside.
     */
    private fun assertCopiedFailure(e: Throwable?, label: String) {
        val links = generateSequence(e) { it.cause }.map { it::class to it.message }.toList()
        val ended = links == listOf(EOFException::class to "Channel is already closed")
        val wrapped = links.size >= 2 &&
            links.last() == (IOException::class to "connection reset") &&
            links.first().first in setOf(ClosedByteChannelException::class, ClosedReadChannelException::class) &&
            links.subList(1, links.size - 1).all { it.first == ClosedByteChannelException::class }
        assertTrue(ended || wrapped, "$label: $links")
    }

    private fun assertCaptured(tx: HttpTransaction, payload: ByteArray, cap: Int, label: String = tx.url) {
        assertEquals(TransactionState.Complete, tx.state, "$label: ${tx.error}")
        val body = assertNotNull(tx.responseBody, label)
        assertContentEquals(payload.copyOf(minOf(cap, payload.size)), body.bytes, label)
        assertEquals(payload.size.toLong(), body.totalBytes, label)
        assertEquals(payload.size > cap, body.truncated, label)
    }

    private inline fun <reified T> assertIs(value: Any): T {
        assertTrue(value is T, "expected ${T::class.simpleName}, was ${value::class.simpleName}")
        return value
    }

    private fun threadAllocatedBytes(): Long =
        (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).currentThreadAllocatedBytes
}
