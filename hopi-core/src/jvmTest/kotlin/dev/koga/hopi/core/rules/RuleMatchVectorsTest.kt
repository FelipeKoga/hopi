package dev.koga.hopi.core.rules

import dev.koga.hopi.core.json.parseJsonTree
import dev.koga.hopi.core.model.HttpTransaction
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The shared match vectors (`commonTest/resources/rules/match-vectors.json`) against the Kotlin
 * matcher, through the wire parser, plus the glob against a brute-force reference.
 */
class RuleMatchVectorsTest {
    private val noContext = object : RuleJsonContext {
        override fun blob(id: Long): ByteArray? = null

        override fun transaction(id: Long): HttpTransaction? = null
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun everyVectorMatchesAsExpected() {
        val text = checkNotNull(javaClass.getResource("/rules/match-vectors.json")) { "missing vectors" }.readText()
        val root = checkNotNull(parseJsonTree(text, Int.MAX_VALUE, 8)) { "vectors are not JSON" }
        val cases = root["cases"] as List<Map<String, Any?>>
        assertTrue(cases.size >= 50, "the table shrank")
        for ((index, case) in cases.withIndex()) {
            val rule = mapOf("match" to case["match"], "delay" to mapOf("beforeRequestMs" to 1L))
            val spec = assertIs<RuleParse.Ok<RuleSpec>>(RuleJson.parseRule(rule, noContext), "case $index").value
            RuleValidation.validate(spec)?.let { fail("case $index does not validate: $it") }
            val headers = (case["headers"] as List<List<String>>?).orEmpty().map { it[0] to it[1] }
            val request = UrlRuleRequest(case["method"] as String, case["url"] as String, headers)
            val compiled = CompiledRule(1, spec, RuleHits())
            assertEquals(case["expect"], compiled.matches(request), "case $index: $case")
        }
    }

    @Test
    fun globAgreesWithABruteForceReference() {
        val random = Random(20261025)
        val alphabet = "ab/."
        repeat(20_000) {
            val pattern = buildString { repeat(random.nextInt(1, 9)) { append((alphabet + "**").random(random)) } }
            val input = buildString { repeat(random.nextInt(0, 12)) { append(alphabet.random(random)) } }
            for (mode in listOf(Glob.Mode.PATH, Glob.Mode.TEXT)) {
                val expected = reference(pattern, input, mode)
                assertEquals(expected, Glob.compile(pattern, mode).matches(input), "'$pattern' ~ '$input' ($mode)")
            }
        }
    }

    @Test
    fun hostGlobsCompareCaseInsensitively() {
        val glob = Glob.compile("*.Example.COM", Glob.Mode.HOST)
        assertTrue(glob.matches("API.example.com"))
        assertEquals(".example.com", glob.literalSuffix)
    }

    @Test
    fun longPatternsUseTheLargeSimulationAndAgree() {
        val pattern = "/" + "a*".repeat(70) + "z"
        val glob = Glob.compile(pattern, Glob.Mode.PATH)
        assertTrue(glob.matches("/" + "a".repeat(70) + "z"))
        assertTrue(glob.matches("/" + "ab".repeat(70) + "z"))
        assertEquals(false, glob.matches("/" + "a".repeat(69) + "z"))
        assertEquals(false, glob.matches("/" + "a".repeat(70) + "/z"))
    }

    @Test
    fun pathologicalPatternsStayLinear() {
        // Classic backtracking blow-ups: many stars against a long input that almost matches.
        // Each ends in a star, so the literal suffix check cannot reject the input up front.
        val patterns = listOf("*a*a*a*a*a*a*a*a*a*a*a*a*b*", "**a**a**a**a**a**a**a**a**b**", "/" + "*a".repeat(60) + "*b*", "/" + "**a".repeat(80) + "**b**")
        val input = "/" + "a".repeat(8_000)
        for (pattern in patterns) {
            val glob = Glob.compile(pattern, Glob.Mode.PATH)
            val started = System.nanoTime()
            repeat(20) { assertEquals(false, glob.matches(input)) }
            val perMatchMs = (System.nanoTime() - started) / 20 / 1e6
            assertTrue(perMatchMs < 50, "'$pattern' took $perMatchMs ms per match")
        }
    }

    /** Pattern semantics by exhaustive recursion: `**` any run, `*` (path) any run without `/`. */
    private fun reference(pattern: String, input: String, mode: Glob.Mode): Boolean {
        fun go(p: Int, i: Int): Boolean {
            if (p == pattern.length) return i == input.length
            if (pattern[p] == '*') {
                var stars = 1
                while (p + stars < pattern.length && pattern[p + stars] == '*') stars++
                val crossesSlash = mode != Glob.Mode.PATH || stars > 1
                var k = i
                while (true) {
                    if (go(p + stars, k)) return true
                    if (k == input.length || (!crossesSlash && input[k] == '/')) return false
                    k++
                }
            }
            return i < input.length && input[i] == pattern[p] && go(p + 1, i + 1)
        }
        return go(0, 0)
    }
}
