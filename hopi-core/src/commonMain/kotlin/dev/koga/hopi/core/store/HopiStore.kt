package dev.koga.hopi.core.store

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionEvent
import dev.koga.hopi.core.redact.Redactor
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Ring buffer of transactions, ordered by id, bounded by [maxEntries] and by [maxTotalBytes] of
 * captured body bytes (oldest evicted first, strictly within both caps).
 *
 * Writers call [submit], which never suspends, and a single consumer coroutine in [scope] applies
 * the writes. Pending snapshots go through a bounded channel that drops the oldest under flood
 * (harmless: the terminal snapshot carries everything). Terminal snapshots go through their own,
 * larger bounded channel that drops nothing silently: a terminal write that does not fit is
 * rejected and counted in [droppedTerminalWrites]. Reads ([transactions], [get], [since]) see the
 * last published snapshot and take no lock.
 */
@HopiInternalApi
public class HopiStore(
    public val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    public val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    channelCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
    eventBufferCapacity: Int = DEFAULT_EVENT_BUFFER_CAPACITY,
    terminalChannelCapacity: Int = DEFAULT_TERMINAL_CHANNEL_CAPACITY,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
        require(channelCapacity > 0) { "channelCapacity must be positive" }
        require(terminalChannelCapacity > 0) { "terminalChannelCapacity must be positive" }
    }

    private val lastId = AtomicLong(0)
    private val clearedThrough = AtomicLong(0)

    /** Pending snapshots, and null wake-ups that only apply a [clear] or publish the drop count. */
    private val writes = Channel<HttpTransaction?>(channelCapacity, BufferOverflow.DROP_OLDEST)
    private val terminalWrites = Channel<HttpTransaction>(terminalChannelCapacity)
    private val batchLimit = channelCapacity
    private val terminalBatchLimit = terminalChannelCapacity
    private val lostTerminalWrites = AtomicLong(0)
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
     * terminal one is ignored and emits nothing. Slow collectors lose the oldest.
     */
    public val events: SharedFlow<TransactionEvent> = _events.asSharedFlow()

    /**
     * Terminal snapshots rejected because their channel was full, since the store was created.
     * Each one is a transaction that never completes in this store and never gets a log line.
     */
    public val droppedTerminalWrites: StateFlow<Long> = _droppedTerminalWrites.asStateFlow()

    private val outwardRedaction = AtomicReference<Redactor?>(null)

    /**
     * What every outward channel (UI copy, cURL, log sink, bridge) applies to this store's
     * transactions: the union of every [includeRedaction] call, [Redactor.Default] before the first.
     */
    public val redactor: Redactor get() = outwardRedaction.load() ?: Redactor.Default

    public fun includeRedaction(redactor: Redactor) {
        while (true) {
            val current = outwardRedaction.load()
            val next = current?.union(redactor) ?: redactor
            if (next === current || outwardRedaction.compareAndSet(current, next)) return
        }
    }

    private val buffer = ArrayDeque<HttpTransaction>()
    private var bufferBytes = 0L
    private var appliedClearedThrough = 0L
    private var evictedThrough = 0L

    private val consumer: Job = scope.launch { consume() }

    /** Allocates the next transaction id: monotonic, starting at 1. */
    public fun nextId(): Long = lastId.incrementAndFetch()

    /**
     * Queues [transaction] to insert or replace the entry with its id. Never suspends. Returns
     * false when the store is closed, or when a terminal snapshot is rejected and counted in
     * [droppedTerminalWrites].
     */
    public fun submit(transaction: HttpTransaction): Boolean {
        if (!transaction.state.isTerminal) return writes.trySend(transaction).isSuccess
        val result = terminalWrites.trySend(transaction)
        if (result.isSuccess || result.isClosed) return result.isSuccess
        lostTerminalWrites.incrementAndFetch()
        // The consumer may have read the count just before this increment and gone idle.
        writes.trySend(null)
        return false
    }

    /** Removes every transaction whose id was allocated before this call, including pending ones. */
    public fun clear() {
        val through = lastId.load()
        while (true) {
            val current = clearedThrough.load()
            if (current >= through || clearedThrough.compareAndSet(current, through)) break
        }
        writes.trySend(null)
    }

    public fun get(id: Long): HttpTransaction? {
        val list = _transactions.value
        val index = list.binarySearchById(id)
        return if (index >= 0) list[index] else null
    }

    /** Transactions with an id greater than [id], oldest first, at most [limit]. */
    public fun since(id: Long, limit: Int = Int.MAX_VALUE): List<HttpTransaction> {
        val list = _transactions.value
        val found = list.binarySearchById(id)
        val from = if (found >= 0) found + 1 else -(found + 1)
        val to = if (list.size - from > limit) from + limit else list.size
        return if (from >= to) emptyList() else list.subList(from, to)
    }

    /** Stops the consumer. Later submissions are rejected. */
    public fun close() {
        writes.close()
        terminalWrites.close()
        consumer.cancel()
    }

    private suspend fun consume() {
        val pendingEvents = ArrayList<TransactionEvent>(batchLimit)
        while (true) {
            val first = select {
                writes.onReceiveCatching { it }
                terminalWrites.onReceiveCatching { it }
            }
            if (first.isClosed) return
            var dirty = apply(first.getOrNull(), pendingEvents)
            // Pending snapshots first: a terminal snapshot queued later must not be overtaken.
            dirty = drain(writes, batchLimit, pendingEvents) || dirty
            dirty = drain(terminalWrites, terminalBatchLimit, pendingEvents) || dirty
            if (dirty) _transactions.value = buffer.toList()
            for (event in pendingEvents) _events.tryEmit(event)
            pendingEvents.clear()
            _droppedTerminalWrites.value = lostTerminalWrites.load()
        }
    }

    private fun drain(lane: Channel<out HttpTransaction?>, limit: Int, events: MutableList<TransactionEvent>): Boolean {
        var dirty = false
        repeat(limit) {
            val next = lane.tryReceive()
            if (!next.isSuccess) return dirty
            dirty = apply(next.getOrNull(), events) || dirty
        }
        return dirty
    }

    /** Returns true when [buffer] changed. A null write only applies a pending [clear]. */
    private fun apply(write: HttpTransaction?, events: MutableList<TransactionEvent>): Boolean {
        val changed = applyClear()
        if (write == null) return changed
        val existing = buffer.binarySearchById(write.id)
        if (!write.state.isTerminal) {
            // The lanes can deliver a pending snapshot after its terminal one, which was kept or evicted.
            val stale = if (existing >= 0) buffer[existing].state.isTerminal else write.id <= evictedThrough
            if (stale) return changed
        }
        val transaction = fitToByteCap(write)
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
        while (buffer.size > maxEntries || (bufferBytes > maxTotalBytes && buffer.isNotEmpty())) {
            val evicted = buffer.removeFirst()
            bufferBytes -= evicted.capturedBytes
            if (evicted.id > evictedThrough) evictedThrough = evicted.id
        }
        return true
    }

    /**
     * [transaction] with its larger body, then if still needed the other, replaced by a skipped
     * body when together they exceed [maxTotalBytes], so one entry never evicts the whole history.
     */
    private fun fitToByteCap(transaction: HttpTransaction): HttpTransaction {
        if (transaction.capturedBytes <= maxTotalBytes) return transaction
        var request = transaction.requestBody
        var response = transaction.responseBody
        val requestSize = request?.capturedBytes ?: 0
        val responseSize = response?.capturedBytes ?: 0
        if (responseSize >= requestSize) {
            response = response?.overByteCap()
            if (requestSize > maxTotalBytes) request = request?.overByteCap()
        } else {
            request = request?.overByteCap()
            if (responseSize > maxTotalBytes) response = response?.overByteCap()
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

        /** [CapturedBody.skippedReason] of a body dropped because its entry alone exceeded the store's byte cap. */
        public const val SKIPPED_OVER_STORE_CAP: String = "over store byte cap"
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

private fun List<HttpTransaction>.binarySearchById(id: Long): Int {
    var low = 0
    var high = size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val midId = this[mid].id
        when {
            midId < id -> low = mid + 1
            midId > id -> high = mid - 1
            else -> return mid
        }
    }
    return -(low + 1)
}
