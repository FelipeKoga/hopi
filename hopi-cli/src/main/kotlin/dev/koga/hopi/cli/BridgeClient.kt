package dev.koga.hopi.cli

import dev.koga.hopi.core.json.JsonWriter
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** One bridge reply line: [raw] exactly as received (what `--json` prints) and its parsed object. */
internal class Reply(val raw: String, val json: JsonObject) {
    val type: String? get() = json.str("type")
}

/**
 * How `hello` proves it comes from the app (Android): [expected] reads the proof the app wrote to
 * its private storage; [mismatch] is the error when `hello` lacks it or carries another.
 */
internal class ProofCheck(val expected: () -> String, val mismatch: String)

/**
 * One connection to a bridge on host loopback. [handshake] sends the token (token transports) and
 * `hello` and checks the answer before anything else is read; a peer that closes without a byte
 * means nothing serves the app (adb accepts the forwarded connection even then).
 */
internal class BridgeClient private constructor(
    private val socket: Socket,
    private val endpoint: BridgeEndpoint,
    private val readTimeoutMs: Int,
) : AutoCloseable {
    private val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8), 64 * 1024)
    private val output = socket.getOutputStream()

    /** The checked `hello` reply, once [handshake] returned. */
    lateinit var hello: Reply
        private set

    /**
     * Sends the token, `hello` and [request] in one write (one round trip), then checks `hello`.
     * A request that mutates (`clear`) is sent only after the check, with [send].
     */
    fun handshake(request: String?): Reply {
        val lines = StringBuilder(160)
        endpoint.token?.let { lines.append(it).append('\n') }
        lines.append(HELLO).append('\n')
        request?.let { lines.append(it).append('\n') }
        write(lines)
        val hello = next() ?: throw notReachable(endpoint.unreachableMessage)
        if (hello.type != "hello") throw notReachable("unexpected first reply from the bridge: ${hello.type?.let(::visible)}")
        val protocol = hello.json.long("protocol")
        if (protocol != PROTOCOL) throw notReachable("the bridge speaks protocol $protocol; this CLI speaks $PROTOCOL")
        val platform = hello.json.str("platform")
        if (platform != endpoint.platform.id) {
            throw notReachable("the bridge reports platform ${platform?.let(::visible)}, not ${endpoint.platform.id}")
        }
        val app = hello.json.str("app")
        if (endpoint.app != null && app != endpoint.app) throw notReachable("the bridge serves ${app?.let(::visible)}, not ${endpoint.app}")
        endpoint.proof?.let { check -> verifyProof(hello, check) }
        this.hello = hello
        return hello
    }

    /**
     * `hello.app` is whatever the peer says; only the proof ties it to the app. Compared in
     * constant time and never printed: an error names the mismatch, not either value.
     */
    private fun verifyProof(hello: Reply, check: ProofCheck) {
        val sent = hello.json.str("proof") ?: throw notReachable(check.mismatch)
        val expected = check.expected()
        if (!MessageDigest.isEqual(sent.encodeToByteArray(), expected.encodeToByteArray())) throw notReachable(check.mismatch)
    }

    fun send(request: String) = write(StringBuilder(request.length + 1).append(request).append('\n'))

    /** Lets `tail` wait for events as long as it takes. */
    fun waitForever() {
        socket.soTimeout = 0
    }

    /**
     * The next reply; an `error` reply becomes a [CliException]. End of input fails with
     * [closedMessage], unless the endpoint knows the host side lost the connection.
     */
    fun reply(closedMessage: String = CLOSED): Reply {
        val reply = next() ?: throw notReachable(endpoint.transportLost() ?: closedMessage)
        if (reply.type == "error") {
            val code = reply.json.str("code")
            val message = visible(reply.json.str("message").orEmpty())
            throw when (code) {
                "NOT_FOUND" -> CliException(ExitCode.NOT_FOUND, message)
                "BAD_REQUEST" -> usageError("the bridge rejected the request: $message")
                "SLOW_CLIENT" -> notReachable(
                    "the bridge disconnected this client for reading too slowly (SLOW_CLIENT): " +
                        (reply.json.long("since")?.let { "run hopi tail --since $it to resume without losing a transaction" }
                            ?: "tail again with --since one below the lowest id still pending"),
                )
                else -> notReachable("bridge error ${code?.let(::visible)}: $message")
            }
        }
        return reply
    }

    private fun next(): Reply? {
        val line = try {
            input.readLine()
        } catch (_: SocketTimeoutException) {
            throw notReachable("the bridge did not answer within ${readTimeoutMs / 1000} s")
        } catch (_: IOException) {
            // A reset is the same as a close: the peer is gone.
            null
        } ?: return null
        val json = try {
            Json.parseToJsonElement(line) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw notReachable("the bridge sent a line that is not a JSON object")
        return Reply(line, json)
    }

    private fun write(text: CharSequence) {
        try {
            output.write(text.toString().encodeToByteArray())
            output.flush()
        } catch (_: IOException) {
            // Never put the payload (it may hold the token) into the message.
            throw notReachable(endpoint.unreachableMessage)
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
            // Already closed.
        }
    }

    companion object {
        const val PROTOCOL: Long = 1
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val CLOSED = "the app closed the bridge connection"

        /** Host loopback by address: `localhost` can resolve to `::1`, where neither adb nor the bridge listens. */
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        private val HELLO = request("hello").endObject().toString()

        fun connect(endpoint: BridgeEndpoint, readTimeoutMs: Int): BridgeClient {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(LOOPBACK, endpoint.port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = readTimeoutMs
                socket.tcpNoDelay = true
            } catch (_: IOException) {
                socket.close()
                throw notReachable(endpoint.unreachableMessage)
            }
            return BridgeClient(socket, endpoint, readTimeoutMs)
        }

        /** `{"cmd":"<command>"` with the object left open for its fields. */
        fun request(command: String): JsonWriter = JsonWriter().beginObject().field("cmd", command)
    }
}

internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
