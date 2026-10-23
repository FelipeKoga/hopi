package dev.koga.hopi.ui

import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.ui.list.RowModelCache
import dev.koga.hopi.ui.list.StatusFilter
import dev.koga.hopi.ui.list.filterRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The list's per-update cost with a full default store (500 transactions): building every row
 * from scratch (the screen opening), rebuilding after one transaction changed (a request
 * completing while the screen is open), and filtering. Prints the medians; the bounds only catch
 * an order-of-magnitude regression, since CI machines vary.
 */
class RowModelBenchmarkTest {
    private val redactor = Redactor.create(queryParameters = listOf("token"))

    private val transactions: List<HttpTransaction> = (1L..500L).map { id ->
        val url = "https://api.example.com/v1/users/$id/orders?page=${id % 7}&token=t$id"
        when {
            id % 50 == 0L -> pending(id, url = url)
            id % 33 == 0L -> failed(id, url = url)
            else -> complete(id, code = if (id % 10 == 0L) 404 else 200, url = url, responseBody = textBody("""{"id":$id}"""))
        }
    }

    @Test
    fun rowModelBuildTimeFor500Transactions() {
        repeat(WARMUP) { RowModelCache().rowsFor(transactions, redactor) }
        val cold = median { RowModelCache().rowsFor(transactions, redactor) }

        val cache = RowModelCache()
        cache.rowsFor(transactions, redactor)
        val changed = transactions.toMutableList()
        var nextChange = 0
        repeat(WARMUP) { cache.rowsFor(transactions, redactor) }
        val oneChanged = median {
            // A different snapshot instance for one id, as when a pending request completes.
            val index = nextChange++ % changed.size
            changed[index] = complete(changed[index].id, url = changed[index].url)
            cache.rowsFor(changed, redactor)
        }
        val rows = cache.rowsFor(changed, redactor)
        val filtered = median { filterRows(rows, "orders?page=3", StatusFilter.Success) }

        val report = "row model, 500 transactions: cold build median ${cold.ms()}, " +
            "one changed median ${oneChanged.ms()}, search + filter median ${filtered.ms()}"
        System.err.println(report)

        assertEquals(500, rows.size)
        assertTrue(cold.inWholeMilliseconds < 250, report)
        assertTrue(oneChanged.inWholeMilliseconds < 50, report)
        assertTrue(filtered.inWholeMilliseconds < 50, report)
    }

    private inline fun median(block: () -> Unit): Duration {
        val samples = ArrayList<Duration>(RUNS)
        repeat(RUNS) {
            val mark = TimeSource.Monotonic.markNow()
            block()
            samples += mark.elapsedNow()
        }
        samples.sort()
        return samples[RUNS / 2]
    }

    private fun Duration.ms(): String = "${inWholeMicroseconds / 1000.0} ms"

    private companion object {
        const val WARMUP = 30
        const val RUNS = 41
    }
}
