package dev.koga.hopi.rules

import dev.koga.hopi.api.HopiInternalApi
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
}

/** The recorders a rule can apply on; a rule names none to apply on all of them. */
public enum class HopiSource {
    Ktor,
    OkHttp,
    UrlConnection,
    UrlSession,
}

/**
 * What every rule accepts besides its action: label, order, limits, sources, extra predicates, a
 * delay. A builder keeps every setting as given and validates none: the rules runtime validates
 * the rule once the block has run.
 */
public open class HopiRuleOptions internal constructor() {
    /** Shown in lists; at most 80 chars. */
    public var label: String? = null

    /** Higher is tried first; ties: the newest first. */
    public var priority: Int = 0

    /** Apply at most this many times (1..1,000,000), then the rule is exhausted; null for no limit. */
    public var times: Int? = null

    public var enabled: Boolean = true

    private val queries = ArrayList<HopiPredicate>()
    private val headerMatches = ArrayList<HopiPredicate>()

    /** The sources of the last [sources] call, which replaces the one before; null without one: every source. */
    @HopiInternalApi
    public var ruleSources: List<HopiSource>? = null
        private set

    /** The query predicates, in call order. */
    @HopiInternalApi
    public val queryPredicates: List<HopiPredicate>
        get() = queries

    /** The request-header predicates, in call order. */
    @HopiInternalApi
    public val headerPredicates: List<HopiPredicate>
        get() = headerMatches

    /** [delay]'s `beforeRequest`, null until it is called, so a negative one still reaches validation. */
    @HopiInternalApi
    public var delayBeforeRequest: Duration? = null
        private set

    /** [delay]'s `beforeResponse`, null until it is called. */
    @HopiInternalApi
    public var delayBeforeResponse: Duration? = null
        private set

    /** Applies the rule only on these recorders. */
    public fun sources(vararg sources: HopiSource) {
        ruleSources = sources.toList()
    }

    /** Matches only when the query parameter [name] has a value matching [value] (`*` any run). */
    public fun query(name: String, value: String) {
        queries += HopiPredicate(name, value, absent = false)
    }

    /** Matches only when the query parameter [name] is present. */
    public fun hasQuery(name: String) {
        queries += HopiPredicate(name, null, absent = false)
    }

    /** Matches only when the query parameter [name] is absent. */
    public fun noQuery(name: String) {
        queries += HopiPredicate(name, null, absent = true)
    }

    /** Matches only when the request header [name] has a value matching [value] (`*` any run). */
    public fun matchHeader(name: String, value: String) {
        headerMatches += HopiPredicate(name, value, absent = false)
    }

    /** Matches only when the request header [name] is present. */
    public fun hasHeader(name: String) {
        headerMatches += HopiPredicate(name, null, absent = false)
    }

    /** Matches only when the request header [name] is absent. */
    public fun noHeader(name: String) {
        headerMatches += HopiPredicate(name, null, absent = true)
    }

    /** Holds the request back before it is sent and/or the response before the app sees it (each 0..120 s). */
    public fun delay(beforeRequest: Duration = Duration.ZERO, beforeResponse: Duration = Duration.ZERO) {
        delayBeforeRequest = beforeRequest
        delayBeforeResponse = beforeResponse
    }
}

/**
 * A query or header predicate as a builder keeps it: [value] null means "present", [absent] means
 * "not present"; a value is a glob, `*` any run.
 */
@HopiInternalApi
public class HopiPredicate internal constructor(public val name: String, public val value: String?, public val absent: Boolean)

/** A [HopiRules.mock] rule: the response the app gets. */
public class HopiMock internal constructor() : HopiRuleOptions() {
    /** 100..599, not 101; 200 when unset (or the record's, with [from]). */
    public var status: Int? = null

    private val headers = ArrayList<Pair<String, String>>()

    /** The response headers, in call order. */
    @HopiInternalApi
    public val headerList: List<Pair<String, String>>
        get() = headers

    /** The body, as UTF-8 or as the copy [body] made; null without one. */
    @HopiInternalApi
    public var bodyBytes: ByteArray? = null
        private set

    /** The recorded transaction [from] named; null without one. */
    @HopiInternalApi
    public var fromTransactionId: Long? = null
        private set

    /** A response header; `Content-Length` is computed, and `Set-Cookie`, `Content-Encoding`, `Transfer-Encoding` are rejected. */
    public fun header(name: String, value: String) {
        headers += name to value
    }

    /** The body as UTF-8 text; at most 1 MiB. */
    public fun body(text: String) {
        bodyBytes = text.encodeToByteArray()
    }

    /** The body bytes, copied; at most 1 MiB. */
    public fun body(bytes: ByteArray) {
        bodyBytes = bytes.copyOf()
    }

    /** Builds the mock from recorded transaction [transactionId]'s response; the fields set here override it. */
    public fun from(transactionId: Long) {
        fromTransactionId = transactionId
    }
}

