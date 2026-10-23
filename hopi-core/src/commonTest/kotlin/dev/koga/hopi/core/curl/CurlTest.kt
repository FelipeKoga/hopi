package dev.koga.hopi.core.curl

import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.pending
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.textBody
import kotlin.test.Test
import kotlin.test.assertEquals

class CurlTest {
    @Test
    fun getWithoutBody() {
        assertEquals(
            "curl -X GET 'https://api.example.com/users?page=2'",
            toCurl(pending(1)),
        )
    }

    @Test
    fun headersInOrderWithDuplicates() {
        val tx = pending(1, headers = listOf("Accept" to "application/json", "X-Trace" to "a", "X-Trace" to "b"))
        assertEquals(
            "curl -X GET 'https://api.example.com/users?page=2' -H 'Accept: application/json' -H 'X-Trace: a' -H 'X-Trace: b'",
            toCurl(tx),
        )
    }

    @Test
    fun singleQuotesAreEscapedEverywhere() {
        val tx = pending(
            1,
            method = "POST",
            url = "https://h/search?q=it's",
            headers = listOf("X-Note" to "don't"),
            body = textBody("{\"name\":\"O'Brien\"}"),
        )
        assertEquals(
            "curl -X POST 'https://h/search?q=it'\\''s' -H 'X-Note: don'\\''t' --data-raw '{\"name\":\"O'\\''Brien\"}'",
            toCurl(tx),
        )
    }

    @Test
    fun shellMetacharactersStayLiteral() {
        val tx = pending(1, method = "POST", url = "https://h/\$(rm -rf)`x`", body = textBody("a\nb \$HOME \\ @file"))
        assertEquals(
            "curl -X POST 'https://h/\$(rm -rf)`x`' --data-raw 'a\nb \$HOME \\ @file'",
            toCurl(tx),
        )
    }

    @Test
    fun nonTokenMethodIsQuoted() {
        assertEquals("curl -X 'GET;id' 'https://h/'", toCurl(pending(1, method = "GET;id", url = "https://h/")))
    }

    @Test
    fun compressedWhenGzipDeflateOrBrAdvertised() {
        for (value in listOf("gzip", "deflate", "br", "identity, GZIP;q=0.5")) {
            val tx = pending(1, url = "https://h/", headers = listOf("Accept-Encoding" to value))
            assertEquals("curl -X GET 'https://h/' -H 'Accept-Encoding: $value' --compressed", toCurl(tx))
        }
        val plain = pending(1, url = "https://h/", headers = listOf("Accept-Encoding" to "identity"))
        assertEquals("curl -X GET 'https://h/' -H 'Accept-Encoding: identity'", toCurl(plain))
    }

    @Test
    fun redactsHeadersAndQueryByDefaultAndOnlyWhenEnabled() {
        val tx = pending(
            1,
            url = "https://h/?token=abc&x=1",
            headers = listOf("Authorization" to "Bearer s3cret", "X-Api-Key" to "k"),
        )
        val redactor = Redactor.create(extraHeaders = listOf("X-Api-Key"), queryParameters = listOf("token"))
        assertEquals(
            "curl -X GET 'https://h/?token=<redacted>&x=1' -H 'Authorization: <redacted>' -H 'X-Api-Key: <redacted>'",
            toCurl(tx, redactor),
        )
        assertEquals(
            "curl -X GET 'https://h/?token=abc&x=1' -H 'Authorization: <redacted>' -H 'X-Api-Key: k'",
            toCurl(tx),
        )
        assertEquals(
            "curl -X GET 'https://h/?token=abc&x=1' -H 'Authorization: Bearer s3cret' -H 'X-Api-Key: k'",
            toCurl(tx, Redactor.Disabled),
        )
    }

    @Test
    fun binaryBodyUsesDataBinaryWithNote() {
        val body = CapturedBody(byteArrayOf(0, 1, 2, -1), 4, false, "application/octet-stream", null)
        val tx = pending(1, method = "PUT", url = "https://h/u", body = body)
        assertEquals(
            "# note: binary request body (4 bytes) is not inlined; save it as body.bin before running this command\n" +
                "curl -X PUT 'https://h/u' --data-binary '@body.bin'",
            toCurl(tx),
        )
    }

