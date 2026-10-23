package dev.koga.hopi.ui.list

import dev.koga.hopi.core.body.formatBytes
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.ui.format.formatDuration
import dev.koga.hopi.ui.format.splitUrl
import dev.koga.hopi.ui.platform.formatTimeOfDay

/** What a status badge shows; also what the status filter matches. */
internal enum class StatusClass { Informational, Success, Redirect, ClientError, ServerError, Failed, Pending }

internal enum class StatusFilter(val label: String) {
    All("All"),
    Success("2xx"),
    Redirect("3xx"),
    ClientError("4xx"),
    ServerError("5xx"),
    Failed("Failed"),
    Pending("Pending"),
    ;

    fun matches(status: StatusClass): Boolean = when (this) {
        All -> true
        Success -> status == StatusClass.Success
        Redirect -> status == StatusClass.Redirect
        ClientError -> status == StatusClass.ClientError
        ServerError -> status == StatusClass.ServerError
        Failed -> status == StatusClass.Failed
        Pending -> status == StatusClass.Pending
    }
}

/**
 * Every string one list row shows, built once per transaction snapshot so composing a row does no
 * formatting. [transaction] is the snapshot it was built from (the cache compares it by identity).
 */
internal class TransactionRow(
    val transaction: HttpTransaction,
    val status: StatusClass,
    /** `200`, `ERR` for a failure, `···` while pending. */
    val badge: String,
    val method: String,
    /** Path, query and fragment of the redacted URL; `/` when it has none. */
    val path: String,
    val host: String,
    val time: String,
    /** `183 ms`, `pending`, or `failed`. */
    val duration: String,
    /** Response size; empty when there is no response body. */
    val size: String,
    /** First line of the error, URL redacted; null unless failed. */
    val error: String?,
    /** The redacted URL, lowercased, for the substring search. */
    val searchKey: String,
) {
    /** What a screen reader says for the badge, whose glyphs (`···`, `ERR`) read poorly. */
    val badgeDescription: String = when (status) {
        StatusClass.Pending -> "Pending"
        StatusClass.Failed -> "Failed"
        else -> "Status $badge"
    }

    /** The second line: the error when failed, else host and start time. */
    val subtitle: String = error ?: "$host  ·  $time"

    val id: Long get() = transaction.id
}

internal fun buildRow(transaction: HttpTransaction, redactor: Redactor): TransactionRow {
    val url = redactor.url(transaction.url)
    val parts = splitUrl(url)
    val status = statusOf(transaction)
    return TransactionRow(
        transaction = transaction,
        status = status,
        badge = when (status) {
            StatusClass.Pending -> "···"
            StatusClass.Failed -> "ERR"
            else -> transaction.responseCode.toString()
        },
        method = transaction.method,
        path = parts.path,
        host = parts.host,
        time = formatTimeOfDay(transaction.startedAtEpochMs, withMillis = false),
        duration = when (transaction.state) {
            TransactionState.Pending -> "pending"
            else -> transaction.durationMs?.let(::formatDuration) ?: ""
        },
        size = transaction.responseBody?.let { formatBytes(it.reportedBytes) } ?: "",
        error = transaction.error?.let { redactor.textMentioning(it, transaction.url).substringBefore('\n') },
        searchKey = url.lowercase(),
    )
}

internal fun statusOf(transaction: HttpTransaction): StatusClass = when (transaction.state) {
    TransactionState.Pending -> StatusClass.Pending
    TransactionState.Failed -> StatusClass.Failed
    TransactionState.Complete -> when (transaction.responseCode ?: 0) {
        in 200..299 -> StatusClass.Success
        in 300..399 -> StatusClass.Redirect
        in 400..499 -> StatusClass.ClientError
        in 500..599 -> StatusClass.ServerError
        else -> StatusClass.Informational
    }
}

/**
 * Row models by transaction id. The store replaces a snapshot instead of mutating it, so a row is
 * rebuilt only when its id maps to a different snapshot instance (a pending transaction that
 * completed); every other row is reused as is. A change of redactor rebuilds everything.
 * Not thread-safe: one collector drives it.
 */
internal class RowModelCache {
    private var rows = HashMap<Long, TransactionRow>()
    private var redactor: Redactor? = null

    /** Rows built since the cache was created; tests read it to prove reuse. */
    var built: Int = 0
        private set

    /** [transactions] oldest first, as the store holds them; returns rows newest first. */
    fun rowsFor(transactions: List<HttpTransaction>, redactor: Redactor): List<TransactionRow> {
        if (redactor !== this.redactor) {
            rows = HashMap()
            this.redactor = redactor
        }
        val previous = rows
        val next = HashMap<Long, TransactionRow>(transactions.size * 4 / 3 + 1)
        val result = ArrayList<TransactionRow>(transactions.size)
        for (i in transactions.indices.reversed()) {
            val transaction = transactions[i]
            val cached = previous[transaction.id]
            val row = if (cached != null && cached.transaction === transaction) {
                cached
            } else {
                built++
                buildRow(transaction, redactor)
            }
            next[transaction.id] = row
            result += row
        }
        rows = next
        return result
    }
}

/** The list as shown: the rows that pass the search and the filter, and per-filter counts. */
internal class ListModel(
    val rows: List<TransactionRow>,
    val total: Int,
    /** Rows matching the search, per [StatusFilter] ordinal, so each chip shows its own count. */
    val counts: IntArray,
)

/**
 * Applies the URL substring [query] (case-insensitive, trimmed) and the status [filter] to [rows]
 * (newest first, kept in that order).
 */
internal fun filterRows(rows: List<TransactionRow>, query: String, filter: StatusFilter): ListModel {
    val needle = query.trim().lowercase()
    val filters = StatusFilter.entries
    val counts = IntArray(filters.size)
    val visible = ArrayList<TransactionRow>(rows.size)
    for (row in rows) {
        if (needle.isNotEmpty() && !row.searchKey.contains(needle)) continue
        for (f in filters) if (f.matches(row.status)) counts[f.ordinal]++
        if (filter.matches(row.status)) visible += row
    }
    return ListModel(visible, rows.size, counts)
}