/** A [HopiRules.delay] rule. */
public class HopiDelay internal constructor() : HopiRuleOptions() {
    public var beforeRequest: Duration = Duration.ZERO
    public var beforeResponse: Duration = Duration.ZERO
}

/** A [HopiRules.edit] rule: what changes in the request and/or the response. */
public class HopiEdit internal constructor() : HopiRuleOptions() {
    /** The request side, null until [request] is called; every call adds to the same one. */
    @HopiInternalApi
    public var requestEdit: HopiRequestEdit? = null
        private set

    /** The response side, null until [response] is called; every call adds to the same one. */
    @HopiInternalApi
    public var responseEdit: HopiResponseEdit? = null
        private set

    public fun request(block: HopiRequestEdit.() -> Unit) {
        requestEdit = (requestEdit ?: HopiRequestEdit()).apply(block)
    }

    public fun response(block: HopiResponseEdit.() -> Unit) {
        responseEdit = (responseEdit ?: HopiResponseEdit()).apply(block)
    }
}

/**
 * Changes to a request (an edit rule's request side). The scheme never changes; an edit that
 * moves the request to another host or port drops its credentials (`Authorization`, `Cookie`,
 * redacted headers) unless it sets them itself.
 */
public class HopiRequestEdit internal constructor() {
    public var method: String? = null
    public var host: String? = null
    public var port: Int? = null

    /** The percent-encoded path, starting with `/`. */
    public var path: String? = null

    private val setQueries = ArrayList<Pair<String, String>>()
    private val removedQueries = ArrayList<String>()
    private val setHeaders = ArrayList<Pair<String, String>>()
    private val addedHeaders = ArrayList<Pair<String, String>>()
    private val removedHeaders = ArrayList<String>()

    /** [setQuery]'s parameters, in call order. */
    @HopiInternalApi
    public val querySets: List<Pair<String, String>>
        get() = setQueries

    /** [removeQuery]'s parameters, in call order. */
    @HopiInternalApi
    public val queryRemovals: List<String>
        get() = removedQueries

    /** [setHeader]'s headers, in call order. */
    @HopiInternalApi
    public val headerSets: List<Pair<String, String>>
        get() = setHeaders

    /** [addHeader]'s headers, in call order. */
    @HopiInternalApi
    public val headerAdds: List<Pair<String, String>>
        get() = addedHeaders

    /** [removeHeader]'s headers, in call order. */
    @HopiInternalApi
    public val headerRemovals: List<String>
        get() = removedHeaders

    /** The new body, as UTF-8 or as the copy [body] made; null to keep the request's. */
    @HopiInternalApi
    public var bodyBytes: ByteArray? = null
        private set

    /** Replaces every value of the query parameter [name]. */
    public fun setQuery(name: String, value: String) {
        setQueries += name to value
    }

    public fun removeQuery(name: String) {
        removedQueries += name
    }

    /** Replaces every value of the header [name]. */
    public fun setHeader(name: String, value: String) {
        setHeaders += name to value
    }

    public fun addHeader(name: String, value: String) {
        addedHeaders += name to value
    }

    public fun removeHeader(name: String) {
        removedHeaders += name
    }

    public fun body(text: String) {
        bodyBytes = text.encodeToByteArray()
    }

    public fun body(bytes: ByteArray) {
        bodyBytes = bytes.copyOf()
    }
}

/** Changes to a response (an edit rule's response side). Unsupported on URLSession. */
public class HopiResponseEdit internal constructor() {
    public var status: Int? = null

    private val setHeaders = ArrayList<Pair<String, String>>()
    private val addedHeaders = ArrayList<Pair<String, String>>()
    private val removedHeaders = ArrayList<String>()

    /** [setHeader]'s headers, in call order. */
    @HopiInternalApi
    public val headerSets: List<Pair<String, String>>
        get() = setHeaders

    /** [addHeader]'s headers, in call order. */
    @HopiInternalApi
    public val headerAdds: List<Pair<String, String>>
        get() = addedHeaders

    /** [removeHeader]'s headers, in call order. */
    @HopiInternalApi
    public val headerRemovals: List<String>
        get() = removedHeaders

    /** The new body, as UTF-8 or as the copy [body] made; null to keep the response's. */
    @HopiInternalApi
    public var bodyBytes: ByteArray? = null
        private set

    public fun setHeader(name: String, value: String) {
        setHeaders += name to value
    }

    public fun addHeader(name: String, value: String) {
        addedHeaders += name to value
    }

    public fun removeHeader(name: String) {
        removedHeaders += name
    }

    public fun body(text: String) {
        bodyBytes = text.encodeToByteArray()
    }

    public fun body(bytes: ByteArray) {
        bodyBytes = bytes.copyOf()
    }
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
