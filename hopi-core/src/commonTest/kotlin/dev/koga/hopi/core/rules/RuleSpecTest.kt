package dev.koga.hopi.core.rules

import dev.koga.hopi.core.json.parseJsonTree
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.redact.Redactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shorthand, validation, the wire format and request edits: everything about a rule but the engine. */
class RuleSpecTest {
    @Test
    fun shorthandForms() {
        val users = MatchShorthand.parse("GET */users/*")
        assertEquals(setOf("GET"), users.methods)
        assertNull(users.host)
        assertEquals("/users/*", users.path)

        val full = MatchShorthand.parse("POST|PUT https://api.example.com:8443/v1/**?page=2&debug&!trace")
        assertEquals(setOf("POST", "PUT"), full.methods)
        assertEquals("https", full.scheme)
        assertEquals("api.example.com", full.host)
        assertEquals(8443, full.port)
        assertEquals("/v1/**", full.path)
        assertEquals(listOf(Predicate("page", "2"), Predicate("debug", null), Predicate("trace", null, absent = true)), full.query)

        val any = MatchShorthand.parse("* *.cdn.example.com/**")
        assertNull(any.methods)
        assertEquals("*.cdn.example.com", any.host)
        assertNull(any.path, "/** is any path")

        val pathOnly = MatchShorthand.parse("/health")
        assertNull(pathOnly.host)
        assertEquals("/health", pathOnly.path)

        val hostOnly = MatchShorthand.parse("api.example.com")
        assertEquals("api.example.com", hostOnly.host)
        assertNull(hostOnly.path)

        val ipv6 = MatchShorthand.parse("http://[::1]:8080/x")
        assertEquals("::1", ipv6.host)
        assertEquals(8080, ipv6.port)

        assertEquals("GET */users/*", users.describe())
    }

    @Test
    fun shorthandErrorsNameTheMatch() {
        for (bad in listOf("", "GE T /a /b", "ftp://h/a", "h:0/a", "h:99999/a", "get(1) /a", "https:///a", "/a#frag", "/" + "a".repeat(300))) {
            val e = assertFailsWith<IllegalArgumentException>(bad) { MatchShorthand.parse(bad) }
            assertTrue(e.message.orEmpty().startsWith("match"), "$bad: ${e.message}")
        }
    }

    @Test
    fun headerPredicates() {
        assertEquals(Predicate("X-Env", "stag*"), MatchShorthand.headerPredicate("X-Env: stag*"))
        assertEquals(Predicate("Authorization", null, absent = true), MatchShorthand.headerPredicate("!Authorization"))
        assertEquals(Predicate("X-Debug", null), MatchShorthand.headerPredicate("X-Debug"))
    }

    private fun spec(
        mock: MockSpec? = null,
        fail: FailKind? = null,
        edit: EditSpec? = null,
        delay: DelaySpec? = null,
        times: Int? = null,
        label: String? = null,
    ) = RuleSpec(MatchShorthand.parse("*/a"), label = label, times = times, delay = delay, mock = mock, fail = fail, edit = edit)

    private fun invalid(spec: RuleSpec, field: String, code: RuleErrorCode = RuleErrorCode.BAD_REQUEST) {
        val error = assertNotNull(RuleValidation.validate(spec), "expected an error on $field")
        assertEquals(code, error.code, error.message)
        assertTrue(error.message.startsWith(field), error.message)
    }