    @Test
    fun truncatedBodyWarns() {
        val tx = pending(1, method = "POST", url = "https://h/", body = textBody("abc", truncated = true, total = 10))
        assertEquals(
            "# warning: request body truncated to 3 of 10 bytes; this command sends the truncated body\n" +
                "curl -X POST 'https://h/' --data-raw 'abc'",
            toCurl(tx),
        )
    }

    @Test
    fun skippedBodyWarns() {
        val tx = pending(1, method = "POST", url = "https://h/", body = CapturedBody.skipped("streaming", "text/plain", null))
        assertEquals(
            "# warning: request body was not captured (streaming); this command sends no body\n" +
                "curl -X POST 'https://h/'",
            toCurl(tx),
        )
    }

    @Test
    fun multipartWarns() {
        val tx = pending(
            1,
            method = "POST",
            url = "https://h/",
            headers = listOf("Content-Type" to "multipart/form-data; boundary=x"),
            body = CapturedBody.skipped("multipart", "multipart/form-data; boundary=x", 100),
        )
        assertEquals(
            "# warning: multipart request body is not reproduced; this command sends no body\n" +
                "curl -X POST 'https://h/' -H 'Content-Type: multipart/form-data; boundary=x'",
            toCurl(tx),
        )
    }

    @Test
    fun emptyBodyAddsNoData() {
        val tx = pending(1, method = "POST", url = "https://h/", body = textBody(""))
        assertEquals("curl -X POST 'https://h/'", toCurl(tx))
    }

    @Test
    fun contentLengthIsLeftToCurlAndNotedWhenTheCommandSendsADifferentLength() {
        val full = pending(1, method = "POST", url = "https://h/", headers = listOf("Content-Length" to "3"), body = textBody("abc"))
        assertEquals("curl -X POST 'https://h/' --data-raw 'abc'", toCurl(full))
        val truncated = pending(
            1, method = "POST", url = "https://h/",
            headers = listOf("Content-Type" to "application/json", "Content-Length" to "10"),
            body = textBody("abc", truncated = true, total = 10),
        )
        assertEquals(
            "# warning: request body truncated to 3 of 10 bytes; this command sends the truncated body\n" +
                "# note: Content-Length header (10) omitted; curl sends the length of the body in this command (3)\n" +
                "curl -X POST 'https://h/' -H 'Content-Type: application/json' --data-raw 'abc'",
            toCurl(truncated),
        )
        val multipart = pending(
            1, method = "POST", url = "https://h/",
            headers = listOf("Content-Type" to "multipart/form-data; boundary=x", "content-length" to "100"),
            body = CapturedBody.skipped("multipart", "multipart/form-data; boundary=x", 100),
        )
        assertEquals(
            "# warning: multipart request body is not reproduced; this command sends no body\n" +
                "# note: Content-Length header (100) omitted; curl sends the length of the body in this command (0)\n" +
                "curl -X POST 'https://h/' -H 'Content-Type: multipart/form-data; boundary=x'",
            toCurl(multipart),
        )
    }

    @Test
    fun transferEncodingIsLeftToCurl() {
        val tx = pending(1, method = "POST", url = "https://h/", headers = listOf("Transfer-Encoding" to "chunked"), body = textBody("abc"))
        assertEquals(
            "# note: Transfer-Encoding header omitted; curl frames the body itself\n" +
                "curl -X POST 'https://h/' --data-raw 'abc'",
            toCurl(tx),
        )
    }

    @Test
    fun headUsesTheHeadOption() {
        assertEquals("curl -I 'https://h/'", toCurl(pending(1, method = "HEAD", url = "https://h/")))
        assertEquals("curl -I 'https://h/'", toCurl(pending(1, method = "head", url = "https://h/")))
        assertEquals(
            "curl -X HEAD 'https://h/' --data-raw 'x'",
            toCurl(pending(1, method = "HEAD", url = "https://h/", body = textBody("x"))),
            "-I cannot be combined with a body",
        )
    }

    @Test
    fun emptyHeaderValuesAreSentEmpty() {
        val tx = pending(1, url = "https://h/", headers = listOf("X-Empty" to "", "Accept" to " ", "X-Full" to "v"))
        assertEquals("curl -X GET 'https://h/' -H 'X-Empty;' -H 'Accept;' -H 'X-Full: v'", toCurl(tx))
    }
}
