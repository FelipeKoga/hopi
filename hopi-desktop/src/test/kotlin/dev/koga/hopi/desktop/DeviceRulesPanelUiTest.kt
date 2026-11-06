package dev.koga.hopi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.koga.hopi.connect.HostEnvironment
import dev.koga.hopi.connect.RemoteRule
import dev.koga.hopi.connect.RemoteSession
import dev.koga.hopi.connect.Targets
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.ui.desktop.HopiPanesState
import dev.koga.hopi.ui.desktop.rememberHopiPanesState
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The rules panel against a real app, end to end: the sample's real bridge and rule engine, the
 * window's real content ([ViewerContent] with the panel, at the window's default 1280 x 820)
 * composed offscreen and driven through its semantics. Skipped unless `-Phopi.desktop.device=`
 * `android` (one emulator, the sample's debug build installed) or `ios` (one booted simulator, the
 * sample's Debug app launched with `simctl launch --console-pty` writing to `<evidence>/ios-app.log`
 * and left on its Diagnostics tab, where `agent-device press` reaches it); both need
 * `sample/devserver/devserver.py` writing its stdout to `<evidence>/devserver.ndjson`. Run it alone
 * with `--tests '*DeviceRulesPanelUiTest'`.
 *
 * Every rule is made in the window, and each step is judged on three oracles: what the app saw (the
 * sample's `rulesTargets` lines: per source GET /json, GET /missing and POST /echo, each tagged
 * `rv=host-<source>`), what the dev server saw (its NDJSON log), and what the window shows (the
 * panel's rows, hits and warnings, the records' rule marks and tags). Checks go to
 * `<evidence>/<platform>-rules-checks.jsonl`, the window's content to `<evidence>/<platform>-rules-*.png`.
 */
@OptIn(ExperimentalTestApi::class)
class DeviceRulesPanelUiTest {
    private val device = System.getProperty("hopi.desktop.device")
    private val evidence: Path = Paths.get(System.getProperty("hopi.desktop.evidence") ?: "build/evidence/desktop")
    private val adb = Paths.get(System.getProperty("user.home"), "Library/Android/sdk/platform-tools/adb").toString()
    private val serverLog: Path get() = evidence.resolve("devserver.ndjson")
    private val failures = mutableListOf<String>()
    private var viewer: Viewer? = null
    private lateinit var panes: HopiPanesState
    private lateinit var plan: Plan

    /**
     * What differs between the platforms: the sources ([targets]), the source each single-source
     * rule is aimed at, and what URLSession cannot do (a delay before the response of a real
     * request, a response edit: docs/design/rules.md, "URLSession").
     */
    private class Plan(
        val platform: String,
        val targets: List<String>,
        val delayTarget: String,
        /** The delayed GET's path and the status it answers; never the edit rule's request, which would win it. */
        val delayPath: String,
        val delayStatus: String,
        val delayField: String,
        val failTarget: String,
        val failError: String,
        val editTarget: String,
        /**
         * Whether the edit rule also sets `X-Hopi-Edited: desktop` on the response. Where response
         * edits are unsupported (URLSession) such a rule never applies, so the edit is request-only
         * there and a separate response edit checks the panel's "not applied" line.
         */
        val editResponse: Boolean,
        val editApplied: String,
        val fire: (step: String) -> Map<Pair<String, String>, JsonObject>,
    ) {
        val requests: Int get() = targets.size * 3
    }

    @AfterTest
    fun closeViewer() {
        viewer?.close()
    }

    @Test
    fun androidRulesPanelEndToEnd() {
        if (device != "android") return
        run(adb, "reverse", "tcp:8765", "tcp:8765")
        run(adb, "shell", "am", "force-stop", PKG)
        run(adb, "shell", "am", "start", "-W", "-n", "$PKG/.MainActivity")
        endToEnd(
            Plan(
                platform = "android",
                targets = listOf("ktor", "ktor-okhttp", "sdk-okhttp", "sdk-urlconnection"),
                delayTarget = "sdk-urlconnection",
                delayPath = "/missing",
                delayStatus = "404",
                delayField = "Before response (ms)",
                failTarget = "sdk-okhttp",
                failError = "SocketTimeoutException",
                editTarget = "ktor",
                editResponse = true,
                editApplied = "edit-request,edit-response",
                fire = ::fireAndroid,
            ),
        )
    }

