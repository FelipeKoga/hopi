package dev.koga.hopi.core.redact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RedactorTest {
    @Test
    fun defaultHeadersAreRedactedCaseInsensitively() {
        val r = Redactor.Default
        for (name in listOf("Authorization", "authorization", "PROXY-AUTHORIZATION", "Cookie", "set-cookie")) {
            assertTrue(r.isRedactedHeader(name), name)
            assertEquals("<redacted>", r.headerValue(name, "secret"))
        }
        assertFalse(r.isRedactedHeader("Accept"))
        assertEquals("json", r.headerValue("Accept", "json"))
    }

    @Test
    fun extraHeadersAddToTheDefaults() {
        val r = Redactor.create(extraHeaders = listOf("X-Api-Key"))
        assertTrue(r.isRedactedHeader("x-api-key"))
        assertTrue(r.isRedactedHeader("Authorization"))
    }

    @Test
    fun headerListKeepsOrderAndDuplicates() {
        val headers = listOf("Cookie" to "a=1", "Accept" to "*/*", "Cookie" to "b=2")
        assertEquals(
            listOf("Cookie" to "<redacted>", "Accept" to "*/*", "Cookie" to "<redacted>"),
            Redactor.Default.headers(headers),
        )
        val clean = listOf("Accept" to "*/*")
        assertSame(clean, Redactor.Default.headers(clean))
    }

    @Test
    fun queryParameterValuesAreRedacted() {
        val r = Redactor.create(queryParameters = listOf("token", "key"))
        assertEquals("https://h/p?token=<redacted>&page=2", r.url("https://h/p?token=abc&page=2"))
        assertEquals("https://h/p?page=2&TOKEN=<redacted>#frag", r.url("https://h/p?page=2&TOKEN=abc#frag"))
        assertEquals("https://h/p?a=1&key=<redacted>&key=<redacted>", r.url("https://h/p?a=1&key=x&key=y"))
        assertEquals("https://h/p?to%6Ben=<redacted>", r.url("https://h/p?to%6Ben=abc"))
        assertEquals("https://h/p?token&token=&x=1", r.url("https://h/p?token&token=&x=1"))
        assertEquals("https://h/p?a=1&&b=2&", r.url("https://h/p?a=1&&b=2&"))
        assertEquals("https://h/p#token=abc", r.url("https://h/p#token=abc"))
        val untouched = "https://h/p?page=2"
        assertSame(untouched, r.url(untouched))
    }

    @Test
    fun disabledModeRedactsNothing() {
        val r = Redactor.create(extraHeaders = listOf("X"), queryParameters = listOf("token"), enabled = false)
        assertFalse(r.enabled)
        assertEquals("secret", r.headerValue("Authorization", "secret"))
        assertEquals("https://h/?token=abc", r.url("https://h/?token=abc"))
        assertSame(Redactor.Disabled, r)
    }

    @Test
    fun unionRedactsWhatEitherRedactsAndIsDisabledOnlyWhenBothAre() {
        val a = Redactor.create(extraHeaders = listOf("X-Api-Key"))
        val b = Redactor.create(queryParameters = listOf("token"))
        val both = a.union(b)
        assertTrue(both.isRedactedHeader("X-Api-Key"))
        assertEquals("https://h/?token=<redacted>", both.url("https://h/?token=abc"))
        assertSame(a, a.union(Redactor.Disabled))
        assertSame(b, Redactor.Disabled.union(b))
        assertSame(Redactor.Disabled, Redactor.Disabled.union(Redactor.Disabled))
        assertSame(both, both.union(a))
    }
}
