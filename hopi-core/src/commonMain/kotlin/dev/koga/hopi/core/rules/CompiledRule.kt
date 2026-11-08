package dev.koga.hopi.core.rules

import dev.koga.hopi.api.HopiInternalApi
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * A rule's application count, shared by every source and by successive [CompiledRule]s of one rule.
 * The count lives in a [HitCounter] each platform provides: an atomic on the JVM and Android, C
 * memory on iOS, where URLSession's hooks claim through the same counter without calling Kotlin.
 */
@HopiInternalApi
public class RuleHits {
    internal val counter: HitCounter = newHitCounter()

    public val value: Long get() = counter.value

    /** One compare-and-increment against [times] (null: unlimited); REFUSED when the limit is reached. */
    internal fun claim(times: Int?): Claim = counter.claim(times)

    internal enum class Claim { CLAIMED, LAST, REFUSED }
}

/** Where a [RuleHits] keeps its count. */
internal interface HitCounter {
    val value: Long

    fun claim(times: Int?): RuleHits.Claim
}

/** The platform's [HitCounter]. */
internal expect fun newHitCounter(): HitCounter

/** The JVM and Android counter: one atomic. */
internal class AtomicHitCounter : HitCounter {
    private val count = AtomicLong(0)

    override val value: Long get() = count.load()

    override fun claim(times: Int?): RuleHits.Claim {
        if (times == null) {
            count.incrementAndFetch()
            return RuleHits.Claim.CLAIMED
        }
        while (true) {
            val current = count.load()
            if (current >= times) return RuleHits.Claim.REFUSED
            if (count.compareAndSet(current, current + 1)) {
                return if (current + 1 == times.toLong()) RuleHits.Claim.LAST else RuleHits.Claim.CLAIMED
            }
        }
    }
}

/**
 * A rule as the sources apply it: its match compiled at `add` time, immutable apart from the
 * shared [hits]. Matched cheapest first: method, scheme, port, host, path, query, headers; the
 * source is the array the rule is in.
 */
@HopiInternalApi
public class CompiledRule internal constructor(
    public val id: Long,
    public val spec: RuleSpec,
    public val hits: RuleHits,
) {
    private val match = spec.match
    private val methodMask: Int
    private val otherMethods: Set<String>?
    private val scheme = match.scheme
    private val port = match.port
    private val host = match.host?.takeIf { it != "*" }?.let { Glob.compile(it, Glob.Mode.HOST) }
    private val path = match.path?.takeIf { it != "**" && it != "/**" }?.let { Glob.compile(it, Glob.Mode.PATH) }
    private val query = match.query.map { CompiledPredicate(it) }.toTypedArray()
    private val headers = match.headers.map { CompiledPredicate(it) }.toTypedArray()

    /** The record mark for an application of this rule ([RuleMark]); sources may add to it (a fail after a delay). */
    public val applied: String = spec.appliedNames()

    public val times: Int? get() = spec.times

    public val exhausted: Boolean get() = spec.times?.let { hits.value >= it } ?: false

    public val delay: DelaySpec? get() = spec.delay?.takeIf { !it.isZero }
    public val mock: MockSpec? get() = spec.mock
    public val fail: FailKind? get() = spec.fail
    public val requestEdit: RequestEdit? get() = spec.edit?.request?.takeIf { !it.isEmpty }
    public val responseEdit: ResponseEdit? get() = spec.edit?.response?.takeIf { !it.isEmpty }

    init {
        var mask = 0
        var others: MutableSet<String>? = null
        match.methods?.forEach { method ->
            val index = STANDARD_METHODS.indexOf(method)
            if (index >= 0) mask = mask or (1 shl index) else (others ?: HashSet<String>().also { others = it }) += method
        }
        methodMask = if (match.methods.isNullOrEmpty()) ANY_METHOD else mask
        otherMethods = others
    }

    /** Whether [request] matches; claims nothing. */
    public fun matches(request: RuleRequest): Boolean = matches(request, methodIndex(request.method))

    /**
     * [matches] with [methodIndex] of the request's method already computed: what [RuleState.firstMatch]
     * passes, so a request's method is looked up once, not once per rule.
     */
    internal fun matches(request: RuleRequest, methodIndex: Int): Boolean {
        if (methodMask != ANY_METHOD && !methodMatches(request.method, methodIndex)) return false
        if (scheme != null && scheme != request.scheme) return false
        if (port != null && port != request.port) return false
        if (host != null && !host.matches(request.host)) return false
        if (path != null && !path.matches(request.encodedPath)) return false
        for (p in query) if (p.absent == request.hasQueryValue(p.name, p.value)) return false
        for (p in headers) if (p.absent == request.hasHeaderValue(p.name, p.value)) return false
        return true
    }

    private fun methodMatches(method: String, index: Int): Boolean {
        if (index >= 0) return methodMask and (1 shl index) != 0
        return otherMethods?.contains(method) == true
    }

    /**
     * Claims one application against `times`. False means the rule is exhausted and does not
     * match; the claim that reaches `times` republishes the rule state without it, once. A refused
     * claim republishes too while the published state still holds the rule: an update that
     * lowered `times` races requests on the older state, whose claims (against the old `times`)
     * can pass the new one without ever being the LAST.
     */
    public fun claim(): Boolean = when (hits.claim(spec.times)) {
        RuleHits.Claim.CLAIMED -> true
        RuleHits.Claim.LAST -> {
            if (republished.compareAndSet(0, 1)) RuleEngine.republishAfterExhaustion()
            true
        }
        RuleHits.Claim.REFUSED -> {
            if (republished.compareAndSet(0, 1)) RuleEngine.republishIfPublished(id)
            false
        }
    }

    /** Set by the one claim (LAST, or the first refused) that republishes the state without this rule. */
    private val republished = AtomicInt(0)

    internal fun withSpec(spec: RuleSpec, hits: RuleHits = this.hits): CompiledRule = CompiledRule(id, spec, hits)

    override fun toString(): String = "CompiledRule(#$id ${match.describe()})"

    /**
     * A query or header predicate: it holds when the request has a value matching [value] (any
     * value when null: `*` or no value given), or, when [absent], when it has no value at all.
     */
    private class CompiledPredicate(predicate: Predicate) {
        val name = predicate.name
        val absent = predicate.absent
        val value = if (absent) null else predicate.value?.takeIf { it != "*" }?.let { Glob.compile(it, Glob.Mode.TEXT) }
    }

    internal companion object {
        private const val ANY_METHOD = -1
        private val STANDARD_METHODS = listOf("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH")

        /** The bit of [method] in a rule's method mask; -1 for a method outside the standard nine. */
        fun methodIndex(method: String): Int = when (method) {
            "GET" -> 0
            "HEAD" -> 1
            "POST" -> 2
            "PUT" -> 3
            "DELETE" -> 4
            "CONNECT" -> 5
            "OPTIONS" -> 6
            "TRACE" -> 7
            "PATCH" -> 8
            else -> -1
        }
    }
}
