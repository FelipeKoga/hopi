package dev.koga.hopi.connect

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.store.HopiStore
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a [RemoteSession] stands. */
@HopiInternalApi
public sealed interface RemoteState {
    public data object Idle : RemoteState

    public data object Connecting : RemoteState

    /** Both connections are up; [app] and [platform] are what `hello` reported. */
    public data class Connected(public val app: String, public val platform: String) : RemoteState

    /** The connections are closed; [message] says why (never the token). [connect] may be called again. */
    public data class Disconnected(public val message: String) : RemoteState
}

/**
 * A host viewer's link to one app's bridge: two connections on one [BridgeEndpoint], because a
 * bridge `tail` reads nothing after it. One streams summaries ([BridgeDecoder.summary]) into
 * [store]; the other serves `get` and `clear`. Bodies cross the bridge only for the transaction
 * [select]ed (or [fetch]ed), so a giant body the viewer never opens is never sent.
 *
 * [store] holds one app process: the app's ids start again at 1 when it restarts, so a
 * [connect] that finds another process (another token or proof) starts a fresh store from
 * [newStore] and closes the old one. A `SLOW_CLIENT` disconnect re-tails at once from the
 * `since` the bridge gave, on the same endpoint. Everything else that ends a connection closes
 * both, and the endpoint (its adb forward), and reports [RemoteState.Disconnected]: the caller
 * decides when to [connect] again (an event: adb's device list, `bridge.json` changing).
 *
 * Nothing here polls: the tail is one thread blocked on its socket, requests run on one thread
 * blocked on its queue, and both sit idle while the app is quiet, as the app's side does.
 */
