package dev.koga.hopi.ui.rules

import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.rules.Capabilities
import dev.koga.hopi.core.rules.EditSpec
import dev.koga.hopi.core.rules.MatchShorthand
import dev.koga.hopi.core.rules.MockSpec
import dev.koga.hopi.core.rules.ResponseEdit
import dev.koga.hopi.core.rules.RuleInfo
import dev.koga.hopi.core.rules.RuleSource
import dev.koga.hopi.core.rules.RuleSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a rule row says of the sources that cannot apply the rule; every rule names its sources, so it holds on any platform. */
class RuleRowsTest {
    private val responseEdit = EditSpec(response = ResponseEdit(status = 503))

    private fun row(spec: RuleSpec): RuleRow = ruleRows(listOf(RuleInfo(1, spec, hits = 0, exhausted = false, Capabilities.unsupported(spec))), Redactor.Default).single()

    @Test
    fun anExclusionIsItsOwnLineAndAStatedDifferenceAWarning() {
        val edit = row(RuleSpec(MatchShorthand.parse("GET */json", setOf(RuleSource.KTOR, RuleSource.URL_SESSION)), edit = responseEdit))
        assertEquals(listOf("not applied on urlsession: ${Capabilities.URLSESSION_RESPONSE_EDIT}"), edit.exclusions)
        assertEquals(emptyList(), edit.warnings)
        // Ktor still applies it.
        assertFalse(edit.inactive)
        assertEquals("0 hits", edit.hits)

        val redirect = row(RuleSpec(MatchShorthand.parse("*/old", setOf(RuleSource.OKHTTP)), mock = MockSpec(302)))
        assertEquals(emptyList(), redirect.exclusions)
        assertEquals(listOf("okhttp: ${Capabilities.NOT_FOLLOWED}"), redirect.warnings)
        assertFalse(redirect.inactive)
    }

    @Test
    fun aRuleEverySourceItNamesExcludesIsInactiveWithItsSwitchAsSet() {
        val row = row(RuleSpec(MatchShorthand.parse("GET */json", setOf(RuleSource.URL_SESSION)), edit = responseEdit))
        assertTrue(row.inactive)
        assertEquals("0 hits · inactive", row.hits)
        assertTrue(row.enabled)
    }
}
