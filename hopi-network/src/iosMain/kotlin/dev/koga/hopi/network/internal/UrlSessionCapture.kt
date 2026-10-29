package dev.koga.hopi.network.internal

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.body.ResponseSkip
import dev.koga.hopi.core.capture.CaptureInstallation
import dev.koga.hopi.core.capture.PlatformCapture
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.log.emitPlatformLogLine
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionSource
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.network.internal.urlsession.HOPI_US_ALREADY_STARTED
import dev.koga.hopi.network.internal.urlsession.HOPI_US_BODY_DATA
import dev.koga.hopi.network.internal.urlsession.HOPI_US_BODY_FILE
import dev.koga.hopi.network.internal.urlsession.HOPI_US_BODY_NOT_OBSERVED
import dev.koga.hopi.network.internal.urlsession.HOPI_US_BODY_STREAM
import dev.koga.hopi.network.internal.urlsession.HOPI_US_BYTE_PATH
import dev.koga.hopi.network.internal.urlsession.HOPI_US_RESPONSE_CAPTURED
import dev.koga.hopi.network.internal.urlsession.HOPI_US_RESPONSE_DOWNLOAD
import dev.koga.hopi.network.internal.urlsession.HOPI_US_RESPONSE_NOT_OBSERVABLE
import dev.koga.hopi.network.internal.urlsession.hopi_us_bytes_sent
import dev.koga.hopi.network.internal.urlsession.hopi_us_cancelled
import dev.koga.hopi.network.internal.urlsession.hopi_us_copy_request_body
import dev.koga.hopi.network.internal.urlsession.hopi_us_copy_response
import dev.koga.hopi.network.internal.urlsession.hopi_us_drop_bytes
import dev.koga.hopi.network.internal.urlsession.hopi_us_duration_ns
import dev.koga.hopi.network.internal.urlsession.hopi_us_error
import dev.koga.hopi.network.internal.urlsession.hopi_us_final_url
import dev.koga.hopi.network.internal.urlsession.hopi_us_method
import dev.koga.hopi.network.internal.urlsession.hopi_us_protocol
import dev.koga.hopi.network.internal.urlsession.hopi_us_release
import dev.koga.hopi.network.internal.urlsession.hopi_us_request_body_kind
import dev.koga.hopi.network.internal.urlsession.hopi_us_request_body_length
import dev.koga.hopi.network.internal.urlsession.hopi_us_request_headers
import dev.koga.hopi.network.internal.urlsession.hopi_us_response_body_kind
import dev.koga.hopi.network.internal.urlsession.hopi_us_response_headers
import dev.koga.hopi.network.internal.urlsession.hopi_us_response_kept_bytes
import dev.koga.hopi.network.internal.urlsession.hopi_us_response_total_bytes
import dev.koga.hopi.network.internal.urlsession.hopi_us_rule_applied
import dev.koga.hopi.network.internal.urlsession.hopi_us_rule_id
import dev.koga.hopi.network.internal.urlsession.hopi_us_ruled_by_protocol
import dev.koga.hopi.network.internal.urlsession.hopi_us_set_store_id
import dev.koga.hopi.network.internal.urlsession.hopi_us_start
import dev.koga.hopi.network.internal.urlsession.hopi_us_started_at_ms
import dev.koga.hopi.network.internal.urlsession.hopi_us_status
import dev.koga.hopi.network.internal.urlsession.hopi_us_store_id
import dev.koga.hopi.network.internal.urlsession.hopi_us_url
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned

/**
 * The Kotlin side of automatic URLSession capture (docs/design/urlsession-capture.md): the native
 * library's hooks observe every task of the process and hand each one here twice, on its own
 * serial capture queue, as it starts and as it ends. This side turns the native state into
 * [HttpTransaction]s and submits them to the store, so every Hopi surface shows them unchanged.
 * Nothing here runs on an app thread or on CFNetwork's queue.
 */
@OptIn(ExperimentalForeignApi::class)
@HopiInternalApi
public object UrlSessionCapture {
    /** How capture runs in this process. */
    public enum class Mode {
        /** [start] has not run (or was disabled). */
        NotStarted,

        /** Every in-process task is recorded with its bodies. */
        Bytes,

        /** This OS failed the self-check: tasks are recorded without bodies. */
        Metadata,
    }

