package dev.koga.hopi.cli

import dev.koga.hopi.connect.usageError
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.rules.MatchShorthand
import dev.koga.hopi.core.rules.MatchSpec
import dev.koga.hopi.core.rules.Predicate
import dev.koga.hopi.core.rules.RuleLimits
import dev.koga.hopi.core.rules.RuleSource
import java.net.URI
import java.net.URISyntaxException

/**
 * A rule from the command line, checked before anything connects: the match shorthand parsed by
 * core (the app receives it structured), numbers, durations, `K: V` headers and bodies read. [json]
 * writes the wire object once the bridge is up, since a large body goes up as a blob of that
 * connection first.
 */
internal class RuleDraft private constructor(
    private val match: MatchSpec?,
    private val fields: (JsonWriter, BodyJson) -> Unit,
    /** The shorthand parsed with the command line's --source, --query and --match-header. */
    private val parseMatch: (String) -> MatchSpec,
) {
    /** `mock add --from <id>` without --match: the match is made from that request once connected. */
    val needsMatch: Boolean get() = match == null

    /** This draft matching [method] and [url] exactly (any query), as the viewers' "Mock This Response". */
    fun matchingExactly(method: String, url: String): RuleDraft = RuleDraft(
        try {
            parseMatch(exactShorthand(method, url))
        } catch (e: IllegalArgumentException) {
            throw usageError("cannot match that request exactly (${e.message}): pass --match")
        },
        fields,
        parseMatch,
    )

    /** Turns a body into its wire object (inline, or a blob it uploads). */
    internal fun interface BodyJson {
        fun of(bytes: ByteArray): String
    }

    /** The rule object (`mock add` and friends). */
    fun json(body: BodyJson): String {
        val w = JsonWriter().beginObject()
        match?.let { writeMatch(w, it) }
        fields(w, body)
        return w.endObject().toString()
    }

    companion object {
        /** [readBody] resolves a `--body` value: `@file`, `@-` (stdin) or the literal text. */
        fun rule(args: CliArgs, readBody: (String) -> ByteArray): RuleDraft {
            val sources = args.values["--source"]?.let { list ->
                list.split(',').map { name -> RuleSource.of(name.trim()) ?: throw usageError("--source: unknown source '$name' (ktor, okhttp, urlconnection, urlsession)") }.toSet()
            }
            val queries = try {
                args.lists["--query"].orEmpty().map(MatchShorthand::predicate)
            } catch (e: IllegalArgumentException) {
                throw usageError("--query: ${e.message}")
            }
            val headers = try {
                args.lists["--match-header"].orEmpty().map(MatchShorthand::headerPredicate)
            } catch (e: IllegalArgumentException) {
                throw usageError("--match-header: ${e.message}")
            }
            val parseMatch = { shorthand: String -> MatchShorthand.parse(shorthand, sources, queries, headers) }
            val match = args.values["--match"]?.let {
                try {
                    parseMatch(it)
                } catch (e: IllegalArgumentException) {
                    throw usageError("--match: ${e.message}")
                }
            }
            val label = args.values["--label"]
            val times = args.values["--times"]?.let { int(it, "--times", 1..RuleLimits.MAX_TIMES) }
            val priority = args.values["--priority"]?.let { int(it, "--priority", Int.MIN_VALUE..Int.MAX_VALUE) }
            val disabled = "--disabled" in args.switches
            val action: (JsonWriter, BodyJson) -> Unit = when (args.command) {
                Command.MockAdd -> mock(args, readBody)
                Command.FailAdd -> fail(args)
                Command.DelayAdd -> delay(args)
                Command.EditAdd -> edit(args, readBody)
                else -> error("not a rule command: ${args.command}")
            }
            val fields = { w: JsonWriter, body: BodyJson ->
                label?.let { w.field("label", it) }
                times?.let { w.field("times", it) }
                priority?.let { w.field("priority", it) }
                if (disabled) w.field("enabled", false)
                action(w, body)
            }
            return RuleDraft(match, fields, parseMatch)
        }

        private fun mock(args: CliArgs, readBody: (String) -> ByteArray): (JsonWriter, BodyJson) -> Unit {
            val from = args.values["--from"]?.let { long(it, "--from") }
            val status = args.values["--status"]?.let { int(it, "--status", 100..599) }
            if (status == null && from == null) throw usageError("mock add needs --status <code> (or --from <id>)")
            val headers = args.lists["--header"]?.map { header(it, "--header") }
            val body = args.values["--body"]?.let { bodyBytes(readBody(it), "--body") }
            val delay = args.values["--delay"]?.let { duration(it, "--delay") }
            return { w, json ->
                delay?.let { w.name("delay").beginObject().field("beforeResponseMs", it).endObject() }
                w.name("mock").beginObject()
                status?.let { w.field("status", it) }
                headers?.let { writePairs(w, "headers", it) }
                body?.let { w.name("body").rawValue(json.of(it)) }
                from?.let { w.field("fromTransaction", it) }
                w.endObject()
            }
        }

        private fun fail(args: CliArgs): (JsonWriter, BodyJson) -> Unit {
            val with = args.values["--with"] ?: throw usageError("fail add needs --with timeout|reset|unknown-host|cancel")
            if (with !in setOf("timeout", "reset", "unknown-host", "cancel")) throw usageError("--with must be timeout, reset, unknown-host or cancel, not '$with'")
            val after = args.values["--after"]?.let { duration(it, "--after") }
            return { w, _ ->
                after?.let { w.name("delay").beginObject().field("beforeResponseMs", it).endObject() }
                w.field("fail", with)
            }
        }

        private fun delay(args: CliArgs): (JsonWriter, BodyJson) -> Unit {
            val before = args.values["--before-request"]?.let { duration(it, "--before-request") } ?: 0
            val after = args.values["--before-response"]?.let { duration(it, "--before-response") } ?: 0
            if (before == 0L && after == 0L) throw usageError("delay add needs --before-request or --before-response")
            return { w, _ -> w.name("delay").beginObject().field("beforeRequestMs", before).field("beforeResponseMs", after).endObject() }
        }

        private fun edit(args: CliArgs, readBody: (String) -> ByteArray): (JsonWriter, BodyJson) -> Unit {
            val request = requestEdit(args, readBody, bodyFlag = "--body")
            val status = args.values["--status"]?.let { int(it, "--status", 100..599) }
            val setHeaders = args.lists["--set-response-header"]?.map { header(it, "--set-response-header") }
            val addHeaders = args.lists["--add-response-header"]?.map { header(it, "--add-response-header") }
            val removeHeaders = args.lists["--remove-response-header"]
            val body = args.values["--response-body"]?.let { bodyBytes(readBody(it), "--response-body") }
            val response = status != null || setHeaders != null || addHeaders != null || removeHeaders != null || body != null
            if (request == null && !response) throw usageError("edit add needs at least one request or response change")
            return { w, json ->
                w.name("edit").beginObject()
                request?.let {
                    w.name("request").beginObject()
                    it(w, json)
                    w.endObject()
                }
                if (response) {
                    w.name("response").beginObject()
                    status?.let { w.field("status", it) }
                    setHeaders?.let { writePairs(w, "setHeaders", it) }
                    addHeaders?.let { writePairs(w, "addHeaders", it) }
                    removeHeaders?.let { writeStrings(w, "removeHeaders", it) }
                    body?.let { w.name("body").rawValue(json.of(it)) }
                    w.endObject()
                }
                w.endObject()
            }
        }

        /** The request-edit object's fields, or null when none of its flags is given. */
        private fun requestEdit(args: CliArgs, readBody: (String) -> ByteArray, bodyFlag: String): ((JsonWriter, BodyJson) -> Unit)? {
            val method = args.values["--method"]
            val host = args.values["--host"]
            val port = args.values["--port"]?.let { int(it, "--port", 1..65535) }
            // Requests to another origin carry their path, query and body there: a person decides.
            if ((host != null || port != null) && "--allow-other-origin" !in args.switches) {
                throw usageError(
                    "--host and --port send the matched requests to another origin, with their path, query and body; " +
                        "add --allow-other-origin to confirm (an AI agent asks its user first)",
                )
            }
            val path = args.values["--path"]
            val setQuery = args.lists["--set-query"]?.map { pair ->
                if ('=' !in pair) throw usageError("--set-query takes name=value, not '$pair'")
                pair.substringBefore('=') to pair.substringAfter('=')
            }
            val removeQuery = args.lists["--remove-query"]
            val setHeaders = args.lists["--set-header"]?.map { header(it, "--set-header") }
            val addHeaders = args.lists["--add-header"]?.map { header(it, "--add-header") }
            val removeHeaders = args.lists["--remove-header"]
            val body = args.values[bodyFlag]?.let { bodyBytes(readBody(it), bodyFlag) }
            if (listOf(method, host, port, path, setQuery, removeQuery, setHeaders, addHeaders, removeHeaders, body).all { it == null }) return null
            return { w, json ->
                method?.let { w.field("method", it) }
                host?.let { w.field("host", it) }
                port?.let { w.field("port", it) }
                path?.let { w.field("path", it) }
                setQuery?.let { writePairs(w, "setQuery", it) }
                removeQuery?.let { writeStrings(w, "removeQuery", it) }
                setHeaders?.let { writePairs(w, "setHeaders", it) }
                addHeaders?.let { writePairs(w, "addHeaders", it) }
                removeHeaders?.let { writeStrings(w, "removeHeaders", it) }
                body?.let { w.name("body").rawValue(json.of(it)) }
            }
        }

        private fun writeMatch(w: JsonWriter, match: MatchSpec) {
            w.name("match").beginObject()
            match.methods?.let { writeStrings(w, "methods", it.toList()) }
            match.scheme?.let { w.field("scheme", it) }
            match.host?.let { w.field("host", it) }
            match.port?.let { w.field("port", it) }
            match.path?.let { w.field("path", it) }
            if (match.query.isNotEmpty()) writePredicates(w, "query", match.query)
            if (match.headers.isNotEmpty()) writePredicates(w, "headers", match.headers)
            match.sources?.let { sources -> writeStrings(w, "sources", RuleSource.entries.filter { it in sources }.map { it.wireName }) }
            w.endObject()
        }

        private fun writePredicates(w: JsonWriter, name: String, predicates: kotlin.collections.List<Predicate>) {
            w.name(name).beginArray()
            for (p in predicates) {
                w.beginObject().field("name", p.name)
                p.value?.let { w.field("value", it) }
                if (p.absent) w.field("absent", true)
                w.endObject()
            }
            w.endArray()
        }

        private fun writePairs(w: JsonWriter, name: String, pairs: kotlin.collections.List<Pair<String, String>>) {
            w.name(name).beginArray()
            for ((k, v) in pairs) w.beginArray().value(k).value(v).endArray()
            w.endArray()
        }

        private fun writeStrings(w: JsonWriter, name: String, values: kotlin.collections.List<String>) {
            w.name(name).beginArray()
            for (v in values) w.value(v)
            w.endArray()
        }

        /** `Name: value` (curl's form); the value may be empty. */
        private fun header(text: String, flag: String): Pair<String, String> {
            val colon = text.indexOf(':')
            if (colon <= 0) throw usageError("$flag takes 'Name: value', not '$text'")
            return text.substring(0, colon).trim() to text.substring(colon + 1).trim()
        }

        private fun bodyBytes(bytes: ByteArray, flag: String): ByteArray {
            if (bytes.size > RuleLimits.MAX_BODY_BYTES) throw usageError("$flag: ${bytes.size} bytes, more than the ${RuleLimits.MAX_BODY_BYTES} a rule body may hold")
            return bytes
        }

        private fun int(text: String, flag: String, range: IntRange): Int =
            text.toIntOrNull()?.takeIf { it in range } ?: throw usageError("$flag must be a number in ${range.first}..${range.last}, not '$text'")

        private fun long(text: String, flag: String): Long =
            text.toLongOrNull()?.takeIf { it >= 1 } ?: throw usageError("$flag must be a transaction id ≥ 1, not '$text'")

        /** Milliseconds of `500ms`, `2s` or `1.5s`, at most the 120 s a rule may wait. */
        fun duration(text: String, flag: String): Long {
            val ms = when {
                text.endsWith("ms") -> text.dropLast(2).toLongOrNull()
                text.endsWith("s") -> text.dropLast(1).toBigDecimalOrNull()?.let { (it * java.math.BigDecimal(1000)).toLong() }
                else -> null
            } ?: throw usageError("$flag takes a duration such as 500ms or 2s, not '$text'")
            if (ms !in 0..RuleLimits.MAX_DELAY_MS) throw usageError("$flag must be 0..120s, not '$text'")
            return ms
        }
    }
}

/**
 * `GET https://api.example.com:8443/users/42`: [url] exactly, without its query (so any query
 * matches), as the viewers write it for "Mock This Response".
 */
internal fun exactShorthand(method: String, url: String): String {
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        null
    }
    val host = uri?.host ?: return "$method ${url.substringBefore('?')}"
    val scheme = uri.scheme?.let { "$it://" }.orEmpty()
    val port = if (uri.port != -1) ":${uri.port}" else ""
    val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
    return "$method $scheme${host.lowercase()}$port$path"
}
