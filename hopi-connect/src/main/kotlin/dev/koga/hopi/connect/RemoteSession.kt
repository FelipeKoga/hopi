package dev.koga.hopi.connect

import dev.koga.hopi.api.HopiInternalApi
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
 * The app's rules as a [RemoteSession] last listed them ([rules]), [RemoteRule.hits] as the app's
 * engine counted them: a summary whose `rule` mark the list may not include lists them again.
 * [supported] is false while not connected and for an app whose `hello` did not say `"rules":true`.
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
 * [select]ed (or [fetch]ed), so a giant body the viewer never opens is never sent; a resume also
 * asks whether the app still has each row still pending here (below), with no bodies.
 *
 * [store] holds one app process: the app's ids start again at 1 when it restarts, so a
 * [connect] that finds another process (another token or proof) starts a fresh store from
 * [newStore] and closes the old one. A `SLOW_CLIENT` disconnect re-tails at once from the
 * `since` the bridge gave, on the same endpoint. That re-tail, and a [connect] that finds the
 * same process, then ask once about each row still pending here (a one-line `list`, the oldest
 * 64): one the app no longer has (withdrawn while this viewer was away or behind, evicted,
 * cleared) would never end, and ends as gone. Everything else that ends a connection closes
 * both, and the endpoint (its adb forward), and reports [RemoteState.Disconnected]: the caller
 * decides when to [connect] again (an event: adb's device list, `bridge.json` changing).
 *
 * Rules (docs/design/rules.md, "Desktop viewer"): for an app whose `hello` said `"rules":true`,
 * the tail asks for rules notices, [rules] is listed on connect, again on each notice and on each
 * ruled request the last list may not include, and the rule commands run on the request
 * connection, after its verified `hello` ([RuleClient]).
 *
 * Perf (docs/design/perf-monitors.md, "Desktop viewer"): for an app whose `hello` said
 * `"perf":true`, [perf] opens a third connection for a `perf.tail`, only while a viewer watches it.
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
    /**
     * Full bodies, as a proxy shows them: asked for at each connect (`capture.set` on the tail
     * connection, so the app keeps it exactly while this viewer is attached), and each body is
     * fetched as soon as its request ends, so it is here even after the app evicted it.
     */
    private val fullBodies: () -> Boolean = { false },
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

    /**
     * The app's perf monitors (docs/design/perf-monitors.md, "Desktop viewer"): nothing is sent
     * for them until a viewer [RemotePerf.watch]es, and then over a connection of their own.
     */
    public val perf: RemotePerf = RemotePerf(requests, readTimeoutMs)

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
    private val checkQueued = AtomicBoolean(false)

    // For the lists ruled summaries and `get` replies ask for (relistForHit): the highest id seen
    // when the last list was asked for, and the ids above it that asked for another already (a
    // pending and a terminal summary of one request ask once).
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
            val perfClient = if (hello.json.bool("perf") == true) PerfClient(request) else null
            // An app whose hello does not offer it never gets the command (it would answer an error).
            val full = fullBodies() && hello.json.bool("fullBodies") == true
            tail = openTail(endpoint, since, rules != null, full)
            val next = Link(endpoint, request, tail, rules, full)
            synchronized(lock) {
                if (closed) throw CliException(ExitCode.NOT_REACHABLE, "the session was closed")
                link = next
                _state.value = RemoteState.Connected(hello.json.str("app").orEmpty(), hello.json.str("platform").orEmpty())
            }
            perf.attach(endpoint, perfClient, lost = { lost(next, it) }, sameProcess = sameProcess)
            startTailThread(next)
            // The selection survives a reconnect: fetch it again if the new link has not got it.
            fetchSelectedSoon()
            if (rules != null) relistSoon()
            if (sameProcess) checkPendingSoon()
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

    /**
     * Lists the rules again on the request thread; notices and ruled summaries arriving meanwhile
     * coalesce into one more, so a burst of ruled requests costs at most one list in flight and one
     * queued.
     */
    private fun relistSoon() {
        if (!relistQueued.compareAndSet(false, true)) return
        try {
            requests.execute {
                relistQueued.set(false)
                // Only the ids seen before asking are sure to be in the list's counts.
                val (current, baseline) = synchronized(lock) { link to highestSeen }
                if (current == null) return@execute
                val client = current.rules ?: return@execute
                val listed = try {
                    client.list().map(::decoded)
                } catch (e: CliException) {
                    if (e.exitCode == ExitCode.NOT_REACHABLE) lost(current, e.message.orEmpty())
                    return@execute
                }
                synchronized(lock) {
                    if (link !== current) return@synchronized
                    rulesBaseline = baseline
                    counted.removeAll { it <= baseline }
                    _rules.value = RemoteRules(true, listed)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
    }

    /**
     * Under [lock], for the current link: the summary or the `get` reply of [id] carries a rule
     * mark, [newMark] when this viewer held [id] without one (or not at all). Lists again when the
     * last list may not include the hit, so the hits shown are only ever the app's own count: above
     * [rulesBaseline] once per request; at or below it, where Ktor, OkHttp and `HttpURLConnection`
     * claimed before the record's id existed, only for a new mark, as URLSession claims a mock or
     * fail once the task starts loading, after its record started. From the moment the link is up,
     * so a hit the first list after a connect may have missed costs one more list, not a wrong count.
     */
    private fun relistForHit(id: Long, newMark: Boolean) {
        if (link?.rules == null) return
        val maybeNewer = if (id > rulesBaseline) counted.add(id) else newMark
        if (maybeNewer) relistSoon()
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
        perf.close()
        requests.shutdownNow()
        _store.value.close()
    }

    /**
     * [rules]: ask for rules notices, only of an app whose `hello` said `"rules":true`. [full]: full
     * bodies for as long as this connection lasts, asked for before the tail starts.
     */
    private fun openTail(endpoint: BridgeEndpoint, since: Long, rules: Boolean, full: Boolean): BridgeClient {
        val tail = BridgeClient.connect(endpoint, readTimeoutMs)
        try {
            val request = BridgeClient.request("tail").field("since", since)
            if (rules) request.field("rules", true)
            val lines = request.endObject().toString()
            tail.handshake(if (full) BridgeClient.request("capture.set").field("fullBodies", true).endObject().toString() + "\n" + lines else lines)
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
                    current.tail = openTail(current.endpoint, since, current.rules != null, current.full)
                } catch (again: CliException) {
                    lost(current, again.message.orEmpty())
                    return
                }
                if (synchronized(lock) { link !== current }) {
                    current.tail.close()
                    return
                }
                checkPendingSoon()
                continue
            } catch (e: CliException) {
                lost(current, e.message.orEmpty())
                return
            }
            when (reply.type) {
                "transaction" -> onSummary(current, reply)
                "notice" -> reply.json.long("dropped")?.let { _dropped.value = it }
                "rules" -> if (synchronized(lock) { link === current }) relistSoon()
                // capture.set's answer, before the tail's backlog: nothing to do.
                "capture" -> Unit
            }
        }
    }

    private fun onSummary(current: Link, reply: Reply) {
        var refetch = false
        var prefetch = false
        synchronized(lock) {
            if (link !== current) return
            val store = _store.value
            val id = reply.json.long("id") ?: return
            val known = store.get(id)
            val startedAt = known?.startedAtEpochMs ?: clock()
            val transaction = BridgeDecoder.summary(reply.json, startedAt) ?: return
            if (id == fetching && transaction.state.isTerminal) fetchingSawTerminal = true
            if (transaction.ruleId != null) relistForHit(id, newMark = known?.ruleId == null)
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
            prefetch = current.full && transaction.state.isTerminal && !refetch
        }
        if (refetch) fetchSelectedSoon()
        if (prefetch) prefetchSoon(reply.json.long("id") ?: return)
    }

    /**
     * Full bodies: [id]'s detail is fetched at once, in the order the requests ended, so its body
     * is here before the app's store evicts it. Behind a selection's fetch on the same thread.
     */
    private fun prefetchSoon(id: Long) {
        try {
            requests.execute {
                if (synchronized(lock) { fetched[id]?.isTerminal == true }) return@execute
                fetchNow(id)
            }
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
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
     * After a reconnect to the same process or a `SLOW_CLIENT` re-tail: asks whether the app still
     * has each row still pending here, the oldest [MAX_PENDING_CHECKS] when its turn comes (the rest
     * wait for their own summary or the next resume). The resumed backlog brings every row the app
     * still has, and a row it ended first is not asked about. One the app no longer has (its
     * recorder withdrew it while this viewer was away or behind, or it was evicted, or cleared in
     * the app) gets no summary again: it would stay pending and hold [resumeSince] below it, so it
     * ends as gone ([markGone]). Each question is a `list` of one summary from just below the id
     * ([appLacks]), so no headers or bodies cross; resumes that come meanwhile coalesce into one more.
     */
    private fun checkPendingSoon() {
        if (!checkQueued.compareAndSet(false, true)) return
        try {
            requests.execute {
                checkQueued.set(false)
                val (current, ids) = synchronized(lock) { link to pending.sorted().take(MAX_PENDING_CHECKS) }
                if (current == null) return@execute
                for (id in ids) {
                    if (!synchronized(lock) { link === current && id in pending }) continue
                    if (appLacks(current, id) ?: return@execute) markGone(current, id)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
    }

    /**
     * Request thread only: whether the app no longer has [id], read from the first summary of a
     * `list` that starts just below it (one line, no bodies); null when the link is lost.
     */
    private fun appLacks(current: Link, id: Long): Boolean? = try {
        current.request.send(BridgeClient.request("list").field("since", id - 1).field("limit", 1).endObject().toString())
        var first: Long? = null
        while (true) {
            val reply = current.request.reply()
            if (reply.type == "end") break
            if (first == null && reply.type == "transaction") first = reply.json.long("id")
        }
        first != id
    } catch (e: CliException) {
        lost(current, e.message.orEmpty())
        null
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
                    // A mark the tail has not brought yet (URLSession marks a record at its end).
                    if (transaction.ruleId != null) relistForHit(id, newMark = _store.value.get(id)?.ruleId == null)
                    submit(_store.value, transaction)
                    false
                }
            }
            if (!again) return transaction
            retried = true
        }
    }

    /**
     * The app no longer has [id] (its store evicted it, a clear made in the app, or its recorder
     * withdrew it): the summary here stays, its bodies marked [CapturedBody.gone] so the detail
     * stops loading and says why, and it is not asked for again. A pending row also ends, failed
     * with [CapturedBody.GONE] as its error, and leaves [pending], so it no longer holds
     * [resumeSince] below it. [fetched] keeps the app's last state of it (pending), because the app
     * may still record its end (a request it evicted or cleared in flight), which then replaces the
     * row; until then a new selection asks for it once more, and its `NOT_FOUND` changes nothing.
     */
    private fun markGone(current: Link, id: Long) {
        synchronized(lock) {
            if (link !== current) return
            fetching = null
            pending -= id
            val store = _store.value
            val shown = store.get(id) ?: return
            // Ended here before, not by the app: the app's own end must still replace it.
            if (shown.error == CapturedBody.GONE && fetched[id]?.isTerminal == false) return
            fetched[id] = shown.state
            val ended = if (shown.state.isTerminal) shown else shown.copy(state = TransactionState.Failed, error = CapturedBody.GONE)
            if (ended === shown && shown.requestBody?.isNotFetched != true && shown.responseBody?.isNotFetched != true) return
            submit(
                store,
                ended.copy(
                    requestBody = ended.requestBody?.let { if (it.isNotFetched) CapturedBody.gone(it.reportedBytes) else it },
                    responseBody = ended.responseBody?.let { if (it.isNotFetched) CapturedBody.gone(it.reportedBytes) else it },
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
        perf.detach()
        current.tail.close()
        current.request.close()
        current.endpoint.close()
    }

    /** [rules] is null for an app without rules: nothing but `get` and `clear` is sent to it. */
    private class Link(
        val endpoint: BridgeEndpoint,
        val request: BridgeClient,
        @Volatile var tail: BridgeClient,
        val rules: RuleClient?,
        /** Full bodies held on [tail]: every ended request's detail is fetched at once. */
        val full: Boolean = false,
    )

    public companion object {
        /** Larger than an app's store: the desktop keeps a longer history of one process. */
        public const val DEFAULT_MAX_ENTRIES: Int = 2_000
        public const val DEFAULT_MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024
        public const val DEFAULT_READ_TIMEOUT_MS: Int = 10_000
        private const val RULES_UNSUPPORTED = "this app's Hopi does not support rules (older Hopi, or rules = false)"

        /** Pending rows one resume asks about ([checkPendingSoon]): one one-line `list` each. */
        private const val MAX_PENDING_CHECKS = 64

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
