package dev.koga.hopi.desktop

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.koga.hopi.connect.RemoteRule
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.rules.FailKind
import dev.koga.hopi.core.rules.MatchShorthand
import dev.koga.hopi.core.rules.MatchSpec
import dev.koga.hopi.core.rules.Predicate
import dev.koga.hopi.core.rules.RuleLimits
import dev.koga.hopi.core.rules.RuleSource
import dev.koga.hopi.core.rules.UrlParts

/** What the rules panel's form makes. */
internal enum class FormKind(val label: String) {
    Mock("Mock"),
    Fail("Fail"),
    Delay("Delay"),
    Edit("Edit"),

    /** Priority, times and hits of an existing rule: what `rules.update` changes. */
    Update("Update"),
}

/** A body chosen from a file: its name for the form, its bytes for the wire (inline or a blob). */
internal class BodyFile(val name: String, val bytes: ByteArray)

/** One body field: a text area, or a file that replaces it. Empty text and no file: no body. */
internal class BodyField {
    val text = TextFieldState()
    var file: BodyFile? by mutableStateOf(null)

    /** Null when nothing was given. */
    fun bytes(field: String): ByteArray? {
        val bytes = file?.bytes ?: text.text.toString().takeIf { it.isNotEmpty() }?.encodeToByteArray() ?: return null
        require(bytes.size <= RuleLimits.MAX_BODY_BYTES) { "$field: ${bytes.size} bytes, more than the ${RuleLimits.MAX_BODY_BYTES} a rule body may hold" }
        return bytes
    }
}

/** A header table: rows of name and value; a row with an empty name is ignored. */
internal class HeaderTable {
    val rows = mutableStateListOf<Pair<TextFieldState, TextFieldState>>()

    fun add(name: String = "", value: String = "") {
        rows += TextFieldState(name) to TextFieldState(value)
    }

    fun pairs(): List<Pair<String, String>> = rows.mapNotNull { (name, value) ->
        name.text.toString().trim().takeIf { it.isNotEmpty() }?.let { it to value.text.toString().trim() }
    }
}

/** The request side of an edit. */
internal class RequestEditFields {
    val method = TextFieldState()
    val host = TextFieldState()
    val port = TextFieldState()
    val path = TextFieldState()
    val setHeaders = HeaderTable()
    val removeHeaders = TextFieldState()
    val body = BodyField()

    fun isEmpty(): Boolean = listOf(method, host, port, path, removeHeaders).all { it.text.isBlank() } &&
        setHeaders.pairs().isEmpty() && body.file == null && body.text.text.isEmpty()

    /** Writes the fields of an edit object into [w] (already inside it). */
    fun write(w: JsonWriter, body: (ByteArray) -> String, field: String) {
        method.value()?.let { w.field("method", it) }
        host.value()?.let { w.field("host", it) }
        port.value()?.let { w.field("port", int(it, "$field.port", 1..65_535)) }
        path.value()?.let { w.field("path", it) }
        setHeaders.pairs().takeIf { it.isNotEmpty() }?.let { writePairs(w, "setHeaders", it) }
        names(removeHeaders).takeIf { it.isNotEmpty() }?.let { writeStrings(w, "removeHeaders", it) }
        this.body.bytes("$field.body")?.let { w.name("body").rawValue(body(it)) }
    }
}

/**
 * The rules panel's add and edit form, as plain state (the panel binds it, tests fill it).
 * [json] checks what the host can (the match shorthand, numbers, durations, sizes) and writes the
 * wire object; the app validates the rest and its refusal is shown as is. Durations are in ms.
 */
internal class RuleForm(val kind: FormKind) {
    val match = TextFieldState()
    val label = TextFieldState()
    val times = TextFieldState()
    val priority = TextFieldState()

    /** Comma-separated source names; empty for all. */
    val sources = TextFieldState()
    var disabled: Boolean by mutableStateOf(false)

    val status = TextFieldState()
    val headers = HeaderTable()
    val body = BodyField()

    /** The record a mock copies status, headers and body from, in the app. */
    var fromTransaction: Long? = null
        private set

