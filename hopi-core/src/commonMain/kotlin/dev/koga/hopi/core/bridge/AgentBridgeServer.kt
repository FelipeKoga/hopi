package dev.koga.hopi.core.bridge

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.log.emitPlatformLogLine
import dev.koga.hopi.core.store.HopiStore
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

/**
 * Accepts connections on [listener] and serves each with a [BridgeSession] on its own threads.
 * One thread blocks in [BridgeListener.accept]; nothing runs while no client is connected.
 * At most [maxClients] connections are served at once (authenticated or not); more are closed at
 * once, so a local process that connects and stays silent can hold at most that many.
 *
 * When `accept` fails (file descriptors exhausted, say) while a client is connected, the loop
 * waits for a session to end, since that frees what accept lacked, and tries again: an event, not
 * a timer. With no client connected nothing would ever wake it, so it stops for good: the
 * listener is closed (clients then get connection refused rather than a silent backlog) and
 * [onStop] removes what advertised it (`bridge.json`).
 */
@HopiInternalApi
public class AgentBridgeServer(
    private val listener: BridgeListener,
    private val store: HopiStore,
    private val info: BridgeInfo,
    private val token: BridgeToken?,
    private val maxClients: Int = MAX_CLIENTS,
    private val onStop: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = AtomicInt(0)
    private val closed = AtomicBoolean(false)
    private val connections = AtomicReference<Set<BridgeConnection>>(emptySet())

    /** Signalled whenever a session ends; conflated, so a burst of ends costs one wake-up. */
    private val sessionEnded = Channel<Unit>(Channel.CONFLATED)

    /** Starts the accept thread and returns. */
    public fun start() {
        startBridgeThread("hopi-bridge") { acceptLoop() }
    }

    /** Runs the accept loop on the calling thread until [close], or until accepting fails for good. */
    public fun acceptLoop() {
        while (!closed.load()) {
            val connection = try {
                listener.accept()
            } catch (e: Exception) {
                if (closed.load()) break
                if (clients.load() > 0) {
                    // A stale signal costs one more attempt at most; then this waits for a fresh one.
                    runBlocking { sessionEnded.receiveCatching() }
                    continue
                }
                stopAccepting(e)
                break
            } ?: break
            if (closed.load() || clients.incrementAndFetch() > maxClients) {
                clients.decrementAndFetch()
                connection.shutdown()
                connection.close()
                continue
            }
            track(connection, add = true)
            if (closed.load()) {
                // close() ran between the check above and track: it shut down the connections it
                // saw, not this one.
                track(connection, add = false)
                clients.decrementAndFetch()
                connection.shutdown()
                connection.close()
                continue
            }
            try {
                startBridgeThread("hopi-bridge-client") {
                    try {
                        BridgeSession(connection, store, info, token, scope).run()
                    } finally {
                        track(connection, add = false)
                        clients.decrementAndFetch()
                        sessionEnded.trySend(Unit)
                    }
                }
            } catch (e: Throwable) {
                track(connection, add = false)
                clients.decrementAndFetch()
                connection.shutdown()
                connection.close()
            }
        }
    }

    /** Clients already connected keep their sessions; nobody new can connect or find the bridge. */
    private fun stopAccepting(cause: Exception) {
        emitBridgeNotice("agent bridge stopped accepting (restart the app to restore it)", cause)
        try {
            listener.close()
        } catch (_: Exception) {
            // Closing is all that was left to do with it.
        }
        onStop()
    }

    /** Stops accepting and disconnects every client. */
    public fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessionEnded.close()
        listener.close()
        connections.load().forEach { it.shutdown() }
        scope.cancel()
    }

    private fun track(connection: BridgeConnection, add: Boolean) {
        while (true) {
            val current = connections.load()
            val next = if (add) current + connection else current - connection
            if (connections.compareAndSet(current, next)) return
        }
    }

    public companion object {
        public const val MAX_CLIENTS: Int = 8
    }
}

/** Starts [body] on a new background (daemon) thread named [name]. */
internal expect fun startBridgeThread(name: String, body: () -> Unit)

/** Binds this platform's transport for [store] and starts accepting; called once per process. */
internal expect fun startPlatformAgentBridge(store: HopiStore)

/** The content of `bridge.json`: `{"port":N,"token":"…"}`. */
internal fun bridgeFileJson(port: Int, token: BridgeToken): String {
    val out = StringBuilder(96)
    out.append("{\"port\":").append(port).append(",\"token\":\"")
    token.writeTo(out)
    out.append("\"}")
    return out.toString()
}

/** One notice line on the platform log; never carries the token. */
internal fun emitBridgeNotice(message: String, cause: Throwable? = null) {
    val text = if (cause == null) message else "$message: ${cause::class.simpleName}: ${cause.message}"
    emitPlatformLogLine(
        JsonWriter().beginObject()
            .field("hopi", 1)
            .field("notice", text)
            .endObject()
            .toString(),
    )
}
