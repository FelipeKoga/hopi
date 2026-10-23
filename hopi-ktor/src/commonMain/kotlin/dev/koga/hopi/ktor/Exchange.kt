package dev.koga.hopi.ktor

import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.store.HopiStore
import io.ktor.client.statement.HttpResponse
import io.ktor.util.AttributeKey
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

internal val ExchangeKey = AttributeKey<Exchange>("HopiExchange")

internal enum class ResponsePlan {
    /** No response seen yet. */
    None,

    /** The body is known (or absent); the transaction completes when the call returns. */
    Ready,

    /** Content-Length fits the cap: read the saved body after `SaveBody`, or tee if it is not saved. */
    AfterSave,

    /** A `Content-Encoding` is set: capture what a decoder at `State` hands the app. */
    Decoded,

    /** The body is teed; the capture side completes the transaction. */
    Teed,
}

/**
 * One send of one request. Pipeline hooks run sequentially on the call's coroutine; the capture
 * side starts after the response fields are set. Exactly one terminal snapshot is submitted.
 */
internal class Exchange(
    private val store: HopiStore,
    private val pending: HttpTransaction,
    private val started: TimeSource.Monotonic.ValueTimeMark,
    private val streamedRequest: StreamedRequestBody?,
) {
    private val finished = AtomicBoolean(false)

    var plan: ResponsePlan = ResponsePlan.None
        private set
    private var responseCode = 0
    private var responseHeaders: List<Pair<String, String>> = emptyList()
    private var protocol: String? = null
    private var responseBody: CapturedBody? = null

    val method: String get() = pending.method

    fun recordResponse(response: HttpResponse) {
        responseCode = response.status.value
        responseHeaders = flatten(response)
        protocol = response.version.toString()
    }

    fun ready(body: CapturedBody?) {
        responseBody = body
        plan = ResponsePlan.Ready
    }

    fun afterSave() {
        plan = ResponsePlan.AfterSave
    }

    fun decoded() {
        plan = ResponsePlan.Decoded
    }

    fun teed() {
        plan = ResponsePlan.Teed
    }

    /** The send pipeline returned: complete unless the capture side owns completion. */
    fun callReturned() {
        when (plan) {
            ResponsePlan.Teed -> Unit
            ResponsePlan.None -> fail(IllegalStateException("no response"))
            ResponsePlan.Ready, ResponsePlan.AfterSave, ResponsePlan.Decoded -> complete(responseBody)
        }
    }

    fun complete(body: CapturedBody?) {
        if (!finished.compareAndSet(false, true)) return
        store.submit(
            withRequestBody().completed(responseCode, responseHeaders, body, elapsedMs(), protocol),
        )
    }

    fun fail(cause: Throwable) {
        if (!finished.compareAndSet(false, true)) return
        store.submit(withRequestBody().failed(describe(cause), elapsedMs()))
    }

    private fun withRequestBody(): HttpTransaction =
        if (streamedRequest == null) pending else pending.copy(requestBody = streamedRequest.body())

    private fun elapsedMs(): Long = started.elapsedNow().inWholeMilliseconds

    private companion object {
        fun flatten(response: HttpResponse): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            for ((name, values) in response.headers.entries()) {
                for (value in values) out += name to value
            }
            return out
        }

        fun describe(cause: Throwable): String {
            if (cause is CancellationException) return "cancelled"
            val name = cause::class.simpleName ?: "Throwable"
            val message = cause.message
            return if (message.isNullOrEmpty()) name else "$name: $message"
        }
    }
}