    @Test
    fun iosRulesPanelEndToEnd() {
        if (device != "ios") return
        endToEnd(
            Plan(
                platform = "ios",
                targets = listOf("ktor-darwin"),
                delayTarget = "ktor-darwin",
                delayPath = "/json",
                delayStatus = "200",
                delayField = "Before request (ms)",
                failTarget = "ktor-darwin",
                // Ktor Darwin reports NSURLErrorTimedOut (-1001) as its own SocketTimeoutException.
                failError = "SocketTimeoutException",
                editTarget = "ktor-darwin",
                editResponse = false,
                editApplied = "edit-request",
                fire = ::fireIos,
            ),
        )
    }

    private fun endToEnd(plan: Plan) {
        this.plan = plan
        Files.createDirectories(evidence)
        Files.deleteIfExists(out())
        runDesktopComposeUiTest(width = 1280, height = 820, testTimeout = 30.minutes) {
            try {
                flow()
            } catch (e: Throwable) {
                runCatching { screenshot("failure.png") }
                throw e
            }
        }
        assertEquals(emptyList(), failures)
    }

    private fun out(): Path = evidence.resolve("${plan.platform}-rules-checks.jsonl")

    private fun ComposeUiTest.flow() {
        val viewer = Viewer(Targets(HostEnvironment())).also { this@DeviceRulesPanelUiTest.viewer = it }
        viewer.start()
        lateinit var panel: RulesPanelState
        setContent {
            panel = remember { RulesPanelState() }
            panes = rememberHopiPanesState()
            Box(Modifier.fillMaxSize()) {
                ViewerContent(viewer, chooseExportFile = { _, _ -> evidence.resolve("unused.har") }, panes = panes, rules = panel, chooseBodyFile = { null })
            }
        }
        waitFor(if (plan.platform == "ios") "iOS" else "Android", 30_000)
        if (onAllNodes(hasText(PKG)).fetchSemanticsNodes().isEmpty()) onNodeWithTag(ViewerTags.APP_FIELD).performTextInput(PKG)
        onNodeWithTag(ViewerTags.CONNECT).performClick()
        waitFor("Connected to $PKG (${plan.platform})", 30_000)
        val session = viewer.session.value!!
        val all = plan.targets
        val n = plan.targets.size

        // List on connect: a fresh process has none.
        onNodeWithTag(ViewerTags.ROOT).performKeyInput { withKeyDown(if (IS_MAC) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.L) } }
        waitFor("No rules.")
        record("panel lists on connect", session.rules.value.supported && session.rules.value.rules.isEmpty(), "supported=${session.rules.value.supported} rules=${session.rules.value.rules.size}")
        screenshot("01-empty.png")

        val base = fire(session, "baseline")
        record(
            "baseline: every request reaches the server",
            base.app.size == plan.requests && base.server.size == plan.requests && base.app.values.none { it.str("error") != null },
            "app=${base.app.size} server=${base.server.size} statuses=${statuses(base)}",
        )
        newestRowInView(base)
        val first = all.last()
        val baseJson = base.app.getValue(first to "get-json")

