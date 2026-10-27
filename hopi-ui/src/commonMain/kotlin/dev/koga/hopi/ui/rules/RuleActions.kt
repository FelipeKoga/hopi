package dev.koga.hopi.ui.rules

import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.rules.MatchSpec
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleJson
import dev.koga.hopi.core.rules.RuleParse
import dev.koga.hopi.core.rules.RuleResult
import dev.koga.hopi.core.rules.RuleSpec
import dev.koga.hopi.core.rules.UrlParts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One rule button of the detail: [run] when [refusal] is null, else disabled with [refusal] shown. */
internal class RuleAction(val run: () -> Unit, val refusal: String?)

/** The detail's rule buttons; a null one is not shown. */
internal class RuleActions(val mock: RuleAction?)

/**
 * The exact match of [transaction]'s request: its method, scheme, host, explicit port (if any) and
 * path, no query. Null for a URL the rule parser cannot read. A literal `*` in the path stays a
 * wildcard: globs have no escape.
 */
internal fun exactMatchOf(transaction: HttpTransaction): MatchSpec? {
    val parts = UrlParts.parse(transaction.url) ?: return null
    return MatchSpec(
        methods = setOf(transaction.method),
        scheme = parts.scheme,
        host = parts.host.lowercase(),
        port = parts.port,
        path = parts.path.ifEmpty { "/" },
    )
}

/** Why "Mock this response" cannot use [transaction], or null. Also right for a host viewer's summaries. */
internal fun mockRefusal(transaction: HttpTransaction): String? = when {
    transaction.state != TransactionState.Complete || transaction.responseCode == null -> "no response to mock"
    !heldInFull(transaction.responseBody) -> "response body not held in full"
    else -> RuleJson.encodedBodyRefusal(transaction)
}

/** In the app, [heldInFull] also needs the bytes here; a host viewer's summary says what the app holds. */
private fun heldInFull(body: CapturedBody?): Boolean = when {
    body == null -> true
    body.isGone -> false
    body.isNotFetched -> body.heldBytes >= body.reportedBytes
    else -> !body.truncated && body.skippedReason == null
}

/** What the in-app buttons did, for the toast. */
internal sealed interface InAppOutcome {
    class Mocked(val ruleId: Long) : InAppOutcome

    class Refused(val message: String) : InAppOutcome
}

/**
 * The in-app screen's rule buttons for [transaction], over the app's own [RuleEngine] (the
 * screen's process is the app's). Rules switched off in every recorder disable it. The mock is
 * built on [dispatcher], off the main thread (a body the record holds encoded is decoded for it),
 * and [onOutcome] is called back in [scope].
 */
internal fun inAppRuleActions(
    transaction: HttpTransaction,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    onOutcome: (InAppOutcome) -> Unit,
): RuleActions {
    val off = if (RuleEngine.rulesAvailable) null else "rules are off in this app (rules = false)"
    return RuleActions(
        mock = RuleAction(
            { scope.launch { onOutcome(withContext(dispatcher) { mockInApp(transaction) }) } },
            off ?: mockRefusal(transaction) ?: if (exactMatchOf(transaction) == null) "URL cannot be matched" else null,
        ),
    )
}

/** An enabled rule mocking [transaction]'s endpoint with its response; [InAppOutcome.Mocked] carries the id for Undo. */
internal fun mockInApp(transaction: HttpTransaction): InAppOutcome {
    val match = exactMatchOf(transaction) ?: return InAppOutcome.Refused("URL cannot be matched")
    val mock = when (val parsed = RuleJson.mockFromTransaction(transaction, status = null, headers = null, body = null)) {
        is RuleParse.Ok -> parsed.value
        // Without the bridge's wording: the field the desktop and the CLI send, and a body to give, which the phone has no editor for.
        is RuleParse.Failed -> return InAppOutcome.Refused(parsed.error.message.removePrefix("mock.fromTransaction: ").removeSuffix("; give a body"))
    }
    return when (val result = RuleEngine.add(RuleSpec(match, label = "from #${transaction.id}", mock = mock))) {
        is RuleResult.Ok -> InAppOutcome.Mocked(result.rule.id)
        is RuleResult.Failed -> InAppOutcome.Refused(result.error.message)
    }
}
