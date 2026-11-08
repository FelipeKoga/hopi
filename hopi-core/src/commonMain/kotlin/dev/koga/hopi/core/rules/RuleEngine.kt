package dev.koga.hopi.core.rules

import dev.koga.hopi.api.HopiInternalApi
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the sources read once per request ([RuleEngine.state]): immutable, [EMPTY] when no enabled,
 * unexhausted rule exists, so the whole no-rule cost of a request is one volatile read and one reference comparison. [bySource] is indexed by [RuleSource.ordinal]
 * and holds, in the order they are tried (priority, then newest first), only the rules that source
 * can apply ([Capabilities]); a source with none of its own sees an empty array.
 */
@HopiInternalApi
public class RuleState internal constructor(
    public val bySource: Array<Array<CompiledRule>>,
    public val revision: Long,
) {
    /** The rules [source] tries, in order. */
    public fun rulesFor(source: RuleSource): Array<CompiledRule> = bySource[source.ordinal]

    /**
     * One rule per request: the first rule of [source] that matches [request] and whose claim
     * succeeds (an exhausted rule does not match and the scan goes on); null when none applies.
     * A matching rule [refused] says the source cannot apply (an edit to another origin on a
     * client that would add the app's credentials back) ends the scan: no rule, nothing claimed.
     */
    public fun firstMatch(source: RuleSource, request: RuleRequest, refused: ((CompiledRule) -> Boolean)? = null): CompiledRule? {
        val rules = bySource[source.ordinal]
        if (rules.isEmpty()) return null
        val methodIndex = CompiledRule.methodIndex(request.method)
        for (rule in rules) {
            if (!rule.matches(request, methodIndex)) continue
            // A rule the source cannot apply safely: the request goes unruled, the rule unclaimed.
            if (refused != null && refused(rule)) return null
            if (rule.claim()) return rule
        }
        return null
    }

    public companion object {
        private val NO_RULES = emptyArray<CompiledRule>()

        public val EMPTY: RuleState = RuleState(Array(RuleSource.entries.size) { NO_RULES }, revision = 0)
    }
}

/**
 * The process-wide rule set (docs/design/rules.md, "Engine"). Mutations are rare (a CLI command, a
 * tap, a test) and at most [RuleLimits.MAX_RULES] entries long: each copies the authoring book,
 * compiles and publishes with a compare-and-set loop, so readers never see a half-built set and
 * never take a lock. [CompiledRule]s are shared between successive states, so their hits survive
 * a republish. Nothing is persisted: the rules and their bodies die with the process.
 *
 * Every call is thread-safe and takes effect before it returns; listeners ([addListener]) run on
 * the mutating thread, synchronously, after the new state is published.
 */
@HopiInternalApi
public object RuleEngine {
    private class Entry(val compiled: CompiledRule, val unsupported: List<Unsupported>) {
        val id: Long get() = compiled.id
        val spec: RuleSpec get() = compiled.spec
    }

    private class Book(val entries: List<Entry>, val revision: Long, val state: RuleState) {
        val bodyBytes: Long get() = entries.sumOf { it.spec.bodyBytes }
    }

    private val book = AtomicReference(Book(emptyList(), revision = 0, RuleState.EMPTY))
    private val published = AtomicReference(RuleState.EMPTY)
    private val nextRuleId = AtomicLong(0)
    private val listeners = AtomicReference<List<(RuleState) -> Unit>>(emptyList())
    private val _revision = MutableStateFlow(0L)

    // Recorders that started (high 32 bits) and how many with rules on (low 32 bits; hello's
    // `rules`): one atomic, so a reader never sees a start counted without its switch.
    private val recorders = AtomicLong(0)

    /** The current state: one volatile read. Compare with [RuleState.EMPTY] by reference. */
    public val state: RuleState get() = published.load()

    /** The book's revision, bumped by every change of the rule set (exhaustion included); for UIs while they are shown. */
    public val revision: StateFlow<Long> = _revision.asStateFlow()

    /**
     * Whether rules can apply in this process: false only when every recorder that started has its
     * `rules` switch off. A process where no recorder started yet answers true (a rule waits for one).
     */
    public val rulesAvailable: Boolean
        get() = recorders.load().let { it == 0L || it and 0xFFFF_FFFFL > 0 }

    /** A recorder (Ktor plugin, Android runtime, URLSession capture) started with its `rules` switch at [rules]. */
    public fun recorderStarted(rules: Boolean) {
        recorders.fetchAndAdd((1L shl 32) + if (rules) 1L else 0L)
    }

    public fun add(spec: RuleSpec): RuleResult {
        RuleValidation.validate(spec)?.let { return RuleResult.Failed(it) }
        val unsupported = Capabilities.unsupported(spec)
        var created: Entry? = null
        val error = mutate { current ->
            if (current.entries.size >= RuleLimits.MAX_RULES) return@mutate limit("rules: at most ${RuleLimits.MAX_RULES} rules")
            if (current.bodyBytes + spec.bodyBytes > RuleLimits.MAX_TOTAL_BODY_BYTES) {
                return@mutate limit("rules: all rule bodies together at most ${RuleLimits.MAX_TOTAL_BODY_BYTES} bytes")
            }
            val entry = created ?: Entry(CompiledRule(nextRuleId.incrementAndFetch(), spec, RuleHits()), unsupported).also { created = it }
            Change(current.entries + entry)
        }
        if (error != null) return RuleResult.Failed(error)
        return RuleResult.Ok(info(checkNotNull(created)))
    }