        // Mock from a selected record: the form is the record's exact request, status overridden here.
        select("/json?rv=host-$first")
        onNodeWithText("Mock this response").performScrollTo().performClick()
        waitFor("Mock this response (#")
        val source = session.store.value.get(panel.form!!.fromTransaction!!)!!
        val exact = URI(source.url).let { "GET ${it.scheme}://${it.host}:${it.port}${it.rawPath}" }
        val prefilled = panel.form!!.match.text.toString()
        record("mock from record prefills the exact request", prefilled == exact, "match='$prefilled' from #${panel.form!!.fromTransaction} ${source.url}")
        onNodeWithTag(RulesTags.STATUS).performTextInput("203")
        screenshot("02-mock-form.png")
        val mock = save(panel)
        waitFor("0 hits")
        val mocked = fire(session, "mock")
        val jsonCalls = mocked.app.filterKeys { it.second == "get-json" }
        record(
            "mock: the app gets 203 with the recorded body, the server sees no /json",
            jsonCalls.size == n && jsonCalls.values.all { it.str("status") == "203" && it.str("body") == baseJson.str("body") } && mocked.server.none { it.path().startsWith("/json") },
            "app=${jsonCalls.map { (k, v) -> "${k.first}=${v.str("status")}" }} server /json lines=${mocked.server.count { it.path().startsWith("/json") }} other server lines=${mocked.server.size}",
        )
        val mockRecords = mocked.records.filter { it.url.contains("/json?") }
        record("mock: the records carry the rule", mockRecords.size == n && mockRecords.all { it.ruleId == mock && it.ruleApplied == "mock" }, mockRecords.map { "#${it.id} rule=${it.ruleId} applied=${it.ruleApplied}" }.toString())
        hits(session, mock, hitsText(n.toLong()), n.toLong())
        searchList("/json?rv=host-")
        val tagged = runCatching { waitUntil(timeoutMillis = 10_000) { onAllNodes(hasContentDescription("mock")).fetchSemanticsNodes().size >= n } }.isSuccess
        record("mock: the list tags the mocked rows", tagged, "${onAllNodes(hasContentDescription("mock")).fetchSemanticsNodes().size} rows tagged mock among the /json rows")
        screenshot("03a-mock-tags.png")
        searchList(null)
        select("/json?rv=host-${all.first()}")
        waitFor("#$mock: mock")
        record("mock: the detail names the rule", true, "Rule #$mock: mock")
        screenshot("03-mock-hits.png")

        // Disable: requests go through and the hits stay; enable: mocked again.
        onNodeWithContentDescription(RulesTags.toggle(mock)).performClick()
        waitUntil(timeoutMillis = 15_000) { session.rules.value.rules.single().enabled.not() }
        val off = fire(session, "mock disabled")
        record(
            "disabled: the app gets the real 200 and the server sees /json",
            off.app.filterKeys { it.second == "get-json" }.values.all { it.str("status") == "200" } && off.server.count { it.path().startsWith("/json") } == n && off.records.none { it.ruleId != null },
            "server /json lines=${off.server.count { it.path().startsWith("/json") }}",
        )
        hits(session, mock, hitsText(n.toLong()), n.toLong())
        screenshot("04-mock-disabled.png")
        onNodeWithContentDescription(RulesTags.toggle(mock)).performClick()
        waitUntil(timeoutMillis = 15_000) { session.rules.value.rules.single().enabled }
        val on = fire(session, "mock enabled")
        record(
            "enabled again: mocked again",
            on.app.filterKeys { it.second == "get-json" }.values.all { it.str("status") == "203" } && on.server.none { it.path().startsWith("/json") },
            "server /json lines=${on.server.count { it.path().startsWith("/json") }}",
        )
        hits(session, mock, hitsText(2L * n), 2L * n)

        // Remove from its row.
        rowButton("Remove", 0).performClick()
        waitFor("Rule #$mock removed")
        waitFor("No rules.")
        record("remove: the row goes", session.rules.value.rules.isEmpty(), panel.message.toString())

