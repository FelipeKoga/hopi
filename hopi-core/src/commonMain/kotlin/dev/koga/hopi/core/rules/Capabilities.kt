package dev.koga.hopi.core.rules

import dev.koga.hopi.core.HopiInternalApi

/**
 * The capability table of docs/design/rules.md ("Capability by source") as data: for each source a
 * rule names, what that source cannot do of it. An [Unsupported.excluded] entry keeps the rule
 * out of that source's array, so a rule never matches where it cannot be applied and no source
 * checks capabilities per request; the others are stated differences, and the rule applies.
 */
@HopiInternalApi
public object Capabilities {
    public const val URLSESSION_RESPONSE_EDIT: String =
        "response edits are unsupported on URLSession: the response reaches the app through CFNetwork's own connection, " +
            "and rewriting it would mean loading the request outside the app's session (its pinning and challenge delegate)"
    public const val URLSESSION_DELAY_BEFORE_RESPONSE: String =
        "a delay before the response of a real request is unsupported on URLSession; it applies with a mock or fail only"
    public const val URLSESSION_BODY_EDIT: String =
        "body edits apply to HTTPBody and upload-from-data tasks only on URLSession; file and stream uploads keep their body " +
            "(the rule's other edits still apply)"
    public const val NOT_FOLLOWED: String =
        "a mocked 3xx, 401 or 407 reaches the app as the final response on this source; the client does not follow it"

    /** Every entry for [spec] on the sources its match names (all four when it names none). */
    public fun unsupported(spec: RuleSpec): List<Unsupported> {
        val sources = spec.match.sources ?: RuleSource.entries.toSet()
        val result = ArrayList<Unsupported>()
        for (source in RuleSource.entries) {
            if (source !in sources) continue
            result += unsupportedOn(source, spec)
        }
        return result
    }

    public fun unsupportedOn(source: RuleSource, spec: RuleSpec): List<Unsupported> {
        val result = ArrayList<Unsupported>(1)
        val response = spec.edit?.response?.takeIf { !it.isEmpty }
        val request = spec.edit?.request?.takeIf { !it.isEmpty }
        if (source == RuleSource.URL_SESSION) {
            if (response != null) result += Unsupported(source, URLSESSION_RESPONSE_EDIT, excluded = true)
            val delay = spec.delay
            if (delay != null && delay.beforeResponseMs > 0 && spec.mock == null && spec.fail == null) {
                result += Unsupported(source, URLSESSION_DELAY_BEFORE_RESPONSE, excluded = true)
            }
            if (request?.body != null) result += Unsupported(source, URLSESSION_BODY_EDIT, excluded = false)
        }
        if (source != RuleSource.KTOR) {
            val status = spec.mock?.status
            if (status != null && (status in 300..399 || status == 401 || status == 407)) {
                result += Unsupported(source, NOT_FOLLOWED, excluded = false)
            }
        }
        return result
    }

    /** Whether [source] gets [spec] in its array. */
    public fun applies(source: RuleSource, spec: RuleSpec): Boolean {
        val sources = spec.match.sources
        if (sources != null && source !in sources) return false
        return unsupportedOn(source, spec).none { it.excluded }
    }
}
