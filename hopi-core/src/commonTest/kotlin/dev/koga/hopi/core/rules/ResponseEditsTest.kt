package dev.koga.hopi.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals

/** The headers every source builds the same way for a mock or a response edit ([ResponseEdits]). */
class ResponseEditsTest {
    @Test
    fun noStoreReplacesEveryCacheControlWithOneAndKeepsTheRestInOrder() {
        val headers = listOf(
            "Content-Type" to "text/plain",
            "Cache-Control" to "max-age=600",
            "X-A" to "1",
            "cache-control" to "public",
            "CACHE-CONTROL" to "no-transform, max-age=60",
            "X-B" to "2",
        )
        assertEquals(
            listOf("Content-Type" to "text/plain", "X-A" to "1", "X-B" to "2", "Cache-Control" to "no-store"),
            ResponseEdits.noStore(headers),
        )
        // With none, it is added; a rule's own no-store is not doubled.
        assertEquals(listOf("X-A" to "1", "Cache-Control" to "no-store"), ResponseEdits.noStore(listOf("X-A" to "1")))
        assertEquals(listOf("Cache-Control" to "no-store"), ResponseEdits.noStore(listOf("Cache-Control" to "no-store")))
        assertEquals(listOf("Cache-Control" to "no-store"), ResponseEdits.noStore(emptyList()))
    }

    @Test
    fun noStoreAppliesToAMocksAndAnEditsHeadersAsTheSourcesBuildThem() {
        val mock = MockSpec(200, listOf("Cache-Control" to "max-age=600", "Content-Type" to "text/plain"), RuleBody.text("mocked"))
        assertEquals(
            listOf("Content-Type" to "text/plain", "Content-Length" to "6", "Cache-Control" to "no-store"),
            ResponseEdits.noStore(ResponseEdits.mockHeaders(mock)),
        )
        // A header-only edit: the server's Cache-Control and the edit's own set one both give way.
        val edit = ResponseEdit(setHeaders = listOf("X-Edited" to "1"), addHeaders = listOf("Cache-Control" to "max-age=60"))
        assertEquals(
            listOf("Content-Type" to "text/plain", "X-Edited" to "1", "Cache-Control" to "no-store"),
            ResponseEdits.noStore(ResponseEdits.editHeaders(listOf("Cache-Control" to "max-age=600", "Content-Type" to "text/plain"), edit)),
        )
    }
}
