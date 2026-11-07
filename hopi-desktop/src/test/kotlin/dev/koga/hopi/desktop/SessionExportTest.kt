package dev.koga.hopi.desktop

import dev.koga.hopi.connect.ExportBudget
import dev.koga.hopi.connect.ExportFormat
import dev.koga.hopi.connect.ExportResult
import dev.koga.hopi.connect.SessionExport
import dev.koga.hopi.connect.BridgeDecoder
import dev.koga.hopi.core.json.TransactionJson
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.redact.Redactor
import java.io.StringWriter
import java.time.OffsetDateTime
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class SessionExportTest {
    private val png = ByteArray(300) { (it * 7).toByte() }
    private val session = listOf(
        completeTx(1),
        completeTx(2, method = "POST", url = "https://user:pw@api.example.com/upload", requestBody = textBody("""{"name":"ü"}"""), code = 201),
        completeTx(3, url = "https://cdn.example.com/a.png", responseBody = binaryBody(png)),
        pendingTx(4, method = "PUT", body = textBody("partial")),
        pendingTx(5).failed("connect timed out", 10),
        completeTx(6, responseBody = textBody("x".repeat(100), contentType = "text/plain", truncated = true, total = 5000)),
    )

    private fun export(
        format: ExportFormat,
        transactions: List<HttpTransaction> = session,
        budget: ExportBudget = ExportBudget(),
        fetch: (Long) -> HttpTransaction? = { null },
    ): Pair<String, ExportResult> {
        val out = StringWriter()
        val result = SessionExport.write(format, transactions, Redactor.Default, out, fetch, budget, creatorVersion = "test")
        return out.toString() to result
    }

    private fun har(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun harIsValidHar12() {
        val (text, result) = export(ExportFormat.Har)
        assertEquals(emptyList(), validateHar(har(text)))
        assertEquals(6, result.transactions)
        assertEquals(0, result.withoutBodies)
    }

    @Test
    fun harCarriesRequestsResponsesAndRedaction() {
        val entries = har(export(ExportFormat.Har).first)["log"]!!.jsonObject["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), entries.map { it["_hopiId"]!!.jsonPrimitive.long })

        val first = entries[0]
        val request = first["request"]!!.jsonObject
        assertEquals("GET", request.string("method"))
        assertEquals(listOf("page" to "2", "q" to "a b"), request["queryString"]!!.jsonArray.map { it.jsonObject.string("name") to it.jsonObject.string("value") })
        assertEquals(Redactor.REDACTED, request.header("Authorization"), "the viewer's redactor applies on top of the app's")
        assertEquals("HTTP/2", request.string("httpVersion"))
        val response = first["response"]!!.jsonObject
        assertEquals(200, response["status"]!!.jsonPrimitive.long.toInt())
        assertEquals("""{"ok":true}""", response["content"]!!.jsonObject.string("text"))
        assertEquals("application/json", response["content"]!!.jsonObject.string("mimeType"))
        assertEquals(42.0, first["time"]!!.jsonPrimitive.double)
        assertEquals("2023-11-14T22:13:20.001Z", first.string("startedDateTime"))

        val post = entries[1]["request"]!!.jsonObject
        assertFalse("pw" in post.string("url"), "userinfo is masked")
        assertEquals("""{"name":"ü"}""", post["postData"]!!.jsonObject.string("text"))

        val image = entries[2]["response"]!!.jsonObject["content"]!!.jsonObject
        assertEquals("base64", image.string("encoding"))
        assertTrue(Base64.getDecoder().decode(image.string("text")).contentEquals(png))
        assertEquals(300, image["size"]!!.jsonPrimitive.long.toInt())

        assertEquals(0, entries[3]["response"]!!.jsonObject["status"]!!.jsonPrimitive.long.toInt())
        assertTrue("pending" in entries[3].string("comment"))
        assertEquals("connect timed out", entries[4]["response"]!!.jsonObject.string("_error"))
        val cut = entries[5]["response"]!!.jsonObject["content"]!!.jsonObject
        assertEquals(5000, cut["size"]!!.jsonPrimitive.long.toInt())
        assertTrue("truncated" in cut.string("comment"))
    }

    @Test
    fun harMarksWhatARuleDid() {
        val marked = listOf(
            completeTx(1).copy(ruleId = 3, ruleApplied = "delay,mock"),
            completeTx(2),
        )
        val (text, _) = export(ExportFormat.Har, marked)
        assertEquals(emptyList(), validateHar(har(text)))
        val entries = har(text)["log"]!!.jsonObject["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3L, entries[0]["_rule"]!!.jsonPrimitive.long)
        assertEquals("delay,mock", entries[0].string("_ruleApplied"))
        assertTrue(listOf("_rule", "_ruleApplied").none { it in entries[1] })
    }

    @Test
    fun summariesAreFetchedWithinTheBudgetAndTheRestKeepsSizes() {
        val small = completeTx(1, responseBody = textBody("small"))
        val giant = completeTx(2, responseBody = textBody("g".repeat(4096)))
        val fetched = mutableListOf<Long>()
        val details = listOf(small, giant).associateBy { it.id }
        val (text, result) = export(
            ExportFormat.Har,
            listOf(summaryOnly(small), summaryOnly(giant)),
            budget = ExportBudget(maxTransactionBytes = 1024, maxTotalBytes = 1024),
        ) { id -> details[id].also { fetched += id } }
        assertEquals(listOf(1L), fetched, "the giant body never crossed the bridge")
        assertEquals(1, result.fetched)
        assertEquals(1, result.withoutBodies)
        val root = har(text)
        assertEquals(emptyList(), validateHar(root))
        val entries = root["log"]!!.jsonObject["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals("small", entries[0]["response"]!!.jsonObject["content"]!!.jsonObject.string("text"))
        val left = entries[1]["response"]!!.jsonObject["content"]!!.jsonObject
        assertNull(left["text"])
        assertEquals(4096, left["size"]!!.jsonPrimitive.long.toInt())
        assertTrue("not exported" in left.string("comment"))
    }

    /**
     * The budget counts what `get` moves: a body the app truncated or skipped costs what it kept,
     * not its size on the wire, which the summary line still reports.
     */
    @Test
    fun truncatedAndSkippedBodiesCountWhatTheAppHoldsNotTheirWireSize() {
        val truncated = completeTx(1, responseBody = textBody("t".repeat(256), truncated = true, total = 50L * 1024 * 1024))
        val skipped = completeTx(2, responseBody = CapturedBody.skipped("binary content type", "video/mp4", 100L * 1024 * 1024))
        val after = completeTx(3, responseBody = textBody("after"))
        val details = listOf(truncated, skipped, after).associateBy { it.id }
        // As the viewer's store holds them: decoded from the bridge's own summary lines.
        val summaries = details.values.map { tx ->
            BridgeDecoder.summary(Json.parseToJsonElement(TransactionJson.bridgeSummary(tx, Redactor.Disabled)).jsonObject, tx.startedAtEpochMs)!!
        }
        assertEquals(50L * 1024 * 1024, summaries[0].responseBody!!.reportedBytes)
        assertEquals(256L, summaries[0].responseBody!!.heldBytes)
        assertEquals(0L, summaries[1].responseBody!!.heldBytes)

        val fetched = mutableListOf<Long>()
        val (text, result) = export(
            ExportFormat.Har,
            summaries,
            budget = ExportBudget(maxTransactionBytes = 1024, maxTotalBytes = 2048),
        ) { id -> details[id].also { fetched += id } }
        assertEquals(listOf(1L, 2L, 3L), fetched, "nothing large crosses for these, so all are fetched")
        assertEquals(0, result.withoutBodies)
        val entries = har(text)["log"]!!.jsonObject["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(Redactor.REDACTED, entries[0]["request"]!!.jsonObject.header("Authorization"), "headers were fetched")
        assertTrue("truncated" in entries[0]["response"]!!.jsonObject["content"]!!.jsonObject.string("comment"))
        assertTrue("not captured: binary content type" in entries[1]["response"]!!.jsonObject["content"]!!.jsonObject.string("comment"))
    }

    @Test
    fun aRowGoneFromTheAppIsExportedWithoutBodiesAndSaysWhy() {
        val gone = completeTx(1).copy(requestHeaders = emptyList(), responseHeaders = emptyList(), requestBody = CapturedBody.gone(0), responseBody = CapturedBody.gone(11))
        val fetched = mutableListOf<Long>()
        val (text, result) = export(ExportFormat.Har, listOf(gone)) { id -> null.also { fetched += id } }
        assertEquals(emptyList(), fetched, "the app said NOT_FOUND already: not asked again")
        assertEquals(1, result.withoutBodies)
        val entry = har(text)["log"]!!.jsonObject["entries"]!!.jsonArray.single().jsonObject
        assertTrue("gone from the app" in entry.string("comment"))
        assertTrue("gone from the app" in entry["response"]!!.jsonObject["content"]!!.jsonObject.string("comment"))
    }

    @Test
    fun ndjsonIsOneBridgeDetailPerLineAndReadsBack() {
        val (text, result) = export(ExportFormat.Ndjson, fetch = { null })
        val lines = text.lines().filter { it.isNotEmpty() }
        assertEquals(session.size, lines.size)
        assertEquals(6, result.transactions)
        val decoded = lines.map { BridgeDecoder.detail(Json.parseToJsonElement(it).jsonObject)!! }
        val redacted = session.map { it.copy(requestHeaders = Redactor.Default.headers(it.requestHeaders), url = Redactor.Default.url(it.url)) }
        for ((expected, actual) in redacted.zip(decoded)) {
            assertEquals(expected.id, actual.id)
            assertEquals(expected.url, actual.url)
            assertEquals(expected.requestHeaders, actual.requestHeaders)
            assertEquals(expected.responseBody, actual.responseBody)
            assertEquals(expected.requestBody, actual.requestBody)
            assertEquals(expected.state, actual.state)
        }
    }

    @Test
    fun ndjsonFetchesSummariesToo() {
        val full = completeTx(1, responseBody = textBody("body"))
        val (text, _) = export(ExportFormat.Ndjson, listOf(summaryOnly(full))) { full }
        val decoded = BridgeDecoder.detail(Json.parseToJsonElement(text.trim()).jsonObject)!!
        assertEquals("body", decoded.responseBody!!.bytes.decodeToString())
    }

    @Test
    fun aBodyOverOneSliceStreamsIntact() {
        val big = "é".repeat(100_000)
        val (text, _) = export(ExportFormat.Har, listOf(completeTx(1, responseBody = textBody(big, contentType = "text/plain"))))
        val root = har(text)
        assertEquals(emptyList(), validateHar(root))
        assertEquals(big, root["log"]!!.jsonObject["entries"]!!.jsonArray[0].jsonObject["response"]!!.jsonObject["content"]!!.jsonObject.string("text"))
    }

    @Test
    fun theValidatorCatchesBrokenHar() {
        val broken = har("""{"log":{"version":"1.2","creator":{"name":"x"},"entries":[{"startedDateTime":"yesterday","time":5,"request":{},"response":{},"cache":{},"timings":{"send":0,"wait":1,"receive":0},"extra":1}]}}""")
        val problems = validateHar(broken)
        assertTrue(problems.any { "creator.version" in it }, problems.toString())
        assertTrue(problems.any { "startedDateTime" in it }, problems.toString())
        assertTrue(problems.any { "request.method" in it }, problems.toString())
        assertTrue(problems.any { "extra" in it }, problems.toString())
        assertTrue(problems.any { "time" in it && "timings" in it }, problems.toString())
    }
}

private fun JsonObject.string(name: String): String = (this[name] as JsonPrimitive).content

private fun JsonObject.header(name: String): String? =
    this["headers"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.string("name").equals(name, ignoreCase = true) }?.string("value")

/**
 * The HAR 1.2 structure (http://www.softwareishard.com/blog/har-12-spec/): every required field
 * with its JSON type, name/value pairs, ISO 8601 dates, non-negative send/wait/receive whose sum
 * is `time`, and no field outside the spec unless it starts with `_`. Returns the problems found.
 */
internal fun validateHar(root: JsonObject): List<String> {
    val problems = mutableListOf<String>()
    fun need(obj: JsonObject, path: String, field: String, kind: String): JsonElement? {
        val value = obj[field]
        val ok = when (kind) {
            "string" -> value is JsonPrimitive && value.isString
            "number" -> value is JsonPrimitive && !value.isString && value.content.toDoubleOrNull() != null
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            else -> error(kind)
        }
        if (!ok) problems += "$path.$field must be a $kind"
        return value.takeIf { ok }
    }
    fun only(obj: JsonObject, path: String, allowed: Set<String>) {
        for (key in obj.keys) if (key !in allowed && !key.startsWith("_")) problems += "$path has the field '$key', not in HAR 1.2"
    }
    fun pairs(obj: JsonObject, path: String, field: String) {
        val array = need(obj, path, field, "array") as? JsonArray ?: return
        array.forEachIndexed { i, item ->
            val pair = item as? JsonObject ?: return@forEachIndexed run { problems += "$path.$field[$i] must be an object" }
            need(pair, "$path.$field[$i]", "name", "string")
            need(pair, "$path.$field[$i]", "value", "string")
        }
    }
    fun number(obj: JsonObject, field: String): Double? = (obj[field] as? JsonPrimitive)?.content?.toDoubleOrNull()

    only(root, "", setOf("log"))
    val log = need(root, "", "log", "object") as? JsonObject ?: return problems
    only(log, "log", setOf("version", "creator", "browser", "pages", "entries", "comment"))
    need(log, "log", "version", "string")
    (need(log, "log", "creator", "object") as? JsonObject)?.let {
        need(it, "log.creator", "name", "string")
        need(it, "log.creator", "version", "string")
    }
    val entries = need(log, "log", "entries", "array") as? JsonArray ?: return problems
    entries.forEachIndexed { i, element ->
        val path = "log.entries[$i]"
        val entry = element as? JsonObject ?: return@forEachIndexed run { problems += "$path must be an object" }
        only(entry, path, setOf("pageref", "startedDateTime", "time", "request", "response", "cache", "timings", "serverIPAddress", "connection", "comment"))
        (need(entry, path, "startedDateTime", "string") as? JsonPrimitive)?.let {
            try {
                OffsetDateTime.parse(it.content)
            } catch (_: Exception) {
                problems += "$path.startedDateTime is not ISO 8601: ${it.content}"
            }
        }
        need(entry, path, "time", "number")
        need(entry, path, "cache", "object")
        (need(entry, path, "request", "object") as? JsonObject)?.let { request ->
            val at = "$path.request"
            only(request, at, setOf("method", "url", "httpVersion", "cookies", "headers", "queryString", "postData", "headersSize", "bodySize", "comment"))
            for (field in listOf("method", "url", "httpVersion")) need(request, at, field, "string")
            need(request, at, "cookies", "array")
            pairs(request, at, "headers")
            pairs(request, at, "queryString")
            need(request, at, "headersSize", "number")
            need(request, at, "bodySize", "number")
            (request["postData"] as? JsonObject)?.let { post ->
                only(post, "$at.postData", setOf("mimeType", "params", "text", "comment"))
                need(post, "$at.postData", "mimeType", "string")
                if ("text" in post && "params" in post) problems += "$at.postData has both text and params"
            }
        }
        (need(entry, path, "response", "object") as? JsonObject)?.let { response ->
            val at = "$path.response"
            only(response, at, setOf("status", "statusText", "httpVersion", "cookies", "headers", "content", "redirectURL", "headersSize", "bodySize", "comment"))
            need(response, at, "status", "number")
            for (field in listOf("statusText", "httpVersion", "redirectURL")) need(response, at, field, "string")
            need(response, at, "cookies", "array")
            pairs(response, at, "headers")
            need(response, at, "headersSize", "number")
            need(response, at, "bodySize", "number")
            (need(response, at, "content", "object") as? JsonObject)?.let { content ->
                only(content, "$at.content", setOf("size", "compression", "mimeType", "text", "encoding", "comment"))
                need(content, "$at.content", "size", "number")
                need(content, "$at.content", "mimeType", "string")
                if ("encoding" in content && "text" !in content) problems += "$at.content has an encoding and no text"
            }
        }
        (need(entry, path, "timings", "object") as? JsonObject)?.let { timings ->
            only(timings, "$path.timings", setOf("blocked", "dns", "connect", "send", "wait", "receive", "ssl", "comment"))
            val required = listOf("send", "wait", "receive").map { field ->
                need(timings, "$path.timings", field, "number")
                number(timings, field)?.also { if (it < 0) problems += "$path.timings.$field must not be negative" }
            }
            val optional = listOf("blocked", "dns", "connect").mapNotNull { number(timings, it) }.filter { it >= 0 }
            val sum = required.filterNotNull().sum() + optional.sum()
            val time = number(entry, "time")
            if (time != null && kotlin.math.abs(time - sum) > 0.001) problems += "$path.time ($time) is not the sum of its timings ($sum)"
        }
    }
    return problems
}
