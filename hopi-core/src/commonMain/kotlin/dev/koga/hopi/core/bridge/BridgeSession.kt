package dev.koga.hopi.core.bridge

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.concurrent.WakeSignal
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.json.encodeUtf8
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.perf.HangEvent
import dev.koga.hopi.core.perf.PerfController
import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfEvent
import dev.koga.hopi.core.perf.PerfHook
import dev.koga.hopi.core.perf.PerfItem
import dev.koga.hopi.core.perf.PerfJson
import dev.koga.hopi.core.perf.PerfLimits
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfResult
import dev.koga.hopi.core.perf.PerfSample
import dev.koga.hopi.core.perf.PerfStartRequest
import dev.koga.hopi.core.perf.perfWallClockMs
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleError
import dev.koga.hopi.core.rules.RuleErrorCode
import dev.koga.hopi.core.rules.RuleJson
import dev.koga.hopi.core.rules.RuleJsonContext
import dev.koga.hopi.core.rules.RuleLimits
import dev.koga.hopi.core.rules.RuleParse
import dev.koga.hopi.core.rules.RuleResult
import dev.koga.hopi.core.store.HopiStore
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
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

    /** A limit of docs/design/rules.md ("Limits") was reached; the message names it. */
    LIMIT,

    /** The app cannot do it: rules are off or absent (the message says why). */
    UNSUPPORTED,
}