    @Test
    fun validationNamesEveryField() {
        assertNull(RuleValidation.validate(spec(mock = MockSpec(500, listOf("Content-Type" to "application/json"), RuleBody.text("{}")))))
        invalid(spec(), "rule")
        invalid(spec(delay = DelaySpec()), "rule")
        invalid(spec(mock = MockSpec(200), fail = FailKind.RESET), "rule")
        invalid(spec(mock = MockSpec(101)), "mock.status")
        invalid(spec(mock = MockSpec(600)), "mock.status")
        invalid(spec(mock = MockSpec(204, body = RuleBody.text("x"))), "mock.body")
        invalid(spec(mock = MockSpec(304, body = RuleBody.text("x"))), "mock.body")
        invalid(spec(mock = MockSpec(200, body = RuleBody(ByteArray(RuleLimits.MAX_BODY_BYTES + 1)))), "mock.body", RuleErrorCode.LIMIT)
        for (name in listOf("Content-Length", "transfer-encoding", "Content-Encoding", "Set-Cookie", "Set-Cookie2")) {
            invalid(spec(mock = MockSpec(200, listOf(name to "x"))), "mock.headers")
        }
        invalid(spec(mock = MockSpec(200, List(33) { "X-$it" to "v" })), "mock.headers")
        invalid(spec(mock = MockSpec(200, listOf("X" to "a".repeat(8200)))), "mock.headers")
        invalid(spec(mock = MockSpec(200, listOf("X" to "a\r\nInjected: 1"))), "mock.headers")
        invalid(spec(mock = MockSpec(200, listOf("Bad Name" to "v"))), "mock.headers")
        invalid(spec(delay = DelaySpec(beforeRequestMs = 120_001)), "delay.beforeRequestMs")
        invalid(spec(delay = DelaySpec(beforeResponseMs = -1)), "delay.beforeResponseMs")
        invalid(spec(fail = FailKind.TIMEOUT, times = 0), "times")
        invalid(spec(fail = FailKind.TIMEOUT, times = 1_000_001), "times")
        invalid(spec(fail = FailKind.TIMEOUT, label = "x".repeat(81)), "label")
        invalid(spec(edit = EditSpec()), "edit")
        for (name in listOf("Host", "Content-Length", "Transfer-Encoding", "Connection", ":authority")) {
            invalid(spec(edit = EditSpec(RequestEdit(setHeaders = listOf(name to "x")))), "edit.request.setHeaders")
        }
        invalid(spec(edit = EditSpec(RequestEdit(removeHeaders = listOf("Host")))), "edit.request.removeHeaders")
        invalid(spec(edit = EditSpec(RequestEdit(path = "no-slash"))), "edit.request.path")
        invalid(spec(edit = EditSpec(RequestEdit(host = "evil.com/x"))), "edit.request.host")
        invalid(spec(edit = EditSpec(RequestEdit(port = 0))), "edit.request.port")
        invalid(spec(edit = EditSpec(RequestEdit(method = "GE T"))), "edit.request.method")
        invalid(spec(edit = EditSpec(response = ResponseEdit(status = 99))), "edit.response.status")
        invalid(spec(edit = EditSpec(response = ResponseEdit(setHeaders = listOf("Set-Cookie" to "a=b")))), "edit.response.setHeaders")
        invalid(spec(edit = EditSpec(response = ResponseEdit(status = 204, body = RuleBody.text("x")))), "edit.response.body")
        assertNull(RuleValidation.validate(spec(edit = EditSpec(RequestEdit(host = "[::1]", port = 8443)))))
        val tooManyPredicates = RuleSpec(MatchSpec(query = List(9) { Predicate("q$it", null) }), fail = FailKind.RESET)
        invalid(tooManyPredicates, "match.query")
        invalid(RuleSpec(MatchSpec(headers = listOf(Predicate("Bad Header", null))), fail = FailKind.RESET), "match.headers")
        invalid(RuleSpec(MatchSpec(sources = emptySet()), fail = FailKind.RESET), "match.sources")
    }

    @Test
    fun replayEditsRefuseTheRedactionPlaceholder() {
        val error = assertNotNull(RuleValidation.validateReplayEdit(RequestEdit(setHeaders = listOf("Authorization" to Redactor.REDACTED))))
        assertTrue(error.message.startsWith("edit.setHeaders"), error.message)
        assertNull(RuleValidation.validateReplayEdit(RequestEdit(setHeaders = listOf("Authorization" to "Bearer real"))))
    }

