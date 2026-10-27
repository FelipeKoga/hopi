package dev.koga.hopi.core.store

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.concurrent.BlockingSemaphore
import dev.koga.hopi.core.concurrent.newBlockingSemaphore
import dev.koga.hopi.core.concurrent.startBackgroundThread
import dev.koga.hopi.core.concurrent.WakeSignal
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionEvent
import dev.koga.hopi.core.redact.Redactor
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Ring buffer of transactions, ordered by id, bounded by [maxEntries] and by [maxTotalBytes] of
 * captured body bytes (oldest evicted first, strictly within both caps, except that the entry
 * just written is evicted last). One entry keeps at most three quarters of [maxTotalBytes].
 *
 * Writers call [submit] or [submitDeferred], which never suspend, and a single consumer applies
 * the writes: a thread of the store's own (`hopi-store`) that blocks with no timer while nothing is
 * queued, or, when a [scope] is given (tests, a host viewer that wants it), a coroutine in it.
 * Pending snapshots go through a bounded channel that drops the oldest under flood (harmless: the
 * terminal snapshot carries everything). Terminal snapshots go through their own, larger bounded
 * channel that drops nothing silently: a terminal write that does not fit is rejected and counted
 * in [droppedTerminalWrites]. While a viewer is attached ([attachViewer]) every write wakes the
 * consumer, so a request shows the moment it starts and the moment it ends. While none is, a
 * pending write wakes nothing, and a terminal one wakes the consumer only when it sleeps with no
 * timer: after a pass the consumer dozes for [batchWindowMillis], and the terminal writes that
 * arrive meanwhile only mark the work (no system call on the request thread), applied together
 * when the doze ends; a doze with nothing marked ends in the untimed sleep. So with nobody watching,
 * a burst of requests costs its threads one wake in all, and a record shows at most
 * [batchWindowMillis] after its end, unless someone calls [awaitApplied] (a bridge client as it
 * connects, the process as it dies), which applies what waited before it returns. Reads ([transactions], [get], [since]) see the last published
 * snapshot and take no lock. A published snapshot shares all but the changed chunks of entries
 * with the one before it, so an update costs the same whether the store holds 5 entries or 500.
 * Terminal writes queued and not yet applied hold at most [maxTotalBytes] of bodies between them
 * besides those the store holds (or one write alone): a consumer that falls behind cannot make a
 * backlog of captures grow the heap.
 */
