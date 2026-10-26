package dev.koga.hopi.rules

import dev.koga.hopi.core.rules.MatchShorthand
import dev.koga.hopi.core.rules.MockSpec
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleError
import dev.koga.hopi.core.rules.RuleErrorCode
import dev.koga.hopi.core.rules.RuleInfo
import dev.koga.hopi.core.rules.RuleJson
import dev.koga.hopi.core.rules.RuleParse
import dev.koga.hopi.core.rules.RulePatch
import dev.koga.hopi.core.rules.RuleResult
import dev.koga.hopi.core.rules.RuleSpec
import dev.koga.hopi.core.store.HopiStore

/**
 * Rules for this process's HTTP traffic (docs/design/rules.md): a rule matches a request and
 * mocks, fails, delays or edits it, on every source Hopi records, each as far as it can without
 * weakening the app's security. For tests and debug menus; the CLI and the desktop viewer reach the
 * same rules over the bridge. Rules live in memory for this process only, are never persisted, and
 * apply from the moment a call returns. `match` is the shorthand `[METHODS ]URL-PATTERN`
 * (docs/design/rules.md, "Shorthand"): methods such as `GET` or `GET|POST`, then
 * `[scheme://]host[:port]path[?query]`, where `*` in a host matches any run of characters, `*` in a
 * path one segment and `**` any number of them.
 *
 * Validation errors throw [IllegalArgumentException] with the field named; reaching a limit (64
 * rules, 1 MiB per body, 8 MiB of bodies) throws [IllegalStateException]. Thread-safe. Tests call
 * [clear] in their teardown: the rules are process-wide, like the store.
 *
 * The noop twin (`hopi-rules-noop`) has this API and does nothing: every call returns `-1`, `false`
 * or an empty list.
 */
public object HopiRules {
    /** A rule that answers matching requests itself, without the network; returns its id. */
    public fun mock(match: String, block: HopiMock.() -> Unit = {}): Long {
        val mock = HopiMock().apply(block)
        val from = mock.fromTransaction
        val spec = if (from == null) {
            MockSpec(mock.status ?: 200, mock.headers.toList(), mock.body)
        } else {
            val record = HopiStore.default.get(from) ?: throw IllegalArgumentException("mock.fromTransaction: no transaction $from")
            when (val built = RuleJson.mockFromTransaction(record, mock.status, mock.headers.takeIf { it.isNotEmpty() }?.toList(), mock.body)) {
                is RuleParse.Failed -> throw exception(built.error)
                is RuleParse.Ok -> built.value
            }
        }
        return add(mock.spec(match, mock = spec))
    }

    /** A rule that fails matching requests the way their stack fails them; returns its id. */
    public fun fail(match: String, failure: HopiFailure, block: HopiRuleOptions.() -> Unit = {}): Long {
        val options = HopiRuleOptions().apply(block)
        return add(options.spec(match, fail = failure.kind))
    }

    /** A rule that only holds matching requests back; needs a non-zero delay. Returns its id. */
    public fun delay(match: String, block: HopiDelay.() -> Unit): Long {
        val delay = HopiDelay().apply(block)
        if (delay.beforeRequest.isPositive() || delay.beforeResponse.isPositive()) delay.delay(delay.beforeRequest, delay.beforeResponse)
        return add(delay.spec(match))
    }

    /** A rule that rewrites matching requests and/or their responses; returns its id. */
    public fun edit(match: String, block: HopiEdit.() -> Unit): Long {
        val edit = HopiEdit().apply(block)
        return add(edit.spec(match, edit = edit.editSpec()))
    }

    /** Every rule, in the order they were added. */
    public fun list(): List<HopiRuleInfo> = RuleEngine.list().map(::info)

    /** Enables or disables rule [id]; false when there is none. */
    public fun setEnabled(id: Long, enabled: Boolean): Boolean = RuleEngine.update(id, RulePatch(enabled = enabled)) is RuleResult.Ok

    /** Removes rule [id]; false when there is none. */
    public fun remove(id: Long): Boolean = RuleEngine.remove(id)

    /** Removes every rule. */
    public fun clear() {
        RuleEngine.clear()
    }

    private fun add(spec: RuleSpec): Long = when (val result = RuleEngine.add(spec)) {
        is RuleResult.Ok -> result.rule.id
        is RuleResult.Failed -> throw exception(result.error)
    }

    private fun exception(error: RuleError): RuntimeException =
        if (error.code == RuleErrorCode.LIMIT) IllegalStateException(error.message) else IllegalArgumentException(error.message)

    private fun info(rule: RuleInfo): HopiRuleInfo = HopiRuleInfo(
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
        fail: dev.koga.hopi.core.rules.FailKind? = null,
        edit: dev.koga.hopi.core.rules.EditSpec? = null,
    ): RuleSpec = RuleSpec(
        match = MatchShorthand.parse(match, sources?.map { it.source }?.toSet(), query, headerPredicates),
        label = label,
        enabled = enabled,
        priority = priority,
        times = times,
        delay = delaySpec,
        mock = mock,
        fail = fail,
        edit = edit,
    )
}