        // Delay, fail and edit, each typed in the panel, each on one source.
        panelButton("Delay").performClick()
        onNodeWithTag(RulesTags.MATCH).performTextInput("GET *${plan.delayPath}?rv=host-${plan.delayTarget}")
        field(plan.delayField).performTextInput("700")
        val delay = save(panel)
        panelButton("Fail").performClick()
        onNodeWithTag(RulesTags.MATCH).performTextInput("POST */echo?rv=host-${plan.failTarget}")
        onNode(hasText("timeout") and hasAnyAncestor(hasTestTag(RulesTags.PANEL))).performClick()
        val fail = save(panel)
        panelButton("Edit").performClick()
        onNodeWithTag(RulesTags.MATCH).performTextInput("GET */missing?rv=host-${plan.editTarget}")
        field("Path").performTextInput("/echo")
        if (plan.editResponse) responseHeader("X-Hopi-Edited", "desktop")
        screenshot("05-edit-form.png")
        val edit = save(panel)
        waitUntil(timeoutMillis = 15_000) { session.rules.value.rules.size == 3 }
        val listed = session.rules.value.rules
        record("three rules listed", listed.map { it.id } == listOf(delay, fail, edit), listed.map { "#${it.id} ${it.match} -> ${it.action} unsupported=${it.unsupported}" }.toString())
        if (!plan.editResponse) {
            // A response edit on URLSession: added with the app's exclusion under its row, then removed.
            panelButton("Edit").performClick()
            onNodeWithTag(RulesTags.MATCH).performTextInput("GET */json?rv=host-${plan.editTarget}")
            responseHeader("X-Hopi-Edited", "desktop")
            val unsupported = save(panel)
            val warned = runCatching { waitFor("not applied on urlsession: response edits are unsupported") }.isSuccess
            record("response edit: the panel says URLSession does not apply it", warned, "message='${panel.message}' unsupported=${rule(session, unsupported).unsupported}")
            screenshot("05b-unsupported-warning.png")
            rowButton("Remove", 3).performClick()
            waitFor("Rule #$unsupported removed")
            waitUntil(timeoutMillis = 15_000) { session.rules.value.rules.size == 3 }
        }

        val three = fire(session, "delay, fail, edit")
        val delayKind = "get-" + plan.delayPath.removePrefix("/")
        val delayed = three.app.getValue(plan.delayTarget to delayKind)
        val baseDelayed = base.app.getValue(plan.delayTarget to delayKind)
        record(
            "delay: the app waits 700 ms more, the server still sees the request",
            delayed.long("ms") >= 700 && delayed.str("status") == plan.delayStatus && three.server.any { it.path() == "${plan.delayPath}?rv=host-${plan.delayTarget}" },
            "ms=${delayed.long("ms")} (baseline ${baseDelayed.long("ms")}) status=${delayed.str("status")}",
        )
        val failed = three.app.getValue(plan.failTarget to "post-echo")
        record(
            "fail timeout: the app sees the platform's timeout, the server sees nothing",
            failed.str("error").orEmpty().contains(plan.failError) && three.server.none { it.str("method") == "POST" && it.path() == "/echo?rv=host-${plan.failTarget}" },
            "error=${failed.str("error")}",
        )
        val edited = three.app.getValue(plan.editTarget to "get-missing")
        val editedOnServer = three.server.filter { it.path() == "/echo?rv=host-${plan.editTarget}" && it.str("method") == "GET" }
        record(
            "edit: the server sees GET /echo, the app gets 200" + if (plan.editResponse) " with X-Hopi-Edited: desktop" else "",
            edited.str("status") == "200" && edited.str("header") == (if (plan.editResponse) "desktop" else null) && editedOnServer.size == 1 && three.server.none { it.path() == "/missing?rv=host-${plan.editTarget}" },
            "app status=${edited.str("status")} header=${edited.str("header")} server=${editedOnServer.map { it.path() }}",
        )
        val marks = mapOf(delay to "delay", fail to "fail", edit to plan.editApplied).map { (id, applied) ->
            val r = three.records.filter { it.ruleId == id }
            "#$id:${r.map { "${it.ruleApplied}/${it.state}" }}" to (r.size == 1 && r.single().ruleApplied == applied)
        }
        record("delay, fail, edit: each record carries its rule", marks.all { it.second }, marks.map { it.first }.toString())
        for (id in listOf(delay, fail, edit)) hits(session, id, null, 1)
        val oneHit = onAllNodes(hasText("1 hit", substring = true) and hasAnyAncestor(hasTestTag(RulesTags.PANEL))).fetchSemanticsNodes().size
        record("the panel shows 1 hit on each row", oneHit == 3, "$oneHit rows with '1 hit'")
        screenshot("06-three-hits.png")

