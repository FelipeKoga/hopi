package dev.koga.hopi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.koga.hopi.connect.RemoteRule
import dev.koga.hopi.connect.RemoteRules
import dev.koga.hopi.connect.RemoteSession
import dev.koga.hopi.connect.RemoteUnsupported
import dev.koga.hopi.core.body.formatBytes
import dev.koga.hopi.core.rules.FailKind
import dev.koga.hopi.ui.desktop.HopiDesktopColors
import dev.koga.hopi.ui.desktop.HopiDesktopType
import dev.koga.hopi.ui.desktop.hopiDesktopColors
import dev.koga.hopi.ui.theme.HopiTone
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

internal object RulesTags {
    const val PANEL = "rules-panel"
    const val MESSAGE = "rules-message"
    const val SAVE = "rules-save"
    const val MATCH = "rules-match"
    const val STATUS = "rules-status"
    const val BODY = "rules-body"
    const val METHOD = "rules-method"

    fun toggle(id: Long) = "Rule #$id enabled"

    fun header(table: String, row: Int, value: Boolean) = "rules-$table-$row-" + if (value) "value" else "name"
}

/** The rules panel's state, hoisted so ⌘L, the menu and the detail's buttons drive it. */
@Stable
internal class RulesPanelState {
    var open: Boolean by mutableStateOf(false)
    var form: RuleForm? by mutableStateOf(null)

    /** The last command's outcome (a refusal, "Rule #3 added"), shown under the header. */
    var message: String? by mutableStateOf(null)
    var busy: Boolean by mutableStateOf(false)

    fun toggle() {
        open = !open
    }

    /** Opens the panel on [form] (a new rule, "Mock this response", an update). */
    fun edit(form: RuleForm) {
        this.form = form
        message = null
        open = true
    }
}

/**
 * The rules panel beside the list (docs/design/rules.md, "Desktop viewer"): the app's rules as
 * [RemoteSession.rules] lists them (re-listed on each notice and on each ruled request the tail
 * brings, so the hits are the app's own), an enable switch, update and remove per row, clear, and
 * the add and update forms. Every command goes over the session's bridge to the app; nothing reads
 * this process's own engine, and what a source cannot do of a rule is what the app reported.
 * [chooseBodyFile] picks a body file (a native dialog in the app, a fixed file in tests).
 */
