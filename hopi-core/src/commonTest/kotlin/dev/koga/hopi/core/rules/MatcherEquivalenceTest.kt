package dev.koga.hopi.core.rules

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CompiledRule.matches] (predicates asked in place, [RuleRequest.hasQueryValue] and
 * [RuleRequest.hasHeaderValue]) agrees with the reference matcher, the list-based one it replaced
 * (2026-10-25), on a broad generated set of rules and requests: methods, schemes, IPv6 hosts,
 * ports, encoded paths, repeated and encoded query names, case-varied headers, and absent, present
 * and glob predicates. Glob itself is shared, so the Kotlin and C glob parity stays as it was.
 */
class MatcherEquivalenceTest {
    /** The matcher before 2026-10-25: each predicate over the list of values. */
    private fun referenceMatches(match: MatchSpec, request: RuleRequest): Boolean {
        val methods = match.methods
        if (!methods.isNullOrEmpty() && request.method !in methods) return false
        if (match.scheme != null && match.scheme != request.scheme) return false
        if (match.port != null && match.port != request.port) return false
        val host = match.host?.takeIf { it != "*" }
        if (host != null && !Glob.compile(host, Glob.Mode.HOST).matches(request.host)) return false
        val path = match.path?.takeIf { it != "**" && it != "/**" }
        if (path != null && !Glob.compile(path, Glob.Mode.PATH).matches(request.encodedPath)) return false
        for (p in match.query) if (!holds(p, request.queryValues(p.name))) return false
        for (p in match.headers) if (!holds(p, request.headerValues(p.name))) return false
        return true
    }

    private fun holds(predicate: Predicate, values: List<String>): Boolean {
        val glob = predicate.value?.takeIf { it != "*" }?.let { Glob.compile(it, Glob.Mode.TEXT) }
        return when {
            predicate.absent -> values.isEmpty()
            predicate.value == null || predicate.value == "*" -> values.isNotEmpty()
            else -> values.any { glob?.matches(it) == true }
        }
    }

    /** Answers the predicates from the lists alone: the interface's default `has…` functions. */
    private class ListOnly(private val inner: RuleRequest) : RuleRequest {
        override val method get() = inner.method
        override val scheme get() = inner.scheme
        override val host get() = inner.host
        override val port get() = inner.port
        override val encodedPath get() = inner.encodedPath

        override fun queryValues(name: String) = inner.queryValues(name)

        override fun headerValues(name: String) = inner.headerValues(name)
    }

    /** A view that answers the predicates in place, by its own scan, as the Android sources do. */
    private class InPlace(method: String, url: String, private val headers: List<Pair<String, String>>) : RuleRequest {
        private val inner = UrlRuleRequest(method, url, headers)
        private val query = UrlParts.parse(url)?.decodedQuery().orEmpty()
        override val method get() = inner.method
        override val scheme get() = inner.scheme
        override val host get() = inner.host
        override val port get() = inner.port
        override val encodedPath get() = inner.encodedPath

        override fun queryValues(name: String) = inner.queryValues(name)

        override fun headerValues(name: String) = inner.headerValues(name)

        override fun hasQueryValue(name: String, value: Glob?): Boolean {
            for ((key, text) in query) if (key == name && (value == null || value.matches(text))) return true
            return false
        }

        override fun hasHeaderValue(name: String, value: Glob?): Boolean {
            for ((key, text) in headers) if (key.equals(name, ignoreCase = true) && (value == null || value.matches(text))) return true
            return false
        }
    }

