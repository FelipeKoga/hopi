package dev.koga.hopi.core.rules

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.redact.Redactor
import kotlin.io.encoding.Base64

/** Where a rule's body comes from when it is not inline, and what `fromTransaction` names. */
@HopiInternalApi
public interface RuleJsonContext {
    /** The staged blob [id]; null when this session holds none by that id. */
    public fun blob(id: Long): ByteArray?

    /** The record [id] of the app's store; null when it is not there. */
    public fun transaction(id: Long): HttpTransaction?
}

@HopiInternalApi
public sealed interface RuleParse<out T> {
    /** [blobs] are the staged blobs [value] took; the session frees them once the command succeeded. */
    public class Ok<T>(public val value: T, public val blobs: Set<Long>) : RuleParse<T>

    public class Failed(public val error: RuleError) : RuleParse<Nothing>
}

/**
 * The rule object of the bridge (docs/design/rules.md, "Bridge"), parsed from [dev.koga.hopi.core.json.parseJsonTree]
 * output and written for `rules.*` replies. Unknown fields are ignored, as on every command. A
 * reply never echoes a body (only `{"bytes":N}`), and every header value it echoes (predicates,
 * mock and edit headers) goes through the store's redactor, like every other outward value.
 */
@HopiInternalApi
public object RuleJson {
    /** Headers a mock built `fromTransaction` drops from the record: the ones a mock may not give. */
    private val NOT_COPIED = setOf("content-length", "transfer-encoding", "content-encoding", "set-cookie", "set-cookie2")

    public fun parseRule(rule: Map<String, Any?>, context: RuleJsonContext): RuleParse<RuleSpec> = parsing {
        val match = obj(rule, "match", "match") ?: emptyMap()
        val matchSpec = MatchSpec(
            methods = strings(match, "methods", "match.methods")?.toSet()?.takeIf { it.isNotEmpty() },
            scheme = str(match, "scheme", "match.scheme"),
            host = str(match, "host", "match.host"),
            port = int(match, "port", "match.port"),
            path = str(match, "path", "match.path"),
            query = predicates(match, "query", "match.query"),
            headers = predicates(match, "headers", "match.headers"),
            sources = strings(match, "sources", "match.sources")?.map { name ->
                RuleSource.of(name) ?: fail("match.sources: unknown source '$name'")
            }?.toSet(),
        )
        val delay = obj(rule, "delay", "delay")?.let {
            DelaySpec(long(it, "beforeRequestMs", "delay.beforeRequestMs") ?: 0, long(it, "beforeResponseMs", "delay.beforeResponseMs") ?: 0)
        }
        val mock = obj(rule, "mock", "mock")?.let { mock(it, context) }
        val failKind = str(rule, "fail", "fail")?.let { FailKind.of(it) ?: fail("fail: must be timeout, reset, unknown-host or cancel") }
        val edit = obj(rule, "edit", "edit")?.let { edit ->
            EditSpec(
                request = obj(edit, "request", "edit.request")?.let { requestEdit(it, "edit.request", context) },
                response = obj(edit, "response", "edit.response")?.let { responseEdit(it, context) },
            )
        }
        RuleSpec(
            match = matchSpec,
            label = str(rule, "label", "label"),
            enabled = bool(rule, "enabled", "enabled") ?: true,
            priority = int(rule, "priority", "priority") ?: 0,
            times = long(rule, "times", "times")?.let { if (it in 1..RuleLimits.MAX_TIMES) it.toInt() else fail("times: must be 1..${RuleLimits.MAX_TIMES}") },
            delay = delay,
            mock = mock,
            fail = failKind,
            edit = edit,
        )
    }

    /** The `rules.update` fields; [RulePatch.times] `null` in the JSON clears the limit. */
    public fun parsePatch(fields: Map<String, Any?>): RuleParse<RulePatch> = parsing {
        RulePatch(
            enabled = bool(fields, "enabled", "enabled"),
            priority = int(fields, "priority", "priority"),
            times = long(fields, "times", "times")?.let { if (it in 1..RuleLimits.MAX_TIMES) it.toInt() else fail("times: must be 1..${RuleLimits.MAX_TIMES}") },
            clearTimes = fields.containsKey("times") && fields["times"] == null,
            resetHits = bool(fields, "resetHits", "resetHits") ?: false,
        )
    }

