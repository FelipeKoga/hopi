package dev.koga.hopi.core.rules

import dev.koga.hopi.api.HopiRuntimes
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.rules.HopiDelay
import dev.koga.hopi.rules.HopiEdit
import dev.koga.hopi.rules.HopiFailure
import dev.koga.hopi.rules.HopiMock
import dev.koga.hopi.rules.HopiPredicate
import dev.koga.hopi.rules.HopiRequestEdit
import dev.koga.hopi.rules.HopiResponseEdit
import dev.koga.hopi.rules.HopiRuleInfo
import dev.koga.hopi.rules.HopiRuleInfoFactory
import dev.koga.hopi.rules.HopiRuleOptions
import dev.koga.hopi.rules.HopiRulesRuntime
import dev.koga.hopi.rules.HopiSource
import kotlin.time.Duration

/**
 * The rules runtime behind `hopi-api`'s `HopiRules` (docs/design/rules.md, "In-process API"), the
 * one the start sequence registers ([register]); core's JVM tests may register it themselves. Each
 * call goes to [ApiRuleCalls], and that is all this object does: a debug build runs from its APK,
 * unoptimized, so ART verifies a class when it is first initialized and loads every class its
 * methods name. Holding the calls itself, this object made the registration on Android's main
 * thread load 42 more classes (the engine's, the store's, the specs') in about 1 ms of the
 * provider's start (the runtime-split plan's stage 7, on the emulator); [ApiRuleCalls] loads them
 * at the first `HopiRules` call instead.
 */
internal object ApiRules : HopiRulesRuntime {
    /**
     * Registers this runtime with `hopi-api`'s registry. Returns how many rules `HopiRules` dropped
     * before (each call returned `-1`), or `-1` when a rules runtime was registered already. Writes
     * nothing: the start sequence writes the notice.
     */
    fun register(): Int = if (HopiRuntimes.registerRules(this)) HopiRuntimes.droppedRules else -1

    override fun mock(match: String, rule: HopiMock): Long = ApiRuleCalls.mock(match, rule)

    override fun fail(match: String, failure: HopiFailure, rule: HopiRuleOptions): Long = ApiRuleCalls.fail(match, failure, rule)

    override fun delay(match: String, rule: HopiDelay): Long = ApiRuleCalls.delay(match, rule)

    override fun edit(match: String, rule: HopiEdit): Long = ApiRuleCalls.edit(match, rule)

    override fun list(): List<HopiRuleInfo> = ApiRuleCalls.list()

    override fun setEnabled(id: Long, enabled: Boolean): Boolean = ApiRuleCalls.setEnabled(id, enabled)

    override fun remove(id: Long): Boolean = ApiRuleCalls.remove(id)

    override fun clear() {
        ApiRuleCalls.clear()
    }
}

/**
 * What [ApiRules] forwards to: each call reads the builder its block filled and goes to
 * [RuleEngine], the engine the bridge uses too. Validation errors throw [IllegalArgumentException]
 * (the match, the rule, a `from` naming no record), a limit [IllegalStateException].
 */
internal object ApiRuleCalls {
    fun mock(match: String, rule: HopiMock): Long {
        val from = rule.fromTransactionId
        val body = rule.bodyBytes?.let { RuleBody(it) }
        val spec = if (from == null) {
            MockSpec(rule.status ?: 200, rule.headerList.toList(), body)
        } else {
            // Never creates the store: a record of a store that does not exist yet does not exist,
            // and creating it here would fix its caps before the start sequence preset them.
            val record = HopiStore.defaultOrNull?.get(from) ?: throw IllegalArgumentException("mock.fromTransaction: no transaction $from")
            when (val built = RuleJson.mockFromTransaction(record, rule.status, rule.headerList.takeIf { it.isNotEmpty() }?.toList(), body)) {
                is RuleParse.Failed -> throw exception(built.error)
                is RuleParse.Ok -> built.value
            }
        }
        return add(rule.spec(match, mock = spec))
    }

    fun fail(match: String, failure: HopiFailure, rule: HopiRuleOptions): Long = add(rule.spec(match, fail = failure.kind()))

