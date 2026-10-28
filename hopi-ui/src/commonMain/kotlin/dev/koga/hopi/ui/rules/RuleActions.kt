package dev.koga.hopi.ui.rules

import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.rules.MatchSpec
import dev.koga.hopi.core.rules.RuleJson
import dev.koga.hopi.core.rules.UrlParts

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

/** A record fetched in full must hold the bytes here; a host viewer's summary says what the app holds. */
private fun heldInFull(body: CapturedBody?): Boolean = when {
    body == null -> true
    body.isGone -> false
    body.isNotFetched -> body.heldBytes >= body.reportedBytes
    else -> !body.truncated && body.skippedReason == null
}