    public fun update(id: Long, patch: RulePatch): RuleResult {
        if (patch.times != null && patch.times !in 1..RuleLimits.MAX_TIMES) {
            return RuleResult.Failed(RuleError(RuleErrorCode.BAD_REQUEST, "times: must be 1..${RuleLimits.MAX_TIMES}"))
        }
        var updated: Entry? = null
        val error = mutate { current ->
            val index = current.entries.indexOfFirst { it.id == id }
            if (index < 0) return@mutate notFound(id)
            val old = current.entries[index]
            val spec = old.spec.copy(
                enabled = patch.enabled ?: old.spec.enabled,
                priority = patch.priority ?: old.spec.priority,
                times = if (patch.clearTimes) null else patch.times ?: old.spec.times,
            )
            val hits = if (patch.resetHits) RuleHits() else old.compiled.hits
            val entry = Entry(old.compiled.withSpec(spec, hits), old.unsupported)
            updated = entry
            Change(current.entries.toMutableList().also { it[index] = entry })
        }
        if (error != null) return RuleResult.Failed(error)
        return RuleResult.Ok(info(checkNotNull(updated)))
    }

    public fun remove(id: Long): Boolean = mutate { current ->
        if (current.entries.none { it.id == id }) return@mutate notFound(id)
        Change(current.entries.filterNot { it.id == id })
    } == null

    /** Removes every rule; returns how many there were. */
    public fun clear(): Int {
        var removed = 0
        mutate { current ->
            removed = current.entries.size
            Change(emptyList())
        }
        return removed
    }

    /** Every rule, in the order they were added. */
    public fun list(): List<RuleInfo> = book.load().entries.map(::info)

    public fun get(id: Long): RuleInfo? = book.load().entries.firstOrNull { it.id == id }?.let(::info)

    /**
     * Calls [listener] after every publish with the new state, on the mutating thread (a claiming
     * request's thread for an exhaustion): it must return at once and never throw. Returns the
     * function that removes it.
     */
    public fun addListener(listener: (RuleState) -> Unit): () -> Unit {
        updateListeners { it + listener }
        return { updateListeners { current -> current.filterNot { it === listener } } }
    }

    /**
     * Republishes without the rule whose claim reached its `times`; on the claiming thread, once per
     * rule. Public for `hopi-network`'s URLSession rule mirror, whose library's hooks claim in native
     * code and call this back on its capture queue.
     */
    public fun republishAfterExhaustion() {
        mutate { current -> Change(current.entries) }
    }

    /** Republishes when the published state still offers rule [id] (an exhausted rule refused a claim). */
    internal fun republishIfPublished(id: Long) {
        val state = published.load()
        if (state.bySource.any { rules -> rules.any { it.id == id } }) republishAfterExhaustion()
    }

    /** Tests: drops every rule and recorder registration. Rule ids keep counting. */
    public fun resetForTests() {
        clear()
        recorders.store(0)
    }

    private class Change(val entries: List<Entry>)

    /**
     * Applies [change] to the book with a compare-and-set loop and publishes the result; returns
     * the error [change] returned (as a [RuleError]) instead of a [Change], else null. [change]
     * may run more than once and must be free of side effects other than idempotent ones.
     */
    private inline fun mutate(change: (Book) -> Any): RuleError? {
        while (true) {
            val current = book.load()
            val result = change(current)
            if (result is RuleError) return result
            result as Change
            val revision = current.revision + 1
            val next = Book(result.entries, revision, compile(result.entries, revision))
            if (book.compareAndSet(current, next)) {
                publish()
                return null
            }
        }
    }

    /**
     * Publishes the book's state. Two mutators may finish out of order, so each publishes whatever
     * the book holds and repeats while the book changed under it: the last store is always the
     * newest book's state.
     */
    private fun publish() {
        var current: Book
        do {
            current = book.load()
            published.store(current.state)
        } while (book.load() !== current)
        _revision.value = current.revision
        for (listener in listeners.load()) listener(current.state)
    }

    private fun compile(entries: List<Entry>, revision: Long): RuleState {
        val live = entries.filter { it.spec.enabled && !it.compiled.exhausted }
        if (live.isEmpty()) return RuleState.EMPTY
        // Priority first (higher tried first), then newest first.
        val ordered = live.sortedWith(compareByDescending<Entry> { it.spec.priority }.thenByDescending { it.id })
        val bySource = Array(RuleSource.entries.size) { index ->
            val source = RuleSource.entries[index]
            ordered.filter { Capabilities.applies(source, it.spec) }.map { it.compiled }.toTypedArray()
        }
        return RuleState(bySource, revision)
    }

    private fun info(entry: Entry): RuleInfo =
        RuleInfo(entry.id, entry.spec, entry.compiled.hits.value, entry.compiled.exhausted, entry.unsupported)

    private inline fun updateListeners(change: (List<(RuleState) -> Unit>) -> List<(RuleState) -> Unit>) {
        while (true) {
            val current = listeners.load()
            if (listeners.compareAndSet(current, change(current))) return
        }
    }

    private fun limit(message: String) = RuleError(RuleErrorCode.LIMIT, message)

    private fun notFound(id: Long) = RuleError(RuleErrorCode.NOT_FOUND, "no rule $id")
}
