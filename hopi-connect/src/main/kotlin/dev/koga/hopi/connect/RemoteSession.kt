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

/**
 * The app's rules as a [RemoteSession] last listed them ([rules]), with [RemoteRule.hits] counted
 * up locally from the `rule` mark of the summaries its tail brought since. [supported] is false
 * while not connected and for an app whose `hello` did not say `"rules":true`.
 */
@HopiInternalApi
public class RemoteRules(public val supported: Boolean, public val rules: List<RemoteRule>) {
    public companion object {
        public val NONE: RemoteRules = RemoteRules(supported = false, rules = emptyList())
    }
}

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
 * Rules (docs/design/rules.md, "Desktop viewer"): for an app whose `hello` said `"rules":true`,
 * the tail asks for rules notices, [rules] is listed on connect and again on each notice, and the
 * rule commands run on the request connection, after its verified `hello` ([RuleClient]).
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
    private val _store = MutableStateFlow(mirrorStore())
    private val _state = MutableStateFlow<RemoteState>(RemoteState.Idle)
    private val _dropped = MutableStateFlow(0L)
    private val _rules = MutableStateFlow(RemoteRules.NONE)

    /** The current app process's transactions, newest last; replaced when the app restarted. */
    public val store: StateFlow<HopiStore> = _store.asStateFlow()
    public val state: StateFlow<RemoteState> = _state.asStateFlow()

    /** Terminal writes the app's store has lost so far (its `notice` lines), for the current process. */
    public val dropped: StateFlow<Long> = _dropped.asStateFlow()

    /** The app's rules; [RemoteRules.NONE] while not connected or when the app has none to offer. */
    public val rules: StateFlow<RemoteRules> = _rules.asStateFlow()

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
    private val relistQueued = AtomicBoolean(false)

    // Local hit counting since the last list: the highest id seen when it was read, and the ids
    // above it already counted (a pending and a terminal summary of one request count once).
    private var rulesBaseline = 0L
    private val counted = HashSet<Long>()

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
            _rules.value = RemoteRules.NONE
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
            // Never sent before this connection's `hello` was verified (RuleClient checks `rules`).
            val rules = if (hello.json.bool("rules") == true) RuleClient(request) else null
            tail = openTail(endpoint, since, rules != null)
            val next = Link(endpoint, request, tail, rules)
            synchronized(lock) {
                if (closed) throw CliException(ExitCode.NOT_REACHABLE, "the session was closed")
                link = next
                _state.value = RemoteState.Connected(hello.json.str("app").orEmpty(), hello.json.str("platform").orEmpty())
            }
            startTailThread(next)
            // The selection survives a reconnect: fetch it again if the new link has not got it.
            fetchSelectedSoon()
            if (rules != null) relistSoon()
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

    /**
     * `rules.add` of the rule object [rule] writes; its `body` argument turns bytes into a body
     * object (inline, or a blob of this connection uploaded first). Runs in order with `get`s.
     * Fails with the app's refusal ([CliException]); the connection stays up for those.
     */
    public fun addRule(rule: (body: (ByteArray) -> String) -> String): CompletableFuture<RemoteRule> = ruleCommand { client ->
        decoded(client.add(rule(client::body))).also { replaceRule(it.id, it, append = true) }
    }

    /** `rules.update`; null fields stay as they are. */
    public fun updateRule(
        id: Long,
        enabled: Boolean? = null,
        priority: Int? = null,
        times: Int? = null,
        clearTimes: Boolean = false,
        resetHits: Boolean = false,
    ): CompletableFuture<RemoteRule> = ruleCommand { client ->
        decoded(client.update(id, enabled, priority, times, clearTimes, resetHits)).also { replaceRule(id, it, append = false) }
    }

    public fun removeRule(id: Long): CompletableFuture<Unit> = ruleCommand { client ->
        client.remove(id)
        replaceRule(id, null, append = false)
    }

    /** Returns how many rules the app removed. */
    public fun clearRules(): CompletableFuture<Long> = ruleCommand { client ->
        client.clear().also { synchronized(lock) { if (_rules.value.supported) _rules.value = RemoteRules(true, emptyList()) } }
    }

    /**
     * Replays record [id] through the app's own client, with the edit object [edit] writes (null,
     * or an [edit] that writes null: none). Returns the new record's id, which the tail brings
     * once the replay is recorded.
     */
    public fun replay(id: Long, edit: ((body: (ByteArray) -> String) -> String?)? = null): CompletableFuture<Long> = ruleCommand { client ->
        client.replay(id, edit?.invoke(client::body)).json.long("id") ?: throw notReachable("the bridge sent a malformed replay reply")
    }

    /**
     * Runs [command] on the request thread over the current link's [RuleClient]. A reply the app
     * refused (bad request, limit, not found, unsupported) fails only the future; anything else
     * lost the connection.
     */
    private fun <T> ruleCommand(command: (RuleClient) -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        try {
            requests.execute {
                val current = synchronized(lock) { link }
                val client = current?.rules
                if (current == null || client == null) {
                    future.completeExceptionally(
                        if (current == null) notReachable("not connected") else CliException(ExitCode.UNSUPPORTED, RULES_UNSUPPORTED),
                    )
                    return@execute
                }
                try {
                    future.complete(command(client))
                } catch (e: CliException) {
                    if (e.exitCode == ExitCode.NOT_REACHABLE) lost(current, e.message.orEmpty())
                    future.completeExceptionally(e)
                } catch (e: RuntimeException) {
                    // The caller's writer refused its own input; nothing reached the app but blobs, which die with the session.
                    future.completeExceptionally(e)
                }
            }
        } catch (_: RejectedExecutionException) {
            future.completeExceptionally(notReachable("the session was closed"))
        }
        return future
    }

    private fun decoded(reply: Reply): RemoteRule = RemoteRule.decode(reply.json) ?: throw notReachable("the bridge sent a malformed rule")

    /** Applies a command's own reply at once; the notice that follows lists them all again. */
    private fun replaceRule(id: Long, rule: RemoteRule?, append: Boolean) {
        synchronized(lock) {
            val shown = _rules.value
            if (!shown.supported) return
            val index = shown.rules.indexOfFirst { it.id == id }
            _rules.value = RemoteRules(
                true,
                when {
                    index >= 0 && rule != null -> shown.rules.toMutableList().also { it[index] = rule }
                    index >= 0 -> shown.rules.filterIndexed { i, _ -> i != index }
                    rule != null && append -> shown.rules + rule
                    else -> shown.rules
                },
            )
        }
    }

    /** Lists the rules again on the request thread; notices arriving meanwhile coalesce into one. */
    private fun relistSoon() {
        if (!relistQueued.compareAndSet(false, true)) return
        try {
            requests.execute {
                relistQueued.set(false)
                val current = synchronized(lock) { link } ?: return@execute
                val client = current.rules ?: return@execute
                val listed = try {
                    client.list().map(::decoded)
                } catch (e: CliException) {
                    if (e.exitCode == ExitCode.NOT_REACHABLE) lost(current, e.message.orEmpty())
                    return@execute
                }
                synchronized(lock) {
                    if (link !== current) return@synchronized
                    rulesBaseline = highestSeen
                    counted.clear()
                    _rules.value = RemoteRules(true, listed)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
    }

    /** Under [lock]: the summary of [id] carries rule [ruleId]'s mark; count it once, if it is newer than the list. */
    private fun countHit(id: Long, ruleId: Long) {
        val shown = _rules.value
        if (!shown.supported || id <= rulesBaseline || !counted.add(id)) return
        val index = shown.rules.indexOfFirst { it.id == ruleId }
        if (index < 0) return
        _rules.value = RemoteRules(true, shown.rules.toMutableList().also { it[index] = it[index].withHit() })
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

    /** [rules]: ask for rules notices, only of an app whose `hello` said `"rules":true`. */
    private fun openTail(endpoint: BridgeEndpoint, since: Long, rules: Boolean): BridgeClient {
        val tail = BridgeClient.connect(endpoint, readTimeoutMs)
        try {
            val request = BridgeClient.request("tail").field("since", since)
            if (rules) request.field("rules", true)
            tail.handshake(request.endObject().toString())
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
                    current.tail = openTail(current.endpoint, since, current.rules != null)
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
                "rules" -> if (synchronized(lock) { link === current }) relistSoon()
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
            transaction.ruleId?.let { countHit(id, it) }
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
    /**
     * A store mirroring the app's, with a viewer attached for its lifetime: the app's tail already
     * paid for every pending line, so the host applies each one at once (no request thread here).
     */
    private fun mirrorStore(): HopiStore = newStore().also { it.attachViewer() }

    private fun adopt(next: ByteArray): Boolean {
        val previous = generation
        generation = next
        if (previous == null) return false
        if (MessageDigest.isEqual(previous, next)) return true
        val old = _store.value
        _store.value = mirrorStore()
        old.close()
        fetched.clear()
        pending.clear()
        highestSeen = 0
        idsAllocated = 0
        _dropped.value = 0
        rulesBaseline = 0
        counted.clear()
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
            _rules.value = RemoteRules.NONE
            if (!closed) _state.value = RemoteState.Disconnected(message)
        }
    }

    private fun closeLink(current: Link) {
        current.tail.close()
        current.request.close()
        current.endpoint.close()
    }

    /** [rules] is null for an app without rules: nothing but `get` and `clear` is sent to it. */
    private class Link(val endpoint: BridgeEndpoint, val request: BridgeClient, @Volatile var tail: BridgeClient, val rules: RuleClient?)

    public companion object {
        /** Larger than an app's store: the desktop keeps a longer history of one process. */
        public const val DEFAULT_MAX_ENTRIES: Int = 2_000
        public const val DEFAULT_MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024
        public const val DEFAULT_READ_TIMEOUT_MS: Int = 10_000
        private const val RULES_UNSUPPORTED = "this app's Hopi does not support rules (older Hopi, or rules = false)"

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
