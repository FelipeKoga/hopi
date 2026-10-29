package dev.koga.hopi.network.internal.ktor

import dev.koga.hopi.ktor.HopiLogSink
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * Per-request wall time of `client.get(url).bodyAsText()` against a 1 KB JSON MockEngine response,
 * with and without the plugin. Prints medians, p95 and deltas; never fails on timing.
 */
class HopiOverheadBenchmark {
    private val body = buildString {
        append("{\"items\":[")
        var i = 0
        while (length < 1000) {
            if (i > 0) append(',')
            append("{\"id\":").append(i).append(",\"name\":\"item-").append(i).append("\"}")
            i++
        }
        append("]}")
    }

    @Test
    fun overheadPerRequest() = runBlocking<Unit> {
        val withLength = headersOf(
            HttpHeaders.ContentType to listOf("application/json"),
            HttpHeaders.ContentLength to listOf(body.encodeToByteArray().size.toString()),
        )
        val withoutLength = headersOf(HttpHeaders.ContentType, "application/json")
        val lines = mutableListOf("Hopi overhead, ${body.length} B JSON body, $MEASURED requests per arm after $WARMUP warm-up")
        TestStore(channelCapacity = 4096).use { t ->
            for ((label, headers) in listOf("Content-Length" to withLength, "chunked" to withoutLength)) {
                val baseline = Arm { HttpClient(engine(headers)) }
                val plugin = Arm {
                    HttpClient(engine(headers)) {
                        hopi(t.store) {
                            logSink = HopiLogSink.Off
                        }
                    }
                }
                repeat(ROUNDS) {
                    baseline.run(WARMUP / ROUNDS, record = false)
                    plugin.run(WARMUP / ROUNDS, record = false)
                }
                repeat(ROUNDS) {
                    baseline.run(MEASURED / ROUNDS, record = true)
                    plugin.run(MEASURED / ROUNDS, record = true)
                }
                val b = baseline.stats()
                val p = plugin.stats()
                lines += "[$label] baseline median ${us(b.median)} µs, p95 ${us(b.p95)} µs | " +
                    "plugin median ${us(p.median)} µs, p95 ${us(p.p95)} µs | " +
                    "delta median ${us(p.median - b.median)} µs, p95 ${us(p.p95 - b.p95)} µs"
            }
        }
        println(lines.joinToString("\n"))
    }

    private fun engine(headers: Headers) = MockEngine { respond(body, HttpStatusCode.OK, headers) }

    private inner class Arm(private val newClient: () -> HttpClient) {
        private val samples = LongArray(MEASURED)
        private var count = 0
        private var client = newClient()
        private var sinceFresh = 0

        suspend fun run(requests: Int, record: Boolean) {
            repeat(requests) {
                // MockEngine keeps every request and response; a fresh one bounds that history.
                if (sinceFresh == FRESH_CLIENT_EVERY) {
                    client.close()
                    client = newClient()
                    sinceFresh = 0
                }
                val start = System.nanoTime()
                client.get("https://api.example.com/items").bodyAsText()
                val elapsed = System.nanoTime() - start
                sinceFresh++
                if (record) samples[count++] = elapsed
            }
        }

        fun stats(): Stats {
            val sorted = samples.copyOf(count).also { it.sort() }
            return Stats(sorted[sorted.size / 2], sorted[(sorted.size * 95) / 100])
        }
    }

    private class Stats(val median: Long, val p95: Long)

    private fun us(nanos: Long): String = ((nanos / 100) / 10.0).toString()

    private companion object {
        const val WARMUP = 10_000
        const val MEASURED = 20_000
        const val ROUNDS = 4
        const val FRESH_CLIENT_EVERY = 5_000
    }
}
