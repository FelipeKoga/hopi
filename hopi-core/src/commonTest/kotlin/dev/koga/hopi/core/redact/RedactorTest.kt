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
        assertEquals("https://h/p#token=<redacted>", r.url("https://h/p#token=abc"))
        assertEquals("https://h/p#/route?token=<redacted>", r.url("https://h/p#/route?token=abc"))
        val untouched = "https://h/p?page=2"
        assertSame(untouched, r.url(untouched))
    }

    @Test
    fun userinfoIsMaskedWheneverRedactionIsOn() {
        val plain = Redactor.Default
        assertEquals("https://<redacted>@api.example.com/v1", plain.url("https://alice:s3cret@api.example.com/v1"))
        assertEquals("http://<redacted>@127.0.0.1:8765", plain.url("http://u@127.0.0.1:8765"))
        // The userinfo ends at the authority's last `@`; an `@` in the path or query is not userinfo.
        assertEquals("https://<redacted>@h/p?mail=a@b", plain.url("https://a@b:p@ss@h/p?mail=a@b"))
        val noUserinfo = "https://h/users/@me?x=a@b#c@d"
        assertSame(noUserinfo, plain.url(noUserinfo))
        val both = Redactor.create(queryParameters = listOf("token"))
        assertEquals("https://<redacted>@h/p?token=<redacted>", both.url("https://u:pw@h/p?token=abc"))
        assertEquals(
            "failed to reach https://<redacted>@h/p",
            plain.textMentioning("failed to reach https://u:pw@h/p", "https://u:pw@h/p"),
        )
        assertEquals("https://u:pw@h/p", Redactor.Disabled.url("https://u:pw@h/p"))
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

    @Test
    fun credentialsAreRedactedByDefault() {
        val r = Redactor.Default
        for (name in listOf("X-Api-Key", "x-auth-token", "X-Amz-Security-Token", "X-CSRF-Token")) {
            assertEquals("<redacted>", r.headerValue(name, "secret"), name)
        }
        assertEquals(
            "https://h/cb?code=<redacted>&state=s&access_token=<redacted>",
            r.url("https://h/cb?code=c&state=s&access_token=t"),
        )
        assertEquals(
            "https://b.s3.amazonaws.com/o?X-Amz-Credential=<redacted>&X-Amz-Signature=<redacted>&X-Amz-Expires=60",
            r.url("https://b.s3.amazonaws.com/o?X-Amz-Credential=AK%2F1&X-Amz-Signature=ab12&X-Amz-Expires=60"),
        )
        assertEquals("https://h/app#access_token=<redacted>&token_type=bearer", r.url("https://h/app#access_token=t&token_type=bearer"))
        assertEquals("https://h/p?a=1;token=<redacted>&key[]=<redacted>", r.url("https://h/p?a=1;token=t&key[]=k"))
        assertEquals("https://h/p?page=2", r.url("https://h/p?page=2"))
    }

    @Test
    fun headersThatCarryUrlsAreRedactedAsUrls() {
        val r = Redactor.Default
        assertEquals("https://h/cb?code=<redacted>", r.headerValue("Location", "https://h/cb?code=c"))
        assertEquals("https://<redacted>@h/", r.headerValue("referer", "https://u:pw@h/"))
        assertEquals(
            listOf("Location" to "/next?token=<redacted>", "Accept" to "*/*"),
            r.headers(listOf("Location" to "/next?token=t", "Accept" to "*/*")),
        )
        val clean = listOf("Location" to "https://h/next")
        assertSame(clean, r.headers(clean))
        assertEquals("https://h/cb?code=c", Redactor.Disabled.headerValue("Location", "https://h/cb?code=c"))
    }

    @Test
    fun everyUrlQuotedInATextIsRedacted() {
        val r = Redactor.Default
        assertEquals(
            "Request timeout has expired [url=https://h/next?token=<redacted>]",
            r.textMentioning("Request timeout has expired [url=https://h/next?token=t]", "https://h/start"),
        )
        assertEquals(
            "from https://<redacted>@a/x to http://b/y?key=<redacted>.",
            r.textMentioning("from https://u:p@a/x to http://b/y?key=k.", "https://u:p@a/x"),
        )
        val plain = "connection reset"
        assertSame(plain, r.textMentioning(plain, "https://h/"))
        assertEquals("https://h/?token=t", Redactor.Disabled.textMentioning("https://h/?token=t", "https://h/?token=t"))
    }

    @Test
    fun secretJsonFieldsAreRedactedInBodies() {
        val r = Redactor.Default
        assertEquals(
            """{"accessToken":"<redacted>","expires_in":3600,"user":{"name":"a","password":"<redacted>"},"note":"\"token\": x"}""",
            r.body("""{"accessToken":"eyJ.a.b","expires_in":3600,"user":{"name":"a","password":"p\"w"},"note":"\"token\": x"}""", "application/json"),
        )
        assertEquals("""[{"otp" : "<redacted>"}, {"API-KEY":"<redacted>"}]""", r.body("""[{"otp" : 123456}, {"API-KEY":null}]""", null))
        // A nested object under a secret name keeps its shape; a truncated value is cut to the end.
        assertEquals("""{"secret":{"token":"<redacted>"},"refresh_token":"<redacted>"""", r.body("""{"secret":{"token":"t"},"refresh_token":"abc""", "application/json"))
        val plain = """{"code":"BR","key":"name","page":2}"""
        assertSame(plain, r.body(plain, "application/json"))
        assertEquals("""{"token":"t"}""", Redactor.Disabled.body("""{"token":"t"}""", "application/json"))
    }

    @Test
    fun formFieldsAreRedactedLikeAQuery() {
        val r = Redactor.Default
        assertEquals(
            "grant_type=authorization_code&code=<redacted>&client_secret=<redacted>&redirect_uri=x",
            r.body("grant_type=authorization_code&code=c&client_secret=s&redirect_uri=x", "application/x-www-form-urlencoded; charset=utf-8"),
        )
        val text = "token=abc"
        assertSame(text, r.body(text, "text/plain"))
    }

    @Test
    fun configuredQueryParametersAreBodyFieldsToo() {
        val r = Redactor.create(queryParameters = listOf("tenant_secret"))
        assertEquals("""{"tenantSecret":"<redacted>"}""", r.body("""{"tenantSecret":"s"}""", "application/json"))
    }
}
