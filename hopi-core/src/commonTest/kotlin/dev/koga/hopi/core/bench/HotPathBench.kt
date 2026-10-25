package dev.koga.hopi.core.bench

import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionSource
import kotlin.time.TimeSource

/** True when the hot-path benchmarks were asked for (`-Phopi.hotpath`); they return at once otherwise. */
internal expect fun hotPathBenchmarkEnabled(): Boolean

/** Runs [rounds] timed rounds of [block] (each [perRound] operations) after [warmups], and prints the median and best ns per operation. */
internal inline fun benchmark(name: String, perRound: Int, warmups: Int = 3, rounds: Int = 7, block: (Int) -> Unit): Double {
    repeat(warmups) { block(perRound) }
    val results = DoubleArray(rounds) {
        val mark = TimeSource.Monotonic.markNow()
        block(perRound)
        mark.elapsedNow().inWholeNanoseconds.toDouble() / perRound
    }
    results.sort()
    val median = results[rounds / 2]
    println("HOTPATH $name median=${format(median)} ns/op best=${format(results[0])} ns/op (${rounds}x$perRound)")
    return median
}

internal fun format(value: Double): String {
    val tenths = (value * 10).toLong()
    return "${tenths / 10}.${tenths % 10}"
}

private val requestHeaders = listOf(
    "Accept" to "application/json",
    "Accept-Encoding" to "gzip",
    "User-Agent" to "okhttp/4.12.0",
    "Authorization" to "Bearer abc.def.ghi",
    "X-Request-Id" to "6f1c2a7e-0d6b-4c1e-9d55-1f6c1c3e2b1a",
    "Host" to "api.example.com",
)

private val responseHeaders = listOf(
    "Content-Type" to "application/json; charset=utf-8",
    "Content-Length" to "17",
    "Date" to "Sat, 24 Oct 2026 19:31:00 GMT",
    "Cache-Control" to "no-cache",
    "Server" to "devserver",
    "Connection" to "keep-alive",
    "X-Trace" to "a1b2c3",
    "Vary" to "Accept-Encoding",
)

private val body = "{\"ok\":true,\"n\":1}".encodeToByteArray()

/** A small request as the sample's `/json` makes it: the shape every per-request benchmark records. */
internal fun benchPending(id: Long): HttpTransaction = HttpTransaction.pending(
    id, 1_761_334_260_000 + id, "GET", "https://api.example.com/v1/users/42?page=2&sort=name", requestHeaders,
    requestBody = null, source = TransactionSource.OKHTTP,
)

internal fun benchComplete(id: Long): HttpTransaction = benchPending(id).completed(
    200, responseHeaders, CapturedBody(body, body.size.toLong(), false, "application/json; charset=utf-8", null), 3, "HTTP/1.1",
)
