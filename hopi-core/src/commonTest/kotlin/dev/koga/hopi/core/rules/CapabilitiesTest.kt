package dev.koga.hopi.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What [Capabilities] reports for a rule, on whichever platform the tests run. */
class CapabilitiesTest {
    private val responseEdit = EditSpec(response = ResponseEdit(status = 503))

    @Test
    fun aRuleNamingNoSourcesIsCheckedAgainstThisPlatformsSourcesOnly() {
        // A mocked 302 is a stated difference on every source but Ktor.
        val redirect = RuleSpec(MatchShorthand.parse("*/old"), mock = MockSpec(302))
        assertEquals(platformSources - RuleSource.KTOR, Capabilities.unsupported(redirect).map { it.source }.toSet())
        val edit = Capabilities.unsupported(RuleSpec(MatchShorthand.parse("*/a"), edit = responseEdit))
        assertEquals(RuleSource.URL_SESSION in platformSources, edit.any { it.source == RuleSource.URL_SESSION && it.excluded })
        // Named sources are reported whatever the platform.
        val named = RuleSpec(MatchShorthand.parse("*/old", RuleSource.entries.toSet()), mock = MockSpec(302))
        assertEquals(setOf(RuleSource.OKHTTP, RuleSource.URL_CONNECTION, RuleSource.URL_SESSION), Capabilities.unsupported(named).map { it.source }.toSet())
    }

    @Test
    fun aRuleAppliesNowhereOnlyWhenEverySourceItIsCheckedAgainstExcludesIt() {
        assertTrue(Capabilities.appliesNowhere(RuleSpec(MatchShorthand.parse("*/a", setOf(RuleSource.URL_SESSION)), edit = responseEdit)))
        assertFalse(Capabilities.appliesNowhere(RuleSpec(MatchShorthand.parse("*/a", setOf(RuleSource.URL_SESSION, RuleSource.KTOR)), edit = responseEdit)))
        // Naming none: on Android and the JVM the Ktor plugin's recorder is a source and excludes
        // nothing, so it applies the rule; on iOS the plugin installs nothing and URLSession, the
        // only source, excludes a response edit, so the rule applies nowhere.
        val urlSessionOnly = platformSources == setOf(RuleSource.URL_SESSION)
        assertEquals(!urlSessionOnly, RuleSource.KTOR in platformSources)
        assertEquals(urlSessionOnly, Capabilities.appliesNowhere(RuleSpec(MatchShorthand.parse("*/a"), edit = responseEdit)))
        // A stated difference is not an exclusion.
        assertFalse(Capabilities.appliesNowhere(RuleSpec(MatchShorthand.parse("*/old", setOf(RuleSource.URL_SESSION)), mock = MockSpec(302))))
    }
}
