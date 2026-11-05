package dev.koga.hopi.core.rules

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.model.TransactionSource

/**
 * The sources rules apply to, by the index of their array in [RuleState.bySource]. The names are
 * [TransactionSource]'s, the records' `source` values.
 */
@HopiInternalApi
public enum class RuleSource(public val wireName: String) {
    KTOR(TransactionSource.KTOR),
    OKHTTP(TransactionSource.OKHTTP),
    URL_CONNECTION(TransactionSource.URL_CONNECTION),
    URL_SESSION(TransactionSource.URL_SESSION),
    ;

    public companion object {
        public fun of(wireName: String): RuleSource? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * A query or header predicate: [value] null means "present"; [absent] means "not present". A
 * value is a [Glob.Mode.TEXT] glob over the percent-decoded query value or the header value.
 */
@HopiInternalApi
public class Predicate(public val name: String, public val value: String?, public val absent: Boolean = false) {
    override fun equals(other: Any?): Boolean = other is Predicate && other.name == name && other.value == value && other.absent == absent

    override fun hashCode(): Int = (name.hashCode() * 31 + (value?.hashCode() ?: 0)) * 31 + absent.hashCode()

    override fun toString(): String = when {
        absent -> "!$name"
        value == null -> name
        else -> "$name=$value"
    }
}

/** What a rule matches; null fields match anything. */
@HopiInternalApi
public class MatchSpec(
    /** Method tokens, compared case-sensitively; null or empty matches any method. */
    public val methods: Set<String>? = null,
    public val scheme: String? = null,
    public val host: String? = null,
    public val port: Int? = null,
    public val path: String? = null,
    public val query: List<Predicate> = emptyList(),
    public val headers: List<Predicate> = emptyList(),
    /** Null means every source. */
    public val sources: Set<RuleSource>? = null,
) {
    /** The shorthand form, for lists: methods, then the URL pattern (see [MatchShorthand]). */
    public fun describe(): String = buildString {
        append(if (methods.isNullOrEmpty()) "*" else methods.joinToString("|"))
        append(' ')
        scheme?.let { append(it).append("://") }
        append(host ?: "*")
        port?.let { append(':').append(it) }
        append(path ?: "/**")
        if (query.isNotEmpty()) append('?').append(query.joinToString("&"))
        if (headers.isNotEmpty()) append(" headers[").append(headers.joinToString(", ")).append(']')
    }
}

@HopiInternalApi
public class DelaySpec(public val beforeRequestMs: Long = 0, public val beforeResponseMs: Long = 0) {
    public val isZero: Boolean get() = beforeRequestMs == 0L && beforeResponseMs == 0L
}

/** How a fail rule fails a request; each source maps it to its stack's own exception or `NSError`. */
@HopiInternalApi
public enum class FailKind(public val wireName: String) {
    TIMEOUT("timeout"),
    RESET("reset"),
    UNKNOWN_HOST("unknown-host"),
    CANCEL("cancel"),
    ;

    public companion object {
        public fun of(wireName: String): FailKind? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * Bytes a rule delivers or sends. Shared by every application of the rule and never handed to an
 * app as a mutable array: a source wraps [bytes] in a fresh read-only source and never writes it.
 */
@HopiInternalApi
public class RuleBody(public val bytes: ByteArray) {
    public val size: Int get() = bytes.size

    override fun equals(other: Any?): Boolean = other is RuleBody && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "RuleBody(${bytes.size} bytes)"

    public companion object {
        public fun text(text: String): RuleBody = RuleBody(text.encodeToByteArray())
    }
}

@HopiInternalApi
public class MockSpec(
    public val status: Int,
    public val headers: List<Pair<String, String>> = emptyList(),
    public val body: RuleBody? = null,
)

/** The request side of an edit. Null fields are unchanged. */
@HopiInternalApi
public class RequestEdit(
    public val method: String? = null,
    public val host: String? = null,
    public val port: Int? = null,
    public val path: String? = null,
    public val setQuery: List<Pair<String, String>> = emptyList(),
    public val removeQuery: List<String> = emptyList(),
    public val setHeaders: List<Pair<String, String>> = emptyList(),
    public val addHeaders: List<Pair<String, String>> = emptyList(),
    public val removeHeaders: List<String> = emptyList(),
    public val body: RuleBody? = null,
) {
    /** True when the edit moves the request to another origin (host or port). */
    public val changesOrigin: Boolean get() = host != null || port != null

    public val isEmpty: Boolean
        get() = method == null && host == null && port == null && path == null && setQuery.isEmpty() &&
            removeQuery.isEmpty() && setHeaders.isEmpty() && addHeaders.isEmpty() && removeHeaders.isEmpty() && body == null

    /** True when the edit touches only what a header-level rewrite reaches (no URL, no method, no body). */
    public val headersOnly: Boolean
        get() = method == null && host == null && port == null && path == null && setQuery.isEmpty() && removeQuery.isEmpty() && body == null
}

@HopiInternalApi
public class ResponseEdit(
    public val status: Int? = null,
    public val setHeaders: List<Pair<String, String>> = emptyList(),
    public val addHeaders: List<Pair<String, String>> = emptyList(),
    public val removeHeaders: List<String> = emptyList(),
    public val body: RuleBody? = null,
) {
    public val isEmpty: Boolean
        get() = status == null && setHeaders.isEmpty() && addHeaders.isEmpty() && removeHeaders.isEmpty() && body == null
}

@HopiInternalApi
public class EditSpec(public val request: RequestEdit? = null, public val response: ResponseEdit? = null)

/** What a rule does: at most one of [mock], [fail], [edit]; none needs a non-zero [delay]. */
@HopiInternalApi
public class RuleSpec(
    public val match: MatchSpec,
    public val label: String? = null,
    public val enabled: Boolean = true,
    public val priority: Int = 0,
    public val times: Int? = null,
    public val delay: DelaySpec? = null,
    public val mock: MockSpec? = null,
    public val fail: FailKind? = null,
    public val edit: EditSpec? = null,
) {
    /** Bytes of the rule's bodies, counted toward [RuleLimits.MAX_TOTAL_BODY_BYTES]. */
    public val bodyBytes: Long
        get() = (mock?.body?.size ?: 0).toLong() + (edit?.request?.body?.size ?: 0) + (edit?.response?.body?.size ?: 0)

    /** The action names in the record mark's fixed order, as far as the rule itself decides them. */
    public fun appliedNames(): String = buildList {
        if (delay?.isZero == false) add(RuleMark.DELAY)
        if (edit?.request?.isEmpty == false) add(RuleMark.EDIT_REQUEST)
        if (mock != null) add(RuleMark.MOCK)
        if (fail != null) add(RuleMark.FAIL)
        if (edit?.response?.isEmpty == false) add(RuleMark.EDIT_RESPONSE)
    }.joinToString(",")

    public fun copy(
        enabled: Boolean = this.enabled,
        priority: Int = this.priority,
        times: Int? = this.times,
        label: String? = this.label,
    ): RuleSpec = RuleSpec(match, label, enabled, priority, times, delay, mock, fail, edit)
}

/** The values of a record's `ruleApplied` mark ([dev.koga.hopi.core.model.HttpTransaction.ruleApplied]), in their fixed order. */
@HopiInternalApi
public object RuleMark {
    public const val DELAY: String = "delay"
    public const val EDIT_REQUEST: String = "edit-request"
    public const val MOCK: String = "mock"
    public const val FAIL: String = "fail"
    public const val EDIT_RESPONSE: String = "edit-response"

    /** All of them in order: what a mark is joined from, so sources never build it ad hoc. */
    public val ORDER: List<String> = listOf(DELAY, EDIT_REQUEST, MOCK, FAIL, EDIT_RESPONSE)

    /** The mark for the actions in [applied], comma-separated in [ORDER]; null when none. */
    public fun of(applied: Collection<String>): String? = ORDER.filter { it in applied }.joinToString(",").ifEmpty { null }
}

/** A change to an existing rule (`rules.update`); null fields stay as they are. */
@HopiInternalApi
public class RulePatch(
    public val enabled: Boolean? = null,
    public val priority: Int? = null,
    /** [clearTimes] removes the limit; otherwise a non-null value sets it. */
    public val times: Int? = null,
    public val clearTimes: Boolean = false,
    public val resetHits: Boolean = false,
)

/** A source that cannot apply (all or part of) a rule, and why: what `rules.add` reports in `unsupported`. */
@HopiInternalApi
public class Unsupported(
    public val source: RuleSource,
    public val reason: String,
    /** False when the rule still applies on [source] and [reason] states a difference (a mocked 3xx not followed). */
    public val excluded: Boolean,
) {
    override fun toString(): String = "${source.wireName}: $reason"
}

/** One rule as listed: its spec plus what the engine knows of it. */
@HopiInternalApi
public class RuleInfo(
    public val id: Long,
    public val spec: RuleSpec,
    public val hits: Long,
    public val exhausted: Boolean,
    public val unsupported: List<Unsupported>,
)

/** Why the engine refused a call; the bridge sends [code] as the wire error code. */
@HopiInternalApi
public enum class RuleErrorCode { BAD_REQUEST, LIMIT, NOT_FOUND, UNSUPPORTED }

@HopiInternalApi
public class RuleError(public val code: RuleErrorCode, public val message: String) {
    override fun toString(): String = "$code: $message"
}

@HopiInternalApi
public sealed interface RuleResult {
    public class Ok(public val rule: RuleInfo) : RuleResult

    public class Failed(public val error: RuleError) : RuleResult
}
