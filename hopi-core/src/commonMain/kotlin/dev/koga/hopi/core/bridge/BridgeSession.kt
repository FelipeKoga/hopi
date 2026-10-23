package dev.koga.hopi.core.bridge

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.store.HopiStore
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** What `hello` reports about the app. */
@HopiInternalApi
public class BridgeInfo(
    public val app: String,
    public val platform: String,
    /**
     * Android only: the secret the app also wrote to its private storage, sent in `hello` so the
     * CLI can tell the app's bridge from another app squatting the socket name. It reaches only
     * peers the transport already authenticated; never logged.
     */
    public val proof: String? = null,
)

/** Wire error codes. */
@HopiInternalApi
public enum class BridgeError {
    NOT_FOUND,
    BAD_REQUEST,

    /**
     * Reserved, never sent: a peer that fails authentication is closed before any byte is
     * written, so an unauthenticated peer learns nothing, not even that the bridge speaks NDJSON.
     */
    UNAUTHORIZED,
    SLOW_CLIENT,
}

/**
 * The transport-agnostic protocol handler for one connection: authenticates it (token transports),
 * then serves `hello`/`list`/`get`/`tail`/`clear` from [store], redacted with the store's redactor.
 *
 * [run] blocks the calling thread, which reads and answers requests; a second thread writes. The
 * two meet in a bounded reply channel, so a client that reads slowly only slows its own requests.
 * Live `tail` events reach the writer through a second bounded channel that the store's event
 * collector fills with a non-suspending `trySend` and nothing else; when it is full the client is
 * disconnected (`SLOW_CLIENT`), so no client ever suspends the store's consumer or another client.
 * Nothing is encoded unless this client asked for it.
 */