    private fun Parsing.mock(mock: Map<String, Any?>, context: RuleJsonContext): MockSpec {
        val from = long(mock, "fromTransaction", "mock.fromTransaction")?.let { id ->
            context.transaction(id) ?: throw RuleFailure(RuleError(RuleErrorCode.NOT_FOUND, "mock.fromTransaction: no transaction $id"))
        }
        val status = int(mock, "status", "mock.status")
        val headers = pairs(mock, "headers", "mock.headers")
        val body = body(mock, "body", "mock.body", context)
        if (from == null) return MockSpec(status ?: fail("mock.status: missing"), headers.orEmpty(), body)
        return fromTransaction(from, status, headers, body)
    }

    /**
     * A mock built in the app from record [from]'s response (status, headers minus the ones a mock
     * may not give, body), each field overridden by the given one when not null. The record's
     * values never cross the bridge for it, so redaction never ends up inside a mock. A response
     * body that is truncated or skipped cannot be the source of a mock body, and one the record
     * holds encoded is copied decoded, as a mock is delivered (see [encodedBodyRefusal]).
     */
    public fun mockFromTransaction(from: HttpTransaction, status: Int?, headers: List<Pair<String, String>>?, body: RuleBody?): RuleParse<MockSpec> =
        parsing { fromTransaction(from, status, headers, body) }

    /**
     * Why record [from]'s response body cannot be copied into a mock because of the encoding it is
     * held in, or null. An OkHttp or `HttpURLConnection` record of a request that named its own
     * `Accept-Encoding` holds the server's bytes: a single gzip or deflate is decoded for the copy,
     * any other encoding is refused. A host viewer's summary carries no headers, so for it the app
     * refuses on the tap.
     */
    public fun encodedBodyRefusal(from: HttpTransaction): String? {
        if ((from.responseBody?.reportedBytes ?: 0L) == 0L) return null
        return encodingRefusal(heldCodings(from))
    }

    private fun encodingRefusal(codings: List<String>): String? = when {
        codings.isEmpty() || codings.size == 1 && codings[0] in DECODABLE_CODINGS -> null
        else -> "response body is encoded (Content-Encoding: ${codings.joinToString(", ")})"
    }

    private fun fromTransaction(from: HttpTransaction, status: Int?, headers: List<Pair<String, String>>?, body: RuleBody?): MockSpec {
        val code = from.responseCode ?: fail("mock.fromTransaction: transaction ${from.id} has no response")
        val recorded = from.responseBody
        val fromBody = when {
            body != null -> body
            recorded == null || recorded.bytes.isEmpty() && (recorded.totalBytes ?: 0L) == 0L && recorded.skippedReason == null -> null
            recorded.truncated || recorded.skippedReason != null -> fail("mock.fromTransaction: response body not held in full")
            else -> RuleBody(copiedBody(from, recorded.bytes))
        }
        return MockSpec(
            status = status ?: code,
            headers = headers ?: from.responseHeaders.filter { it.first.lowercase() !in NOT_COPIED },
            body = fromBody,
        )
    }

    /** The body a mock copies from record [from]: its held [bytes], decoded when the record holds them encoded. */
    private fun copiedBody(from: HttpTransaction, bytes: ByteArray): ByteArray {
        val codings = heldCodings(from)
        encodingRefusal(codings)?.let { fail("mock.fromTransaction: $it; give a body") }
        val coding = codings.singleOrNull() ?: return bytes
        val decoded = decodeBody(coding, bytes, RuleLimits.MAX_BODY_BYTES) ?: fail("mock.fromTransaction: response body does not decode as $coding; give a body")
        if (decoded.size > RuleLimits.MAX_BODY_BYTES) {
            throw RuleFailure(RuleError(RuleErrorCode.LIMIT, "mock.fromTransaction: decoded response body larger than ${RuleLimits.MAX_BODY_BYTES} bytes"))
        }
        return decoded
    }

