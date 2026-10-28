package dev.koga.hopi.core.rules

import dev.koga.hopi.api.HopiInternalApi

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
        "a mocked 3xx, 401 or 407 reaches the client as the final response on this source: OkHttp, HttpURLConnection " +
            "and URLSession do not follow it; a client above them (Ktor's HttpRedirect, Auth) still does"

    /**
     * Every entry for [spec] on the sources its match names; when it names none, on the sources that
     * can record on this platform ([platformSources]), so an Android app is never told what URLSession
     * cannot do.
     */
    public fun unsupported(spec: RuleSpec): List<Unsupported> {
        val sources = sourcesOf(spec)
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

    /**
     * True when every source [unsupported] checks [spec] against (the named ones, or
     * [platformSources] when it names none) excludes it, whatever its switch says (a response edit
     * aimed at URLSession only). False does not mean a source applies it: a named source this
     * platform lacks excludes nothing yet never reads its array, and on iOS the Ktor plugin defers
     * to URLSession capture for the Darwin engine.
     */
    public fun appliesNowhere(spec: RuleSpec): Boolean = sourcesOf(spec).none { applies(it, spec) }

    private fun sourcesOf(spec: RuleSpec): Set<RuleSource> = spec.match.sources ?: platformSources
}

/**
 * The sources that can record on this platform: what a rule naming no sources is checked against
 * ([Capabilities.unsupported]). The rule arrays are not limited to them; a source this platform
 * lacks never reads its own.
 */
internal expect val platformSources: Set<RuleSource>