@Composable
internal fun RulesPanel(
    session: RemoteSession?,
    state: RulesPanelState,
    chooseBodyFile: () -> BodyFile?,
    modifier: Modifier = Modifier,
) {
    val colors = hopiDesktopColors
    val scope = rememberCoroutineScope()
    val rules = session?.rules?.collectAsState()?.value ?: RemoteRules.NONE

    fun <T> send(command: () -> CompletableFuture<T>, done: (T) -> String?) {
        state.busy = true
        scope.launchCommand(state, command, done)
    }

    Column(modifier.fillMaxHeight().testTag(RulesTags.PANEL)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText("Rules", style = HopiDesktopType.title.copy(color = colors.text))
            if (rules.rules.isNotEmpty()) BasicText(rules.rules.size.toString(), style = HopiDesktopType.meta.copy(color = colors.textFaint))
            Spacer(Modifier.weight(1f))
            // Destructive: it removes every rule in the app.
            ChromeButton(
                "Clear",
                enabled = rules.supported && rules.rules.isNotEmpty() && !state.busy && session != null,
                onClick = { session?.let { s -> send({ s.clearRules() }) { removed -> if (removed == 1L) "1 rule removed" else "$removed rules removed" } } },
                variant = ButtonVariant.Destructive,
            )
            ChromeButton("Close", onClick = { state.open = false }, variant = ButtonVariant.Ghost)
        }
        state.message?.let {
            // A callout in the outcome's hue (messageTone).
            val tone = messageTone(it, colors)
            BasicText(
                it,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(tone.bg)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag(RulesTags.MESSAGE),
                style = HopiDesktopType.meta.copy(color = tone.fg),
            )
        }
        when {
            session == null -> Note("Not connected.")
            !rules.supported -> Note("This app's Hopi does not support rules (not connected yet, an older Hopi, or rules = false).")
            else -> {
                val form = state.form
                if (form != null) {
                    FormView(form, state, chooseBodyFile) {
                        submit(form, session, state, scope)
                    }
                } else {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicText("Add", style = HopiDesktopType.section.copy(color = colors.textFaint), modifier = Modifier.align(Alignment.CenterVertically))
                        for (kind in listOf(FormKind.Mock, FormKind.Fail, FormKind.Delay, FormKind.Edit)) {
                            ChromeButton(kind.label, onClick = { state.edit(RuleForm(kind)) }, variant = ButtonVariant.Secondary)
                        }
                    }
                    if (rules.rules.isEmpty()) {
                        Note("No rules. Add one here, mock a response from a request's detail, or use the hopi CLI.")
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(rules.rules, key = { it.id }) { rule ->
                                RuleRow(
                                    rule,
                                    enabled = !state.busy,
                                    onToggle = { on -> send({ session.updateRule(rule.id, enabled = on) }) { null } },
                                    onUpdate = { state.edit(RuleForm.updateOf(rule)) },
                                    onRemove = { send({ session.removeRule(rule.id) }) { "Rule #${rule.id} removed" } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The message's hue: what this panel reports itself ("Rule #3 added", "… updated", "… removed",
 * "2 rules removed") is success, or warning when an added rule lists sources it does not fully
 * apply to (more lines follow); anything else is a refusal or an error.
 */
private fun messageTone(message: String, colors: HopiDesktopColors): HopiTone = when {
    !DONE.matches(message.substringBefore('\n')) -> colors.error
    '\n' in message -> colors.warning
    else -> colors.success
}

private val DONE = Regex("""Rule #\d+ (added|updated|removed)|\d+ rules? removed""")

/** Checks the form on this side first (nothing is uploaded for a form that cannot be sent), then sends it. */
private fun submit(form: RuleForm, session: RemoteSession, state: RulesPanelState, scope: CoroutineScope) {
    val dryRun = { _: ByteArray -> "{}" }
    try {
        when (form.kind) {
            FormKind.Update -> form.update()
            else -> form.json(dryRun)
        }
    } catch (e: IllegalArgumentException) {
        state.message = e.message
        return
    }
    when (form.kind) {
        FormKind.Update -> {
            val update = form.update()
            val id = form.ruleId ?: return
            // Only once a command is launched: its end is the one place busy is cleared.
            state.busy = true
            scope.launchCommand(
                state,
                { session.updateRule(id, priority = update.priority, times = update.times, clearTimes = update.times == null, resetHits = update.resetHits) },
            ) {
                state.form = null
                "Rule #$id updated"
            }
        }
        else -> {
            state.busy = true
            scope.launchCommand(state, { session.addRule { body -> form.json(body) } }) { rule ->
                state.form = null
                listOf("Rule #${rule.id} added").plus(rule.unsupported.map { if (it.applies) "warning: $it" else it.notApplied() }).joinToString("\n")
            }
        }
    }
}

/** An exclusion as the panel words it: the source never gets the rule. */
private fun RemoteUnsupported.notApplied(): String = "not applied on $source: $reason"

/** Runs [command] (a session future) and shows [done]'s text, or the app's refusal as it gave it. */
private fun <T> CoroutineScope.launchCommand(state: RulesPanelState, command: () -> CompletableFuture<T>, done: (T) -> String?) {
    launch {
        state.message = try {
            done(command().await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val cause = if (e is ExecutionException || e is CompletionException) e.cause ?: e else e
            cause.message ?: cause.toString()
        } finally {
            state.busy = false
        }
    }
}

@Composable
private fun RuleRow(rule: RemoteRule, enabled: Boolean, onToggle: (Boolean) -> Unit, onUpdate: () -> Unit, onRemove: () -> Unit) {
    val colors = hopiDesktopColors
    // M3 Expressive: each rule a tonal card with large corners.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.containerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChromeSwitch(rule.enabled, RulesTags.toggle(rule.id), onChange = { if (enabled) onToggle(it) })
            BasicText("#${rule.id}", style = HopiDesktopType.meta.copy(color = colors.textFaint))
            BasicText(
                rule.match,
                modifier = Modifier.weight(1f),
                style = HopiDesktopType.mono.copy(color = if (rule.enabled && !rule.inactive) colors.text else colors.textFaint),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ChromeButton("Update", onClick = onUpdate, enabled = enabled)
            ChromeButton("Remove", onClick = onRemove, enabled = enabled)
        }
        // The same text as `action  ·  hits  ·  label`: exhausted in the warning amber, inactive in red.
        val hits = buildAnnotatedString {
            append(rule.hits.toString())
            rule.times?.let { append("/$it") }
            append(if (rule.hits == 1L && rule.times == null) " hit" else " hits")
            if (rule.exhausted) withStyle(SpanStyle(color = colors.warning.fg)) { append(" · exhausted") }
            // Every source it names excludes it: it never applies, whatever its switch says.
            if (rule.inactive) withStyle(SpanStyle(color = colors.error.fg)) { append(" · inactive") }
            if (rule.priority != 0) append(" · priority ${rule.priority}")
        }
        val line = buildAnnotatedString {
            append(rule.action)
            append("  ·  ")
            append(hits)
            rule.label?.let {
                append("  ·  ")
                append(it)
            }
        }
        BasicText(line, modifier = Modifier.padding(start = 42.dp, top = 2.dp), style = HopiDesktopType.meta.copy(color = colors.textMuted))
        // A source that never gets the rule in the warning amber, first; a difference it applies with, muted.
        for (entry in rule.unsupported.sortedBy { it.applies }) {
            BasicText(
                if (entry.applies) "⚠ $entry" else entry.notApplied(),
                modifier = Modifier.padding(start = 42.dp),
                style = HopiDesktopType.meta.copy(color = if (entry.applies) colors.textMuted else colors.warning.fg),
            )
        }
    }
}

@Composable
private fun FormView(form: RuleForm, state: RulesPanelState, chooseBodyFile: () -> BodyFile?, onSave: () -> Unit) {
    val colors = hopiDesktopColors
    // The title and the buttons stay in view above the fields, which scroll.
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(form.title, modifier = Modifier.weight(1f), style = HopiDesktopType.bodyStrong.copy(color = colors.text))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChromeButton(
                when (form.kind) {
                    FormKind.Update -> "Update"
                    else -> "Add rule"
                },
                onClick = onSave,
                enabled = !state.busy,
                variant = ButtonVariant.Primary,
                modifier = Modifier.testTag(RulesTags.SAVE),
            )
            ChromeButton("Cancel", onClick = {
                state.form = null
                state.message = null
            })
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (form.kind) {
            FormKind.Update -> {
                Field("Priority", form.priority, "0")
                Field("Times", form.times, "no limit")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChromeSwitch(form.resetHits, "Reset hits", onChange = { form.resetHits = it })
                    BasicText("Reset hits", style = HopiDesktopType.label.copy(color = colors.text))
                }
            }
            else -> {
                Field("Match", form.match, "GET */users/*", Modifier.testTag(RulesTags.MATCH))
                if (form.kind == FormKind.Mock && form.fromTransaction != null) {
                    Hint("Status, headers and body are copied from #${form.fromTransaction} in the app; a field set here overrides them.")
                }
                when (form.kind) {
                    FormKind.Mock -> {
                        Field("Status", form.status, if (form.fromTransaction != null) "as recorded" else "200", Modifier.testTag(RulesTags.STATUS))
                        Headers("Headers", form.headers)
                        Body("Body", form.body, chooseBodyFile, Modifier.testTag(RulesTags.BODY))
                        Field("Delay before response (ms)", form.beforeResponseMs, "0")
                    }
                    FormKind.Fail -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (kind in FailKind.entries) ChromeChip(kind.wireName, selected = form.failKind == kind, onClick = { form.failKind = kind })
                        }
                        Field("After (ms)", form.beforeResponseMs, "0")
                    }
                    FormKind.Delay -> {
                        Field("Before request (ms)", form.beforeRequestMs, "0")
                        Field("Before response (ms)", form.beforeResponseMs, "0")
                    }
                    FormKind.Edit -> {
                        Section("Request")
                        RequestEdit(form.request, chooseBodyFile)
                        Section("Response")
                        Field("Status", form.status, "unchanged")
                        Headers("Set headers", form.headers)
                        Field("Remove headers", form.responseRemoveHeaders, "Name, Name")
                        Body("Body", form.body, chooseBodyFile)
                        Field("Delay before response (ms)", form.beforeResponseMs, "0")
                    }
                    else -> Unit
                }
                Section("Options")
                Field("Label", form.label, "optional")
                Field("Times", form.times, "no limit")
                Field("Priority", form.priority, "0")
                Field("Sources", form.sources, "all (or okhttp, ktor, urlconnection, urlsession)")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChromeSwitch(!form.disabled, "Rule enabled", onChange = { form.disabled = !it })
                    BasicText("Enabled", style = HopiDesktopType.label.copy(color = colors.text))
                }
            }
        }
    }
}

@Composable
private fun RequestEdit(fields: RequestEditFields, chooseBodyFile: () -> BodyFile?) {
    Field("Method", fields.method, "unchanged", Modifier.testTag(RulesTags.METHOD))
    Field("Host", fields.host, "unchanged")
    Field("Port", fields.port, "unchanged")
    Field("Path", fields.path, "unchanged")
    Headers("Set headers", fields.setHeaders)
    Field("Remove headers", fields.removeHeaders, "Name, Name")
    Body("Body", fields.body, chooseBodyFile)
}

@Composable
private fun Field(label: String, state: TextFieldState, placeholder: String, modifier: Modifier = Modifier) {
    val colors = hopiDesktopColors
    Column {
        BasicText(label, style = HopiDesktopType.meta.copy(color = colors.textFaint))
        ChromeTextField(state, placeholder, modifier.fillMaxWidth().padding(top = 2.dp))
    }
}

@Composable
private fun Headers(label: String, table: HeaderTable) {
    val colors = hopiDesktopColors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label, style = HopiDesktopType.meta.copy(color = colors.textFaint))
        for ((index, row) in table.rows.withIndex()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ChromeTextField(row.first, "Name", Modifier.weight(0.4f).testTag(RulesTags.header(label, index, value = false)))
                ChromeTextField(row.second, "value", Modifier.weight(0.6f).testTag(RulesTags.header(label, index, value = true)))
                ChromeButton("×", onClick = { table.rows.removeAt(index) }, variant = ButtonVariant.Ghost)
            }
        }
        ChromeButton("Add header", onClick = { table.add() })
    }
}