    /** One [start] call's settings, compared with the first call's to tell a later caller it was ignored. */
    public data class Settings(
        val maxEntries: Int,
        val maxTotalBytes: Long,
        val maxBodyBytes: Long,
        val redactedHeaders: List<String>,
        val redactedQueryParameters: List<String>,
        val redactionDisabled: Boolean,
        val summaryLog: Boolean,
        val agentBridge: Boolean,
        /** Rules (mock, delay, fail, request edit) apply to URLSession tasks. */
        val rules: Boolean = true,
    )

    /** The engine package of Ktor's Darwin client, which sends through URLSession. */
    public const val KTOR_DARWIN_ENGINE_PACKAGE: String = "io.ktor.client.engine.darwin."

    private val modeOrdinal = AtomicInt(Mode.NotStarted.ordinal)
    private val first = AtomicReference<Settings?>(null)
    private val target = AtomicReference<Target?>(null)

    private class Target(val store: HopiStore, val maxBodyBytes: Int)

    // Each task's pending record by store id, reused by its terminal one. Touched on the serial
    // capture queue only, so it needs no lock; an entry lives as long as its task runs.
    private val startRecords = HashMap<Long, HttpTransaction>()

    public val mode: Mode get() = Mode.entries[modeOrdinal.load()]

    /**
     * Installs the hooks, once per process; the first call's [settings] win, and a later call with
     * different ones writes a notice line instead. Invalid caps write a notice and start nothing,
     * without using up the first call. Its one caller is the network tool ([NetworkTool]), behind
     * the start sequence's own checks of the caps and of a second start, so these checks are
     * defensive: no Swift code calls it, and it has no noop. [injectedStore] is for tests.
     */
    public fun start(settings: Settings, injectedStore: HopiStore? = null): Mode {
        if (first.load() == null) {
            val problem = CaptureInstallation.invalidCaps(settings.maxEntries, settings.maxTotalBytes, settings.maxBodyBytes)
            if (problem != null) {
                emitPlatformLogLine(notice("$NOT_STARTED_NOTICE$problem"))
                return mode
            }
        }
        if (!first.compareAndSet(null, settings)) {
            if (first.load() != settings && settings.summaryLog) emitPlatformLogLine(notice(IGNORED_CONFIG_NOTICE))
            return mode
        }
        val installed = CaptureInstallation.install(
            maxEntries = settings.maxEntries,
            maxTotalBytes = settings.maxTotalBytes,
            maxBodyBytes = settings.maxBodyBytes,
            redactedHeaders = settings.redactedHeaders,
            redactedQueryParameters = settings.redactedQueryParameters,
            redactionDisabled = settings.redactionDisabled,
            summaryLog = settings.summaryLog,
            agentBridge = settings.agentBridge,
            injectedStore = injectedStore,
        )
        target.store(Target(installed.store, installed.maxBodyBytes))
        // From now on the Ktor plugin leaves Darwin clients' requests to this capture. Covered before
        // the hooks go live: a request the plugin finds still uncovered once the engine answered was
        // resumed before them, so a Ktor request in flight across this call is recorded once or
        // missed, never twice (the plugin withdraws its record when it finds the engine covered).
        PlatformCapture.cover(KTOR_DARWIN_ENGINE_PACKAGE)
        val result = hopi_us_start(installed.maxBodyBytes.toLong(), staticCFunction(::captureStarted), staticCFunction(::captureFinished))
        val started = when (result) {
            HOPI_US_BYTE_PATH -> Mode.Bytes
            HOPI_US_ALREADY_STARTED -> mode
            else -> Mode.Metadata
        }
        modeOrdinal.store(started.ordinal)
        if (started == Mode.Metadata && settings.summaryLog) emitPlatformLogLine(notice(METADATA_NOTICE))
        RuleEngine.recorderStarted(settings.rules)
        if (settings.rules && result != HOPI_US_ALREADY_STARTED) {
            val store = installed.store
            UrlSessionRules.enable { store.redactor }
            if (!UrlSessionRules.sessionsCovered && settings.summaryLog) emitPlatformLogLine(notice(SESSIONS_NOTICE))
        }
        return started
    }