    private fun Parsing.requestEdit(edit: Map<String, Any?>, field: String, context: RuleJsonContext): RequestEdit {
        if (edit.containsKey("scheme")) fail("$field.scheme: the scheme cannot change")
        return RequestEdit(
            method = str(edit, "method", "$field.method"),
            host = str(edit, "host", "$field.host"),
            port = int(edit, "port", "$field.port"),
            path = str(edit, "path", "$field.path"),
            setQuery = pairs(edit, "setQuery", "$field.setQuery").orEmpty(),
            removeQuery = strings(edit, "removeQuery", "$field.removeQuery").orEmpty(),
            setHeaders = pairs(edit, "setHeaders", "$field.setHeaders").orEmpty(),
            addHeaders = pairs(edit, "addHeaders", "$field.addHeaders").orEmpty(),
            removeHeaders = strings(edit, "removeHeaders", "$field.removeHeaders").orEmpty(),
            body = body(edit, "body", "$field.body", context),
        )
    }

    private fun Parsing.responseEdit(edit: Map<String, Any?>, context: RuleJsonContext): ResponseEdit = ResponseEdit(
        status = int(edit, "status", "edit.response.status"),
        setHeaders = pairs(edit, "setHeaders", "edit.response.setHeaders").orEmpty(),
        addHeaders = pairs(edit, "addHeaders", "edit.response.addHeaders").orEmpty(),
        removeHeaders = strings(edit, "removeHeaders", "edit.response.removeHeaders").orEmpty(),
        body = body(edit, "body", "edit.response.body", context),
    )

    private fun Parsing.body(parent: Map<String, Any?>, key: String, field: String, context: RuleJsonContext): RuleBody? {
        val body = obj(parent, key, field) ?: return null
        val forms = listOf("text", "base64", "blob").count { body.containsKey(it) }
        if (forms != 1) fail("$field: exactly one of text, base64, blob")
        str(body, "text", "$field.text")?.let { return RuleBody.text(it).also { b -> checkSize(b.size.toLong(), field) } }
        str(body, "base64", "$field.base64")?.let {
            val bytes = try {
                Base64.decode(it)
            } catch (_: IllegalArgumentException) {
                fail("$field.base64: not base64")
            }
            checkSize(bytes.size.toLong(), field)
            return RuleBody(bytes)
        }
        val id = long(body, "blob", "$field.blob") ?: fail("$field.blob: missing")
        val size = long(body, "size", "$field.size") ?: fail("$field.size: missing")
        val bytes = context.blob(id) ?: fail("$field.blob: no blob $id in this session")
        if (bytes.size.toLong() != size) fail("$field.size: blob $id holds ${bytes.size} bytes, not $size")
        blobs += id
        return RuleBody(bytes)
    }

    private fun Parsing.checkSize(size: Long, field: String) {
        if (size > RuleLimits.MAX_BODY_BYTES) throw RuleFailure(RuleError(RuleErrorCode.LIMIT, "$field: larger than ${RuleLimits.MAX_BODY_BYTES} bytes"))
    }

    private fun Parsing.predicates(parent: Map<String, Any?>, key: String, field: String): List<Predicate> {
        val list = parent[key] ?: return emptyList()
        if (list !is List<*>) fail("$field: must be an array")
        return list.map { item ->
            val p = item as? Map<*, *> ?: fail("$field: each predicate is an object")
            @Suppress("UNCHECKED_CAST")
            val m = p as Map<String, Any?>
            Predicate(
                name = str(m, "name", "$field.name") ?: fail("$field.name: missing"),
                value = str(m, "value", "$field.value"),
                absent = bool(m, "absent", "$field.absent") ?: false,
            )
        }
    }

    // --- Writing ---

