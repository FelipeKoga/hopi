package dev.koga.hopi.ui.list

import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.ui.complete
import dev.koga.hopi.ui.failed
import dev.koga.hopi.ui.pending
import dev.koga.hopi.ui.textBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TransactionRowsTest {
    @Test
    fun rowShowsMethodCodePathHostDurationAndSize() {
        val row = buildRow(complete(1, responseBody = textBody("x".repeat(2048))), Redactor.Default)
        assertEquals(StatusClass.Success, row.status)
        assertEquals("200", row.badge)
        assertEquals("Status 200", row.badgeDescription)
        assertEquals("GET", row.method)
        assertEquals("/users?page=2", row.path)
        assertEquals("api.example.com", row.host)
        assertEquals("183 ms", row.duration)
        assertEquals("2.0 KB", row.size)
        assertNull(row.error)
        assertEquals("api.example.com  ·  ${row.time}", row.subtitle)
    }

    @Test
    fun aSourceOtherThanKtorIsTagged() {
        val captured = buildRow(complete(1).copy(source = "urlsession"), Redactor.Default)
        assertEquals("api.example.com  ·  ${captured.time}  ·  URLSession", captured.subtitle)
        assertEquals("api.example.com  ·  ${captured.time}", buildRow(complete(2).copy(source = "ktor"), Redactor.Default).subtitle)
    }

    @Test
    fun statusClassesCoverEveryState() {
        val cases = mapOf(
            complete(1, code = 101) to StatusClass.Informational,
            complete(2, code = 204) to StatusClass.Success,
            complete(3, code = 302) to StatusClass.Redirect,
            complete(4, code = 404) to StatusClass.ClientError,
            complete(5, code = 503) to StatusClass.ServerError,
            failed(6) to StatusClass.Failed,
            pending(7) to StatusClass.Pending,
        )
        for ((transaction, expected) in cases) assertEquals(expected, statusOf(transaction), "id ${transaction.id}")
    }

    @Test
    fun pendingAndFailedRowsAreMarked() {
        val pendingRow = buildRow(pending(1), Redactor.Default)
        assertEquals("···", pendingRow.badge)
        assertEquals("Pending", pendingRow.badgeDescription)
        assertEquals("pending", pendingRow.duration)
        assertEquals("", pendingRow.size)

        val failedRow = buildRow(failed(2), Redactor.Default)
        assertEquals("ERR", failedRow.badge)
        assertEquals("Failed", failedRow.badgeDescription)
        assertEquals("12 ms", failedRow.duration)
        assertEquals("java.net.UnknownHostException: down.example.com", failedRow.error)
        assertEquals(failedRow.error, failedRow.subtitle)
    }

    @Test
    fun rowsUseTheRedactedUrlAndNeverShowUserinfo() {
        val redactor = Redactor.create(queryParameters = listOf("token"))
        val row = buildRow(complete(1, url = "https://user:pw@api.example.com:8443/a?token=s3cret&x=1"), redactor)
        assertEquals("api.example.com:8443", row.host)
        assertEquals("/a?token=<redacted>&x=1", row.path)
        assertEquals(false, row.searchKey.contains("s3cret"))
        // Neither the user nor the password is in the search key: searching for them finds nothing.
        assertEquals("https://<redacted>@api.example.com:8443/a?token=<redacted>&x=1", row.searchKey)
        assertEquals(0, filterRows(listOf(row), "pw@", StatusFilter.All).rows.size)
    }

    @Test
    fun hostOnlyUrlShowsRootPath() {
        assertEquals("/", buildRow(complete(1, url = "https://example.com"), Redactor.Default).path)
        assertEquals("/?q=1", buildRow(complete(2, url = "https://example.com?q=1"), Redactor.Default).path)
    }

    @Test
    fun cacheReturnsNewestFirstAndReusesUnchangedSnapshots() {
        val cache = RowModelCache()
        val one = complete(1)
        val two = pending(2)
        val first = cache.rowsFor(listOf(one, two), Redactor.Default)
        assertEquals(listOf(2L, 1L), first.map { it.id })
        assertEquals(2, cache.built)

        // Same snapshots: nothing is rebuilt, and the very same row objects come back.
        val again = cache.rowsFor(listOf(one, two), Redactor.Default)
        assertEquals(2, cache.built)
        assertSame(first[0], again[0])
        assertSame(first[1], again[1])

        // The pending one completed: only that row is rebuilt.
        val twoDone = complete(2, code = 500)
        val third = cache.rowsFor(listOf(one, twoDone, complete(3)), Redactor.Default)
        assertEquals(4, cache.built)
        assertEquals(listOf(3L, 2L, 1L), third.map { it.id })
        assertEquals(StatusClass.ServerError, third[1].status)
        assertSame(first[1], third[2])
    }

    @Test
    fun cacheRebuildsWhenTheRedactorChangesAndForgetsEvictedRows() {
        val cache = RowModelCache()
        val one = complete(1, url = "https://a.example/x?token=t")
        cache.rowsFor(listOf(one), Redactor.Default)
        val redacting = Redactor.create(queryParameters = listOf("token"))
        val rows = cache.rowsFor(listOf(one), redacting)
        assertEquals(2, cache.built)
        assertEquals("/x?token=<redacted>", rows.single().path)

        // Id 1 left the store; when it shows up again (it cannot, but a cache must not assume) it is rebuilt.
        cache.rowsFor(emptyList(), redacting)
        cache.rowsFor(listOf(one), redacting)
        assertEquals(3, cache.built)
    }

    @Test
    fun searchMatchesUrlSubstringsCaseInsensitively() {
        val rows = RowModelCache().rowsFor(
            listOf(
                complete(1, url = "https://api.example.com/users"),
                complete(2, url = "https://api.example.com/Orders/7"),
                complete(3, url = "https://cdn.example.net/img.png"),
            ),
            Redactor.Default,
        )
        assertEquals(listOf(2L), filterRows(rows, "  orders/ ", StatusFilter.All).rows.map { it.id })
        assertEquals(listOf(2L, 1L), filterRows(rows, "API.EXAMPLE", StatusFilter.All).rows.map { it.id })
        assertEquals(listOf(3L, 2L, 1L), filterRows(rows, "", StatusFilter.All).rows.map { it.id })
        assertEquals(emptyList(), filterRows(rows, "nothing", StatusFilter.All).rows)
    }

    @Test
    fun statusFilterKeepsOneClassAndCountsFollowTheSearch() {
        val rows = RowModelCache().rowsFor(
            listOf(
                complete(1, code = 200),
                complete(2, code = 201, url = "https://other.example/"),
                complete(3, code = 301),
                complete(4, code = 404),
                complete(5, code = 500),
                failed(6),
                pending(7),
            ),
            Redactor.Default,
        )
        assertEquals(listOf(4L), filterRows(rows, "", StatusFilter.ClientError).rows.map { it.id })
        assertEquals(listOf(6L), filterRows(rows, "", StatusFilter.Failed).rows.map { it.id })
        assertEquals(listOf(7L), filterRows(rows, "", StatusFilter.Pending).rows.map { it.id })
        assertEquals(listOf(5L), filterRows(rows, "", StatusFilter.ServerError).rows.map { it.id })
        assertEquals(listOf(3L), filterRows(rows, "", StatusFilter.Redirect).rows.map { it.id })
        assertEquals(listOf(2L, 1L), filterRows(rows, "", StatusFilter.Success).rows.map { it.id })

        val all = filterRows(rows, "", StatusFilter.All)
        assertEquals(7, all.total)
        assertEquals(listOf(7, 2, 1, 1, 1, 1, 1), all.counts.toList())

        val searched = filterRows(rows, "api.example.com", StatusFilter.Success)
        assertEquals(listOf(1L), searched.rows.map { it.id })
        assertEquals(7, searched.total)
        assertEquals(listOf(5, 1, 1, 1, 1, 0, 1), searched.counts.toList())
    }

    @Test
    fun aRedirectThatChangedTheHostIsShownAndSearchable() {
        val moved = buildRow(complete(1).copy(finalUrl = "https://cdn.example.org/a.json"), Redactor.Default)
        assertEquals("api.example.com → cdn.example.org  ·  ${moved.time}", moved.subtitle)
        assertTrue("cdn.example.org" in moved.searchKey)
        val sameHost = buildRow(complete(2).copy(finalUrl = "https://api.example.com/v2/users"), Redactor.Default)
        assertEquals("api.example.com → redirected  ·  ${sameHost.time}", sameHost.subtitle)
    }

    @Test
    fun aRuledRequestIsTagged() {
        assertEquals(emptyList(), buildRow(complete(1), Redactor.Default).tags)
        assertEquals(listOf("MOCK"), buildRow(complete(1).copy(ruleId = 3, ruleApplied = "delay,mock"), Redactor.Default).tags)
        assertEquals(listOf("FAIL"), ruleTags(failed(1).copy(ruleId = 3, ruleApplied = "delay,fail")))
        assertEquals(listOf("EDIT"), ruleTags(complete(1).copy(ruleId = 3, ruleApplied = "delay,edit-request")))
        assertEquals(listOf("DELAY"), ruleTags(complete(1).copy(ruleId = 3, ruleApplied = "delay")))
    }
}
