package dev.koga.hopi.ktor

import dev.koga.hopi.core.model.CapturedBody
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the app reads in place of a teed body: a delegating channel over [source], read by the
 * app's own reader. There is no coroutine of its own and no second channel, so a chunk takes no
 * extra hop. Bytes move from [source] into [own] (a segment move, no copy) only when the app asks
 * for more, as Ktor's `CountedByteReadChannel` does. While [capture] is below its cap, each move
 * also copies the new bytes into its blocks, which grow without copying, so the app's reader pays
 * at most one copy of the cap in all; past the cap a move only adds its size to the count. Joining
 * the blocks at the end costs the reader at most [INLINE_JOIN] bytes; a larger join runs once,
 * off the reader's thread.
 *
 * The transaction ends here, on the reader's own calls and never by blocking, suspending or posting
 * per chunk (the only post is that one join): it completes once [source] ended and everything it delivered passed through [own] (the
 * app read to the end, or cancelled after the whole body had arrived), and it fails when [source]
 * fails or the app cancels while the body is still arriving. Cancelling cancels [source], as
 * without the plugin, which lets the engine release the connection. Nothing observes a body the
 * app never reads and never cancels, not even a failure of its source: its transaction stays
 * pending until it does.
 *
 * Like every [ByteReadChannel], it has a single reader. [cancel] may come from another thread while
 * that reader runs (Ktor's `bodyAsChannel` copy cancels it when the app cancels its channel), so
 * it touches [own] and [source]'s buffer only when no reader has started.
 */
@OptIn(InternalAPI::class)
internal class TeedBody(
    private val source: ByteReadChannel,
    private val exchange: Exchange,
    internal val capture: BoundedBuffer,
    private val contentType: String?,
) : ByteReadChannel {
    private val own = Buffer()
    private val settled = AtomicBoolean(false)

    /** Who owns [own]: nobody yet, the reader, or a [cancel] that came before any read. */
    private val owner = AtomicInt(NOBODY)

    /** Read and written by the reader only: whether it has taken [own]. */
    private var reading = false

    override val closedCause: Throwable?
        get() = source.closedCause?.also { cause -> settle(cause) }

    override val isClosedForRead: Boolean
        get() {
            if (!own.exhausted()) return closedCause != null
            val closed = source.isClosedForRead
            if (closed) settle(source.closedCause)
            return closed
        }

    /**
     * Like a failed `ByteChannel`, a failed source fails this read even while [own] still holds
     * bytes: [pull] then throws the source's own read failure.
     */
    override val readBuffer: Source
        get() {
            if (!reading) startReading()
            if (own.exhausted() || source.closedCause != null) pull()
            return own
        }

    /**
     * What [source] already holds is moved in without suspending; only a real wait goes through
     * [awaitRest], a tail call, so a wait that need not suspend allocates nothing here.
     */
    override suspend fun awaitContent(min: Int): Boolean {
        if (!reading) startReading()
        // As `ByteChannel.awaitContent` rethrows its close cause first, bytes held or not.
        closedCause?.let { throw it }
        if (own.size < min) pull()
        if (own.size >= min) return true
        return awaitRest(min)
    }

    /**
     * Waits for the rest of [min] in [source] and moves it in; notes the end or a failure. A source
     * that fails during the wait ends it with false and nothing moved, as `ByteChannel.awaitContent`
     * returns false then: the reader's next call reports the failure exactly as without the plugin
     * (readAvailable sees the end, readRemaining throws the close cause, readFully an EOF).
     */
    private suspend fun awaitRest(min: Int): Boolean {
        try {
            source.awaitContent(min - own.size.toInt())
        } catch (cause: Throwable) {
            settle(cause)
            throw cause
        }
        source.closedCause?.let { cause ->
            settle(cause)
            return false
        }
        pull()
        if (own.size >= min) return true
        if (source.isClosedForRead) settle(source.closedCause)
        return false
    }

    override fun cancel(cause: Throwable?) {
        if (!settled.load()) {
            // With no reader yet, the body may have arrived unread: moving it in does not suspend.
            // A reader that has started owns the buffers, so then only the source's state is read.
            val unread = owner.compareAndSet(NOBODY, CANCEL)
            val arrived = try {
                if (unread) pull()
                source.isClosedForRead
            } catch (_: Throwable) {
                false
            }
            if (arrived) {
                // Racing a reader's last move, this records the capture as of now; the app's own
                // bytes are never touched from here.
                settle(source.closedCause)
            } else {
                val reason = cause ?: IOException("Channel was cancelled")
                settle(CancellationException("response body cancelled before its end", reason))
            }
        }
        source.cancel(cause)
    }

    private fun startReading() {
        if (!owner.compareAndSet(NOBODY, READER)) {
            // A cancel came first and may still be moving the body in.
            throw source.closedCause ?: IOException("Channel was cancelled")
        }
        reading = true
    }

    /** Moves what [source] holds to the end of [own], capturing it; rethrows the source's failure. */
    private fun pull() {
        val buffered = try {
            source.readBuffer
        } catch (cause: Throwable) {
            settle(cause)
            throw cause
        }
        val from = own.size
        val moved = buffered.transferTo(own)
        if (moved > 0) capture.capture(own, from, moved)
    }

    /**
     * Ends the transaction once: completed when [failure] is null, else failed with its origin. A
     * capture whose blocks need a join of more than [INLINE_JOIN] bytes is joined off the reader.
     */
    private fun settle(failure: Throwable?) {
        if (settled.load() || !settled.compareAndSet(false, true)) return
        when {
            // The part of the body that arrived stays in the failed record, joined as a complete one would be.
            failure != null && capture.joinSize > INLINE_JOIN -> exchange.failLater(joinScope, channelFailureOrigin(failure)) { capture.body(contentType) }
            failure != null -> exchange.fail(channelFailureOrigin(failure), partialBodyOrNull())
            capture.joinSize > INLINE_JOIN -> exchange.completeLater(joinScope) { capture.body(contentType) }
            else -> {
                val body = try {
                    capture.body(contentType)
                } catch (cause: Throwable) {
                    // Only a cancel racing the reader's last move can see the capture torn.
                    exchange.fail(cause)
                    return
                }
                exchange.complete(body)
            }
        }
    }

    private fun partialBodyOrNull(): CapturedBody? = try {
        capture.body(contentType)
    } catch (_: Throwable) {
        null
    }

    private companion object {
        /** Joining this much costs less than a dispatch; a larger join leaves the reader's thread. */
        const val INLINE_JOIN = 64 * 1024

        /** Where large captures are joined: nothing cancels it, so no transaction is lost there. */
        val joinScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        const val NOBODY = 0
        const val READER = 1
        const val CANCEL = 2
    }
}