    /** `{"type":"rule",…}`: one rule as `rules.*` reply lines carry it. */
    public fun ruleLine(rule: RuleInfo, redactor: Redactor): String {
        val w = JsonWriter().beginObject().field("type", "rule").field("id", rule.id)
        val spec = rule.spec
        spec.label?.let { w.field("label", it) }
        w.field("enabled", spec.enabled).field("priority", spec.priority).field("times", spec.times)
            .field("hits", rule.hits).field("exhausted", rule.exhausted)
        writeMatch(w, spec.match, redactor)
        spec.delay?.let {
            w.name("delay").beginObject().field("beforeRequestMs", it.beforeRequestMs).field("beforeResponseMs", it.beforeResponseMs).endObject()
        }
        spec.mock?.let { mock ->
            w.name("mock").beginObject().field("status", mock.status)
            writePairs(w, "headers", mock.headers, redactor)
            writeBody(w, mock.body)
            w.endObject()
        }
        spec.fail?.let { w.field("fail", it.wireName) }
        spec.edit?.let { edit ->
            w.name("edit").beginObject()
            edit.request?.let { r ->
                w.name("request").beginObject()
                r.method?.let { w.field("method", it) }
                r.host?.let { w.field("host", it) }
                r.port?.let { w.field("port", it) }
                r.path?.let { w.field("path", it) }
                // Query values may carry what the redactor would hide in a URL: shown by name only when redacted.
                if (r.setQuery.isNotEmpty()) writePairs(w, "setQuery", r.setQuery.map { (k, v) -> k to redactor.queryValue(k, v) }, Redactor.Disabled)
                if (r.removeQuery.isNotEmpty()) writeStrings(w, "removeQuery", r.removeQuery)
                writeHeaderEdits(w, r.setHeaders, r.addHeaders, r.removeHeaders, redactor)
                writeBody(w, r.body)
                w.endObject()
            }
            edit.response?.let { r ->
                w.name("response").beginObject()
                r.status?.let { w.field("status", it) }
                writeHeaderEdits(w, r.setHeaders, r.addHeaders, r.removeHeaders, redactor)
                writeBody(w, r.body)
                w.endObject()
            }
            w.endObject()
        }
        w.name("unsupported").beginArray()
        for (u in rule.unsupported) {
            w.beginObject().field("source", u.source.wireName).field("reason", u.reason).field("applies", !u.excluded).endObject()
        }
        w.endArray()
        return w.endObject().toString()
    }

    /** The rule's action as text for lists: `mock 500 (61 B)`, `fail timeout`, `delay 2000ms before response`. */
    public fun describeAction(spec: RuleSpec): String = buildList {
        spec.mock?.let { add("mock ${it.status}" + (it.body?.let { b -> " (${b.size} B)" } ?: "")) }
        spec.fail?.let { add("fail ${it.wireName}") }
        spec.edit?.let { edit ->
            val sides = listOfNotNull(edit.request?.takeIf { !it.isEmpty }?.let { "request" }, edit.response?.takeIf { !it.isEmpty }?.let { "response" })
            add("edit " + sides.joinToString("+"))
        }
        spec.delay?.takeIf { !it.isZero }?.let { d ->
            add(
                "delay " + listOfNotNull(
                    d.beforeRequestMs.takeIf { it > 0 }?.let { "${it}ms before request" },
                    d.beforeResponseMs.takeIf { it > 0 }?.let { "${it}ms before response" },
                ).joinToString(", "),
            )
        }
    }.joinToString(", ")

    private fun writeMatch(w: JsonWriter, match: MatchSpec, redactor: Redactor) {
        w.name("match").beginObject()
        match.methods?.let { writeStrings(w, "methods", it.toList()) }
        match.scheme?.let { w.field("scheme", it) }
        match.host?.let { w.field("host", it) }
        match.port?.let { w.field("port", it) }
        match.path?.let { w.field("path", it) }
        if (match.query.isNotEmpty()) writePredicates(w, "query", match.query) { name, value -> redactor.queryValue(name, value) }
        if (match.headers.isNotEmpty()) writePredicates(w, "headers", match.headers) { name, value -> redactor.headerValue(name, value) }
        match.sources?.let { sources -> writeStrings(w, "sources", RuleSource.entries.filter { it in sources }.map { it.wireName }) }
        w.field("shorthand", match.describeRedacted(redactor))
        w.endObject()
    }

    /** [MatchSpec.describe] with query and header predicate values through [redactor]: what lists show. */
    public fun describeMatch(match: MatchSpec, redactor: Redactor): String = match.describeRedacted(redactor)

    private fun MatchSpec.describeRedacted(redactor: Redactor): String = MatchSpec(
        methods, scheme, host, port, path,
        query.map { Predicate(it.name, it.value?.let { v -> redactor.queryValue(it.name, v) }, it.absent) },
        headers.map { Predicate(it.name, it.value?.let { v -> redactor.headerValue(it.name, v) }, it.absent) },
        sources,
    ).describe()

