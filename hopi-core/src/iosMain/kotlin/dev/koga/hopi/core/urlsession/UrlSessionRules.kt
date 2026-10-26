package dev.koga.hopi.core.urlsession

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.internal.urlsession.HOPI_US_ACTION_DELAY
import dev.koga.hopi.core.internal.urlsession.HOPI_US_ACTION_EDIT
import dev.koga.hopi.core.internal.urlsession.HOPI_US_ACTION_FAIL
import dev.koga.hopi.core.internal.urlsession.HOPI_US_ACTION_MOCK
import dev.koga.hopi.core.internal.urlsession.HOPI_US_RULES_SESSIONS
import dev.koga.hopi.core.internal.urlsession.hopi_us_rules_add
import dev.koga.hopi.core.internal.urlsession.hopi_us_rules_begin
import dev.koga.hopi.core.internal.urlsession.hopi_us_rules_delay
import dev.koga.hopi.core.internal.urlsession.hopi_us_rules_edit
import dev.koga.hopi.core.internal.urlsession.hopi_us_rules_enable
import dev.koga.hopi.core.internal.urlsession.hopi_us_rules_fail
import dev.koga.hopi.core.internal.urlsession.hopi_us_rules_match
import dev.koga.hopi.core.internal.urlsession.hopi_us_rules_mock
import dev.koga.hopi.core.internal.urlsession.hopi_us_rules_predicate
import dev.koga.hopi.core.internal.urlsession.hopi_us_rules_publish
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.rules.CompiledRule
import dev.koga.hopi.core.rules.NativeHitCounter
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleSource
import dev.koga.hopi.core.rules.RuleState
import dev.koga.hopi.core.rules.UrlParts
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSLock
import platform.Foundation.create

/**
 * The Kotlin side of rules on URLSession (docs/design/rules.md, "URLSession"): mirrors the
 * engine's URLSession rules into the native library on every publish, synchronously, so a rule is
 * live in the hooks when `rules.add` returns, and republishes after a native claim used up a
 * rule. The hooks themselves never call Kotlin.
 */
@OptIn(ExperimentalForeignApi::class)
@HopiInternalApi
public object UrlSessionRules {
    private val enabled = AtomicBoolean(false)
    private val features = AtomicInt(0)
    private val redactor = AtomicReference<() -> Redactor>({ Redactor.Default })

    // Serializes mirrors: listeners of two concurrent mutations may run in either order, so each
    // mirror publishes the engine's current state, not the one it was called with. A lock, not a
    // spin: a mutator that waits sleeps while the other copies bodies, and still returns only
    // after the native set holds its change. Everything below is guarded by it.
    private val mirroring = NSLock()
    private var mirrored: RuleState? = null
    private var mirroredRules: Array<CompiledRule> = emptyArray()

    // The NSData of each rule body the published set holds, by the body's array (identity): a
    // republish passes the same NSData instead of copying the bytes again.
    private var bodyData: Map<ByteArray, NSData> = emptyMap()

    /** How many times the native rule set was rebuilt (tests; read under no lock, so approximate while mutating). */
    public var publishes: Int = 0
        private set

    private val CREDENTIALS = listOf("authorization", "proxy-authorization", "cookie")

    /** Whether new sessions get the rule protocol (false: only `URLSession.shared` can be mocked). */
    public val sessionsCovered: Boolean get() = features.load() and HOPI_US_RULES_SESSIONS != 0

    /**
     * Turns rules on, once, after capture started; [redactor] gives the store's redaction, whose
     * headers an origin-changing edit removes. Mirrors the rules that exist already.
     */
    internal fun enable(redactor: () -> Redactor) {
        if (!enabled.compareAndSet(false, true)) return
        this.redactor.store(redactor)
        features.store(hopi_us_rules_enable(staticCFunction(::rulesExhausted)))
        RuleEngine.addListener { mirror() }
        mirror()
    }

