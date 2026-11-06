package dev.koga.hopi.core.curl

import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.pending
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.textBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CurlTest {
    @Test
    fun getWithoutBody() {
        assertEquals(
            "curl -X GET 'https://api.example.com/users?page=2'",
            toCurl(pending(1)),
        )
    }

    @Test
    fun userinfoIsRedactedUnlessRedactionIsDisabled() {
        val tx = pending(1, url = "https://alice:s3cret@api.example.com/v1")
        assertEquals("curl -X GET 'https://<redacted>@api.example.com/v1'", toCurl(tx))
        assertEquals("curl -X GET 'https://alice:s3cret@api.example.com/v1'", toCurl(tx, Redactor.Disabled))
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
            "curl -X GET 'https://h/?token=<redacted>&x=1' -H 'Authorization: <redacted>' -H 'X-Api-Key: <redacted>'",
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
    fun aBodyThatDoesNotFitMaxCharsIsCutInsideAClosedQuoteWithAWarning() {
        val body = "it's ".repeat(400_000) // 2M chars, a quote every 5
        val tx = pending(1, method = "POST", url = "https://h/", headers = listOf("Content-Length" to "2000000"), body = textBody(body))
        val curl = toCurl(tx, maxChars = 100_000)
        assertTrue(curl.length <= 100_000, "${curl.length} chars")
        val lines = curl.lines()
        assertTrue(lines[0].startsWith(CURL_BODY_CUT_WARNING), lines[0])
        val kept = Regex("first (\\d+) characters \\((\\d+) of 2000000 bytes\\)").find(lines[0])
        assertNotNull(kept, lines[0])
        assertEquals(kept.groupValues[1], kept.groupValues[2], "ASCII: one byte per char")
        assertEquals("# note: Content-Length header (2000000) omitted; curl sends the length of the body in this command (${kept.groupValues[1]})", lines[1])
        val command = lines[2]
        assertTrue(command.startsWith("curl -X POST 'https://h/' --data-raw 'it'\\''s "), command.take(60))
        // The quote is closed: undoing the quoting gives back exactly the kept prefix.
        assertTrue(command.endsWith("'"))
        val quoted = command.removePrefix("curl -X POST 'https://h/' --data-raw ")
        assertEquals(body.take(kept.groupValues[1].toInt()), quoted.removeSurrounding("'").replace("'\\''", "'"))
        // Each quote costs 4 chars, so 5 chars of body take 8: the budget is used almost whole.
        assertTrue(kept.groupValues[1].toInt() > 99_000 * 5 / 8, "the budget is mostly used: ${kept.groupValues[1]}")
    }

    @Test
    fun aCutNeverSplitsASurrogatePairAndABodyThatFitsIsUnchanged() {
        val emoji = "\uD83D\uDE00".repeat(300_000)
        val curl = toCurl(pending(1, method = "POST", url = "https://h/", body = textBody(emoji)), maxChars = 10_001)
        assertTrue(curl.length <= 10_001)
        val quoted = curl.lines().last().substringAfter("--data-raw '").removeSuffix("'")
        assertTrue(quoted.isNotEmpty() && quoted.length % 2 == 0 && !quoted.last().isHighSurrogate(), "${quoted.length}")
        val crowded = toCurl(pending(1, method = "POST", url = "https://h/" + "p".repeat(20_000), body = textBody("x".repeat(50_000))), maxChars = 10_000)
        assertTrue(crowded.startsWith("# warning: request body cut out (50000 bytes)"), crowded.take(80))
        assertTrue(!crowded.contains("--data-raw"))
        val small = pending(1, method = "POST", url = "https://h/", body = textBody("abc"))
        assertEquals(toCurl(small), toCurl(small, maxChars = 100_000))
        assertEquals("curl -X POST 'https://h/' --data-raw 'abc'", toCurl(small, maxChars = 100_000))
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