    internal fun onStarted(handle: COpaquePointer?) {
        if (handle == null) return
        try {
            val target = target.load() ?: return
            val id = target.store.nextId()
            hopi_us_set_store_id(handle, id)
            val record = pending(handle, id, target.maxBodyBytes, bytesSent = null)
            startRecords[id] = record
            target.store.submit(record)
        } catch (_: Throwable) {
            // Never let an exception cross into the native caller: that would abort the app.
        } finally {
            hopi_us_release(handle)
        }
    }

    internal fun onFinished(handle: COpaquePointer?) {
        if (handle == null) return
        try {
            val target = target.load() ?: return
            val id = hopi_us_store_id(handle)
            if (id == 0L) return
            val pending = startRecords.remove(id)
            target.store.submit(terminal(handle, id, target.maxBodyBytes, pending))
        } catch (_: Throwable) {
            // As in onStarted: the record is lost, the app is not.
        } finally {
            hopi_us_drop_bytes(handle)
            hopi_us_release(handle)
        }
    }

    private fun pending(handle: COpaquePointer, id: Long, maxBodyBytes: Int, bytesSent: Long?): HttpTransaction {
        val headers = pairs(hopi_us_request_headers(handle))
        return HttpTransaction.pending(
            id = id,
            startedAtEpochMs = hopi_us_started_at_ms(handle),
            method = hopi_us_method(handle) ?: "GET",
            url = hopi_us_url(handle) ?: "",
            requestHeaders = headers,
            requestBody = requestBody(handle, maxBodyBytes, header(headers, "Content-Type"), bytesSent),
            source = TransactionSource.URL_SESSION,
            ruleId = hopi_us_rule_id(handle).takeIf { it > 0 },
            ruleApplied = hopi_us_rule_applied(handle),
        )
    }

    /**
     * The end record, on top of the start one when there is one: its request half does not change,
     * except a skipped body, which only now knows how many bytes went out.
     */
    private fun terminal(handle: COpaquePointer, id: Long, maxBodyBytes: Int, started: HttpTransaction?): HttpTransaction {
        val base = when {
            started == null -> pending(handle, id, maxBodyBytes, bytesSent = hopi_us_bytes_sent(handle))
            started.requestBody?.skippedReason == null -> started
            else -> started.copy(
                requestBody = requestBody(handle, maxBodyBytes, header(started.requestHeaders, "Content-Type"), hopi_us_bytes_sent(handle)),
            )
        }
        // The rule mark is final now: a mock or fail is claimed when the task starts loading, after
        // the start record.
        val request = base.copy(
            finalUrl = hopi_us_final_url(handle),
            ruleId = hopi_us_rule_id(handle).takeIf { it > 0 },
            ruleApplied = hopi_us_rule_applied(handle),
        )
        val durationMs = hopi_us_duration_ns(handle) / 1_000_000
        val error = hopi_us_error(handle)?.let { if (hopi_us_cancelled(handle)) "cancelled" else it }
        val status = hopi_us_status(handle)
        if (status < 0) return request.failed(error ?: "no HTTP response", durationMs)
        val headers = pairs(hopi_us_response_headers(handle))
        // A response the rule protocol made never crossed a network: it has no protocol.
        val protocol = if (hopi_us_ruled_by_protocol(handle)) null else protocolName(hopi_us_protocol(handle))
        if (error != null) {
            // The response arrived, then the task failed: keep it, with the part of the body that came.
            return request.failedAfterResponse(
                error,
                durationMs,
                responseCode = status,
                responseHeaders = headers,
                responseBody = responseBody(handle, request.method, status, header(headers, "Content-Type")),
                protocol = protocol,
            )
        }
        return request.completed(
            responseCode = status,
            responseHeaders = headers,
            responseBody = responseBody(handle, request.method, status, header(headers, "Content-Type")),
            durationMs = durationMs,
            protocol = protocol,
        )
    }

    private fun requestBody(handle: COpaquePointer, maxBodyBytes: Int, contentType: String?, bytesSent: Long?): CapturedBody? =
        when (hopi_us_request_body_kind(handle)) {
            HOPI_US_BODY_DATA -> {
                val length = hopi_us_request_body_length(handle)
                val bytes = copy(minOf(length, maxBodyBytes.toLong()).toInt()) { dst, cap -> hopi_us_copy_request_body(handle, dst, cap) }
                CapturedBody(bytes, length, truncated = bytes.size < length, contentType, skippedReason = null)
            }
            HOPI_US_BODY_STREAM -> CapturedBody.skipped(SKIPPED_STREAM, contentType, bytesSent)
            HOPI_US_BODY_FILE -> CapturedBody.skipped(SKIPPED_FILE, contentType, bytesSent)
            HOPI_US_BODY_NOT_OBSERVED -> CapturedBody.skipped(SKIPPED_NOT_OBSERVED, contentType, bytesSent)
            else -> null
        }