    fun delay(match: String, rule: HopiDelay): Long {
        // A positive var replaces what delay() set; a negative one is ignored, and the rule then
        // fails validation for want of a delay.
        if (rule.beforeRequest.isPositive() || rule.beforeResponse.isPositive()) rule.delay(rule.beforeRequest, rule.beforeResponse)
        return add(rule.spec(match))
    }

    fun edit(match: String, rule: HopiEdit): Long =
        add(rule.spec(match, edit = EditSpec(rule.requestEdit?.toEdit(), rule.responseEdit?.toEdit())))

    fun list(): List<HopiRuleInfo> = RuleEngine.list().map(::info)

    fun setEnabled(id: Long, enabled: Boolean): Boolean = RuleEngine.update(id, RulePatch(enabled = enabled)) is RuleResult.Ok

    fun remove(id: Long): Boolean = RuleEngine.remove(id)

    fun clear() {
        RuleEngine.clear()
    }

    private fun add(spec: RuleSpec): Long = when (val result = RuleEngine.add(spec)) {
        is RuleResult.Ok -> result.rule.id
        is RuleResult.Failed -> throw exception(result.error)
    }

    private fun exception(error: RuleError): RuntimeException =
        if (error.code == RuleErrorCode.LIMIT) IllegalStateException(error.message) else IllegalArgumentException(error.message)

    private fun info(rule: RuleInfo): HopiRuleInfo = HopiRuleInfoFactory.create(
        id = rule.id,
        label = rule.spec.label,
        enabled = rule.spec.enabled,
        priority = rule.spec.priority,
        times = rule.spec.times,
        hits = rule.hits,
        exhausted = rule.exhausted,
        match = rule.spec.match.describe(),
        action = RuleJson.describeAction(rule.spec),
        unsupported = rule.unsupported.map { it.toString() },
    )

    private fun HopiRuleOptions.spec(
        match: String,
        mock: MockSpec? = null,
        fail: FailKind? = null,
        edit: EditSpec? = null,
    ): RuleSpec = RuleSpec(
        match = MatchShorthand.parse(match, ruleSources?.map { it.source() }?.toSet(), queryPredicates.map { it.predicate() }, headerPredicates.map { it.predicate() }),
        label = label,
        enabled = enabled,
        priority = priority,
        times = times,
        delay = delaySpec(),
        mock = mock,
        fail = fail,
        edit = edit,
    )

    /** Null until `delay()` was called, so a negative delay still reaches validation. */
    private fun HopiRuleOptions.delaySpec(): DelaySpec? {
        val beforeRequest = delayBeforeRequest
        val beforeResponse = delayBeforeResponse
        if (beforeRequest == null && beforeResponse == null) return null
        return DelaySpec((beforeRequest ?: Duration.ZERO).inWholeMilliseconds, (beforeResponse ?: Duration.ZERO).inWholeMilliseconds)
    }

    private fun HopiRequestEdit.toEdit(): RequestEdit = RequestEdit(
        method, host, port, path, querySets.toList(), queryRemovals.toList(), headerSets.toList(), headerAdds.toList(), headerRemovals.toList(), bodyBytes?.let { RuleBody(it) },
    )

    private fun HopiResponseEdit.toEdit(): ResponseEdit =
        ResponseEdit(status, headerSets.toList(), headerAdds.toList(), headerRemovals.toList(), bodyBytes?.let { RuleBody(it) })

    private fun HopiPredicate.predicate(): Predicate = Predicate(name, value, absent)

    private fun HopiFailure.kind(): FailKind = when (this) {
        HopiFailure.Timeout -> FailKind.TIMEOUT
        HopiFailure.Reset -> FailKind.RESET
        HopiFailure.UnknownHost -> FailKind.UNKNOWN_HOST
        HopiFailure.Cancel -> FailKind.CANCEL
    }

    private fun HopiSource.source(): RuleSource = when (this) {
        HopiSource.Ktor -> RuleSource.KTOR
        HopiSource.OkHttp -> RuleSource.OKHTTP
        HopiSource.UrlConnection -> RuleSource.URL_CONNECTION
        HopiSource.UrlSession -> RuleSource.URL_SESSION
    }
}