    /**
     * Publishes the engine's current URLSession rules to the native library. Never throws. A state
     * whose URLSession rules are the ones mirrored already (a change on another source's rules)
     * publishes nothing: the compiled rules are shared between states.
     */
    public fun mirror() {
        mirroring.lock()
        try {
            val state = RuleEngine.state
            if (state === mirrored) return
            val rules = state.rulesFor(RuleSource.URL_SESSION)
            if (!sameRules(rules, mirroredRules)) {
                publish(rules)
                mirroredRules = rules
            }
            mirrored = state
        } catch (_: Throwable) {
            // A rule that cannot be mirrored stays off URLSession; the app is not affected.
        } finally {
            mirroring.unlock()
        }
    }

    private fun sameRules(a: Array<CompiledRule>, b: Array<CompiledRule>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] !== b[i]) return false
        return true
    }

    private fun publish(rules: Array<CompiledRule>) {
        publishes++
        if (rules.isEmpty()) {
            hopi_us_rules_publish(null)
            bodyData = emptyMap()
            return
        }
        val previous = bodyData
        val data = HashMap<ByteArray, NSData>()
        fun nsData(bytes: ByteArray): NSData = data.getOrPut(bytes) { previous[bytes] ?: bytes.toNSData() }
        val strip = (CREDENTIALS + redactor.load()().redactedHeaderNames).distinct()
        val builder = hopi_us_rules_begin()
        for (rule in rules) {
            val action = when {
                rule.mock != null -> HOPI_US_ACTION_MOCK
                rule.fail != null -> HOPI_US_ACTION_FAIL
                rule.requestEdit != null -> HOPI_US_ACTION_EDIT
                else -> HOPI_US_ACTION_DELAY
            }
            val hits = (rule.hits.counter as NativeHitCounter).handle
            hopi_us_rules_add(builder, rule.id, hits, rule.times?.toLong() ?: 0L, rule.applied, action)
            val match = rule.spec.match
            hopi_us_rules_match(
                builder,
                match.methods?.takeIf { it.isNotEmpty() }?.toList(),
                match.scheme,
                match.port ?: -1,
                match.host?.takeIf { it != "*" }?.lowercase(),
                match.path?.takeIf { it != "**" && it != "/**" },
            )
            for (p in match.query) hopi_us_rules_predicate(builder, false, p.name, p.value?.takeIf { it != "*" }, p.absent)
            for (p in match.headers) hopi_us_rules_predicate(builder, true, p.name, p.value?.takeIf { it != "*" }, p.absent)
            rule.delay?.let { hopi_us_rules_delay(builder, it.beforeRequestMs, it.beforeResponseMs) }
            rule.mock?.let { hopi_us_rules_mock(builder, it.status, flat(it.headers), it.body?.bytes?.let(::nsData)) }
            rule.fail?.let { hopi_us_rules_fail(builder, it.ordinal) }
            rule.requestEdit?.let { edit ->
                hopi_us_rules_edit(
                    builder,
                    edit.method,
                    edit.host?.removePrefix("[")?.removeSuffix("]"),
                    edit.port ?: -1,
                    edit.path,
                    (edit.removeQuery + edit.setQuery.map { it.first }).distinct(),
                    edit.setQuery.map { (name, value) -> UrlParts.percentEncode(name) + "=" + UrlParts.percentEncode(value) },
                    (edit.removeHeaders + edit.setHeaders.map { it.first }).map { it.lowercase() }.distinct(),
                    (edit.setHeaders + edit.addHeaders).map { it.first.lowercase() }.distinct(),
                    strip,
                    flat(edit.setHeaders + edit.addHeaders),
                    edit.body?.bytes?.let(::nsData),
                )
            }
        }
        hopi_us_rules_publish(builder)
        bodyData = data
    }

    internal fun flat(pairs: List<Pair<String, String>>): List<String> {
        val out = ArrayList<String>(pairs.size * 2)
        for ((name, value) in pairs) {
            out += name
            out += value
        }
        return out
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

// The native library's callback, on the capture queue: top-level, as staticCFunction needs.
private fun rulesExhausted() {
    try {
        RuleEngine.republishAfterExhaustion()
    } catch (_: Throwable) {
        // Never let an exception cross into the native caller.
    }
}