@HopiInternalApi
public class BridgeSession(
    private val connection: BridgeConnection,
    private val store: HopiStore,
    private val info: BridgeInfo,
    /** Null when the transport authenticated the peer itself (Android's peer uid check). */
    private val token: BridgeToken?,
    scope: CoroutineScope,
    private val liveCapacity: Int = liveCapacityFor(store),
    replyCapacity: Int = DEFAULT_REPLY_CAPACITY,
) {
    private val replies = Channel<Outgoing>(replyCapacity)
    private val live = Channel<Live>(liveCapacity)
    private val tailJob = SupervisorJob(scope.coroutineContext[Job])
    private val tailScope = CoroutineScope(scope.coroutineContext + tailJob)
    private val writerState = AtomicInt(IDLE)
    private val holders = AtomicInt(1)
    private val tailing = AtomicBoolean(false)
    private val tailSince = AtomicLong(0)
    private val eventsAfterFinal = AtomicInt(0)

    // Writer thread only: what this client has been sent of the live stream, for SLOW_CLIENT's `since`.
    private var sentPending: HashSet<Long>? = null
    private var highestSent = 0L

    /** Serves the connection until either side ends it, then releases it. */
    public fun run() {
        var writerStarted = false
        var inputEnded = false
        try {
            val reader = BridgeLineReader(connection)
            if (token != null && !authenticated(reader, token)) return
            holders.incrementAndFetch()
            try {
                startBridgeThread("hopi-bridge-writer") { writeLoop() }
            } catch (e: Throwable) {
                holders.decrementAndFetch()
                throw e
            }
            writerStarted = true
            try {
                runBlocking { readLoop(reader) }
                inputEnded = true
            } catch (_: CancellationException) {
                // The writer ended the session (a failed write, or SLOW_CLIENT) while a reply waited.
            }
        } finally {
            if (!writerStarted) {
                tailJob.cancel()
                connection.shutdown()
            } else if (tailing.load() && !inputEnded) {
                // The writer ended the stream, or reading failed: stop everything now.
                tailJob.cancel()
                live.cancel()
                replies.cancel()
            } else if (tailing.load()) {
                // The client half-closed (or went away) after `tail`: no new events, but the writer
                // still sends the backlog and the events already queued, then shuts down.
                tailJob.cancel()
                live.close()
                replies.close()
            } else {
                // Let the writer flush the replies already queued (a client may half-close after its request).
                replies.close()
            }
            release()
        }
    }

    private fun authenticated(reader: BridgeLineReader, token: BridgeToken): Boolean {
        val line = reader.readLine()
        return line is BridgeLine.Text && token.matches(line.text)
    }

    private suspend fun readLoop(reader: BridgeLineReader) {
        while (true) {
            val line = when (val next = reader.readLine()) {
                BridgeLine.End -> return
                BridgeLine.TooLong -> {
                    reply(error(BridgeError.BAD_REQUEST, "request longer than ${BridgeCommand.MAX_LINE_LENGTH} chars"))
                    continue
                }
                is BridgeLine.Text -> next.text
            }
            if (line.isBlank()) continue
            val command = when (val parsed = BridgeCommand.parse(line)) {
                is BridgeParseResult.Invalid -> {
                    reply(error(BridgeError.BAD_REQUEST, parsed.message))
                    continue
                }
                is BridgeParseResult.Ok -> parsed.command
            }
            when (command) {
                BridgeCommand.Hello -> reply(hello())
                is BridgeCommand.List -> {
                    val redactor = store.redactor
                    for (transaction in store.since(command.since, command.limit)) {
                        reply(TransactionJson.bridgeSummary(transaction, redactor))
                    }
                    reply(END_LINE)
                }
                is BridgeCommand.Get -> {
                    val transaction = store.get(command.id)
                    if (transaction == null) {
                        reply(error(BridgeError.NOT_FOUND, "no transaction ${command.id}"))
                    } else {
                        // Encoded by the writer as it sends: a queued reply holds the snapshot, not
                        // a string several times its body.
                        replies.send(Outgoing.Detail(transaction))
                    }
                }
                is BridgeCommand.Tail -> {
                    startTail(command.since)
                    // The stream runs until disconnect; later input is read only to notice that.
                    while (true) {
                        if (reader.readLine() == BridgeLine.End) return
                    }
                }
                BridgeCommand.Clear -> {
                    // `through`: the last id the clear covers, so a host viewer clears exactly those
                    // (its tail may already hold transactions started after it).
                    val through = store.clear()
                    reply(JsonWriter().beginObject().field("type", "ok").field("through", through).endObject().toString())
                }
            }
        }
    }

    /**
     * Subscribes to live events before reading the backlog, so nothing published in between is
     * missed; the writer then skips every live event the backlog already showed (see [isNew]).
     */
    private suspend fun startTail(since: Long) {
        tailSince.store(since)
        tailing.store(true)
        // The store calls this on its consumer, losslessly; `events` would drop under a burst unnoticed.
        // A queued event holds a body-free copy: a client that stops reading must not pin captured
        // bodies (up to a whole queue of them, evicted ones included) outside the store's byte cap.
        val remove = store.addEventListener { offer(Live.Event(it.transaction.liveSummary())) }
        tailJob.invokeOnCompletion { remove() }
        val droppedAtStart = store.droppedTerminalWrites.value
        // Holes made before this client connected: the stream says so first, and every later
        // increase follows as its own notice, so no drop goes unreported.
        if (droppedAtStart > 0) reply(droppedNotice(droppedAtStart))
        tailScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var reported = droppedAtStart
            store.droppedTerminalWrites.collect { dropped ->
                if (dropped > reported) {
                    reported = dropped
                    offer(Live.Dropped(dropped))
                }
            }
        }
        val backlog = store.since(since)
        val shown = HashMap<Long, HttpTransaction>()
        val redactor = store.redactor
        for (transaction in backlog) {
            reply(TransactionJson.bridgeSummary(transaction, redactor))
            shown[transaction.id] = transaction.liveSummary()
        }
        replies.send(Outgoing.StartLive(since, shown))
    }

    /** Runs on the store's consumer (events) or a collector (drop count): never suspends, never encodes. */
    private fun offer(item: Live) {
        val result = live.trySend(item)
        if (result.isSuccess) return
        if (result.isClosed) {
            // The writer owes the client its SLOW_CLIENT line. A client that stopped reading may
            // leave that write parked on a full socket, so once the client has fallen behind by
            // another full queue, the store's own events (no timer) cut the connection.
            if (writerState.load() == FINAL && eventsAfterFinal.incrementAndFetch() > liveCapacity) connection.shutdown()
            return
        }
        fellBehind()
    }

    /**
     * The live queue overflowed although it holds more than one store batch, so the client fell
     * behind by a whole queue. The writer sends the `SLOW_CLIENT` line as its last one once its
     * current write (if any) completes, and shuts down. Being inside a write says nothing about
     * the socket being full, so this never cuts the connection itself: a write parked on a full
     * socket is cut by the store's next events instead (see [offer]), which needs no timer. The
     * event listener stays registered until then.
     */
    private fun fellBehind() {
        while (true) {
            val state = writerState.load()
            if (state == FINAL) return
            if (writerState.compareAndSet(state, FINAL)) break
        }
        live.cancel()
        replies.cancel()
    }

    private fun writeLoop() {
        try {
            runBlocking { writeAll() }
        } catch (_: Throwable) {
            // A failed write or a cancelled queue ends the session; nothing to report to whom.
        } finally {
            if (writerState.load() == FINAL) {
                try {
                    writeRaw(slowClientError(resumeSince()))
                } catch (_: Exception) {
                    // The line did not fit after all.
                }
            }
            tailJob.cancel()
            live.cancel()
            replies.cancel()
            connection.shutdown()
            release()
        }
    }

    private suspend fun writeAll() {
        for (item in replies) {
            when (item) {
                is Outgoing.Line -> send(item.text)
                is Outgoing.Detail -> sendDetail(item.transaction)
                is Outgoing.StartLive -> {
                    streamLive(item.since, item.shown)
                    return
                }
            }
        }
    }

    private suspend fun streamLive(since: Long, shown: HashMap<Long, HttpTransaction>) {
        val pending = HashSet<Long>()
        var highest = since
        for ((id, transaction) in shown) {
            if (!transaction.state.isTerminal) pending += id
            if (id > highest) highest = id
        }
        highestSent = highest
        sentPending = pending
        for (item in live) {
            when (item) {
                is Live.Dropped -> send(droppedNotice(item.total))
                is Live.Event -> if (isNew(item.transaction, since, shown)) {
                    send(TransactionJson.bridgeSummary(item.transaction, store.redactor))
                    val id = item.transaction.id
                    if (item.transaction.state.isTerminal) pending -= id else pending += id
                    if (id > highestSent) highestSent = id
                }
            }
        }
    }

    /**
     * Where this client can tail again without losing a transaction: one below the lowest id it
     * was sent as pending and never as terminal (ids are allocated when a request starts, so the
     * last id received is not safe), else the highest id it was sent. Before the live stream
     * began it is the `since` it asked for. Runs on the writer thread.
     */
    private fun resumeSince(): Long {
        val pending = sentPending ?: return tailSince.load()
        return if (pending.isEmpty()) highestSent else pending.min() - 1
    }

    /**
     * False for a live event the client already has: an id at or below `since`, a snapshot equal
     * to the one the backlog sent (both sides are [liveSummary] copies, so equal means the same
     * summary line), or any snapshot of a transaction the backlog showed terminal (a `Started`
     * queued before the backlog read, say). [shown] holds only backlog snapshots still pending, so
     * it never outgrows the backlog and empties as they complete; ids first seen live need no
     * entry, since the store never emits a pending snapshot after a terminal one.
     */
    private fun isNew(transaction: HttpTransaction, since: Long, shown: HashMap<Long, HttpTransaction>): Boolean {
        if (transaction.id <= since) return false
        val previous = shown[transaction.id] ?: return true
        if (previous == transaction || previous.state.isTerminal) return false
        if (transaction.state.isTerminal) shown.remove(transaction.id) else shown[transaction.id] = transaction
        return true
    }

    private fun send(line: String) {
        if (!writerState.compareAndSet(IDLE, WRITING)) throw StopWriting
        writeRaw(line)
        if (!writerState.compareAndSet(WRITING, IDLE)) throw StopWriting
    }

    /**
     * The `get` reply, encoded and written in slices of [DETAIL_CHUNK_CHARS], so a giant body costs
     * its decoded text plus one slice, not the whole line built, copied and encoded at once. The
     * line is one write state like any other: a `SLOW_CLIENT` decision waits for its end.
     */
    private fun sendDetail(transaction: HttpTransaction) {
        if (!writerState.compareAndSet(IDLE, WRITING)) throw StopWriting
        val out = StringBuilder(DETAIL_CHUNK_CHARS + 1024)
        TransactionJson.writeBridgeDetail(transaction, store.redactor, out, DETAIL_CHUNK_CHARS) {
            writeText(out, newline = false)
            out.setLength(0)
        }
        writeText(out, newline = true)
        if (!writerState.compareAndSet(WRITING, IDLE)) throw StopWriting
    }

    private fun writeRaw(line: String) = writeText(line, newline = true)

    // Writer thread only: reused for every line, grown to the longest slice written.
    private var encoded = ByteArray(0)

    /** Writes [text] as UTF-8, plus `\n` when [newline], in one write and without an intermediate string. */
    private fun writeText(text: CharSequence, newline: Boolean) {
        val needed = text.length * 3 + 1
        if (encoded.size < needed) encoded = ByteArray(maxOf(needed, minOf(encoded.size * 2, MAX_REUSED_BUFFER)))
        var n = encodeUtf8(text, encoded)
        if (newline) encoded[n++] = '\n'.code.toByte()
        connection.write(encoded, n)
        if (encoded.size > MAX_REUSED_BUFFER) encoded = ByteArray(0)
    }

    private suspend fun reply(line: String) {
        replies.send(Outgoing.Line(line))
    }

    private fun release() {
        if (holders.decrementAndFetch() == 0) connection.close()
    }

    private fun hello(): String = JsonWriter().beginObject()
        .field("type", "hello")
        .field("protocol", PROTOCOL_VERSION)
        .field("app", info.app)
        .field("platform", info.platform)
        .field("count", store.since(0).size)
        .field("dropped", store.droppedTerminalWrites.value)
        .also { writer -> info.proof?.let { writer.field("proof", it) } }
        .endObject()
        .toString()

    private sealed interface Outgoing {
        class Line(val text: String) : Outgoing

        /** A `get` reply, encoded only when written. */
        class Detail(val transaction: HttpTransaction) : Outgoing

        /** Everything before it was backlog; the writer switches to the live queue. */
        class StartLive(val since: Long, val shown: HashMap<Long, HttpTransaction>) : Outgoing
    }

    private sealed interface Live {
        /** [transaction] is a [liveSummary] copy: no body bytes, no headers. */
        class Event(val transaction: HttpTransaction) : Live

        class Dropped(val total: Long) : Live
    }

    private object StopWriting : RuntimeException()

    public companion object {
        public const val PROTOCOL_VERSION: Int = 1

        /** Live events a client may lag behind, beyond one store batch, before it is disconnected. */
        public const val DEFAULT_LIVE_LAG: Int = 512
        public const val DEFAULT_REPLY_CAPACITY: Int = 64

        /**
         * One full store batch (delivered to the listener in one burst, faster than any writer
         * drains it) plus [DEFAULT_LIVE_LAG], so only a client that really stopped keeping up
         * overflows. Bounded; the channel allocates its slots only as they fill.
         */
        public fun liveCapacityFor(store: HopiStore): Int = store.maxBatchSize + DEFAULT_LIVE_LAG

        private val NO_BYTES = ByteArray(0)

        /**
         * What a live summary line needs of [this], and nothing it does not: headers dropped and
         * each body reduced to its reported size and flags, so a queued event costs a few hundred
         * bytes whatever the bodies weigh. [TransactionJson.bridgeSummary] writes the same line
         * for it as for the original.
         */
        internal fun HttpTransaction.liveSummary(): HttpTransaction {
            if (requestHeaders.isEmpty() && responseHeaders.isEmpty() && requestBody.isSizeOnly() && responseBody.isSizeOnly()) {
                return this
            }
            return copy(
                requestHeaders = emptyList(),
                requestBody = requestBody?.sizeOnly(),
                responseHeaders = emptyList(),
                responseBody = responseBody?.sizeOnly(),
            )
        }

        private fun CapturedBody?.isSizeOnly(): Boolean = this == null || (bytes.isEmpty() && totalBytes != null)

        private fun CapturedBody.sizeOnly(): CapturedBody =
            if (isSizeOnly()) this else CapturedBody(NO_BYTES, reportedBytes, truncated, contentType, skippedReason, originalCapturedBytes = heldBytes)

        /** Chars of a body escaped, encoded and written at a time by a `get` reply. */
        private const val DETAIL_CHUNK_CHARS = 32 * 1024

        /** A larger encode buffer (a line that is not a sliced `get`) is dropped after its write. */
        private const val MAX_REUSED_BUFFER = 1024 * 1024

        /**
         * Encodes [text] into [out] (at least 3 bytes per char) and returns the byte count. An
         * unpaired surrogate becomes U+FFFD, as [String.encodeToByteArray] does.
         */
        private fun encodeUtf8(text: CharSequence, out: ByteArray): Int {
            var n = 0
            var i = 0
            val length = text.length
            while (i < length) {
                val c = text[i].code
                when {
                    c < 0x80 -> out[n++] = c.toByte()
                    c < 0x800 -> {
                        out[n++] = (0xC0 or (c shr 6)).toByte()
                        out[n++] = (0x80 or (c and 0x3F)).toByte()
                    }
                    c in 0xD800..0xDBFF && i + 1 < length && text[i + 1].code in 0xDC00..0xDFFF -> {
                        val point = 0x10000 + ((c - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                        out[n++] = (0xF0 or (point shr 18)).toByte()
                        out[n++] = (0x80 or ((point shr 12) and 0x3F)).toByte()
                        out[n++] = (0x80 or ((point shr 6) and 0x3F)).toByte()
                        out[n++] = (0x80 or (point and 0x3F)).toByte()
                        i++
                    }
                    else -> {
                        val unit = if (c in 0xD800..0xDFFF) 0xFFFD else c
                        out[n++] = (0xE0 or (unit shr 12)).toByte()
                        out[n++] = (0x80 or ((unit shr 6) and 0x3F)).toByte()
                        out[n++] = (0x80 or (unit and 0x3F)).toByte()
                    }
                }
                i++
            }
            return n
        }

        private const val IDLE = 0
        private const val WRITING = 1
        private const val FINAL = 2

        private val END_LINE = JsonWriter().beginObject().field("type", "end").endObject().toString()

        public fun error(code: BridgeError, message: String): String = JsonWriter().beginObject()
            .field("type", "error")
            .field("code", code.name)
            .field("message", message)
            .endObject()
            .toString()

        /**
         * `{"type":"error","code":"SLOW_CLIENT","message":"…","since":N}`: tailing again with
         * `since` N loses nothing this client was owed (it may repeat some summaries).
         */
        public fun slowClientError(since: Long): String = JsonWriter().beginObject()
            .field("type", "error")
            .field("code", BridgeError.SLOW_CLIENT.name)
            .field("message", "client fell behind the live stream; tail again with since $since to resume without losing a transaction")
            .field("since", since)
            .endObject()
            .toString()

        /** `{"type":"notice","dropped":N}`, N counting every terminal write the store lost so far. */
        public fun droppedNotice(total: Long): String = JsonWriter().beginObject()
            .field("type", "notice")
            .field("dropped", total)
            .endObject()
            .toString()
    }
}