@Composable
private fun Body(label: String, body: BodyField, chooseBodyFile: () -> BodyFile?, modifier: Modifier = Modifier) {
    val colors = hopiDesktopColors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label, style = HopiDesktopType.meta.copy(color = colors.textFaint))
        val file = body.file
        if (file != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("${file.name} (${formatBytes(file.bytes.size.toLong())})", style = HopiDesktopType.mono.copy(color = colors.text))
                ChromeButton("Remove file", onClick = { body.file = null })
            }
        } else {
            ChromeTextField(body.text, "none", modifier.fillMaxWidth(), multiline = true)
            ChromeButton("Choose file…", onClick = { chooseBodyFile()?.let { body.file = it } })
        }
    }
}

@Composable
private fun Section(text: String) {
    BasicText(text.replaceFirstChar { it.uppercase() }, modifier = Modifier.padding(top = 6.dp), style = HopiDesktopType.section.copy(color = hopiDesktopColors.primary))
}

@Composable
private fun Hint(text: String) {
    BasicText(text, style = HopiDesktopType.meta.copy(color = hopiDesktopColors.textMuted))
}

@Composable
private fun Note(text: String) {
    BasicText(text, modifier = Modifier.padding(12.dp).width(360.dp), style = HopiDesktopType.meta.copy(color = hopiDesktopColors.textMuted))
}