    private fun writePredicates(w: JsonWriter, name: String, predicates: List<Predicate>, redact: (String, String) -> String) {
        w.name(name).beginArray()
        for (p in predicates) {
            w.beginObject().field("name", p.name)
            p.value?.let { w.field("value", redact(p.name, it)) }
            if (p.absent) w.field("absent", true)
            w.endObject()
        }
        w.endArray()
    }

    private fun writeHeaderEdits(
        w: JsonWriter,
        set: List<Pair<String, String>>,
        add: List<Pair<String, String>>,
        remove: List<String>,
        redactor: Redactor,
    ) {
        if (set.isNotEmpty()) writePairs(w, "setHeaders", set, redactor)
        if (add.isNotEmpty()) writePairs(w, "addHeaders", add, redactor)
        if (remove.isNotEmpty()) writeStrings(w, "removeHeaders", remove)
    }

    private fun writePairs(w: JsonWriter, name: String, pairs: List<Pair<String, String>>, redactor: Redactor) {
        w.name(name).beginArray()
        for ((k, v) in pairs) w.beginArray().value(k).value(redactor.headerValue(k, v)).endArray()
        w.endArray()
    }

    private fun writeStrings(w: JsonWriter, name: String, values: List<String>) {
        w.name(name).beginArray()
        for (v in values) w.value(v)
        w.endArray()
    }

    private fun writeBody(w: JsonWriter, body: RuleBody?) {
        body ?: return
        w.name("body").beginObject().field("bytes", body.size).endObject()
    }

    // --- Parsing helpers: each throws a RuleFailure naming the field ---

    private class RuleFailure(val error: RuleError) : RuntimeException(error.message)

    private class Parsing {
        val blobs = HashSet<Long>()
    }

    private inline fun <T> parsing(block: Parsing.() -> T): RuleParse<T> {
        val p = Parsing()
        return try {
            RuleParse.Ok(p.block(), p.blobs)
        } catch (e: RuleFailure) {
            RuleParse.Failed(e.error)
        }
    }

    private fun fail(message: String): Nothing = throw RuleFailure(RuleError(RuleErrorCode.BAD_REQUEST, message))

    private fun obj(parent: Map<String, Any?>, key: String, field: String): Map<String, Any?>? {
        val v = parent[key] ?: return null
        @Suppress("UNCHECKED_CAST")
        return v as? Map<String, Any?> ?: fail("$field: must be an object")
    }

    private fun str(parent: Map<String, Any?>, key: String, field: String): String? {
        val v = parent[key] ?: return null
        return v as? String ?: fail("$field: must be a string")
    }

    private fun long(parent: Map<String, Any?>, key: String, field: String): Long? {
        val v = parent[key] ?: return null
        return v as? Long ?: fail("$field: must be an integer")
    }

    private fun int(parent: Map<String, Any?>, key: String, field: String): Int? {
        val v = long(parent, key, field) ?: return null
        if (v !in Int.MIN_VALUE..Int.MAX_VALUE) fail("$field: out of range")
        return v.toInt()
    }

    private fun bool(parent: Map<String, Any?>, key: String, field: String): Boolean? {
        val v = parent[key] ?: return null
        return v as? Boolean ?: fail("$field: must be true or false")
    }

    private fun strings(parent: Map<String, Any?>, key: String, field: String): List<String>? {
        val v = parent[key] ?: return null
        val list = v as? List<*> ?: fail("$field: must be an array of strings")
        if (list.size > RuleLimits.MAX_HEADERS) fail("$field: more than ${RuleLimits.MAX_HEADERS} entries")
        return list.map { it as? String ?: fail("$field: must be an array of strings") }
    }

    private fun pairs(parent: Map<String, Any?>, key: String, field: String): List<Pair<String, String>>? {
        val v = parent[key] ?: return null
        val list = v as? List<*> ?: fail("$field: must be an array of [name, value] pairs")
        if (list.size > RuleLimits.MAX_HEADERS) fail("$field: more than ${RuleLimits.MAX_HEADERS} headers")
        return list.map { item ->
            val pair = item as? List<*> ?: fail("$field: must be an array of [name, value] pairs")
            if (pair.size != 2) fail("$field: must be an array of [name, value] pairs")
            (pair[0] as? String ?: fail("$field: names are strings")) to (pair[1] as? String ?: fail("$field: values are strings"))
        }
    }
}
