package dev.koga.hopi.ui.rules

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
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

/** How the in-app list draws what a source cannot do of a rule. */
@OptIn(ExperimentalTestApi::class)
class RulesPaneUiTest {
    private fun info(id: Long, spec: RuleSpec) = RuleInfo(id, spec, hits = 0, exhausted = false, Capabilities.unsupported(spec))

    @Test
    fun anExclusionSaysNotAppliedAndARuleThatAppliesNowhereSaysInactive() = runComposeUiTest {
        val urlSessionOnly = RuleSpec(MatchShorthand.parse("GET */json", setOf(RuleSource.URL_SESSION)), edit = EditSpec(response = ResponseEdit(status = 503)))
        val redirect = RuleSpec(MatchShorthand.parse("*/old", setOf(RuleSource.OKHTTP)), mock = MockSpec(302))
        val rows = ruleRows(listOf(info(4, urlSessionOnly), info(5, redirect)), Redactor.Default)
        setContent { RulesPane(rows, rulesOff = false, onBack = {}, onToggle = { _, _ -> }, onRemove = {}, onRemoveAll = {}) }

        onNodeWithText("not applied on urlsession: response edits are unsupported on URLSession", substring = true).assertExists()
        onNodeWithText("edit response  ·  0 hits · inactive").assertExists()
        // The switch stays as the rule was set.
        onNodeWithContentDescription(RulesTags.toggle(4)).assertIsOn()
        onNodeWithText("⚠ okhttp: ${Capabilities.NOT_FOLLOWED}").assertExists()
        onNodeWithText("mock 302  ·  0 hits").assertExists()
    }
}