    private fun responseBody(handle: COpaquePointer, method: String, status: Int, contentType: String?): CapturedBody? {
        if (method == "HEAD" || status < 200 || status == 204 || status == 304) return null
        val total = hopi_us_response_total_bytes(handle)
        return when (hopi_us_response_body_kind(handle)) {
            HOPI_US_RESPONSE_CAPTURED -> {
                val kept = hopi_us_response_kept_bytes(handle)
                val bytes = copy(kept.toInt()) { dst, cap -> hopi_us_copy_response(handle, dst, cap) }
                CapturedBody(bytes, total, truncated = bytes.size < total, contentType, skippedReason = null)
            }
            HOPI_US_RESPONSE_DOWNLOAD -> CapturedBody.skipped(SKIPPED_DOWNLOAD, contentType, total)
            HOPI_US_RESPONSE_NOT_OBSERVABLE -> CapturedBody.skipped(SKIPPED_NOT_OBSERVABLE, contentType, total)
            else -> CapturedBody.skipped(ResponseSkip.reason(contentType) ?: ResponseSkip.BINARY, contentType, total)
        }
    }

    /** One array of [size] filled by [fill] in place (pinned), cut to what it wrote. */
    private inline fun copy(size: Int, fill: (COpaquePointer, Long) -> Long): ByteArray {
        if (size <= 0) return ByteArray(0)
        val bytes = ByteArray(size)
        val written = bytes.usePinned { fill(it.addressOf(0), size.toLong()) }.toInt()
        return if (written == size) bytes else bytes.copyOf(written.coerceIn(0, size))
    }

    private fun pairs(flat: List<*>?): List<Pair<String, String>> {
        if (flat == null) return emptyList()
        val out = ArrayList<Pair<String, String>>(flat.size / 2)
        var i = 0
        while (i + 1 < flat.size) {
            out += flat[i].toString() to flat[i + 1].toString()
            i += 2
        }
        return out
    }

    private fun header(headers: List<Pair<String, String>>, name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** The metrics' ALPN name, written the way the Ktor plugin records Ktor's `HttpProtocolVersion`. */
    public fun protocolName(name: String?): String? = when (name?.lowercase()) {
        null, "" -> null
        "http/1.1" -> "HTTP/1.1"
        "http/1.0" -> "HTTP/1.0"
        "h2", "h2c" -> "HTTP/2.0"
        "h3" -> "HTTP/3.0"
        else -> name
    }

    private fun notice(text: String): String = JsonWriter().beginObject()
        .field("hopi", 1)
        .field("notice", text)
        .endObject()
        .toString()

    public const val SKIPPED_STREAM: String = "stream, not captured"
    public const val SKIPPED_FILE: String = "file, not captured"
    public const val SKIPPED_NOT_OBSERVED: String = "not observed"
    public const val SKIPPED_DOWNLOAD: String = "download"
    public const val SKIPPED_NOT_OBSERVABLE: String = "not observable"
    private const val METADATA_NOTICE = "urlsession: byte capture unavailable on this OS, recording metadata only"
    private const val SESSIONS_NOTICE =
        "urlsession: rules cannot reach new sessions on this OS (session factory self-check failed); mock and fail apply to URLSession.shared only"
    private const val NOT_STARTED_NOTICE = "urlsession: not started, invalid configuration: "
    private const val IGNORED_CONFIG_NOTICE = "urlsession: already started; this call's configuration is ignored"
}

// The native library's callbacks: top-level, as staticCFunction needs functions that capture nothing.
@OptIn(ExperimentalForeignApi::class)
private fun captureStarted(handle: COpaquePointer?) = UrlSessionCapture.onStarted(handle)

@OptIn(ExperimentalForeignApi::class)
private fun captureFinished(handle: COpaquePointer?) = UrlSessionCapture.onFinished(handle)
