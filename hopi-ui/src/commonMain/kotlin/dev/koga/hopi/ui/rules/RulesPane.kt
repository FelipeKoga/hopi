package dev.koga.hopi.ui.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.rules.Capabilities
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleInfo
import dev.koga.hopi.core.rules.RuleJson
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ui.component.ActionButton
import dev.koga.hopi.ui.component.HairlineDivider
import dev.koga.hopi.ui.component.IconButton
import dev.koga.hopi.ui.component.Switch
import dev.koga.hopi.ui.list.EmptyState
import dev.koga.hopi.ui.theme.HopiColors
import dev.koga.hopi.ui.theme.HopiIcons
import dev.koga.hopi.ui.theme.HopiType
import dev.koga.hopi.ui.theme.LocalHopiColors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn

internal object RulesTags {
    const val BACK = "Back to requests"
    const val REMOVE_ALL = "Remove all rules"

    fun toggle(id: Long) = "Rule #$id enabled"

    fun remove(id: Long) = "Remove rule #$id"
}

/** One rule as the in-app list shows it; every string built off the main thread. */
internal class RuleRow(
    val id: Long,
    val match: String,
    val action: String,
    /** `3 hits`, `1/1 hits · exhausted`, `0 hits · inactive`. */
    val hits: String,
    val label: String?,
    val enabled: Boolean,
    /**
     * Every source the rule is checked against (the named ones, or the platform's when it names
     * none) excludes it ([Capabilities.appliesNowhere]), whatever its switch says.
     */
    val inactive: Boolean,
    /** `not applied on urlsession: …` for each source that never gets the rule. */
    val exclusions: List<String>,
    /** `okhttp: …` for each source that applies the rule with a stated difference. */
    val warnings: List<String>,
)

internal fun ruleRows(rules: List<RuleInfo>, redactor: Redactor): List<RuleRow> = rules.map { rule ->
    val times = rule.spec.times
    val inactive = Capabilities.appliesNowhere(rule.spec)
    RuleRow(
        id = rule.id,
        match = RuleJson.describeMatch(rule.spec.match, redactor),
        action = RuleJson.describeAction(rule.spec).ifEmpty { "?" },
        hits = buildString {
            append(rule.hits)
            times?.let { append('/').append(it) }
            append(if (rule.hits == 1L && times == null) " hit" else " hits")
            if (rule.exhausted) append(" · exhausted")
            if (inactive) append(" · inactive")
        },
        label = rule.spec.label,
        enabled = rule.spec.enabled,
        inactive = inactive,
        exclusions = rule.unsupported.filter { it.excluded }.map { "not applied on ${it.source.wireName}: ${it.reason}" },
        warnings = rule.unsupported.filterNot { it.excluded }.map { it.toString() },
    )
}

/**
 * The app's rules, read from [RuleEngine] only while the caller is composed: again on each
 * revision (a change of the set), on each store change (a ruled request counts a hit, which
 * publishes nothing) and on each redactor the store grows to (a match value it now redacts).
 */
@Composable
internal fun rememberRuleRows(store: HopiStore, formatDispatcher: CoroutineDispatcher): State<List<RuleRow>?> =
    produceState<List<RuleRow>?>(null, store, formatDispatcher) {
        combine(RuleEngine.revision, store.transactions, store.redactors) { _, _, _ -> ruleRows(RuleEngine.list(), store.redactor) }
            .conflate()
            .flowOn(formatDispatcher)
            .collect { value = it }
    }

/**
 * The in-app rules list: match, action and hits per rule, an enable switch and remove per row,
 * "Remove all". No editor: rules are written with the CLI, the desktop viewer or `HopiRules`.
 */
@Composable
internal fun RulesPane(
    rows: List<RuleRow>?,
    rulesOff: Boolean,
    onBack: () -> Unit,
    onToggle: (Long, Boolean) -> Unit,
    onRemove: (Long) -> Unit,
    onRemoveAll: () -> Unit,
) {
    val colors = LocalHopiColors.current
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(HopiIcons.Back, RulesTags.BACK, onBack)
            Spacer(Modifier.width(4.dp))
            BasicText("Rules", style = HopiType.title.copy(color = colors.text))
            if (!rows.isNullOrEmpty()) {
                Spacer(Modifier.width(8.dp))
                BasicText(rows.size.toString(), style = HopiType.meta.copy(color = colors.textFaint))
            }
            Spacer(Modifier.weight(1f))
            ActionButton(HopiIcons.Trash, RulesTags.REMOVE_ALL, onRemoveAll, enabled = !rows.isNullOrEmpty())
        }
        if (rulesOff) {
            BasicText(
                "Rules are off in this app (rules = false): they are listed, but no request is ruled.",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                style = HopiType.meta.copy(color = colors.textMuted),
            )
        }
        HairlineDivider()
        when {
            rows == null -> Unit
            rows.isEmpty() -> EmptyState(
                "No rules",
                "Mock a response from a request's detail, or add rules with the hopi CLI, the desktop viewer or HopiRules.",
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.id }, contentType = { "rule" }) { row -> RuleRowItem(row, colors, onToggle, onRemove) }
            }
        }
    }
}

@Composable
private fun RuleRowItem(row: RuleRow, colors: HopiColors, onToggle: (Long, Boolean) -> Unit, onRemove: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    "#${row.id}  ${row.match}",
                    style = HopiType.monoStrong.copy(color = if (row.enabled && !row.inactive) colors.text else colors.textFaint),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                BasicText(
                    listOfNotNull(row.action, row.hits, row.label).joinToString("  ·  "),
                    style = HopiType.meta.copy(color = colors.textMuted),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // A source that never gets the rule in full contrast; a difference it applies with, muted.
                for (exclusion in row.exclusions) {
                    BasicText(exclusion, style = HopiType.meta.copy(color = colors.text))
                }
                for (warning in row.warnings) {
                    BasicText("⚠ $warning", style = HopiType.meta.copy(color = colors.textMuted))
                }
            }
            Switch(row.enabled, RulesTags.toggle(row.id), onChange = { onToggle(row.id, it) })
            IconButton(HopiIcons.Trash, RulesTags.remove(row.id), onClick = { onRemove(row.id) })
        }
        HairlineDivider(Modifier.padding(start = 16.dp))
    }
}