@HopiInternalApi
public class RemoteSession(
    private val open: () -> BridgeEndpoint,
    private val newStore: () -> HopiStore = { HopiStore(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_TOTAL_BYTES) },
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val _store = MutableStateFlow(newStore())
    private val _state = MutableStateFlow<RemoteState>(RemoteState.Idle)
    private val _dropped = MutableStateFlow(0L)

    /** The current app process's transactions, newest last; replaced when the app restarted. */
    public val store: StateFlow<HopiStore> = _store.asStateFlow()
    public val state: StateFlow<RemoteState> = _state.asStateFlow()

    /** Terminal writes the app's store has lost so far (its `notice` lines), for the current process. */
    public val dropped: StateFlow<Long> = _dropped.asStateFlow()

    private val requests: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "hopi-remote-request").apply { isDaemon = true }
    }

    /** Guards [link], [generation] and the decode bookkeeping below. */
    private val lock = Any()

    /** Held for a whole [connect], so two never open links at once; [close] does not wait for it. */
    private val connecting = Any()
    private var link: Link? = null
    private var closed = false

    /** SHA-256 of what identifies one app process (token, proof); compared, never shown. */
    private var generation: ByteArray? = null

    // For the current store: what was fetched with `get`, so a later summary of the same snapshot
    // (a re-tail replays the backlog) never replaces bodies with sizes.
    private val fetched = HashMap<Long, TransactionState>()
    private val pending = HashSet<Long>()
    private var highestSeen = 0L
    private var idsAllocated = 0L

    // The id the request thread is fetching, and whether the tail saw it terminal meanwhile: a
    // `get` answered pending can then be read after the terminal summary, which never comes again.
    private var fetching: Long? = null
    private var fetchingSawTerminal = false

    @Volatile
    private var selected: Long? = null
    private val fetchQueued = AtomicBoolean(false)

    /**
     * Opens both connections and starts the tail; blocks until both answered `hello` (call it off
     * the UI thread). Any previous connections and their endpoint are closed first. Failures end
     * in [RemoteState.Disconnected], never thrown. Concurrent calls run one after the other.
     */
    public fun connect(): Unit = synchronized(connecting) { connectNow() }

    private fun connectNow() {
        synchronized(lock) {
            if (closed) return
            link?.let(::closeLink)
            link = null
            _state.value = RemoteState.Connecting
        }
        var endpoint: BridgeEndpoint? = null
        var request: BridgeClient? = null
        var tail: BridgeClient? = null
        try {
            endpoint = open()
            request = BridgeClient.connect(endpoint, readTimeoutMs)
            val hello = request.handshake(null)
            val sameProcess = synchronized(lock) {
                if (closed) throw CliException(ExitCode.NOT_REACHABLE, "the session was closed")
                adopt(fingerprint(endpoint, hello))
            }
            val since = if (sameProcess) synchronized(lock) { resumeSince() } else 0L
            tail = openTail(endpoint, since)
            val next = Link(endpoint, request, tail)
            synchronized(lock) {
                if (closed) throw CliException(ExitCode.NOT_REACHABLE, "the session was closed")
                link = next
                _state.value = RemoteState.Connected(hello.json.str("app").orEmpty(), hello.json.str("platform").orEmpty())
            }
            startTailThread(next)
            // The selection survives a reconnect: fetch it again if the new link has not got it.
            fetchSelectedSoon()
        } catch (e: CliException) {
            // A close() between the tail's handshake and adopting the link leaves the tail here.
            tail?.close()
            request?.close()
            endpoint?.close()
            synchronized(lock) { if (!closed) _state.value = RemoteState.Disconnected(e.message.orEmpty()) }
        }
    }

    /**
     * The transaction the viewer shows, or null. Its body is fetched once (again when it
     * completes after a pending fetch); a newer selection made while a `get` runs waits for it,
     * then wins, so a quick run through the list fetches at most one stale body.
     */
    public fun select(id: Long?) {
        selected = id
        if (id != null) fetchSelectedSoon()
    }

    /**
     * Fetches [id]'s detail into [store] on the request connection and returns it; blocks (export
     * calls it off the UI thread). Null when the app no longer has it or the link is down.
     */
    public fun fetch(id: Long): HttpTransaction? = try {
        requests.submit<HttpTransaction?> { fetchNow(id) }.get()
    } catch (_: Exception) {
        null
    }

    /** Clears the app's store, then this one. Returns at once; the request runs in order with `get`s. */
    public fun clear(): Future<*> = try {
        requests.submit(Runnable { clearNow() })
    } catch (_: RejectedExecutionException) {
        CompletableFuture.completedFuture(Unit)
    }

    private fun clearNow() {
        val current = synchronized(lock) { link } ?: return
        val through = try {
            current.request.send(BridgeClient.request("clear").endObject().toString())
            current.request.reply().json.long("through")
        } catch (e: CliException) {
            lost(current, e.message.orEmpty())
            return
        }
        synchronized(lock) {
            if (link !== current) return@synchronized
            if (through == null) {
                // An app from before `through`: everything seen so far.
                _store.value.clear()
                fetched.clear()
                pending.clear()
            } else {
                // Exactly what the app cleared: the tail may already have brought transactions
                // started after it (kept), or may still bring summaries from before it (ignored).
                _store.value.clear(through)
                fetched.keys.removeAll { it <= through }
                pending.removeAll { it <= through }
            }
        }
    }

    /** Closes both connections and the endpoint, stops the request thread and closes [store]. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            link?.let(::closeLink)
            link = null
            _state.value = RemoteState.Disconnected("closed")
        }
        requests.shutdownNow()
        _store.value.close()
    }

    private fun openTail(endpoint: BridgeEndpoint, since: Long): BridgeClient {
        val tail = BridgeClient.connect(endpoint, readTimeoutMs)
        try {
            tail.handshake(BridgeClient.request("tail").field("since", since).endObject().toString())
            tail.waitForever()
            return tail
        } catch (e: Throwable) {
            tail.close()
            throw e
        }
    }

    private fun startTailThread(current: Link) {
        Thread({ tailLoop(current) }, "hopi-remote-tail").apply { isDaemon = true }.start()
    }

    private fun tailLoop(current: Link) {
        while (true) {
            val reply = try {
                current.tail.reply()
            } catch (e: SlowClientException) {
                // Resumed on the same endpoint; the replayed summaries are skipped where fetched.
                val since = e.since ?: synchronized(lock) { resumeSince() }
                current.tail.close()
                try {
                    current.tail = openTail(current.endpoint, since)
                } catch (again: CliException) {
                    lost(current, again.message.orEmpty())
                    return
                }
                if (synchronized(lock) { link !== current }) {
                    current.tail.close()
                    return
                }
                continue
            } catch (e: CliException) {
                lost(current, e.message.orEmpty())
                return
            }
            when (reply.type) {
                "transaction" -> onSummary(current, reply)
                "notice" -> reply.json.long("dropped")?.let { _dropped.value = it }
            }
        }
    }

    private fun onSummary(current: Link, reply: Reply) {
        var refetch = false
        synchronized(lock) {
            if (link !== current) return
            val store = _store.value
            val id = reply.json.long("id") ?: return
            val startedAt = store.get(id)?.startedAtEpochMs ?: clock()
            val transaction = BridgeDecoder.summary(reply.json, startedAt) ?: return
            if (id == fetching && transaction.state.isTerminal) fetchingSawTerminal = true
            val had = fetched[id]
            if (had != null) {
                // The detail already holds this snapshot or a later one.
                if (had.isTerminal || !transaction.state.isTerminal) return
                fetched -= id
                refetch = id == selected
            }
            if (transaction.state.isTerminal) pending -= id else pending += id
            if (id > highestSeen) highestSeen = id
            submit(store, transaction)
        }
        if (refetch) fetchSelectedSoon()
    }

    private fun fetchSelectedSoon() {
        if (selected == null || !fetchQueued.compareAndSet(false, true)) return
        try {
            requests.execute {
                fetchQueued.set(false)
                val id = selected ?: return@execute
                if (synchronized(lock) { fetched[id]?.isTerminal == true }) return@execute
                fetchNow(id)
                // A selection made while this `get` ran.
                val now = selected
                if (now != null && now != id) fetchSelectedSoon()
            }
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
    }

    /**
     * Request thread only. A pending reply read after the tail brought the terminal summary is
     * asked for once more (the app is terminal by then), else the row would keep that summary's
     * missing headers and bodies with nothing left to fetch them.
     */
    private fun fetchNow(id: Long): HttpTransaction? {
        var retried = false
        while (true) {
            val current = synchronized(lock) {
                fetching = id
                fetchingSawTerminal = false
                link
            } ?: return null
            val reply = try {
                current.request.send(BridgeClient.request("get").field("id", id).endObject().toString())
                current.request.reply()
            } catch (e: CliException) {
                // NOT_FOUND: cleared or evicted on the app's side; the connection is still fine.
                if (e.exitCode == ExitCode.NOT_FOUND) {
                    markGone(current, id)
                    return null
                }
                lost(current, e.message.orEmpty())
                return null
            }
            val transaction = BridgeDecoder.detail(reply.json) ?: return null
            val again = synchronized(lock) {
                if (link !== current) return null
                fetching = null
                if (!retried && !transaction.state.isTerminal && fetchingSawTerminal) {
                    true
                } else {
                    fetched[id] = transaction.state
                    if (transaction.state.isTerminal) pending -= id
                    if (id > highestSeen) highestSeen = id
                    submit(_store.value, transaction)
                    false
                }
            }
            if (!again) return transaction
            retried = true
        }
    }

    /**
     * The app no longer has [id] (its store evicted it, or a clear made in the app): the summary
     * here stays, its bodies marked [CapturedBody.gone] so the detail stops loading and says why,
     * and it is not asked for again.
     */
    private fun markGone(current: Link, id: Long) {
        synchronized(lock) {
            if (link !== current) return
            fetching = null
            val store = _store.value
            val shown = store.get(id) ?: return
            fetched[id] = shown.state
            if (shown.requestBody?.isNotFetched != true && shown.responseBody?.isNotFetched != true) return
            submit(
                store,
                shown.copy(
                    requestBody = shown.requestBody?.let { if (it.isNotFetched) CapturedBody.gone(it.reportedBytes) else it },
                    responseBody = shown.responseBody?.let { if (it.isNotFetched) CapturedBody.gone(it.reportedBytes) else it },
                ),
            )
        }
    }

    /** Under [lock]. Ids come from the store, as in the plugin, so that its `clear` covers them. */
    private fun submit(store: HopiStore, transaction: HttpTransaction) {
        while (idsAllocated < transaction.id) idsAllocated = store.nextId()
        store.submit(transaction)
    }

    /**
     * Under [lock]: keeps the store when [next] is the process already shown, else starts a fresh
     * one (ids restart with the process). Returns true for the same process.
     */
    private fun adopt(next: ByteArray): Boolean {
        val previous = generation
        generation = next
        if (previous == null) return false
        if (MessageDigest.isEqual(previous, next)) return true
        val old = _store.value
        _store.value = newStore()
        old.close()
        fetched.clear()
        pending.clear()
        highestSeen = 0
        idsAllocated = 0
        _dropped.value = 0
        // Its ids mean other transactions here: fetching it would pull a body nobody opened.
        selected = null
        return false
    }

    /** Under [lock]: one below the lowest id still pending here, else the highest seen (as the bridge's own resume point). */
    private fun resumeSince(): Long = pending.minOrNull()?.let { it - 1 } ?: highestSeen

    private fun lost(current: Link, message: String) {
        synchronized(lock) {
            if (link !== current) return
            closeLink(current)
            link = null
            if (!closed) _state.value = RemoteState.Disconnected(message)
        }
    }

    private fun closeLink(current: Link) {
        current.tail.close()
        current.request.close()
        current.endpoint.close()
    }

    private class Link(val endpoint: BridgeEndpoint, val request: BridgeClient, @Volatile var tail: BridgeClient)

    public companion object {
        /** Larger than an app's store: the desktop keeps a longer history of one process. */
        public const val DEFAULT_MAX_ENTRIES: Int = 2_000
        public const val DEFAULT_MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024
        public const val DEFAULT_READ_TIMEOUT_MS: Int = 10_000

        /** The token (iOS, JVM) and the proof (Android) are new with every app start. */
        private fun fingerprint(endpoint: BridgeEndpoint, hello: Reply): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            endpoint.token?.let { digest.update(it.encodeToByteArray()) }
            digest.update(0)
            hello.json.str("proof")?.let { digest.update(it.encodeToByteArray()) }
            digest.update(0)
            hello.json.str("app")?.let { digest.update(it.encodeToByteArray()) }
            return digest.digest()
        }
    }
}