/**
 * The transport-agnostic protocol handler for one connection: authenticates it (token transports),
 * then serves `hello`/`list`/`get`/`tail`/`clear` from [store], redacted with the store's redactor,
 * and, after `hello` on the same connection, the rule commands (`rules.*`, `blob.put`)
 * against [RuleEngine]. Blobs a client stages live in this session only and die with it.
 *
 * [run] blocks the calling thread, which reads and answers requests; a second thread writes. The
 * two meet in a bounded reply channel, so a client that reads slowly only slows its own requests.
 * The writer is a plain thread that blocks on a [WakeSignal] while both of its queues are empty,
 * never in a coroutine event loop (Kotlin/Native's wakes on a keep-alive timer), so a connected,
 * idle `tail` costs no CPU at all; every producer of its queues wakes it after queueing.
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

    /** Wakes the writer thread: after each item queued for it, and after its queues close. */
    private val writerSignal = WakeSignal()

    /** How many times the writer slept and was woken (tests: it must not wake while nothing is sent). */
    internal val writerWakeups: Long get() = writerSignal.wakeups

    /** True while the writer blocks with nothing to send. */
    internal val writerSleeping: Boolean get() = writerSignal.sleeping

    // Writer thread only: what this client has been sent of the live stream, for SLOW_CLIENT's `since`.
    private var sentPending: HashSet<Long>? = null
    private var highestSent = 0L
    private var highestPerfSent = 0L

    // Writer thread only: the hangs this client was sent as ongoing and not yet as ended.
    private val ongoingHangsSent = HashSet<Long>()

    // Set by the reader before the writer streams: this session's tail is `perf.tail`.
    @kotlin.concurrent.Volatile
    private var perfTail = false

    /** Serves the connection until either side ends it, then releases it. */
    public fun run() {
        var writerStarted = false
        var inputEnded = false
        var detachViewer: (() -> Unit)? = null
        try {
            val reader = BridgeLineReader(connection)
            if (token != null && !authenticated(reader, token)) return
            // A connected client is a viewer: requests in flight show as pending at once. What waited
            // unapplied while nobody watched (starts, and ends marked in the store's batch window) is
            // applied before the first request is read, so a `list` or `get` sent with the token and
            // `hello` in one write sees every request that ended before the client connected.
            detachViewer = store.attachViewer()
            store.awaitApplied(ATTACH_APPLY_TIMEOUT_MS)
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
            detachViewer?.invoke()
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
            writerSignal.wake()
            release()
        }
    }

    private fun authenticated(reader: BridgeLineReader, token: BridgeToken): Boolean {
        val line = reader.readLine()
        return line is BridgeLine.Text && token.matches(line.text)
    }

    // Reader thread only: whether `hello` was answered on this connection, and the staged blobs.
    private var helloSeen = false
    private val blobs = HashMap<Long, Blob>()

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
            if (command.isWrite) {
                when {
                    !helloSeen -> reply(error(BridgeError.BAD_REQUEST, "hello first"))
                    command is BridgeCommand.PerfTail -> {
                        if (!startPerfTail(command)) continue
                        // The stream runs until disconnect; later input is read only to notice that.
                        while (true) {
                            if (reader.readLine() == BridgeLine.End) return
                        }
                    }
                    command is BridgeCommand.Perf -> servePerf(command)
                    !RuleEngine.rulesAvailable -> reply(error(BridgeError.UNSUPPORTED, "rules are off in this app (rules = false)"))
                    else -> serveRules(command)
                }
                continue
            }
            when (command) {
                BridgeCommand.Hello -> {
                    helloSeen = true
                    reply(hello())
                }
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
                        enqueue(Outgoing.Detail(transaction))
                    }
                }
                is BridgeCommand.Tail -> {
                    startTail(command.since, command.rules)
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
                else -> serveRules(command)
            }
        }
    }

    /** The write commands, after `hello`. Reader thread only. */
    private suspend fun serveRules(command: BridgeCommand) {
        when (command) {
            is BridgeCommand.RulesAdd -> when (val parsed = RuleJson.parseRule(command.rule, ruleContext)) {
                is RuleParse.Failed -> reply(ruleError(parsed.error))
                is RuleParse.Ok -> when (val result = RuleEngine.add(parsed.value)) {
                    is RuleResult.Failed -> reply(ruleError(result.error))
                    is RuleResult.Ok -> {
                        parsed.blobs.forEach { blobs.remove(it) }
                        reply(RuleJson.ruleLine(result.rule, store.redactor))
                    }
                }
            }
            BridgeCommand.RulesList -> {
                val redactor = store.redactor
                for (rule in RuleEngine.list()) reply(RuleJson.ruleLine(rule, redactor))
                reply(END_LINE)
            }
            is BridgeCommand.RulesUpdate -> when (val parsed = RuleJson.parsePatch(command.fields)) {
                is RuleParse.Failed -> reply(ruleError(parsed.error))
                is RuleParse.Ok -> when (val result = RuleEngine.update(command.id, parsed.value)) {
                    is RuleResult.Failed -> reply(ruleError(result.error))
                    is RuleResult.Ok -> reply(RuleJson.ruleLine(result.rule, store.redactor))
                }
            }
            is BridgeCommand.RulesRemove ->
                reply(if (RuleEngine.remove(command.id)) OK_LINE else error(BridgeError.NOT_FOUND, "no rule ${command.id}"))
            BridgeCommand.RulesClear ->
                reply(JsonWriter().beginObject().field("type", "ok").field("removed", RuleEngine.clear()).endObject().toString())
            is BridgeCommand.BlobPut -> reply(putBlob(command))
            else -> reply(error(BridgeError.BAD_REQUEST, "unknown cmd"))
        }
    }

    /** The perf commands but `perf.tail`, after `hello`. Reader thread only. */
    private suspend fun servePerf(command: BridgeCommand.Perf) {
        val controller = PerfHook.controller ?: return reply(error(BridgeError.UNSUPPORTED, NO_PERF))
        when (command) {
            is BridgeCommand.PerfStart -> {
                if (!controller.remoteControl) return reply(error(BridgeError.UNSUPPORTED, NO_REMOTE_CONTROL))
                limitProblem(command)?.let { return reply(error(BridgeError.LIMIT, it)) }
                if (command.monitors.isEmpty()) return reply(error(BridgeError.BAD_REQUEST, "perf.start: name at least one monitor"))
                unavailable(controller, command.monitors)?.let { return reply(error(BridgeError.UNSUPPORTED, it)) }
                val request = PerfStartRequest(command.monitors, command.hangMs, command.intervalMs)
                reply(perfResult(controller.start(request, PerfOwner.Lease(command.ttlSec))))
            }
            is BridgeCommand.PerfStop -> {
                if (!controller.remoteControl) return reply(error(BridgeError.UNSUPPORTED, NO_REMOTE_CONTROL))
                reply(PerfJson.statusLine(controller.stop(command.monitors)))
            }
            BridgeCommand.PerfStatus -> reply(PerfJson.statusLine(controller.status()))
            is BridgeCommand.PerfSnapshot -> {
                if (command.points !in 1..PerfLimits.SERIES_POINTS.toLong()) {
                    return reply(error(BridgeError.LIMIT, "points: 1..${PerfLimits.SERIES_POINTS}"))
                }
                val unknown = command.series.filter { it !in PerfData.SERIES }
                if (unknown.isNotEmpty()) return reply(error(BridgeError.BAD_REQUEST, "unknown series $unknown"))
                snapshot(controller, command)
            }
            is BridgeCommand.PerfEvent -> {
                val event = controller.data.event(command.seq)
                reply(if (event == null) error(BridgeError.NOT_FOUND, "no perf event ${command.seq}") else PerfJson.eventLine(event))
            }
            is BridgeCommand.PerfTail -> Unit
        }
    }

    private fun limitProblem(command: BridgeCommand.PerfStart): String? = when {
        command.hangMs != null && command.hangMs !in PerfLimits.MIN_HANG_THRESHOLD_MS..PerfLimits.MAX_HANG_THRESHOLD_MS ->
            "hangMs: ${PerfLimits.MIN_HANG_THRESHOLD_MS}..${PerfLimits.MAX_HANG_THRESHOLD_MS}"
        command.intervalMs != null && command.intervalMs !in PerfLimits.MIN_SAMPLE_INTERVAL_MS..PerfLimits.MAX_SAMPLE_INTERVAL_MS ->
            "intervalMs: ${PerfLimits.MIN_SAMPLE_INTERVAL_MS}..${PerfLimits.MAX_SAMPLE_INTERVAL_MS}"
        command.ttlSec > PerfLimits.MAX_LEASE_SEC -> "ttlSec: 0..${PerfLimits.MAX_LEASE_SEC} (0: no expiry)"
        else -> null
    }

    private fun unavailable(controller: PerfController, monitors: Set<dev.koga.hopi.core.perf.PerfMonitor>): String? {
        val missing = monitors - controller.available
        return if (missing.isEmpty()) null else "not available on ${controller.platform}: ${missing.joinToString { it.wireName }}"
    }

    private fun perfResult(result: PerfResult): String = when (result) {
        is PerfResult.Ok -> PerfJson.statusLine(result.status)
        is PerfResult.Failed -> error(if (result.code == BridgeError.LIMIT.name) BridgeError.LIMIT else BridgeError.UNSUPPORTED, result.message)
    }

    /** Series, the open second, summaries, events, threads and PSS as asked, then `end`. */
    private suspend fun snapshot(controller: PerfController, command: BridgeCommand.PerfSnapshot) {
        val data = controller.data
        // Readers close a second strictly in the past, never the current one.
        data.tick(perfWallClockMs() / 1000)
        val points = command.points.toInt()
        val series = command.series.ifEmpty { PerfData.SERIES }
        if ("frames" in series) reply(PerfJson.seriesLine("frames", PerfData.FRAME_COLUMNS, data.frames.last(points)))
        if ("cpu" in series) reply(PerfJson.seriesLine("cpu", PerfData.CPU_COLUMNS, data.cpu.last(points)))
        if ("memory" in series) reply(PerfJson.seriesLine("memory", PerfData.MEMORY_COLUMNS, data.memory.last(points)))
        data.seconds.provisional()?.let { reply(PerfJson.framesLine(it, provisional = true)) }
        reply(PerfJson.summaryLine(data.summary()))
        if (command.events) {
            val frames = if (command.fullStacks) Int.MAX_VALUE else PerfLimits.SNAPSHOT_STACK_FRAMES
            for (event in data.eventsSince(command.since)) reply(PerfJson.eventLine(event, frames))
        }
        if (command.threads) reply(PerfJson.threadsLine(controller.threads(PerfLimits.DEFAULT_TOP_THREADS)))
        if (command.pss) reply(PerfJson.pssLine(controller.measurePss()))
        reply(PerfJson.statusLine(controller.status()))
        reply(END_LINE)
    }

    /**
     * `perf.tail`: subscribes before reading the backlog, as [startTail] does, so nothing published
     * in between is missed; the writer skips a live event the backlog already showed (the same
     * instance: a hang completed later is a new one, and is sent). Monitors named in `start` are
     * held by this session and released when it ends. False when it was refused.
     */
    private suspend fun startPerfTail(command: BridgeCommand.PerfTail): Boolean {
        val controller = PerfHook.controller
        if (controller == null) {
            reply(error(BridgeError.UNSUPPORTED, NO_PERF))
            return false
        }
        if (command.start.isNotEmpty()) {
            if (!controller.remoteControl) {
                reply(error(BridgeError.UNSUPPORTED, NO_REMOTE_CONTROL))
                return false
            }
            unavailable(controller, command.start)?.let {
                reply(error(BridgeError.UNSUPPORTED, it))
                return false
            }
        }
        val session = nextPerfSession.incrementAndFetch()
        tailSince.store(command.since)
        perfTail = true
        tailing.store(true)
        val data = controller.data
        val remove = data.addListener { item -> offerPerf(item, command) }
        tailJob.invokeOnCompletion { remove() }
        if (command.start.isNotEmpty()) {
            val result = controller.start(PerfStartRequest(command.start), PerfOwner.Tail(session))
            tailJob.invokeOnCompletion { controller.releaseTail(session) }
            reply(perfResult(result))
        } else {
            reply(PerfJson.statusLine(controller.status()))
        }
        if (command.threads) {
            controller.watchThreads(true)
            tailJob.invokeOnCompletion { controller.watchThreads(false) }
        }
        if (command.fps) {
            // iOS tier 2: the display-link cadence runs while at least one viewer asks for it.
            controller.watchCadence(true)
            tailJob.invokeOnCompletion { controller.watchCadence(false) }
        }
        val shown = HashMap<Long, PerfEvent>()
        for (event in data.eventsSince(command.since)) {
            reply(PerfJson.eventLine(event))
            shown[event.seq] = event
        }
        enqueue(Outgoing.StartPerfLive(shown, if (command.threads) controller else null))
        return true
    }

    private fun offerPerf(item: PerfItem, command: BridgeCommand.PerfTail) {
        if (item is PerfSample && !command.samples && !command.threads) return
        offer(Live.Perf(item, command.samples))
    }

    /**
     * Appends one slice to a staged blob. Slices arrive in order: [BridgeCommand.BlobPut.offset]
     * must equal the bytes received so far, which proves no line was lost. Any failure discards
     * the blob, so a client never builds a rule on a blob with a hole.
     */
    private fun putBlob(command: BridgeCommand.BlobPut): String {
        val existing = blobs[command.blob]
        if (existing == null) {
            if (command.offset != 0L) return error(BridgeError.BAD_REQUEST, "blob ${command.blob}: offset ${command.offset}, expected 0")
            if (blobs.size >= RuleLimits.MAX_BLOBS_PER_SESSION) {
                return error(BridgeError.LIMIT, "blob: at most ${RuleLimits.MAX_BLOBS_PER_SESSION} blobs per session")
            }
        } else if (command.offset != existing.size.toLong()) {
            blobs.remove(command.blob)
            return error(BridgeError.BAD_REQUEST, "blob ${command.blob}: offset ${command.offset}, expected ${existing.size}; blob discarded")
        }
        val bytes = try {
            Base64.decode(command.data)
        } catch (_: IllegalArgumentException) {
            blobs.remove(command.blob)
            return error(BridgeError.BAD_REQUEST, "blob ${command.blob}: data is not base64; blob discarded")
        }
        val blob = existing ?: Blob()
        if (blob.size + bytes.size > RuleLimits.MAX_BODY_BYTES) {
            blobs.remove(command.blob)
            return error(BridgeError.LIMIT, "blob ${command.blob}: larger than ${RuleLimits.MAX_BODY_BYTES} bytes; blob discarded")
        }
        blob.append(bytes)
        blobs[command.blob] = blob
        return JsonWriter().beginObject().field("type", "ok").field("blob", command.blob).field("size", blob.size).endObject().toString()
    }

    private val ruleContext = object : RuleJsonContext {
        override fun blob(id: Long): ByteArray? = blobs[id]?.bytes()

        override fun transaction(id: Long): HttpTransaction? = store.get(id)
    }

    private fun ruleError(error: RuleError): String = error(
        when (error.code) {
            RuleErrorCode.BAD_REQUEST -> BridgeError.BAD_REQUEST
            RuleErrorCode.LIMIT -> BridgeError.LIMIT
            RuleErrorCode.NOT_FOUND -> BridgeError.NOT_FOUND
            RuleErrorCode.UNSUPPORTED -> BridgeError.UNSUPPORTED
        },
        error.message,
    )

    /** A blob being staged: bytes appended in order, at most [RuleLimits.MAX_BODY_BYTES]. Reader thread only. */
    private class Blob {
        private var buffer = ByteArray(0)
        var size = 0
            private set

        fun append(bytes: ByteArray) {
            if (size + bytes.size > buffer.size) buffer = buffer.copyOf(maxOf(size + bytes.size, minOf(buffer.size * 2, RuleLimits.MAX_BODY_BYTES)))
            bytes.copyInto(buffer, size)
            size += bytes.size
        }

        fun bytes(): ByteArray = buffer.copyOf(size)
    }

    /**
     * Subscribes to live events before reading the backlog, so nothing published in between is
     * missed; the writer then skips every live event the backlog already showed (see [isNew]).
     */
    private suspend fun startTail(since: Long, rules: Boolean) {
        tailSince.store(since)
        tailing.store(true)
        // Only a tail that asked: an older client never sees a line type it does not know.
        if (rules) {
            val removeRules = RuleEngine.addListener { offer(Live.Rules(RuleEngine.revision.value)) }
            tailJob.invokeOnCompletion { removeRules() }
        }
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
        enqueue(Outgoing.StartLive(since, shown))
    }

    /** Runs on the store's consumer (events) or a collector (drop count): never suspends, never encodes. */
    private fun offer(item: Live) {
        val result = live.trySend(item)
        if (result.isSuccess) {
            writerSignal.wake()
            return
        }
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
        writerSignal.wake()
    }

    private fun writeLoop() {
        try {
            writeAll()
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

    /** Writes the replies in order until the live stream starts or the queue closes. Writer thread only. */
    private fun writeAll() {
        while (true) {
            val next = replies.tryReceive()
            val item = next.getOrNull()
            if (item == null) {
                if (next.isClosed) return
                writerSignal.await()
                continue
            }
            when (item) {
                is Outgoing.Line -> send(item.text)
                is Outgoing.Detail -> sendDetail(item.transaction)
                is Outgoing.StartLive -> {
                    streamLive(item.since, item.shown)
                    return
                }
                is Outgoing.StartPerfLive -> {
                    streamPerfLive(item.shown, item.threads)
                    return
                }
            }
        }
    }

    private fun streamLive(since: Long, shown: HashMap<Long, HttpTransaction>) {
        val pending = HashSet<Long>()
        var highest = since
        for ((id, transaction) in shown) {
            if (!transaction.state.isTerminal) pending += id
            if (id > highest) highest = id
        }
        highestSent = highest
        sentPending = pending
        while (true) {
            val next = live.tryReceive()
            val item = next.getOrNull()
            if (item == null) {
                if (next.isClosed) return
                writerSignal.await()
                continue
            }
            when (item) {
                is Live.Dropped -> send(droppedNotice(item.total))
                is Live.Rules -> send(rulesNotice(item.revision))
                is Live.Perf -> Unit
                is Live.Event -> if (isNew(item.transaction, since, shown)) {
                    liveLine.setLength(0)
                    TransactionJson.appendBridgeSummary(item.transaction, store.redactor, liveLine)
                    send(liveLine)
                    val id = item.transaction.id
                    if (item.transaction.state.isTerminal) pending -= id else pending += id
                    if (id > highestSent) highestSent = id
                }
            }
        }
    }

    /** The live perf stream. Writer thread only. */
    private fun streamPerfLive(shown: HashMap<Long, PerfEvent>, threads: PerfController?) {
        var highest = tailSince.load()
        for ((seq, event) in shown) {
            if (seq > highest) highest = seq
            if (event is HangEvent && !event.ended) ongoingHangsSent += seq
        }
        highestPerfSent = highest
        while (true) {
            val next = live.tryReceive()
            val item = next.getOrNull()
            if (item == null) {
                if (next.isClosed) return
                writerSignal.await()
                continue
            }
            if (item !is Live.Perf) continue
            when (val perf = item.item) {
                is PerfEvent -> {
                    // The backlog's own instance: already sent. A newer version (a hang's end) is not.
                    if (shown[perf.seq] === perf) continue
                    // A hang is updated in place under its seq (later stacks, its end): a client
                    // that resumed from a seq at or above it still needs those. Any other event at
                    // or below `since` is one the client has.
                    if (perf.seq <= tailSince.load() && perf !is HangEvent) continue
                    send(PerfJson.eventLine(perf))
                    if (perf.seq > highestPerfSent) highestPerfSent = perf.seq
                    if (perf is HangEvent) {
                        if (perf.ended) ongoingHangsSent -= perf.seq else ongoingHangsSent += perf.seq
                    }
                }
                is dev.koga.hopi.core.perf.FrameSecond -> send(PerfJson.framesLine(perf))
                is PerfSample -> {
                    if (item.samples) send(PerfJson.sampleLine(perf))
                    // The per-thread list rides the sampler's tick, scanned there while watched.
                    if (threads != null) send(PerfJson.threadsLine(threads.threads(PerfLimits.DEFAULT_TOP_THREADS)))
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
        if (perfTail) {
            // Below a hang it was sent as ongoing: its end may be among what was dropped.
            val sent = maxOf(highestPerfSent, tailSince.load())
            val ongoing = ongoingHangsSent.minOrNull() ?: return sent
            return minOf(sent, ongoing - 1)
        }
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

    // Writer thread only: each live summary line is built here, then encoded straight from it.
    private val liveLine = StringBuilder(512)

    private fun send(line: CharSequence) {
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

    private fun writeRaw(line: CharSequence) = writeText(line, newline = true)

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
        enqueue(Outgoing.Line(line))
    }

    private suspend fun enqueue(item: Outgoing) {
        replies.send(item)
        writerSignal.wake()
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
        // Whether the rule commands work here; absent on an older app, which a host reads as false.
        .field("rules", RuleEngine.rulesAvailable)
        // Whether the perf commands work here, and may start and stop monitors (docs/design/perf-monitors.md).
        .also { writer ->
            val perf = PerfHook.controller
            writer.field("perf", perf != null)
            writer.field("perfControl", perf?.remoteControl == true)
        }
        .also { writer -> info.proof?.let { writer.field("proof", it) } }
        .endObject()
        .toString()

    private sealed interface Outgoing {
        class Line(val text: String) : Outgoing

        /** A `get` reply, encoded only when written. */
        class Detail(val transaction: HttpTransaction) : Outgoing

        /** Everything before it was backlog; the writer switches to the live queue. */
        class StartLive(val since: Long, val shown: HashMap<Long, HttpTransaction>) : Outgoing

        /** The same for `perf.tail`; [threads]: send the per-thread list with each sample. */
        class StartPerfLive(val shown: HashMap<Long, PerfEvent>, val threads: PerfController?) : Outgoing
    }

    private sealed interface Live {
        /** [transaction] is a [liveSummary] copy: no body bytes, no headers. */
        class Event(val transaction: HttpTransaction) : Live

        class Dropped(val total: Long) : Live

        /** The rule set changed; [revision] is the engine's. */
        class Rules(val revision: Long) : Live

        /** A perf item for a `perf.tail`; [samples]: the client asked for sample lines. */
        class Perf(val item: PerfItem, val samples: Boolean) : Live
    }

    private object StopWriting : RuntimeException()

    public companion object {
        public const val PROTOCOL_VERSION: Int = 1

        private val nextPerfSession = AtomicLong(0)
        private const val NO_PERF = "perf monitors are absent from this app (no hopi-perf in this build, or perf left out of Hopi's start)"
        private const val NO_REMOTE_CONTROL =
            "perf monitors are not remotely controllable in this app: remote control is off in its Hopi configuration " +
                "(perf remoteControl, or perfRemoteControl on iOS, is false)"

        /** Live events a client may lag behind, beyond one store batch, before it is disconnected. */
        public const val DEFAULT_LIVE_LAG: Int = 512
        public const val DEFAULT_REPLY_CAPACITY: Int = 64

        /**
         * The most a new connection waits for the store to apply what waited before serving it:
         * a pass takes well under a millisecond; only a flood of queued records takes longer, and
         * then the client is served what is applied by this time.
         */
        internal const val ATTACH_APPLY_TIMEOUT_MS: Long = 250

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

        private const val IDLE = 0
        private const val WRITING = 1
        private const val FINAL = 2

        private val END_LINE = JsonWriter().beginObject().field("type", "end").endObject().toString()
        private val OK_LINE = JsonWriter().beginObject().field("type", "ok").endObject().toString()

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

        /** `{"type":"rules","rev":N}`: sent to a `tail` that asked (`"rules":true`) whenever the rule set changes. */
        public fun rulesNotice(revision: Long): String = JsonWriter().beginObject()
            .field("type", "rules")
            .field("rev", revision)
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