    /** The rule [FormKind.Update] changes. */
    var ruleId: Long? = null
        private set
    var resetHits: Boolean by mutableStateOf(false)

    var failKind: FailKind by mutableStateOf(FailKind.TIMEOUT)
    val beforeRequestMs = TextFieldState()
    val beforeResponseMs = TextFieldState()

    val request = RequestEditFields()
    val responseRemoveHeaders = TextFieldState()

    /** The form's title in the panel. */
    val title: String
        get() = when (kind) {
            FormKind.Mock -> fromTransaction?.let { "Mock this response (#$it)" } ?: "New mock"
            FormKind.Fail -> "New fail"
            FormKind.Delay -> "New delay"
            FormKind.Edit -> "New edit"
            FormKind.Update -> "Update rule #$ruleId"
        }

    /** The rule object for `rules.add`; [body] turns bytes into a body object (inline, or an uploaded blob). */
    fun json(body: (ByteArray) -> String): String {
        check(kind != FormKind.Update) { "not a rule form: $kind" }
        val parsedSources = sources.value()?.split(',')?.map { name ->
            RuleSource.of(name.trim()) ?: throw IllegalArgumentException("sources: unknown source '${name.trim()}' (ktor, okhttp, urlconnection, urlsession)")
        }?.toSet()
        val spec = try {
            MatchShorthand.parse(match.text.toString(), parsedSources)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(e.message ?: "match: not a pattern")
        }
        val w = JsonWriter().beginObject()
        writeMatch(w, spec)
        label.value()?.let { w.field("label", it) }
        times.value()?.let { w.field("times", int(it, "times", 1..RuleLimits.MAX_TIMES)) }
        priority.value()?.let { w.field("priority", int(it, "priority", Int.MIN_VALUE..Int.MAX_VALUE)) }
        if (disabled) w.field("enabled", false)
        val beforeRequest = beforeRequestMs.value()?.let { duration(it, "before request") } ?: 0L
        val beforeResponse = beforeResponseMs.value()?.let { duration(it, "before response") } ?: 0L
        if (beforeRequest > 0 || beforeResponse > 0) {
            w.name("delay").beginObject().field("beforeRequestMs", beforeRequest).field("beforeResponseMs", beforeResponse).endObject()
        }
        when (kind) {
            FormKind.Mock -> {
                val code = status.value()?.let { int(it, "status", 100..599) }
                require(code != null || fromTransaction != null) { "status: a mock needs a status" }
                w.name("mock").beginObject()
                code?.let { w.field("status", it) }
                headers.pairs().takeIf { it.isNotEmpty() }?.let { writePairs(w, "headers", it) }
                this.body.bytes("body")?.let { w.name("body").rawValue(body(it)) }
                fromTransaction?.let { w.field("fromTransaction", it) }
                w.endObject()
            }
            FormKind.Fail -> w.field("fail", failKind.wireName)
            FormKind.Delay -> require(beforeRequest > 0 || beforeResponse > 0) { "delay: give a delay before the request or before the response" }
            FormKind.Edit -> {
                val code = status.value()?.let { int(it, "response status", 100..599) }
                val responseHeaders = headers.pairs()
                val removed = names(responseRemoveHeaders)
                val responseBody = this.body.bytes("response body")
                val response = code != null || responseHeaders.isNotEmpty() || removed.isNotEmpty() || responseBody != null
                require(!request.isEmpty() || response) { "edit: change the request or the response" }
                w.name("edit").beginObject()
                if (!request.isEmpty()) {
                    w.name("request").beginObject()
                    request.write(w, body, "request")
                    w.endObject()
                }
                if (response) {
                    w.name("response").beginObject()
                    code?.let { w.field("status", it) }
                    responseHeaders.takeIf { it.isNotEmpty() }?.let { writePairs(w, "setHeaders", it) }
                    removed.takeIf { it.isNotEmpty() }?.let { writeStrings(w, "removeHeaders", it) }
                    responseBody?.let { w.name("body").rawValue(body(it)) }
                    w.endObject()
                }
                w.endObject()
            }
            FormKind.Update -> Unit
        }
        return w.endObject().toString()
    }