    private val context = object : RuleJsonContext {
        val blobs = mutableMapOf(1L to "from a blob".encodeToByteArray())
        val records = mutableMapOf<Long, HttpTransaction>()

        override fun blob(id: Long): ByteArray? = blobs[id]

        override fun transaction(id: Long): HttpTransaction? = records[id]
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(json: String): RuleParse<RuleSpec> {
        val request = assertNotNull(parseJsonTree("""{"cmd":"rules.add","rule":$json}"""), json)
        return RuleJson.parseRule(request["rule"] as Map<String, Any?>, context)
    }

    @Test
    fun wireRuleRoundTrip() {
        val parsed = assertIs<RuleParse.Ok<RuleSpec>>(
            parse(
                """{"label":"users 500","priority":2,"times":3,"match":{"methods":["GET"],"scheme":"https","host":"*.example.com",""" +
                    """"path":"/users/*","query":[{"name":"page","value":"2"},{"name":"trace","absent":true}],""" +
                    """"headers":[{"name":"Authorization","value":"Bearer *"}],"sources":["okhttp","ktor"]},""" +
                    """"delay":{"beforeResponseMs":2000},"mock":{"status":500,"headers":[["Content-Type","application/json"]],"body":{"text":"{\"error\":\"x\"}"}}}""",
            ),
        )
        val spec = parsed.value
        assertEquals(setOf(RuleSource.OKHTTP, RuleSource.KTOR), spec.match.sources)
        assertEquals(2000, spec.delay?.beforeResponseMs)
        assertEquals("{\"error\":\"x\"}", spec.mock?.body?.bytes?.decodeToString())
        val line = RuleJson.ruleLine(RuleInfo(7, spec, hits = 1, exhausted = false, Capabilities.unsupported(spec)), Redactor.Default)
        assertTrue("\"type\":\"rule\",\"id\":7" in line, line)
        assertTrue("\"body\":{\"bytes\":13}" in line, "bodies are echoed by size only: $line")
        assertTrue("x\\\"}" !in line, "the body text never crosses back: $line")
        assertTrue("\"value\":\"<redacted>\"" in line, "a predicate on a redacted header is redacted: $line")
        assertTrue("Bearer" !in line, line)
        val reparsed = parseJsonTree(line, Int.MAX_VALUE)
        assertNotNull(reparsed)
        assertEquals("GET https://*.example.com/users/*?page=2&!trace headers[Authorization=<redacted>]", (reparsed["match"] as Map<*, *>)["shorthand"])
    }

    @Test
    fun wireBodiesTextBase64AndBlobs() {
        val base64 = assertIs<RuleParse.Ok<RuleSpec>>(parse("""{"mock":{"status":200,"body":{"base64":"AAEC"}}}"""))
        assertEquals(listOf<Byte>(0, 1, 2), base64.value.mock?.body?.bytes?.toList())
        val blob = assertIs<RuleParse.Ok<RuleSpec>>(parse("""{"mock":{"status":200,"body":{"blob":1,"size":11}}}"""))
        assertEquals("from a blob", blob.value.mock?.body?.bytes?.decodeToString())
        assertEquals(setOf(1L), blob.blobs)
        val wrongSize = assertIs<RuleParse.Failed>(parse("""{"mock":{"status":200,"body":{"blob":1,"size":12}}}"""))
        assertTrue(wrongSize.error.message.startsWith("mock.body.size"), wrongSize.error.message)
        assertIs<RuleParse.Failed>(parse("""{"mock":{"status":200,"body":{"blob":2,"size":1}}}"""))
        assertIs<RuleParse.Failed>(parse("""{"mock":{"status":200,"body":{"base64":"!!"}}}"""))
        assertIs<RuleParse.Failed>(parse("""{"mock":{"status":200,"body":{"text":"a","base64":"AA=="}}}"""))
        assertIs<RuleParse.Failed>(parse("""{"mock":{"status":"200"}}"""))
        assertIs<RuleParse.Failed>(parse("""{"fail":"explode"}"""))
        assertIs<RuleParse.Failed>(parse("""{"match":{"sources":["cronet"]},"fail":"reset"}"""))
        assertIs<RuleParse.Failed>(parse("""{"edit":{"request":{"scheme":"http"}}}"""))
    }

    @Test
    fun mockFromATransactionCopiesTheResponseInTheApp() {
        val body = CapturedBody("""{"ok":true}""".encodeToByteArray(), 11, truncated = false, "application/json", null)
        context.records[42] = HttpTransaction.pending(42, 0, "GET", "https://h/a", emptyList(), null)
            .completed(201, listOf("Content-Type" to "application/json", "Set-Cookie" to "s=1", "Content-Length" to "11", "X-Kept" to "1"), body, 5, "h2")
        val copied = assertIs<RuleParse.Ok<RuleSpec>>(parse("""{"mock":{"fromTransaction":42}}""")).value.mock
        assertNotNull(copied)
        assertEquals(201, copied.status)
        assertEquals(listOf("Content-Type" to "application/json", "X-Kept" to "1"), copied.headers)
        assertEquals("""{"ok":true}""", copied.body?.bytes?.decodeToString())
        val overridden = assertIs<RuleParse.Ok<RuleSpec>>(parse("""{"mock":{"fromTransaction":42,"status":500}}""")).value.mock
        assertEquals(500, overridden?.status)
        context.records[43] = context.records.getValue(42).copy(responseBody = CapturedBody(ByteArray(4), 100, truncated = true, null, null))
        val truncated = assertIs<RuleParse.Failed>(parse("""{"mock":{"fromTransaction":43}}"""))
        assertTrue("not held in full" in truncated.error.message)
        assertEquals(RuleErrorCode.NOT_FOUND, assertIs<RuleParse.Failed>(parse("""{"mock":{"fromTransaction":99}}""")).error.code)
    }

    @Test
    fun patchesClearTimesWithNull() {
        val patch = assertIs<RuleParse.Ok<RulePatch>>(RuleJson.parsePatch(mapOf("id" to 1L, "times" to null, "enabled" to false))).value
        assertTrue(patch.clearTimes)
        assertEquals(false, patch.enabled)
        assertIs<RuleParse.Failed>(RuleJson.parsePatch(mapOf("times" to 0L)))
    }

    @Test
    fun requestEditsKeepTheSchemeAndStripCredentialsAcrossOrigins() {
        val headers = listOf("Authorization" to "Bearer t", "Cookie" to "c=1", "X-Api-Key" to "k", "Accept" to "a", "X-Two" to "1", "X-Two" to "2")
        val redactor = Redactor.create(extraHeaders = listOf("X-Api-Key"))
        val sameOrigin = assertNotNull(
            RequestEdits.apply("GET", "https://api.example.com/v1/users?page=1&x=y#f", headers, RequestEdit(path = "/v2/users", setQuery = listOf("page" to "2 3")), redactor),
        )
        assertEquals("https://api.example.com/v2/users?x=y&page=2%203#f", sameOrigin.url)
        assertEquals(headers, sameOrigin.headers)
        val moved = assertNotNull(
            RequestEdits.apply("GET", "https://api.example.com/v1", headers, RequestEdit(host = "staging.example.com", port = 8443, setHeaders = listOf("Cookie" to "mine=1")), redactor),
        )
        assertTrue(moved.originChanged)
        assertEquals("https://staging.example.com:8443/v1", moved.url)
        assertEquals(listOf("Accept" to "a", "X-Two" to "1", "X-Two" to "2", "Cookie" to "mine=1"), moved.headers)
        // An added credential never travels next to the app's own: the originals go whatever the edit adds.
        val added = assertNotNull(
            RequestEdits.apply(
                "GET", "https://api.example.com/v1", headers,
                RequestEdit(host = "staging.example.com", addHeaders = listOf("Cookie" to "debug=1", "Authorization" to "Bearer test", "X-Api-Key" to "mine")),
                redactor,
            ),
        )
        assertEquals(
            listOf("Accept" to "a", "X-Two" to "1", "X-Two" to "2", "Cookie" to "debug=1", "Authorization" to "Bearer test", "X-Api-Key" to "mine"),
            added.headers,
        )
        // On the same origin an add keeps the original next to it, as without a host edit.
        val sameOriginAdd = assertNotNull(RequestEdits.apply("GET", "https://api.example.com/v1", headers, RequestEdit(addHeaders = listOf("Cookie" to "debug=1")), redactor))
        assertEquals(headers + ("Cookie" to "debug=1"), sameOriginAdd.headers)
        val headerEdits = RequestEdits.editHeaders(
            headers,
            RequestEdit(setHeaders = listOf("x-two" to "3"), addHeaders = listOf("X-New" to "n"), removeHeaders = listOf("accept")),
            crossOrigin = null,
        )
        assertEquals(listOf("Authorization" to "Bearer t", "Cookie" to "c=1", "X-Api-Key" to "k", "x-two" to "3", "X-New" to "n"), headerEdits)
        val defaultPort = assertNotNull(RequestEdits.apply("GET", "https://h/a", emptyList(), RequestEdit(port = 443), redactor))
        assertEquals("https://h/a", defaultPort.url)
        assertEquals(false, defaultPort.originChanged)
        assertNull(RequestEdits.apply("GET", "not a url", emptyList(), RequestEdit(path = "/x"), redactor))
        val removeQuery = assertNotNull(RequestEdits.apply("GET", "https://h/a?a=1&b=2", emptyList(), RequestEdit(removeQuery = listOf("a", "b")), redactor))
        assertEquals("https://h/a", removeQuery.url)
    }

    @Test
    fun urlPartsRoundTripAndDecode() {
        for (url in listOf("https://h/a?b=1#c", "http://u:p@h:8080/", "https://[::1]:443/x", "https://h", "https://h?q")) {
            assertEquals(url, UrlParts.parse(url).toString())
        }
        assertNull(UrlParts.parse("ftp://h/a"))
        assertNull(UrlParts.parse("https:///a"))
        assertEquals("a b+é%zz", UrlParts.percentDecode("a+b%2B%C3%A9%zz"))
        assertEquals("a%20b%2Fc", UrlParts.percentEncode("a b/c"))
        val request = UrlRuleRequest("GET", "https://H.example:8443/p%20q?x=%31&x=2&y", listOf("A" to "1", "a" to "2"))
        assertEquals("/p%20q", request.encodedPath)
        assertEquals(8443, request.port)
        assertEquals(listOf("1", "2"), request.queryValues("x"))
        assertEquals(listOf(""), request.queryValues("y"))
        assertEquals(listOf("1", "2"), request.headerValues("A"))
    }
}