        // Update the fail rule to times 1: exhausted at once (1 hit), then the request goes through.
        rowButton("Update", 1).performClick()
        waitFor("Update rule #$fail")
        field("Times").performTextInput("1")
        onNodeWithTag(RulesTags.SAVE).performClick()
        waitFor("Rule #$fail updated")
        waitUntil(timeoutMillis = 15_000) { rule(session, fail).exhausted }
        waitFor("1/1 hits · exhausted")
        record("update times=1: the row says exhausted", true, rule(session, fail).let { "hits=${it.hits} times=${it.times} exhausted=${it.exhausted}" })
        val exhausted = fire(session, "fail exhausted")
        record(
            "exhausted: the POST reaches the server again",
            exhausted.app.getValue(plan.failTarget to "post-echo").str("status") == "200" && exhausted.server.any { it.str("method") == "POST" && it.path() == "/echo?rv=host-${plan.failTarget}" },
            "status=${exhausted.app.getValue(plan.failTarget to "post-echo").str("status")}",
        )
        hits(session, fail, "1/1 hits", 1)
        hits(session, delay, null, 2)
        screenshot("07-exhausted.png")

        // Clear: every rule goes, every request reaches the server unchanged.
        onNode(hasText("Clear") and hasAnyAncestor(hasTestTag(RulesTags.PANEL))).performClick()
        waitFor("3 rules removed")
        waitFor("No rules.")
        val cleared = fire(session, "cleared")
        record(
            "clear: back to the baseline",
            cleared.server.size == plan.requests && cleared.records.none { it.ruleId != null } &&
                cleared.app.all { (k, v) -> v.str("status") == base.app.getValue(k).str("status") && v.str("error") == null },
            "server=${cleared.server.size} statuses=${statuses(cleared)}",
        )
        screenshot("08-cleared.png")

