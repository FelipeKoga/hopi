package dev.koga.hopi.core.rules

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.store.HopiStore
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The per-source replay hook (docs/design/rules.md, "Replay"): a small object a recorder creates
 * once per client or session (never per request) and sets on the terminal records it writes
 * ([HttpTransaction.replayTarget]), holding the client weakly. It lives in the record, so eviction
 * and `clear` drop it with the record. Implemented by each source in its own module.
 */
@HopiInternalApi
public interface ReplayTarget {
    /** The [dev.koga.hopi.core.model.TransactionSource] of the records it serves. */
    public val source: String

    /** Null when [send] can run now; else why not (the client was closed or collected), sent as `UNSUPPORTED`. */
    public fun refusal(): String?

    /**
     * Sends [request] through the original client or session, asynchronously, handing [handoff] to
     * the recorder (a request attribute, a tag, a task state) so it records under [ReplayHandoff.id].
     * Must call [ReplayHandoff.finish] exactly once when the send has ended, whatever happened.
     * May throw only before anything was sent; the engine then finishes the hand-off itself.
     */
    public fun send(request: ReplayRequest, handoff: ReplayHandoff)
}

/** The request a replay re-sends: the recorded one, unredacted, with the replay's edit applied. */
@HopiInternalApi
public class ReplayRequest(
    public val method: String,
    public val url: String,
    public val headers: List<Pair<String, String>>,
    /** Null when the request has no body. */
    public val body: ByteArray?,
    public val contentType: String?,
    /**
     * Null unless the replay's edit changed the origin; then the lowercased credential and redacted
     * names it removed and does not supply itself ([RequestEdits.crossOriginStripped]), which a
     * target whose stack adds headers below Hopi must refuse to let through.
     */
    public val crossOriginStripped: Set<String>? = null,
)

/**
 * The promise a replay makes to its caller: a record with [id] and `replayOf` [replayOf] will
 * exist. The recorder [take]s it when it records under [id]; when the send ends without that (the
 * client closed between the check and the send, the call was cancelled before the recorder ran,
 * the factory threw), [finish] submits a `Failed` record under [id] itself.
 */
@HopiInternalApi
public class ReplayHandoff internal constructor(
    public val id: Long,
    public val replayOf: Long,
    private val store: HopiStore,
    private val original: ReplayRequest,
    private val startedAtEpochMs: Long,
) {
    private val taken = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    /** The recorder records under [id]: true once, false for a second recorder of the same send. */
    public fun take(): Boolean = taken.compareAndSet(false, true)

    public val isTaken: Boolean get() = taken.load()

    /**
     * The send ended; [error] is what it threw, if anything. Idempotent. Ends the replay's
     * in-flight count, and records the failure when no recorder took the hand-off.
     */
    public fun finish(error: String?) {
        if (!finished.compareAndSet(false, true)) return
        RuleEngine.endReplay()
        if (take()) {
            store.submit(
                HttpTransaction.pending(id, startedAtEpochMs, original.method, original.url, original.headers, original.body?.let(::captured))
                    .copy(replayOf = replayOf)
                    .failed(error ?: "replay ended before it was sent", durationMs = 0),
            )
        }
    }

    private fun captured(bytes: ByteArray) = CapturedBody(bytes, bytes.size.toLong(), truncated = false, original.contentType, skippedReason = null)
}

@HopiInternalApi
public sealed interface ReplayResult {
    public class Started(public val id: Long, public val of: Long) : ReplayResult

    public class Refused(public val error: RuleError) : ReplayResult
}

/** Starts replays; only ever explicit (a bridge `replay`, `HopiRules.replay`, a Replay button). */
@HopiInternalApi
public object Replays {
    /**
     * Re-sends record [transactionId] of [store], with [edit] applied, through the client that sent
     * it. Returns the new record's id at once, before the request runs.
     */
    @OptIn(ExperimentalTime::class)
    public fun start(store: HopiStore, transactionId: Long, edit: RequestEdit?): ReplayResult {
        val transaction = store.get(transactionId) ?: return refused(RuleErrorCode.NOT_FOUND, "no transaction $transactionId")
        edit?.let { RuleValidation.validateReplayEdit(it)?.let { error -> return ReplayResult.Refused(error) } }
        val target = transaction.replayTarget
            ?: return refused(RuleErrorCode.UNSUPPORTED, "transaction $transactionId cannot be replayed: its recorder offers no replay for it")
        target.refusal()?.let { return refused(RuleErrorCode.UNSUPPORTED, "transaction $transactionId cannot be replayed: $it") }
        val body = transaction.requestBody
        if (edit?.body == null && body != null && !body.heldInFull) {
            return refused(RuleErrorCode.UNSUPPORTED, "transaction $transactionId cannot be replayed: request body not held in full")
        }
        val edited = edit?.let {
            RequestEdits.apply(transaction.method, transaction.url, transaction.requestHeaders, it, store.redactor)
                ?: return refused(RuleErrorCode.UNSUPPORTED, "transaction $transactionId cannot be replayed: its URL cannot be edited")
        }
        val request = ReplayRequest(
            method = edited?.method ?: transaction.method,
            url = edited?.url ?: transaction.url,
            headers = edited?.headers ?: transaction.requestHeaders,
            body = edited?.body?.bytes ?: body?.bytes?.takeIf { body.bytes.isNotEmpty() || body.totalBytes == 0L },
            contentType = body?.contentType,
            crossOriginStripped = edit?.takeIf { edited?.originChanged == true }?.let { RequestEdits.crossOriginStripped(it, store.redactor) },
        )
        if (!RuleEngine.beginReplay()) return refused(RuleErrorCode.LIMIT, "replay: at most ${RuleLimits.MAX_REPLAYS_IN_FLIGHT} in flight")
        val handoff = ReplayHandoff(store.nextId(), transactionId, store, request, Clock.System.now().toEpochMilliseconds())
        try {
            target.send(request, handoff)
        } catch (e: Throwable) {
            handoff.finish(e.toString())
        }
        return ReplayResult.Started(handoff.id, transactionId)
    }

    private val CapturedBody.heldInFull: Boolean get() = skippedReason == null && !truncated

    private fun refused(code: RuleErrorCode, message: String) = ReplayResult.Refused(RuleError(code, message))
}