@HopiInternalApi
public class HopiStore(
    public val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    public val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    scope: CoroutineScope? = null,
    channelCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
    eventBufferCapacity: Int = DEFAULT_EVENT_BUFFER_CAPACITY,
    terminalChannelCapacity: Int = DEFAULT_TERMINAL_CHANNEL_CAPACITY,
    /**
     * Thread mode: how long the consumer dozes after a pass, while terminal writes with no viewer
     * attached only mark the work; 0 wakes it for every terminal write, as before 2026-10-25.
     */
    public val batchWindowMillis: Long = DEFAULT_BATCH_WINDOW_MS,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
        require(channelCapacity > 0) { "channelCapacity must be positive" }
        require(terminalChannelCapacity > 0) { "terminalChannelCapacity must be positive" }
        require(batchWindowMillis >= 0) { "batchWindowMillis must not be negative" }
    }

    private val lastId = AtomicLong(0)
    private val clearedThrough = AtomicLong(0)

    /** Pending writes: an [HttpTransaction] or a [DeferredRecord] (or [Deferred]) that builds one. */
    private val writes = Channel<Any>(channelCapacity, BufferOverflow.DROP_OLDEST)
    private val terminalWrites = Channel<Any>(terminalChannelCapacity)

    /**
     * What wakes the consumer after a write, a [clear] or a lost terminal write: the thread's
     * signal, or the coroutine's conflated doorbell. A producer that finds the consumer awake
     * pays one atomic exchange (thread) or one conflated send (coroutine), nothing more.
     */
    private val signal: WakeSignal? = if (scope == null) WakeSignal() else null
    private val doorbell: Channel<Unit>? = if (scope == null) null else Channel(Channel.CONFLATED)

    @kotlin.concurrent.Volatile
    private var closed = false

    /** Thread mode: how many times the consumer slept and was woken (tests: none while idle). */
    internal val consumerWakeups: Long get() = signal?.wakeups ?: 0

    /** Thread mode: true while the consumer blocks with nothing queued, with no timer. */
    internal val consumerSleeping: Boolean get() = signal?.sleeping ?: false

    /** Thread mode: true while the consumer dozes after a pass (tests). */
    internal val consumerDozing: Boolean get() = signal?.dozing ?: false

    /** Thread mode: how many of the consumer's dozes ran their whole window (tests). */
    internal val consumerDozeTimeouts: Long get() = signal?.dozeTimeouts ?: 0

    private val windowNanos = batchWindowMillis * 1_000_000

    /**
     * Terminal writes queued without a wake since the consumer's last pass began: at [forceWakeAt]
     * a write wakes the consumer out of its doze, so a flood never waits a whole window against
     * the lane's capacity.
     */
    private val marked = AtomicInt(0)
    private val forceWakeAt = maxOf(1, terminalChannelCapacity / 2)
    private val batchLimit = channelCapacity
    private val terminalBatchLimit = terminalChannelCapacity
    private val lostTerminalWrites = AtomicLong(0)

    /**
     * Body bytes of the terminal writes queued and not yet released by a pass ([heldBytes]): what
     * a consumer that fell behind keeps alive outside [maxTotalBytes]. An end that would take it
     * past [maxTotalBytes] while another is queued goes in without its bodies ([SKIPPED_STORE_BACKLOG]),
     * and an end that takes it to half of [maxTotalBytes] wakes the consumer out of its doze, as
     * half the lane does. One atomic add per end that holds bodies; the consumer takes a pass's
     * bytes off once the pass is published, so the events it holds until then are counted too.
     */
    private val queuedBytes = AtomicLong(0)
    private val wakeAtQueuedBytes = maxOf(1L, maxTotalBytes / 2)

    /**
     * Thread mode: callers of [awaitApplied] not yet taken by a pass. The consumer takes them before
     * a pass and releases [applied] once for each when the lanes are drained, so a caller returns
     * after a pass that began after its call. Read by the consumer only between passes.
     */
    private val applyWaiters = AtomicInt(0)
    private val applied: BlockingSemaphore? = if (scope == null) newBlockingSemaphore() else null

    /** How many viewers are attached ([attachViewer]): while none is, a pending write wakes nothing. */
    private val viewers = AtomicInt(0)

    /** How many viewers are attached (tests). */
    internal val attachedViewers: Int get() = viewers.load()

    /**
     * The most body bytes one entry keeps: three quarters of [maxTotalBytes], so at least a
     * quarter stays for older entries, while a body at the plugin's largest cap (half the store)
     * keeps a request or response of its own. Read by the consumer, so declared before it starts.
     */
    private val maxEntryBytes: Long = maxOf(1L, maxTotalBytes - maxTotalBytes / 4)

    /**
     * The most events one consumer pass can publish at once (its first write plus a full drain of
     * both lanes). Event listeners receive them in one synchronous burst, so a queue that a
     * listener fills must hold at least this many to tell a burst from a reader that stopped.
     */
    public val maxBatchSize: Int = 1 + channelCapacity + terminalChannelCapacity
    private val _droppedTerminalWrites = MutableStateFlow(0L)

    private val _transactions = MutableStateFlow<List<HttpTransaction>>(emptyList())
    private val _events = MutableSharedFlow<TransactionEvent>(
        extraBufferCapacity = eventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Newest last. */
    public val transactions: StateFlow<List<HttpTransaction>> = _transactions.asStateFlow()

    /**
     * Emitted after the snapshot containing the change is published, also for writes the store did
     * not retain (cleared or evicted). A pending snapshot that arrives after its transaction's
     * terminal one is ignored and emits nothing, and so is every later snapshot of a [withdraw]n
     * record. Slow collectors lose the oldest.
     */
    public val events: SharedFlow<TransactionEvent> = _events.asSharedFlow()

    /**
     * Terminal snapshots rejected because their channel was full, since the store was created.
     * Each one is a transaction that never completes in this store and never gets a log line.
     */
    public val droppedTerminalWrites: StateFlow<Long> = _droppedTerminalWrites.asStateFlow()

    private val listeners = AtomicReference<List<(TransactionEvent) -> Unit>>(emptyList())

    /**
     * Calls [listener] on the consumer for every event published from now on, in order and without
     * loss (unlike [events], whose slow collectors lose the oldest), each after the snapshot
     * containing it is published. [listener] must return at once: it runs on the store's consumer
     * and must never block, suspend or throw. Returns the function that removes it.
     */
    public fun addEventListener(listener: (TransactionEvent) -> Unit): () -> Unit {
        updateListeners { it + listener }
        return { updateListeners { current -> current.filterNot { it === listener } } }
    }

    private inline fun updateListeners(change: (List<(TransactionEvent) -> Unit>) -> List<(TransactionEvent) -> Unit>) {
        while (true) {
            val current = listeners.load()
            if (listeners.compareAndSet(current, change(current))) return
        }
    }

    private val outwardRedaction = AtomicReference<Redactor?>(null)

    /**
     * What every outward channel (UI copy, cURL, log sink, bridge) applies to this store's
     * transactions: the union of every [includeRedaction] call, [Redactor.Default] before the first.
     */
    public val redactor: Redactor get() = outwardRedaction.load() ?: Redactor.Default

    private val _redactors = MutableStateFlow(Redactor.Default)

    /**
     * [redactor] as a flow: it emits each time [includeRedaction] grows it, as a recorder installs (a
     * client's plugin, URLSession capture's start, Android capture's first recorded request on that
     * request's thread, once per process), so a screen showing a record redacts it again at once.
     */
    public val redactors: StateFlow<Redactor> = _redactors.asStateFlow()

    public fun includeRedaction(redactor: Redactor) {
        while (true) {
            val current = outwardRedaction.load()
            val next = current?.union(redactor) ?: redactor
            if (next === current) return
            if (outwardRedaction.compareAndSet(current, next)) break
        }
        // The latest, not this call's: a concurrent call may have grown it again since, and the
        // last one to publish must not leave an older redactor in the flow.
        while (true) {
            val latest = this.redactor
            _redactors.value = latest
            if (this.redactor === latest) return
        }
    }

    private val buffer = ChunkedTransactions()
    private var bufferBytes = 0L
    private var appliedClearedThrough = 0L
    /**
     * The highest id evicted so far. An id at or below it that is not in [buffer] was evicted (a
     * stale pending write for it is ignored); one that is in [buffer] stays, since the entry just
     * written is evicted last and can outlive newer ones.
     */
    private var evictedThrough = 0L

    // Consumer only (declared before it starts): the events of the current pass.
    private val pendingEvents = ArrayList<TransactionEvent>()

    // Consumer only: what a pass drained, so the loop knows whether a lane may hold more.
    private var drainedToLimit = false

    // Consumer only: the [queuedBytes] of the terminal writes the current pass took.
    private var passBytes = 0L

    // Consumer only: ids of [withdraw]n records, whose late snapshots are ignored. One per request
    // that was in flight while a platform recorder started, so a few per process.
    private val withdrawnIds = HashSet<Long>()

    // Everything the consumer reads is declared above: it may start before the constructor returns.
    private val consumer: Job? = scope?.launch { consumeOnDoorbell() }

    init {
        if (scope == null) startBackgroundThread("hopi-store") { consumeOnThread() }
    }

    /** Allocates the next transaction id: monotonic, starting at 1. */
    public fun nextId(): Long = lastId.incrementAndFetch()

    /**
     * Queues [transaction] to insert or replace the entry with its id. Never suspends. Returns
     * false when the store is closed, or when a terminal snapshot is rejected and counted in
     * [droppedTerminalWrites]. A terminal snapshot queued behind ends that already hold
     * [maxTotalBytes] of bodies is kept without its bodies' bytes ([SKIPPED_STORE_BACKLOG]), but for
     * a request body it shares with its start (an end built on its start), which the record keeps
     * whenever that start reached the store.
     */
    public fun submit(transaction: HttpTransaction): Boolean = enqueue(transaction, transaction.state.isTerminal)

    /**
     * [submit] for a snapshot that [build] makes on the store's consumer, so the producer's thread
     * (an app's request thread) only queues it: no record building and no hand-off to a thread of
     * its own first. [terminal] says which lane, so what [build] returns must agree with it. [build]
     * must return at once and must not block; a throw drops the write. A recorder submits a
     * request's start before its end, so the start is built first.
     */
    public fun submitDeferred(terminal: Boolean, build: () -> HttpTransaction): Boolean = enqueue(Deferred(build), terminal)

    /**
     * Queues [record]'s start: its [DeferredRecord.pendingSnapshot], built on the consumer. Like
     * [submitDeferred], with no allocation: the record object itself is what is queued.
     */
    public fun submitStart(record: DeferredRecord): Boolean = enqueue(record, terminal = false)

    /**
     * Queues [record]'s end: its [DeferredRecord.terminalSnapshot], built on the consumer. Submitted
     * once per record, after [submitStart], with every field it reads written before this call.
     */
    public fun submitEnd(record: DeferredRecord): Boolean = enqueue(record, terminal = true)

    /**
     * Withdraws a record that another recorder may also have made (the Ktor plugin's, when a
     * platform recorder started while the request was in the engine): the store drops the entry
     * and ignores every later snapshot of its id, so the request is never listed twice, at the cost
     * of maybe missing it. Queued like an end, after the record's start, and never suspends; false
     * when the store is closed or the lane is full (counted in [droppedTerminalWrites]). [last] is
     * the record's final snapshot, failed with an error that says why. Nothing is published for it,
     * except when a pass before this one published the start and a viewer is attached: a viewer or
     * a bridge tail may show that start, so [last] ends it there ([TransactionEvent.Completed],
     * not retained, like any write the store does not keep).
     */
    public fun withdraw(last: HttpTransaction): Boolean = enqueue(Withdrawal(last), terminal = true)

    private fun enqueue(write: Any, terminal: Boolean): Boolean {
        if (!terminal) {
            val sent = writes.trySend(write).isSuccess
            // Nobody shows a request in flight: its start waits in the lane for the next wake (its
            // own end, at the latest), and the request thread pays no second wake.
            if (sent && viewers.load() > 0) wake()
            return sent
        }
        var queuedWrite = if (write is HttpTransaction) onStoredStart(write) else write
        var held = heldBytes(queuedWrite)
        var queued = 0L
        if (held > 0) {
            queued = queuedBytes.addAndFetch(held)
            if (queued > maxTotalBytes && queued > held) {
                // The consumer is a whole store of bodies behind: this end goes in with its sizes but
                // without its bytes, as a body over the entry cap does, rather than not at all.
                queuedBytes.addAndFetch(-held)
                queuedWrite = withoutBodies(queuedWrite)
                held = 0
            }
        }
        val result = terminalWrites.trySend(queuedWrite)
        if (result.isSuccess) {
            // Nobody watching: the end waits for the doze to end, or wakes a consumer asleep with no timer.
            if (viewers.load() > 0 || queued >= wakeAtQueuedBytes || marked.incrementAndFetch() >= forceWakeAt) wake() else mark()
            return true
        }
        if (held > 0) queuedBytes.addAndFetch(-held)
        if (result.isClosed) return false
        lostTerminalWrites.incrementAndFetch()
        // The consumer publishes the count on its next pass.
        wake()
        return false
    }

    /**
     * [end] as an [EndOnStoredStart] when its request body is the very one the store holds for its
     * pending start (an end built on its start, as the Ktor plugin's and URLSession capture's are),
     * else [end] itself. A start still in its lane is not enough: that lane drops its oldest under a
     * flood, and the end would then hold the array alone, uncounted (a pass that evicts the start
     * before the end is applied leaves it so until then: at most what that pass evicted). One
     * volatile read and a binary search, for an end whose request body has bytes.
     */
    private fun onStoredStart(end: HttpTransaction): Any {
        val request = end.requestBody
        if (request == null || request.bytes.isEmpty()) return end
        val start = get(end.id) ?: return end
        return if (!start.state.isTerminal && start.requestBody === request) EndOnStoredStart(end) else end
    }

    /**
     * Body bytes [write] keeps alive while it is queued, the same when it is queued and when it is
     * applied: an [EndOnStoredStart] counts its response only, the store counting its request's
     * array. A [submitDeferred] build counts none: nothing in Hopi queues bodies that way.
     */
    private fun heldBytes(write: Any): Long = when (write) {
        is HttpTransaction -> write.capturedBytes
        is EndOnStoredStart -> (write.end.responseBody?.capturedBytes ?: 0).toLong()
        is DeferredRecord -> write.heldBytes()
        else -> 0L
    }

    /** [write] with each body that holds bytes reduced to its size, on the producer's thread before it is queued. */
    private fun withoutBodies(write: Any): Any = when (write) {
        is HttpTransaction -> write.copy(requestBody = write.requestBody?.backlogOverByteCap(), responseBody = write.responseBody?.backlogOverByteCap())
        // Its request's array stays with the store's start whatever becomes of the end: only the response goes.
        is EndOnStoredStart -> EndOnStoredStart(write.end.copy(responseBody = write.end.responseBody?.backlogOverByteCap()))
        is DeferredRecord -> {
            write.shedBodies()
            write
        }
        else -> write
    }

    private fun CapturedBody.backlogOverByteCap(): CapturedBody =
        if (bytes.isEmpty()) this else CapturedBody.skipped(SKIPPED_STORE_BACKLOG, contentType, totalBytes ?: capturedBytes.toLong())

    private fun wake() {
        signal?.wake() ?: doorbell?.trySend(Unit)
    }

    private fun mark() {
        signal?.mark() ?: doorbell?.trySend(Unit)
    }

    /**
     * Attaches a viewer of requests in flight (a bridge connection, the UI on screen): while at
     * least one is attached, every pending write wakes the consumer, so a request shows as pending
     * the moment it starts; while none is, pending writes wait in their lane and the next wake (a
     * terminal write, a clear, an attach) applies them, so a request's start costs its thread no
     * wake. Attaching wakes the consumer at once, which applies what waited on its next pass
     * ([awaitApplied] waits for that pass). With nobody attached, more
     * than the pending lane's capacity of requests started and not ended loses the oldest starts
     * (as under any flood): those requests appear when they end. Returns the function that
     * detaches the viewer; calling it again does nothing.
     */
    public fun attachViewer(): () -> Unit {
        viewers.incrementAndFetch()
        wake()
        val attached = AtomicBoolean(true)
        return { if (attached.compareAndSet(true, false)) viewers.decrementAndFetch() }
    }

    /**
     * Thread mode: wakes the consumer, also out of its doze, and waits at most [timeoutMillis] for a
     * pass that began after this call to drain the lanes, its events and listeners (the summary log
     * line) included. So what was queued before the call, the ends that waited in the doze among it,
     * is in [transactions] and logged when this returns true. False on timeout, when called after
     * [close], and in coroutine mode (whose consumer is never in a doze), where it returns at once; a
     * caller still waiting when the store closes is released with true. A caller that times out while
     * another waits can hand that one its permit (it may then return after an earlier pass): only
     * under a flood that outlasts the timeout, and the guarantee then is the timeout's. Never called
     * on a request's path: a bridge client calls it as it connects, the process as it dies. Called on
     * the consumer itself (a listener), it waits out [timeoutMillis] and returns false.
     */
    public fun awaitApplied(timeoutMillis: Long): Boolean {
        val done = applied ?: return false
        if (closed) return false
        applyWaiters.incrementAndFetch()
        wake()
        val nanos = timeoutMillis * 1_000_000
        if (done.tryAcquire(nanos)) return true
        while (true) {
            val waiting = applyWaiters.load()
            if (waiting > 0) {
                // Not taken by a pass yet: withdrawn, so no permit is released for this call.
                if (applyWaiters.compareAndSet(waiting, waiting - 1)) return false
            } else {
                // A pass took it and releases its permit as it ends: take that one, not a later caller's.
                return done.tryAcquire(nanos)
            }
        }
    }

    private fun releaseAppliedWaiters(count: Int) {
        val done = applied ?: return
        repeat(count) { done.release() }
    }

    private class Deferred(val build: () -> HttpTransaction)

    private class Withdrawal(val last: HttpTransaction)

    /** A terminal snapshot whose request body is the one the store held for its start when it was queued ([onStoredStart]). */
    private class EndOnStoredStart(val end: HttpTransaction)

    /**
     * A record whose snapshots the consumer builds ([submitStart], [submitEnd]): the request thread
     * queues the object itself, so a hand-off allocates nothing. Both snapshot functions run on the
     * consumer, must return at once and must not block; a throw drops that write.
     */
    @HopiInternalApi
    public interface DeferredRecord {
        public fun pendingSnapshot(): HttpTransaction

        /** Must return a terminal snapshot. */
        public fun terminalSnapshot(): HttpTransaction

        /**
         * The bytes of the body arrays this record keeps for its end: what [submitEnd] counts as
         * queued until the store applied the end. Read on the thread that submits the end and again
         * on the consumer, so it must not change once [submitEnd] is called; must return at once and
         * never throw.
         */
        public fun heldBytes(): Long

        /**
         * Lets go of the arrays [heldBytes] counts, which then returns 0: the store is behind by a
         * whole store of queued bodies. Called on the thread that submits the end, before it is
         * queued; [terminalSnapshot] then records each of those bodies skipped with
         * [SKIPPED_STORE_BACKLOG] and the size it had.
         */
        public fun shedBodies()
    }

    /**
     * Removes every transaction whose id was allocated before this call, including pending ones.
     * Returns the last id it covers.
     */
    public fun clear(): Long = clear(lastId.load())

    /**
     * Removes every transaction with an id up to [through], including pending ones, and ignores
     * later snapshots of them. A host viewer passes the id the app's own clear covered (the bridge
     * `clear` reply's `through`): a transaction its tail added since, with a higher id, stays.
     */
    public fun clear(through: Long): Long {
        while (true) {
            val current = clearedThrough.load()
            if (current >= through || clearedThrough.compareAndSet(current, through)) break
        }
        wake()
        return through
    }

    /** The retained transaction with [id]; null once a [clear] covered it, even before the consumer applied it. */
    public fun get(id: Long): HttpTransaction? {
        if (id <= clearedThrough.load()) return null
        val list = _transactions.value
        val index = list.binarySearchById(id)
        return if (index >= 0) list[index] else null
    }

    /**
     * Transactions with an id greater than [id], oldest first, at most [limit]. Like [get], it
     * honors a [clear] at once, so a reader never sees a transaction after clearing it.
     */
    public fun since(id: Long, limit: Int = Int.MAX_VALUE): List<HttpTransaction> {
        val list = _transactions.value
        val found = list.binarySearchById(maxOf(id, clearedThrough.load()))
        val from = if (found >= 0) found + 1 else -(found + 1)
        val to = if (list.size - from > limit) from + limit else list.size
        return if (from >= to) emptyList() else list.subList(from, to)
    }

    /** Stops the consumer. Later submissions are rejected. */
    public fun close() {
        closed = true
        writes.close()
        terminalWrites.close()
        doorbell?.close()
        signal?.wake()
        consumer?.cancel()
    }

    private fun consumeOnThread() {
        val signal = signal ?: return
        var waiters = 0
        try {
            while (true) {
                signal.await()
                do {
                    if (closed) return
                    // Callers of awaitApplied so far; one who calls during the pass is taken by the next.
                    waiters = takeAppliedWaiters()
                    while (pass()) {
                        if (closed) return
                        waiters += takeAppliedWaiters()
                    }
                    releaseAppliedWaiters(waiters)
                    waiters = 0
                    // A doze with nothing marked (or a window of 0) ends here, in the untimed sleep.
                } while (windowNanos > 0 && signal.doze(windowNanos))
            }
        } finally {
            // Closed (or failed): nobody waits for a pass that will not come.
            releaseAppliedWaiters(waiters + applyWaiters.exchange(0))
        }
    }

    private fun takeAppliedWaiters(): Int = if (applyWaiters.load() == 0) 0 else applyWaiters.exchange(0)

    private suspend fun consumeOnDoorbell() {
        val doorbell = doorbell ?: return
        for (ignored in doorbell) {
            @Suppress("ControlFlowWithEmptyBody")
            while (pass()) {
            }
        }
    }

    /**
     * Applies what the lanes hold, at most one batch of each, then publishes the snapshot and the
     * events. Returns true when a lane held a full batch, so more may be queued without a wake.
     */
    private fun pass(): Boolean {
        drainedToLimit = false
        marked.store(0)
        var dirty = applyClear()
        // Pending snapshots first: a terminal snapshot queued later must not be overtaken.
        dirty = drain(writes, batchLimit, terminal = false) || dirty
        dirty = drain(terminalWrites, terminalBatchLimit, terminal = true) || dirty
        if (dirty) _transactions.value = buffer.snapshot()
        if (pendingEvents.isNotEmpty()) {
            val subscribed = listeners.load()
            for (event in pendingEvents) {
                _events.tryEmit(event)
                for (listener in subscribed) listener(event)
            }
            pendingEvents.clear()
        }
        // The pass's bodies are in the buffer now, or evicted and held by no queue: released.
        if (passBytes != 0L) {
            queuedBytes.addAndFetch(-passBytes)
            passBytes = 0
        }
        _droppedTerminalWrites.value = lostTerminalWrites.load()
        return drainedToLimit
    }

    private fun drain(lane: Channel<Any>, limit: Int, terminal: Boolean): Boolean {
        var dirty = false
        repeat(limit) {
            val next = lane.tryReceive().getOrNull() ?: return dirty
            dirty = apply(next, terminal) || dirty
        }
        drainedToLimit = true
        return dirty
    }

    /** Returns true when [buffer] changed. */
    private fun apply(queued: Any, terminalLane: Boolean): Boolean {
        val changed = applyClear()
        if (queued is Withdrawal) return withdrawNow(queued.last) || changed
        val write = try {
            // What the producer counted is released with this pass, whatever becomes of the write.
            if (terminalLane) passBytes += heldBytes(queued)
            when (queued) {
                is HttpTransaction -> queued
                is EndOnStoredStart -> queued.end
                is DeferredRecord -> if (terminalLane) queued.terminalSnapshot() else queued.pendingSnapshot()
                is Deferred -> queued.build()
                else -> return changed
            }
        } catch (_: Throwable) {
            // A record lost to a bug in its recorder; the store and the app go on.
            return changed
        }
        val events = pendingEvents
        val existing = buffer.binarySearchById(write.id)
        if (!write.state.isTerminal) {
            // The lanes can deliver a pending snapshot after its terminal one, which was kept or evicted.
            val stale = if (existing >= 0) buffer[existing].state.isTerminal else write.id <= evictedThrough
            if (stale) return changed
        }
        // A withdrawn record's late start (its lane can deliver it after the withdrawal) brings nothing back.
        if (withdrawnIds.isNotEmpty() && write.id in withdrawnIds) return changed
        val transaction = fitToByteCap(withStartRequestBody(write, existing))
        events += when {
            transaction.state.isTerminal -> TransactionEvent.Completed(transaction)
            existing >= 0 -> TransactionEvent.Updated(transaction)
            else -> TransactionEvent.Started(transaction)
        }
        if (transaction.id <= appliedClearedThrough) return changed
        if (existing >= 0) {
            bufferBytes += transaction.capturedBytes - buffer[existing].capturedBytes
            buffer[existing] = transaction
        } else {
            bufferBytes += transaction.capturedBytes
            val insertAt = -(existing + 1)
            if (insertAt == buffer.size) buffer.addLast(transaction) else buffer.add(insertAt, transaction)
        }
        // Oldest first, but never the entry just written while another is left: a long download
        // that started first (lowest id) and completes large would otherwise be evicted the
        // moment it completes, keeping the newer, smaller entries instead. It fits on its own
        // (fitToByteCap), so the loop ends.
        while (buffer.size > maxEntries || (bufferBytes > maxTotalBytes && buffer.isNotEmpty())) {
            val index = if (buffer.size > 1 && buffer.first().id == transaction.id) 1 else 0
            val evicted = buffer.removeAt(index)
            bufferBytes -= evicted.capturedBytes
            if (evicted.id > evictedThrough) evictedThrough = evicted.id
        }
        return true
    }

    /**
     * [write] with the request body of its pending start at [existing] when the backlog took its
     * own ([SKIPPED_STORE_BACKLOG]) and the start holds that body, as its size says: an end built on
     * its start carries the start's array, so letting it go freed nothing, and the record keeps it.
     */
    private fun withStartRequestBody(write: HttpTransaction, existing: Int): HttpTransaction {
        val shed = write.requestBody ?: return write
        if (existing < 0 || shed.skippedReason != SKIPPED_STORE_BACKLOG) return write
        val start = buffer[existing]
        val kept = start.requestBody
        if (start.state.isTerminal || kept == null || kept.skippedReason != null || kept.reportedBytes != shed.totalBytes) return write
        return write.copy(requestBody = kept)
    }

    /**
     * Applies a [withdraw]: the entry goes, and so do this pass's events about it, which nobody has
     * seen yet. A start published by an earlier pass may be on a viewer's screen or in a tail's
     * stream, so while a viewer is attached [last] ends it there. Returns true when [buffer] changed.
     */
    private fun withdrawNow(last: HttpTransaction): Boolean {
        val id = last.id
        withdrawnIds += id
        val events = pendingEvents
        var startedInThisPass = false
        for (i in events.indices.reversed()) {
            val event = events[i]
            if (event.transaction.id != id) continue
            if (event is TransactionEvent.Started) startedInThisPass = true
            events.removeAt(i)
        }
        val existing = buffer.binarySearchById(id)
        if (existing < 0) return false
        bufferBytes -= buffer.removeAt(existing).capturedBytes
        if (!startedInThisPass && viewers.load() > 0) events += TransactionEvent.Completed(last)
        return true
    }

    /**
     * [transaction] with its larger body (the request's on a tie: the response is what is read
     * most), then if still needed the other, replaced by a skipped body when together they exceed
     * three quarters of [maxTotalBytes]. So one entry never evicts the whole history: at least a
     * quarter of the store stays for older entries.
     */
    private fun fitToByteCap(transaction: HttpTransaction): HttpTransaction {
        val entryCap = maxEntryBytes
        if (transaction.capturedBytes <= entryCap) return transaction
        var request = transaction.requestBody
        var response = transaction.responseBody
        val requestSize = request?.capturedBytes ?: 0
        val responseSize = response?.capturedBytes ?: 0
        if (responseSize > requestSize) {
            response = response?.overByteCap()
            if (requestSize > entryCap) request = request?.overByteCap()
        } else {
            request = request?.overByteCap()
            if (responseSize > entryCap) response = response?.overByteCap()
        }
        return transaction.copy(requestBody = request, responseBody = response)
    }

    private fun CapturedBody.overByteCap(): CapturedBody =
        CapturedBody.skipped(SKIPPED_OVER_STORE_CAP, contentType, totalBytes ?: capturedBytes.toLong())

    private fun applyClear(): Boolean {
        val through = clearedThrough.load()
        if (through <= appliedClearedThrough) return false
        appliedClearedThrough = through
        var removed = false
        while (buffer.isNotEmpty() && buffer.first().id <= through) {
            bufferBytes -= buffer.removeFirst().capturedBytes
            removed = true
        }
        return removed
    }

    public companion object {
        public const val DEFAULT_MAX_ENTRIES: Int = 500
        public const val DEFAULT_MAX_TOTAL_BYTES: Long = 16L * 1024 * 1024
        public const val DEFAULT_CHANNEL_CAPACITY: Int = 256
        public const val DEFAULT_TERMINAL_CHANNEL_CAPACITY: Int = 4096

        /** [batchWindowMillis] by default: how late a record may show while no viewer is attached. */
        public const val DEFAULT_BATCH_WINDOW_MS: Long = 50

        /** [CapturedBody.skippedReason] of a body dropped because its entry alone exceeded the store's byte cap. */
        public const val SKIPPED_OVER_STORE_CAP: String = "over store byte cap"

        /**
         * [CapturedBody.skippedReason] of a body dropped as its end was queued, because the ends
         * already queued for a consumer that fell behind held [HopiStore.maxTotalBytes] of bodies.
         */
        public const val SKIPPED_STORE_BACKLOG: String = "store backlog over byte cap"
        public const val DEFAULT_EVENT_BUFFER_CAPACITY: Int = 1024

        private val defaultStore = AtomicReference<HopiStore?>(null)

        /** The process-wide store; created with default caps unless [configureDefault] ran first. */
        public val default: HopiStore
            get() = defaultStore.load() ?: configureDefault()

        /** Creates the process-wide store with these caps if none exists yet; otherwise returns it unchanged. */
        public fun configureDefault(
            maxEntries: Int = DEFAULT_MAX_ENTRIES,
            maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
        ): HopiStore {
            defaultStore.load()?.let { return it }
            val candidate = HopiStore(maxEntries, maxTotalBytes)
            if (defaultStore.compareAndSet(null, candidate)) return candidate
            candidate.close()
            return defaultStore.load() ?: candidate
        }
    }
}

private fun List<HttpTransaction>.binarySearchById(id: Long): Int = binarySearchById(size, id) { this[it].id }

private fun ChunkedTransactions.binarySearchById(id: Long): Int = binarySearchById(size, id) { this[it].id }

private inline fun binarySearchById(size: Int, id: Long, idAt: (Int) -> Long): Int {
    var low = 0
    var high = size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val midId = idAt(mid)
        when {
            midId < id -> low = mid + 1
            midId > id -> high = mid - 1
            else -> return mid
        }
    }
    return -(low + 1)
}