        viewer.close()
    }

    // --- the app, the server, the records ---

    private class Fire(val app: Map<Pair<String, String>, JsonObject>, val server: List<JsonObject>, val records: List<HttpTransaction>)

    /** Sends the sample's `rulesTargets` requests ([Plan.fire]) and collects the three oracles. */
    private fun ComposeUiTest.fire(session: RemoteSession, step: String): Fire {
        val marker = serverLines()
        val highest = session.store.value.transactions.value.maxOfOrNull { it.id } ?: 0
        val started = System.nanoTime()
        val app = plan.fire(step)
        fun ours() = session.store.value.transactions.value.filter { it.id > highest && it.url.contains("rv=host-") }
        val arrived = runCatching { waitUntil(timeoutMillis = 30_000) { ours().size >= plan.requests && ours().all { it.state.isTerminal } } }.isSuccess
        val records = ours()
        val server = serverSince(marker)
        record("fire ($step)", app.size == plan.requests && arrived, "app lines=${app.size} records=${records.size} server lines=${server.size} in ${ms(started)} ms")
        return Fire(app, server, records)
    }

    /** The sample's `rulesTargets` action, relaunched in the same process (`CLEAR_TASK`), so its rules stay; its lines from logcat. */
    private fun fireAndroid(@Suppress("UNUSED_PARAMETER") step: String): Map<Pair<String, String>, JsonObject> {
        run(adb, "logcat", "-c")
        run(adb, "shell", "am", "start", "-n", "$PKG/.MainActivity", "-f", "0x10008000", "--es", "rulesTargets", "all")
        return poll { run(adb, "logcat", "-d", "-v", "raw", "-s", "HopiSample:I").lines() }
    }

    /** The sample's "Rules targets (for host rules)" button, pressed with agent-device; its lines from the app's console. */
    private fun fireIos(@Suppress("UNUSED_PARAMETER") step: String): Map<Pair<String, String>, JsonObject> {
        val log = evidence.resolve("ios-app.log")
        val before = Files.readAllLines(log).size
        val press = ProcessBuilder("agent-device", "press", "label=\"Rules targets (for host rules)\"").directory(evidence.toFile()).redirectErrorStream(true).start()
        val output = press.inputStream.bufferedReader().readText()
        assertEquals(0, press.waitFor(), "agent-device press: $output")
        return poll { Files.readAllLines(log).drop(before) }
    }

    private fun poll(lines: () -> List<String>): Map<Pair<String, String>, JsonObject> {
        var app = emptyMap<Pair<String, String>, JsonObject>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (app.size < plan.requests && System.nanoTime() < deadline) {
            Thread.sleep(500)
            app = lines().mapNotNull { line -> line.indexOf("{\"rulesTarget\"").takeIf { it >= 0 }?.let { line.substring(it) } }
                .mapNotNull { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                .associateBy { it.str("target")!! to it.str("rulesTarget")!! }
        }
        return app
    }

    private fun serverLines(): Int = Files.readAllLines(serverLog).size

    private fun serverSince(marker: Int): List<JsonObject> {
        Thread.sleep(300)
        return Files.readAllLines(serverLog).drop(marker).filter { it.startsWith("{") }.map { Json.parseToJsonElement(it).jsonObject }
            .filter { it.path().contains("rv=host-") }
    }

    private fun statuses(fire: Fire) = fire.app.map { (k, v) -> "${k.first}/${k.second}=${v.str("status")}" }

    private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.content

    private fun JsonObject.long(name: String): Long = str(name)?.toLongOrNull() ?: -1

    private fun JsonObject.path(): String = str("path").orEmpty()

    private fun JsonObject.header(name: String): String? =
        (this["headers"] as? JsonArray)?.map { it.jsonArray }?.firstOrNull { it[0].jsonPrimitive.content.equals(name, ignoreCase = true) }?.get(1)?.jsonPrimitive?.content

    private fun rule(session: RemoteSession, id: Long): RemoteRule = session.rules.value.rules.first { it.id == id }

    private fun hitsText(hits: Long) = if (hits == 1L) "1 hit" else "$hits hits"

    /** [id]'s hits as the session counts them and, when [text] is given, as its row shows them; re-checked after the re-list settles. */
    private fun ComposeUiTest.hits(session: RemoteSession, id: Long, text: String?, expected: Long) {
        val reached = runCatching { waitUntil(timeoutMillis = 15_000) { rule(session, id).hits == expected } }.isSuccess
        if (text != null) waitFor(text)
        Thread.sleep(2_000)
        waitForIdle()
        val settled = rule(session, id).hits
        val shown = text == null || onAllNodes(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(RulesTags.PANEL))).fetchSemanticsNodes().isNotEmpty()
        record("rule #$id: $expected hits", reached && settled == expected && shown, "session hits=$settled after the re-list" + (text?.let { "; the row shows '$it': $shown" } ?: ""))
    }

    // --- the window ---

    private val rows = SemanticsMatcher("a list row") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open details" }

    /**
     * Not a check: whether the newest row is in view after a burst of rows arrived on a list nobody
     * scrolled or selected in (the tail's live view).
     */
    private fun ComposeUiTest.newestRowInView(fire: Fire) {
        waitForIdle()
        val newest = fire.records.maxBy { it.id }
        val path = URI(newest.url).let { it.rawPath + "?" + it.rawQuery }
        val listTop = onNode(hasContentDescription(SEARCH) and hasSetTextAction()).fetchSemanticsNode().boundsInRoot.bottom
        val shown = onAllNodes(rows).fetchSemanticsNodes()
        val node = shown.firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == path } }
        val inView = node != null && node.boundsInRoot.top >= listTop
        val firstInView = shown.firstOrNull { it.boundsInRoot.top >= listTop }?.config?.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        note(
            "the newest row is in view after a burst on an untouched list",
            inView,
            "newest #${newest.id} $path ${if (node == null) "not composed" else "top=${node.boundsInRoot.top} list top=$listTop"}; first row in view: $firstInView",
        )
        screenshot("01b-after-burst.png")
    }

    /**
     * Selects the newest row whose path and query are exactly [path]: the list's own search narrows
     * it first (rows that arrive above a scrolled list stay out of view), then is cleared again.
     */
    private fun ComposeUiTest.select(path: String) {
        searchList(path)
        // The list is filtered off the main thread: wait until every row shown has the query.
        waitUntil(timeoutMillis = 15_000) {
            val shown = onAllNodes(rows).fetchSemanticsNodes()
            shown.isNotEmpty() && shown.all { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.contains(path) } }
        }
        onAllNodes(rows and hasText(path)).onFirst().performClick()
        waitUntil(timeoutMillis = 10_000) { panes.selectedId != null }
        val chosen = panes.selectedId
        searchList(null)
        val kept = runCatching { waitUntil(timeoutMillis = 3_000) { panes.selectedId == chosen } }.isSuccess && panes.selectedId == chosen
        record("clearing the list search keeps the selection ($path)", kept, "selected #$chosen, after clearing the search: #${panes.selectedId}")
        if (!kept) panes.selectedId = chosen
        // The detail's rule buttons appear once the selected record's detail is fetched.
        waitFor("Mock this response")
    }

    private fun ComposeUiTest.searchList(query: String?) {
        val search = onNode(hasContentDescription(SEARCH) and hasSetTextAction())
        search.performTextClearance()
        if (query != null) search.performTextInput(query)
        waitForIdle()
    }

    /** Adds a row to the edit form's response "Set headers" table (the second table; the first is the request's). */
    private fun ComposeUiTest.responseHeader(name: String, value: String) {
        onAllNodes(hasText("Add header") and hasAnyAncestor(hasTestTag(RulesTags.PANEL)))[1].performScrollTo().performClick()
        onNodeWithTag(RulesTags.header("Set headers", 0, value = false)).performScrollTo().performTextInput(name)
        onNodeWithTag(RulesTags.header("Set headers", 0, value = true)).performTextInput(value)
    }

    private fun ComposeUiTest.panelButton(label: String): SemanticsNodeInteraction = onNode(hasText(label) and hasAnyAncestor(hasTestTag(RulesTags.PANEL)))

    private fun ComposeUiTest.rowButton(label: String, row: Int): SemanticsNodeInteraction =
        onAllNodes(hasContentDescription(label) and hasAnyAncestor(hasTestTag(RulesTags.PANEL)))[row]

    /** Saves the open form and returns the new rule's id from the panel's message. */
    private fun ComposeUiTest.save(panel: RulesPanelState): Long {
        onNodeWithTag(RulesTags.SAVE).performClick()
        waitUntil(timeoutMillis = 15_000) { panel.form == null && panel.message.orEmpty().startsWith("Rule #") }
        return Regex("Rule #(\\d+) added").find(panel.message!!)!!.groupValues[1].toLong()
    }

    /** The text field right after the label [label] in the panel's form (the fields carry no tag of their own). */
    private fun ComposeUiTest.field(label: String): SemanticsNodeInteraction {
        val nodes = onAllNodes((hasText(label) or hasSetTextAction()) and hasAnyAncestor(hasTestTag(RulesTags.PANEL)), useUnmergedTree = true).fetchSemanticsNodes()
        var armed = false
        for (node in nodes) {
            val isField = node.config.getOrNull(SemanticsActions.SetText) != null
            if (!isField && node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true) armed = true
            if (armed && isField) return onNode(SemanticsMatcher("field after '$label'") { it.id == node.id }, useUnmergedTree = true).performScrollTo()
        }
        error("no field labelled '$label'")
    }

    private fun ComposeUiTest.waitFor(text: String, timeoutMs: Long = 30_000) {
        waitUntil(timeoutMillis = timeoutMs) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.screenshot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", evidence.resolve("${plan.platform}-rules-$name").toFile())
    }

    private fun record(check: String, pass: Boolean, detail: String) = write(check, if (pass) "PASS" else "FAIL", detail).also { if (!pass) failures += "$check: $detail" }

    /** An observation, never a failure. */
    private fun note(check: String, yes: Boolean, detail: String) = write(check, if (yes) "INFO yes" else "INFO no", detail)

    private fun write(check: String, result: String, detail: String) {
        val line = "{\"check\":${Json.encodeToString(check)},\"result\":\"$result\",\"detail\":${Json.encodeToString(detail)}}\n"
        Files.writeString(out(), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        System.err.print(line)
    }

    private fun ms(since: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since)

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "${command.joinToString(" ")}: $output")
        return output
    }

    private companion object {
        const val PKG = "dev.koga.hopi.sample"

        /** The list's search field (hopi-ui's `ListTags.SEARCH`, internal there). */
        const val SEARCH = "Search URL"
    }
}
