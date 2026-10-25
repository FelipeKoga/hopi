package dev.koga.hopi.rules

import dev.koga.hopi.core.rules.DelaySpec
import dev.koga.hopi.core.rules.EditSpec
import dev.koga.hopi.core.rules.FailKind
import dev.koga.hopi.core.rules.Predicate
import dev.koga.hopi.core.rules.RequestEdit
import dev.koga.hopi.core.rules.ResponseEdit
import dev.koga.hopi.core.rules.RuleBody
import dev.koga.hopi.core.rules.RuleSource
import kotlin.time.Duration

/** How a [HopiRules.fail] rule fails a request: each source throws or reports its own stack's error. */
public enum class HopiFailure {
    /** A timeout (`SocketTimeoutException`, `NSURLErrorTimedOut`). */
    Timeout,

    /** The connection reset (`SocketException`, `NSURLErrorNetworkConnectionLost`). */
    Reset,

    /** The host does not resolve (`UnknownHostException`, `NSURLErrorCannotFindHost`). */
    UnknownHost,

    /** The call cancelled, as if the app cancelled it. */
    Cancel,
    ;

    internal val kind: FailKind
        get() = when (this) {
            Timeout -> FailKind.TIMEOUT
            Reset -> FailKind.RESET
            UnknownHost -> FailKind.UNKNOWN_HOST
            Cancel -> FailKind.CANCEL
        }
}

/** The recorders a rule can apply on; a rule names none to apply on all of them. */
public enum class HopiSource {
    Ktor,
    OkHttp,
    UrlConnection,
    UrlSession,
    ;

    internal val source: RuleSource
        get() = when (this) {
            Ktor -> RuleSource.KTOR
            OkHttp -> RuleSource.OKHTTP
            UrlConnection -> RuleSource.URL_CONNECTION
            UrlSession -> RuleSource.URL_SESSION
        }
}

/** What every rule accepts besides its action: label, order, limits, sources, extra predicates, a delay. */
public open class HopiRuleOptions internal constructor() {
    /** Shown in lists; at most 80 chars. */
    public var label: String? = null

    /** Higher is tried first; ties: the newest first. */
    public var priority: Int = 0

    /** Apply at most this many times (1..1,000,000), then the rule is exhausted; null for no limit. */
    public var times: Int? = null

    public var enabled: Boolean = true

    internal var sources: List<HopiSource>? = null
    internal val query = ArrayList<Predicate>()
    internal val headerPredicates = ArrayList<Predicate>()
    internal var delaySpec: DelaySpec? = null

    /** Applies the rule only on these recorders. */
    public fun sources(vararg sources: HopiSource) {
        this.sources = sources.toList()
    }

    /** Matches only when the query parameter [name] has a value matching [value] (`*` any run). */
    public fun query(name: String, value: String) {
        query += Predicate(name, value)
    }

    /** Matches only when the query parameter [name] is present. */
    public fun hasQuery(name: String) {
        query += Predicate(name, null)
    }

    /** Matches only when the query parameter [name] is absent. */
    public fun noQuery(name: String) {
        query += Predicate(name, null, absent = true)
    }

    /** Matches only when the request header [name] has a value matching [value] (`*` any run). */
    public fun matchHeader(name: String, value: String) {
        headerPredicates += Predicate(name, value)
    }

    /** Matches only when the request header [name] is present. */
    public fun hasHeader(name: String) {
        headerPredicates += Predicate(name, null)
    }

    /** Matches only when the request header [name] is absent. */
    public fun noHeader(name: String) {
        headerPredicates += Predicate(name, null, absent = true)
    }

    /** Holds the request back before it is sent and/or the response before the app sees it (each 0..120 s). */
    public fun delay(beforeRequest: Duration = Duration.ZERO, beforeResponse: Duration = Duration.ZERO) {
        delaySpec = DelaySpec(beforeRequest.inWholeMilliseconds, beforeResponse.inWholeMilliseconds)
    }
}

/** A [HopiRules.mock] rule: the response the app gets. */
public class HopiMock internal constructor() : HopiRuleOptions() {
    /** 100..599, not 101; 200 when unset (or the record's, with [from]). */
    public var status: Int? = null

    internal val headers = ArrayList<Pair<String, String>>()
    internal var body: RuleBody? = null
    internal var fromTransaction: Long? = null

    /** A response header; `Content-Length` is computed, and `Set-Cookie`, `Content-Encoding`, `Transfer-Encoding` are rejected. */
    public fun header(name: String, value: String) {
        headers += name to value
    }

    /** The body as UTF-8 text; at most 1 MiB. */
    public fun body(text: String) {
        body = RuleBody.text(text)
    }

