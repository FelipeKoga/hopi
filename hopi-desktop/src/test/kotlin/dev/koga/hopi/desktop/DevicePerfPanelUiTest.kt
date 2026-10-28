package dev.koga.hopi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.koga.hopi.connect.HostEnvironment
import dev.koga.hopi.connect.Targets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import javax.imageio.ImageIO
import kotlin.math.roundToLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The perf panel against a real app (device verification, docs/design/perf-monitors.md): the
 * sample's perf lab has already run (the host's `benchmark/perf/verify.py` or a lab launch) and
 * left its monitors on; the host wrote what the CLI read, `hopi hangs --json`, to
 * `<evidence>/<platform>-cli-hangs.ndjson`. The window's real content is composed offscreen,
 * connects, opens the panel and is judged against the CLI's record: every hang row with the same
 * sequence and duration, a hang's stack naming the lab's function, the monitors shown on, CPU and
 * memory figures present. On Android the lab's click-burn button is then tapped through input
 * dispatch while the panel is open, and the new hang must arrive on the panel's live stream.
 * Skipped unless `-Phopi.desktop.device=android|ios`; run it alone with `--tests '*DevicePerfPanelUiTest'`.
 */
@OptIn(ExperimentalTestApi::class)
class DevicePerfPanelUiTest {
    private val device = System.getProperty("hopi.desktop.device")
    private val evidence: Path = Paths.get(System.getProperty("hopi.desktop.evidence") ?: "build/evidence/perf")
    private val adb = Paths.get(System.getProperty("user.home"), "Library/Android/sdk/platform-tools/adb").toString()
    private val failures = mutableListOf<String>()
    private var viewer: Viewer? = null
    private lateinit var platform: String

    @AfterTest
    fun closeViewer() {
        viewer?.close()
    }

    @Test
    fun perfPanelAgainstTheLab() {
        platform = device ?: return
        if (platform != "android" && platform != "ios") return
        Files.createDirectories(evidence)
        Files.deleteIfExists(out())
        runDesktopComposeUiTest(width = 1280, height = 820, testTimeout = 10.minutes) {
            try {
                flow()
            } catch (e: Throwable) {
                runCatching { screenshot("failure.png") }
                throw e
            }
        }
        assertEquals(emptyList(), failures)
    }

    private fun out(): Path = evidence.resolve("$platform-desktop-checks.jsonl")

