package dev.koga.hopi.ktor

import dev.koga.hopi.core.model.CapturedBody
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.availableForRead
import io.ktor.utils.io.close
import io.ktor.utils.io.pool.ByteArrayPool
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** What the send hook records for a request body, and the content to send in its place. */
internal class RequestCapture(
    val body: CapturedBody?,
    val outgoing: OutgoingContent,
    val streamed: StreamedRequestBody?,
)

internal fun captureRequest(content: OutgoingContent, limit: Int): RequestCapture = when (content) {
    is MultiPartFormDataContent -> skippedRequest(content, SkipReason.MULTIPART)
    is OutgoingContent.ProtocolUpgrade -> skippedRequest(content, SkipReason.PROTOCOL_UPGRADE)
    is OutgoingContent.NoContent -> RequestCapture(null, content, null)
    is OutgoingContent.ByteArrayContent -> {
        val bytes = content.bytes()
        val kept = bytes.copyOf(minOf(bytes.size, limit))
        val body = CapturedBody(
            kept, bytes.size.toLong(), truncated = kept.size < bytes.size, content.contentType?.toString(), null,
        )
        RequestCapture(body, content, null)
    }
    is OutgoingContent.ContentWrapper -> {
        val delegate = content.delegate()
        val inner = captureRequest(delegate, limit)
        val outgoing = if (inner.outgoing === delegate) content else content.copy(inner.outgoing)
        RequestCapture(inner.body, outgoing, inner.streamed)
    }
    is OutgoingContent.ReadChannelContent, is OutgoingContent.WriteChannelContent -> {
        val streamed = StreamedRequestBody(content, limit)
        RequestCapture(null, streamed, streamed)
    }
}

private fun skippedRequest(content: OutgoingContent, reason: String) = RequestCapture(
    CapturedBody.skipped(reason, content.contentType?.toString(), content.contentLength),
    content,
    null,
)

/**
 * Sends [original]'s bytes unchanged while keeping a bounded copy. Every [writeTo] reads the
 * original afresh, so a resend (redirect, retry) still sends the whole body; the copy kept is the
 * last one that reached its end.
 */
internal class StreamedRequestBody(
    private val original: OutgoingContent,
    private val limit: Int,
) : OutgoingContent.WriteChannelContent() {
    private val captured = AtomicReference<CapturedBody?>(null)

    override val contentType: ContentType? get() = original.contentType
    override val contentLength: Long? get() = original.contentLength
    override val status: HttpStatusCode? get() = original.status
    override val headers: Headers get() = original.headers

    override fun <T : Any> getProperty(key: AttributeKey<T>): T? = original.getProperty(key)

    override fun <T : Any> setProperty(key: AttributeKey<T>, value: T?) {
        original.setProperty(key, value)
    }

    /** The captured body once fully sent; otherwise a skipped body marked incomplete. */
    fun body(): CapturedBody = captured.load()
        ?: CapturedBody.skipped(SkipReason.INCOMPLETE, original.contentType?.toString(), original.contentLength)

    override suspend fun writeTo(channel: ByteWriteChannel) {
        when (original) {
            is OutgoingContent.ReadChannelContent -> pump(original.readFrom(), channel)
            is OutgoingContent.WriteChannelContent -> coroutineScope {
                val pipe = ByteChannel()
                launch {
                    try {
                        original.writeTo(pipe)
                        pipe.flushAndClose()
                    } catch (cause: Throwable) {
                        pipe.close(cause)
                        throw cause
                    }
                }
                pump(pipe, channel)
            }
            else -> error("StreamedRequestBody wraps only channel contents")
        }
    }

    private suspend fun pump(source: ByteReadChannel, wire: ByteWriteChannel) {
        val buffer = BoundedBuffer(limit, original.contentLength)
        val chunk = ByteArrayPool.borrow()
        try {
            while (true) {
                val read = source.readAvailable(chunk)
                if (read < 0) break
                buffer.append(chunk, 0, read)
                wire.writeFully(chunk, 0, read)
                // The engine's channel does not auto-flush: without this a slow or live body would
                // reach the network only once 1 MiB has built up.
                if (source.availableForRead == 0) wire.flush()
            }
            source.closedCause?.let { throw it }
            wire.flush()
        } finally {
            ByteArrayPool.recycle(chunk)
        }
        captured.store(buffer.body(original.contentType?.toString()))
    }
}

/** Request headers in order, plus the content's own type, length and headers as the engine sends them. */
internal fun requestHeaders(request: HttpRequestBuilder, content: OutgoingContent): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    for ((name, values) in request.headers.entries()) {
        for (value in values) out += name to value
    }
    for ((name, values) in content.headers.entries()) {
        for (value in values) out += name to value
    }
    if (content !is OutgoingContent.NoContent) {
        content.contentType?.let { type ->
            if (out.none { it.first.equals(HttpHeaders.ContentType, ignoreCase = true) }) {
                out += HttpHeaders.ContentType to type.toString()
            }
        }
        content.contentLength?.let { length ->
            if (out.none { it.first.equals(HttpHeaders.ContentLength, ignoreCase = true) }) {
                out += HttpHeaders.ContentLength to length.toString()
            }
        }
    }
    return out
}