    /** The body bytes, copied; at most 1 MiB. */
    public fun body(bytes: ByteArray) {
        body = RuleBody(bytes.copyOf())
    }

    /** Builds the mock from recorded transaction [transactionId]'s response; the fields set here override it. */
    public fun from(transactionId: Long) {
        fromTransaction = transactionId
    }
}

/** A [HopiRules.delay] rule. */
public class HopiDelay internal constructor() : HopiRuleOptions() {
    public var beforeRequest: Duration = Duration.ZERO
    public var beforeResponse: Duration = Duration.ZERO
}

/** A [HopiRules.edit] rule: what changes in the request and/or the response. */
public class HopiEdit internal constructor() : HopiRuleOptions() {
    private var request: HopiRequestEdit? = null
    private var response: HopiResponseEdit? = null

    public fun request(block: HopiRequestEdit.() -> Unit) {
        request = (request ?: HopiRequestEdit()).apply(block)
    }

    public fun response(block: HopiResponseEdit.() -> Unit) {
        response = (response ?: HopiResponseEdit()).apply(block)
    }

    internal fun editSpec(): EditSpec = EditSpec(request?.toEdit(), response?.toEdit())
}

/**
 * Changes to a request (an edit rule's request side, or a replay's). The scheme never changes; an
 * edit that moves the request to another host or port drops its credentials (`Authorization`,
 * `Cookie`, redacted headers) unless it sets them itself.
 */
public class HopiRequestEdit internal constructor() {
    public var method: String? = null
    public var host: String? = null
    public var port: Int? = null

    /** The percent-encoded path, starting with `/`. */
    public var path: String? = null

    private val setQuery = ArrayList<Pair<String, String>>()
    private val removeQuery = ArrayList<String>()
    private val setHeaders = ArrayList<Pair<String, String>>()
    private val addHeaders = ArrayList<Pair<String, String>>()
    private val removeHeaders = ArrayList<String>()
    private var body: RuleBody? = null

    /** Replaces every value of the query parameter [name]. */
    public fun setQuery(name: String, value: String) {
        setQuery += name to value
    }

    public fun removeQuery(name: String) {
        removeQuery += name
    }

    /** Replaces every value of the header [name]. */
    public fun setHeader(name: String, value: String) {
        setHeaders += name to value
    }

    public fun addHeader(name: String, value: String) {
        addHeaders += name to value
    }

    public fun removeHeader(name: String) {
        removeHeaders += name
    }

    public fun body(text: String) {
        body = RuleBody.text(text)
    }

    public fun body(bytes: ByteArray) {
        body = RuleBody(bytes.copyOf())
    }

    internal fun toEdit(): RequestEdit = RequestEdit(method, host, port, path, setQuery.toList(), removeQuery.toList(), setHeaders.toList(), addHeaders.toList(), removeHeaders.toList(), body)
}

/** Changes to a response (an edit rule's response side). Unsupported on URLSession. */
public class HopiResponseEdit internal constructor() {
    public var status: Int? = null

    private val setHeaders = ArrayList<Pair<String, String>>()
    private val addHeaders = ArrayList<Pair<String, String>>()
    private val removeHeaders = ArrayList<String>()
    private var body: RuleBody? = null

    public fun setHeader(name: String, value: String) {
        setHeaders += name to value
    }

    public fun addHeader(name: String, value: String) {
        addHeaders += name to value
    }

    public fun removeHeader(name: String) {
        removeHeaders += name
    }

    public fun body(text: String) {
        body = RuleBody.text(text)
    }

    public fun body(bytes: ByteArray) {
        body = RuleBody(bytes.copyOf())
    }

    internal fun toEdit(): ResponseEdit = ResponseEdit(status, setHeaders.toList(), addHeaders.toList(), removeHeaders.toList(), body)
}

/** One rule as [HopiRules.list] returns it. */
public class HopiRuleInfo internal constructor(
    public val id: Long,
    public val label: String?,
    public val enabled: Boolean,
    public val priority: Int,
    public val times: Int?,
    /** Requests the rule was applied to. */
    public val hits: Long,
    /** True once [hits] reached [times]: the rule no longer applies until it is reset or removed. */
    public val exhausted: Boolean,
    /** The match in shorthand form. */
    public val match: String,
    /** What the rule does, as text (`mock 500 (61 B)`). */
    public val action: String,
    /** Each recorder that cannot apply (all or part of) the rule, with the reason. */
    public val unsupported: List<String>,
) {
    override fun toString(): String = "#$id $match  $action" + (times?.let { "  $hits/$it" } ?: "  $hits hits")
}