    private fun ComposeUiTest.flow() {
        val cliHangs = Files.readAllLines(evidence.resolve("$platform-cli-hangs.ndjson"))
            .filter { it.startsWith("{") }
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it.str("type") == "perf.event" && it.str("kind") == "hang" && it.str("state") == "ended" }
        record("the CLI's record has hangs", cliHangs.isNotEmpty(), "${cliHangs.size} ended hangs: ${cliHangs.map { "#${it.num("seq")} ${it.num("ms")} ms" }}")

        val viewer = Viewer(Targets(HostEnvironment())).also { this@DevicePerfPanelUiTest.viewer = it }
        viewer.start()
        setContent {
            val perf = remember { PerfPanelState() }
            Box(Modifier.fillMaxSize()) {
                ViewerContent(viewer, chooseExportFile = { _, _ -> evidence.resolve("unused.har") }, perf = perf)
            }
        }
        waitFor(if (platform == "ios") "iOS" else "Android", 30_000)
        if (onAllNodes(hasText(PKG)).fetchSemanticsNodes().isEmpty()) onNodeWithTag(ViewerTags.APP_FIELD).performTextInput(PKG)
        onNodeWithTag(ViewerTags.CONNECT).performClick()
        waitFor("Connected to $PKG ($platform)", 30_000)
        val session = viewer.session.value!!
        onNodeWithTag(ViewerTags.ROOT).performKeyInput { withKeyDown(if (IS_MAC) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.P) } }
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasTestTag(PerfTags.PANEL)).fetchSemanticsNodes().isNotEmpty() }
        val backfilled = runCatching { waitUntil(timeoutMillis = 20_000) { session.perf.view.value.hangs.count { !it.ongoing } >= cliHangs.size } }.isSuccess
        val view = session.perf.view.value
        record("backfill: the panel holds every hang the CLI read", backfilled, "panel ${view.hangs.size} hangs, CLI ${cliHangs.size}")

        for (hang in cliHangs) {
            val seq = hang.num("seq")!!
            val ms = hang.num("ms")!!
            val row = "#$seq  $ms ms"
            val shown = onAllNodes(hasText(row, substring = true)).fetchSemanticsNodes().isNotEmpty()
            val model = view.hangs.firstOrNull { it.seq == seq }
            record("hang #$seq: row shows the CLI's $ms ms", shown && model?.ms?.roundToLong() == ms, "row '$row' shown=$shown, panel model ${model?.ms} ms")
        }
        screenshot("01-panel.png")

        val withStack = cliHangs.firstOrNull { h -> h.str("stacks").orEmpty().contains(NEEDLE[platform]!!) }
        if (withStack != null) {
            val seq = withStack.num("seq")!!
            onAllNodes(hasText("#$seq  ", substring = true)).onFirst().performScrollTo().performClick()
            val named = runCatching { waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(NEEDLE[platform]!!, substring = true)).fetchSemanticsNodes().isNotEmpty() } }.isSuccess
            record("hang #$seq: the expanded stack names ${NEEDLE[platform]}", named, "the panel's stack text")
            screenshot("02-hang-stack.png")
        } else {
            record("a CLI hang names ${NEEDLE[platform]}", false, "none of the CLI's hangs")
        }

        val status = session.perf.view.value.status
        val running = status?.running?.map { it.name }.orEmpty()
        for (monitor in listOf("frames", "hangs", "cpu", "memory").filter { it in (status?.available ?: emptyList()) }) {
            val node = onAllNodes(hasContentDescription(PerfTags.toggle(monitor))).fetchSemanticsNodes().firstOrNull()
            val on = node?.config?.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
            record("switch $monitor matches perf.status", on == (monitor in running), "switch on=$on, status running=$running")
        }
        val sampled = runCatching { waitUntil(timeoutMillis = 15_000) { session.perf.view.value.samples.any { it.cpu != null && it.residentBytes != null } } }.isSuccess
        val last = session.perf.view.value.samples.lastOrNull()
        record("CPU and memory samples reach the panel", sampled, "last sample cpu=${last?.cpu}% resident=${last?.residentBytes?.let { it shr 20 }} MB, ${session.perf.view.value.samples.size} samples")
        val cpuShown = last?.cpu?.let { onAllNodes(hasText("${(it * 10).roundToLong() / 10.0}%", substring = true)).fetchSemanticsNodes().isNotEmpty() } ?: false
        note("the CPU figure is on screen", cpuShown, "${last?.cpu}")

        if (platform == "android") {
            // verify.py's memory step left the app in the background; its own component-only intent brings the task back, lab and all (a launcher intent would start a second MainActivity).
            run(adb, "shell", "am", "start", "-W", "-n", "$PKG/.MainActivity")
            val before = session.perf.view.value.hangs.map { it.seq }.toSet()
            val tapped = tapClickBurn()
            record("live: click burn tapped through input dispatch", tapped, "uiautomator found the button")
            val arrived = runCatching {
                waitUntil(timeoutMillis = 15_000) { session.perf.view.value.hangs.any { it.seq !in before && !it.ongoing } }
            }.isSuccess
            val fresh = session.perf.view.value.hangs.firstOrNull { it.seq !in before && !it.ongoing }
            record(
                "live: the click burn's hang arrives on the open panel, 600 ms",
                arrived && fresh != null && fresh.ms != null && fresh.ms!! in 580.0..660.0 && fresh.stacks.flatten().any { "PerfLab.clickBurn" in it },
                "#${fresh?.seq} ${fresh?.ms} ms, stack names clickBurn=${fresh?.stacks?.flatten()?.any { "PerfLab.clickBurn" in it }}",
            )
            if (fresh != null) waitFor("#${fresh.seq}  ", 10_000)
            screenshot("03-live-hang.png")
        }
    }

    private fun tapClickBurn(): Boolean {
        run(adb, "shell", "uiautomator", "dump", "/data/local/tmp/hopi-perf-ui.xml")
        val xml = run(adb, "shell", "cat", "/data/local/tmp/hopi-perf-ui.xml")
        val node = Regex("<node [^>]*>").findAll(xml).map { it.value }.firstOrNull { "text=\"Click burn (600 ms in onClick)\"" in it } ?: return false
        val (x1, y1, x2, y2) = Regex("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"").find(node)!!.destructured
        run(adb, "shell", "input", "tap", "${(x1.toInt() + x2.toInt()) / 2}", "${(y1.toInt() + y2.toInt()) / 2}")
        return true
    }

    private fun JsonObject.str(key: String): String? = this[key]?.let { if (key == "stacks") it.toString() else it.jsonPrimitive.content }

    private fun JsonObject.num(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull ?: this[key]?.jsonPrimitive?.content?.toDoubleOrNull()?.roundToLong()

    private fun ComposeUiTest.waitFor(text: String, timeoutMs: Long = 30_000) {
        waitUntil(timeoutMillis = timeoutMs) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.screenshot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", evidence.resolve("$platform-desktop-$name").toFile())
    }

    private fun record(check: String, pass: Boolean, detail: String) = write(check, if (pass) "PASS" else "FAIL", detail).also { if (!pass) failures += "$check: $detail" }

    private fun note(check: String, yes: Boolean, detail: String) = write(check, if (yes) "INFO yes" else "INFO no", detail)

    private fun write(check: String, result: String, detail: String) {
        val line = "{\"check\":${Json.encodeToString(check)},\"result\":\"$result\",\"detail\":${Json.encodeToString(detail)}}\n"
        Files.writeString(out(), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        System.err.print(line)
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "${command.joinToString(" ")}: $output")
        return output
    }

    private companion object {
        const val PKG = "dev.koga.hopi.sample"
        val NEEDLE = mapOf("android" to "PerfLab.hang", "ios" to "PerfLab#hang")
    }
}