    /** What [FormKind.Update] sends: priority (null: unchanged), times (null: no limit), and whether hits restart. */
    fun update(): RuleUpdate {
        check(kind == FormKind.Update) { "not an update form: $kind" }
        return RuleUpdate(
            priority = priority.value()?.let { int(it, "priority", Int.MIN_VALUE..Int.MAX_VALUE) },
            times = times.value()?.let { int(it, "times", 1..RuleLimits.MAX_TIMES) },
            resetHits = resetHits,
        )
    }

    companion object {
        /**
         * "Mock this response" of [transaction]: its exact method, scheme, host, port and path,
         * status, headers and body copied in the app (`fromTransaction`), each field overridable.
         */
        fun mockOf(transaction: HttpTransaction): RuleForm = RuleForm(FormKind.Mock).apply {
            fromTransaction = transaction.id
            match.edit { replace(0, length, exactShorthand(transaction)) }
        }

        fun updateOf(rule: RemoteRule): RuleForm = RuleForm(FormKind.Update).apply {
            ruleId = rule.id
            priority.edit { replace(0, length, rule.priority.toString()) }
            rule.times?.let { t -> times.edit { replace(0, length, t.toString()) } }
        }

        /** `GET https://api.example.com:8443/users/42`: the request without its query; the URL as recorded when it cannot be split. */
        fun exactShorthand(transaction: HttpTransaction): String {
            val parts = UrlParts.parse(transaction.url) ?: return "${transaction.method} ${transaction.url.substringBefore('?')}"
            return MatchSpec(
                methods = setOf(transaction.method),
                scheme = parts.scheme,
                host = parts.host.lowercase(),
                port = parts.port,
                path = parts.path.ifEmpty { "/" },
            ).describe()
        }
    }
}

internal class RuleUpdate(val priority: Int?, val times: Int?, val resetHits: Boolean)

private fun TextFieldState.value(): String? = text.toString().trim().takeIf { it.isNotEmpty() }

private fun names(field: TextFieldState): List<String> = field.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

private fun int(text: String, field: String, range: IntRange): Int =
    text.toIntOrNull()?.takeIf { it in range } ?: throw IllegalArgumentException("$field: a number in ${range.first}..${range.last}, not '$text'")

private fun duration(text: String, field: String): Long =
    text.toLongOrNull()?.takeIf { it in 0..RuleLimits.MAX_DELAY_MS } ?: throw IllegalArgumentException("$field: milliseconds in 0..${RuleLimits.MAX_DELAY_MS}, not '$text'")

private fun writeMatch(w: JsonWriter, match: MatchSpec) {
    w.name("match").beginObject()
    match.methods?.let { writeStrings(w, "methods", it.toList()) }
    match.scheme?.let { w.field("scheme", it) }
    match.host?.let { w.field("host", it) }
    match.port?.let { w.field("port", it) }
    match.path?.let { w.field("path", it) }
    if (match.query.isNotEmpty()) writePredicates(w, "query", match.query)
    match.sources?.let { sources -> writeStrings(w, "sources", RuleSource.entries.filter { it in sources }.map { it.wireName }) }
    w.endObject()
}

private fun writePredicates(w: JsonWriter, name: String, predicates: List<Predicate>) {
    w.name(name).beginArray()
    for (p in predicates) {
        w.beginObject().field("name", p.name)
        p.value?.let { w.field("value", it) }
        if (p.absent) w.field("absent", true)
        w.endObject()
    }
    w.endArray()
}

private fun writePairs(w: JsonWriter, name: String, pairs: List<Pair<String, String>>) {
    w.name(name).beginArray()
    for ((k, v) in pairs) w.beginArray().value(k).value(v).endArray()
    w.endArray()
}

private fun writeStrings(w: JsonWriter, name: String, values: List<String>) {
    w.name(name).beginArray()
    for (v in values) w.value(v)
    w.endArray()
}