    private val methods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "PROPFIND")
    private val hosts = listOf("api.example.com", "API.Example.COM", "staging.example.org", "example.org", "10.0.2.2", "::1", "localhost")
    private val paths = listOf("", "/", "/json", "/users/1", "/users/1/posts", "/a%20b/c", "/never/x/y", "/login", "/items/9", "/slow")
    private val queries = listOf(null, "", "page=2", "page=2&page=3", "x=%31&x=2&y&z=a+b", "only", "a=1&b=", "%70age=2", "page=%32")
    private val headerNames = listOf("X-Env", "x-env", "Accept", "Authorization", "Content-Type", "X-Debug")
    private val headerValues = listOf("staging", "stag", "prod", "*/*", "Bearer t", "", "application/json")

    private fun randomRequest(random: Random): Triple<String, String, List<Pair<String, String>>> {
        val scheme = if (random.nextBoolean()) "https" else "http"
        val host = hosts.random(random).let { if (it.contains(':')) "[$it]" else it }
        val port = listOf("", ":80", ":443", ":8080").random(random)
        val query = queries.random(random)?.let { "?$it" }.orEmpty()
        val url = "$scheme://$host$port${paths.random(random)}$query"
        val headers = List(random.nextInt(4)) { headerNames.random(random) to headerValues.random(random) }
        return Triple(methods.random(random), url, headers)
    }

    private fun randomPredicate(random: Random, names: List<String>): Predicate = when (random.nextInt(4)) {
        0 -> Predicate(names.random(random), null, absent = true)
        1 -> Predicate(names.random(random), null)
        2 -> Predicate(names.random(random), "*")
        else -> Predicate(names.random(random), listOf("2", "stag*", "*json", "prod", "", "a b", "1", "*").random(random))
    }

    /** Each field is often left open, so that enough requests match for the predicates to decide. */
    private fun <T> Random.orNull(chance: Int, value: () -> T): T? = if (nextInt(chance) == 0) value() else null

    private fun randomMatch(random: Random): MatchSpec = MatchSpec(
        methods = random.orNull(2) { List(1 + random.nextInt(3)) { methods.random(random) }.toSet() },
        scheme = random.orNull(3) { listOf("http", "https").random(random) },
        host = random.orNull(2) { listOf("*", "*.example.com", "api.example.com", "*.example.org", "staging.*", "::1", "10.0.2.2", "API.*").random(random) },
        port = random.orNull(4) { listOf(80, 443, 8080).random(random) },
        path = random.orNull(2) { listOf("/**", "**", "/json", "*/json", "/users/*", "/users/**", "/a%20b/*", "/never/**", "/").random(random) },
        query = List(random.nextInt(3)) { randomPredicate(random, listOf("page", "x", "y", "z", "only", "a", "b", "missing")) },
        headers = List(random.nextInt(3)) { randomPredicate(random, headerNames + "Missing") },
    )

    @Test
    fun theCompiledMatcherAgreesWithTheReferenceOnGeneratedCases() {
        val random = Random(20261025)
        var matched = 0
        var cases = 0
        repeat(600) { r ->
            val match = randomMatch(random)
            val rule = CompiledRule(r.toLong(), RuleSpec(match), RuleHits())
            repeat(60) {
                val (method, url, headers) = randomRequest(random)
                val view = UrlRuleRequest(method, url, headers)
                val expected = referenceMatches(match, view)
                val context = "${match.describe()} vs $method $url $headers"
                assertEquals(expected, rule.matches(view), "default: $context")
                assertEquals(expected, rule.matches(ListOnly(view)), "lists: $context")
                assertEquals(expected, rule.matches(InPlace(method, url, headers)), "in place: $context")
                if (expected) matched++
                cases++
            }
        }
        // The set must exercise both outcomes broadly, or it proves nothing.
        assertTrue(matched > cases / 50 && matched < cases - cases / 50, "matched $matched of $cases")
    }

    @Test
    fun predicateQueriesAgreeWithTheValueLists() {
        val random = Random(7)
        val globs = listOf(null, "2", "stag*", "*json", "", "a b", "1", "*").map { it?.let { g -> Glob.compile(g, Glob.Mode.TEXT) } }
        repeat(2000) {
            val (method, url, headers) = randomRequest(random)
            val view = UrlRuleRequest(method, url, headers)
            val inPlace = InPlace(method, url, headers)
            for (glob in globs) {
                for (name in listOf("page", "x", "y", "z", "only", "missing")) {
                    val expected = view.queryValues(name).let { v -> if (glob == null) v.isNotEmpty() else v.any { glob.matches(it) } }
                    assertEquals(expected, view.hasQueryValue(name, glob), "$url ?$name $glob")
                    assertEquals(expected, inPlace.hasQueryValue(name, glob), "$url ?$name $glob in place")
                }
                for (name in headerNames + "Missing") {
                    val expected = view.headerValues(name).let { v -> if (glob == null) v.isNotEmpty() else v.any { glob.matches(it) } }
                    assertEquals(expected, view.hasHeaderValue(name, glob), "$headers $name $glob")
                    assertEquals(expected, inPlace.hasHeaderValue(name, glob), "$headers $name $glob in place")
                }
            }
        }
    }
}
