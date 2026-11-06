package dev.koga.hopi.desktop

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import dev.koga.hopi.connect.HostEnvironment
import dev.koga.hopi.connect.Targets
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The viewer against a real device, end to end: real adb, the sample's real bridge, the window's
 * real content composed offscreen. Skipped unless `-Phopi.desktop.device=android` (one
 * emulator running, the sample's debug build installed with `-Psample.maxBodyBytes=8388608`, the
 * dev server up writing its stdout to `<evidence>/devserver.ndjson`, where the request overhead
 * counts the POSTs, and `adb reverse tcp:8765 tcp:8765`) or `=ios` (one booted simulator with the
 * sample's Debug build installed). Each check appends a JSON line to `<evidence>/<platform>-checks.jsonl`
 * and the window's content goes to `<evidence>/<platform>-*.png` (`hopi.desktop.evidence`,
 * default `build/evidence/desktop`).
 */
@OptIn(ExperimentalTestApi::class)
class DeviceViewerUiTest {
    private val device = System.getProperty("hopi.desktop.device")
    private val evidence: Path = Paths.get(System.getProperty("hopi.desktop.evidence") ?: "build/evidence/desktop")
    private val repo: Path = Paths.get(System.getProperty("hopi.repo") ?: "..").toAbsolutePath().normalize()
    private val adb = Paths.get(System.getProperty("user.home"), "Library/Android/sdk/platform-tools/adb").toString()
    private val clipboard = RecordingClipboard()

    /** Every failed check; asserted at the end so one run reports them all. */
    private val failures = mutableListOf<String>()

    @Test
    fun androidEmulatorEndToEnd() {
        if (device != "android") return
        Files.createDirectories(evidence)
        runComposeUiTest(testTimeout = 30.minutes) { android() }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun iosSimulatorEndToEnd() {
        if (device != "ios") return
        Files.createDirectories(evidence)
        runComposeUiTest(testTimeout = 30.minutes) { ios() }
        assertEquals(emptyList(), failures)
    }

    private fun ComposeUiTest.android() {
        val out = evidence.resolve("android-checks.jsonl")
        // A fresh app whose launch fetches a 5 MB JSON body (the debug build's capture cap raised to
        // 8 MB, see the class KDoc: OkHttp capture records the sample's Ktor requests).
        run(adb, "reverse", "tcp:8765", "tcp:8765")
        run(adb, "shell", "am", "force-stop", PKG)
        run(adb, "shell", "am", "start", "-W", "-n", "$PKG/.MainActivity", "--es", "giant", "'mb=5;runs=1;delay=1'")
        val viewer = compose()
        waitFor("Android", 30_000)
        if (onAllNodes(hasText(PKG)).fetchSemanticsNodes().isEmpty()) onNodeWithTag(ViewerTags.APP_FIELD).performTextInput(PKG)
        onNodeWithTag(ViewerTags.CONNECT).performClick()
        waitFor("Connected to $PKG (android)", 30_000)
        val session = viewer.session.value!!
        fun txs(): List<HttpTransaction> = session.store.value.transactions.value

        // Live rows: the launch's giant GET, then the Diagnostics requests fired now.
        waitUntil(timeoutMillis = 120_000) { txs().any { it.url.contains("/giant") && it.state == TransactionState.Complete } }
        val giant = txs().first { it.url.contains("/giant") }
        record(out, "giant row arrives", giant.responseBody!!.isNotFetched, "id=${giant.id} reported=${giant.responseBody!!.reportedBytes}")
        python("ui.py", "tap", "desktop diagnostics", "Diagnostics")
        val before = txs().size
        val fired = System.nanoTime()
        python("ui.py", "tap", "desktop fire", "Fire requests")
        waitUntil(timeoutMillis = 60_000) { txs().any { it.url.endsWith("/echo") && it.state.isTerminal } && txs().size >= before + 6 }
        record(out, "rows appear live", true, "${txs().size - before} new rows ${ms(fired)} ms after the tap (includes ui.py's own settle wait)")
        waitFor("/echo")
        screenshot("android-live-rows.png")
        connections(out, "connected, streaming")
        record(out, "the picker offers Reconnect while connected", onAllNodes(hasText("Reconnect")).fetchSemanticsNodes().isNotEmpty(), "after adb's track-devices updates")

        // Selecting the 5 MB row fetches only its body.
        val others = txs().filter { it.id != giant.id && it.responseBody != null }.map { it.id }
        val opened = System.nanoTime()
        onAllNodes(hasText("/giant", substring = true)).onFirst().performClick()
        waitUntil(timeoutMillis = 120_000) { session.store.value.get(giant.id)?.responseBody?.isNotFetched == false }
        val fetchMs = ms(opened)
        val body = session.store.value.get(giant.id)!!.responseBody!!
        waitUntil(timeoutMillis = 60_000) { onAllNodes(hasText("Loading body", substring = true)).fetchSemanticsNodes().isEmpty() }
        val stillSummaries = others.count { session.store.value.get(it)?.responseBody?.isNotFetched == true }
        record(out, "5 MB body opens", body.capturedBytes.toLong() == giant.responseBody!!.reportedBytes && body.capturedBytes > 5_000_000 && !body.truncated, "captured=${body.capturedBytes} of ${giant.responseBody!!.reportedBytes} truncated=${body.truncated} fetchMs=$fetchMs")
        record(out, "only the selected body fetched", stillSummaries == others.size, "$stillSummaries of ${others.size} other rows still summaries")
        onNodeWithText("Response").performClick()
        val tab = System.nanoTime()
        val rendered = runCatching {
            waitUntil(timeoutMillis = 60_000) {
                onAllNodes(hasText("Loading body", substring = true)).fetchSemanticsNodes().isEmpty() &&
                    onAllNodes(hasText("Loading headers", substring = true)).fetchSemanticsNodes().isEmpty()
            }
        }.isSuccess
        record(out, "5 MB body renders in the Response tab", rendered, "${ms(tab)} ms after the tab click")
        screenshot("android-giant-open.png")

        // Copy cURL of the POST /echo (Authorization header: redacted).
        val echo = txs().first { it.url.endsWith("/echo") }
        onAllNodes(hasText("/echo", substring = true)).onFirst().performClick()
        waitUntil(timeoutMillis = 30_000) { session.store.value.get(echo.id)?.requestBody?.isNotFetched == false }
        waitUntil(timeoutMillis = 30_000) { onAllNodes(hasContentDescription("Copy cURL")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Copy cURL").performClick()
        waitUntil(timeoutMillis = 15_000) { clipboard.text != null }
        val curl = clipboard.text!!
        val auth = Regex("-H '(?i:authorization): ([^']*)'").find(curl)?.groupValues?.get(1)
        record(out, "copy cURL", curl.startsWith("curl") && curl.contains("/echo") && auth != null && !auth.startsWith("Bearer s"), "auth header in clip: $auth; ${curl.length} chars")
        Files.writeString(evidence.resolve("android-curl.txt"), curl)
        waitFor("cURL copied")
        screenshot("android-curl-copied.png")

        // ⌘E: HAR 1.2 of the whole session; ⇧⌘E: NDJSON.
        exportTo = evidence.resolve("android-session.har")
        onNodeWithTag(ViewerTags.ROOT).performKeyInput { command(Key.E) }
        waitFor("Exported", 120_000)
        val har = Json.parseToJsonElement(Files.readString(exportTo)).jsonObject
        val problems = validateHar(har)
        record(out, "export HAR", problems.isEmpty(), "${Files.size(exportTo)} bytes; validator: $problems; notice: ${viewer.notice.value}")
        exportTo = evidence.resolve("android-session.ndjson")
        onNodeWithTag(ViewerTags.ROOT).performKeyInput { withKeyDown(Key.ShiftLeft) { command(Key.E) } }
        waitUntil(timeoutMillis = 120_000) { viewer.notice.value.orEmpty().contains("android-session.ndjson") && viewer.notice.value.orEmpty().startsWith("Exported") }
        record(out, "export NDJSON", Files.readAllLines(exportTo).size == txs().size, "${Files.size(exportTo)} bytes, ${Files.readAllLines(exportTo).size} lines; notice: ${viewer.notice.value}")

        // 60 s idle with the viewer connected: the app's threads' CPU ticks.
        onNodeWithTag(ViewerTags.ROOT).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        Thread.sleep(15_000) // the exports' and the giant body's GC and JIT tail, before the window
        val pid = run(adb, "shell", "pidof", PKG).trim()
        val t0 = threadTicks(pid)
        Thread.sleep(60_000)
        val t1 = threadTicks(pid)
        val busy = t1.mapNotNull { (tid, now) -> t0[tid]?.let { was -> Triple(tid, now.first, now.second - was.second) } }.filter { it.third > 0 }
        val hopiTicks = t1.filter { it.value.first.startsWith("hopi") }.map { (tid, now) -> now.second - (t0[tid]?.second ?: 0) }.sum()
        record(
            out,
            "idle 60 s connected: Hopi threads 0 ticks",
            hopiTicks == 0L,
            "hopi threads=${t1.values.count { it.first.startsWith("hopi") }} ticks=$hopiTicks; process threads with ticks: ${busy.map { "${it.second}=${it.third}" }}",
        )
        connections(out, "idle")

        // Force-stop: the status says stopped; a relaunch reconnects with no action here.
        val sampler = ConnectionSampler().also { it.start() }
        run(adb, "shell", "am", "force-stop", PKG)
        waitFor("App stopped", 60_000)
        record(out, "force-stop shows stopped", true, statusText(viewer.status.value))
        screenshot("android-app-stopped.png")
        val relaunched = System.nanoTime()
        run(adb, "shell", "am", "start", "-W", "-n", "$PKG/.MainActivity")
        waitFor("Connected to $PKG (android)", 60_000)
        record(out, "relaunch reconnects by itself", true, "connected ${ms(relaunched)} ms after am start returned")
        screenshot("android-reconnected.png")

        // adb kill-server / start-server: offline, then connected again.
        val reset = System.nanoTime()
        sampler.pause = true
        run(adb, "kill-server")
        // Another adb client (the viewer's own device tracking, an agent's MCP server) may start
        // the server first; start-server then fails with "didn't ACK", and that server is as good.
        run(adb, "start-server", check = false)
        sampler.pause = false
        run(adb, "wait-for-device")
        run(adb, "reverse", "tcp:8765", "tcp:8765")
        waitUntil(timeoutMillis = 90_000) { viewer.status.value !is ViewerStatus.Connected || ms(reset) > 60_000 }
        val during = statusText(viewer.status.value)
        waitFor("Connected to $PKG (android)", 90_000)
        record(out, "adb kill-server/start-server reconnects", true, "status meanwhile: '$during'; connected ${ms(reset)} ms after kill-server")
        Thread.sleep(3_000)
        sampler.close()
        record(out, "connections never above 2", sampler.max.get() <= 2, "max ${sampler.max.get()} over ${sampler.samples.get()} samples of /proc/net/unix during stop, relaunch and adb reset")
        connections(out, "after the adb reset")

        // Request overhead: 500 POST /items with the viewer connected and disconnected, alternating.
        python("ui.py", "tap", "desktop diagnostics", "Diagnostics")
        val runs = mutableListOf<String>()
        for (round in 1..3) {
            for (connected in listOf(true, false)) {
                if (connected && viewer.status.value !is ViewerStatus.Connected) {
                    onNodeWithTag(ViewerTags.CONNECT).performClick()
                    waitFor("Connected to $PKG (android)", 60_000)
                }
                if (!connected) {
                    viewer.disconnect()
                    waitFor("Not connected", 30_000)
                }
                runs += "${if (connected) "connected" else "disconnected"}: ${createItems()}"
            }
        }
        record(out, "request overhead, 500 POST /items", true, runs.joinToString("; "))
        viewer.close()
    }

    private fun ComposeUiTest.ios() {
        val out = evidence.resolve("ios-checks.jsonl")
        run("xcrun", "simctl", "terminate", "booted", PKG, check = false)
        run("xcrun", "simctl", "launch", "booted", PKG)
        val viewer = compose()
        waitFor("iOS", 30_000)
        onNodeWithTag(ViewerTags.APP_FIELD).performTextInput(PKG)
        onNodeWithTag(ViewerTags.CONNECT).performClick()
        waitFor("Connected to $PKG (ios)", 30_000)
        val session = viewer.session.value!!
        record(out, "connects through bridge.json", true, statusText(viewer.status.value))

        // Live rows: a relaunch with a 1 MB giant run, then a second one while connected.
        val relaunched = System.nanoTime()
        run("xcrun", "simctl", "terminate", "booted", PKG)
        waitFor("App stopped", 60_000)
        record(out, "terminate shows stopped", true, statusText(viewer.status.value))
        screenshot("ios-app-stopped.png")
        run("xcrun", "simctl", "launch", "booted", PKG, "-giant", "mb=1;runs=3;delay=4")
        waitFor("Connected to $PKG (ios)", 60_000)
        record(out, "relaunch reconnects by itself", true, "connected ${ms(relaunched)} ms after terminate")
        val store = viewer.session.value!!.store
        waitUntil(timeoutMillis = 120_000) { store.value.transactions.value.count { it.url.contains("/giant") && it.state.isTerminal } >= 3 }
        val rows = store.value.transactions.value
        record(out, "rows appear live", rows.size >= 3, "${rows.size} rows: ${rows.map { "${it.method} ${it.url.substringAfter("8765")} ${it.responseCode} ${it.source}" }}")
        // The Darwin client's requests are recorded by URLSession capture: the plugin installs nothing on iOS.
        record(out, "rows come from URLSession capture", rows.all { it.source == "urlsession" }, rows.groupingBy { it.source }.eachCount().toString())
        waitFor("/giant")
        onAllNodes(hasText("/giant", substring = true)).onFirst().performClick()
        val fetched = runCatching {
            waitUntil(timeoutMillis = 60_000) { store.value.transactions.value.any { it.responseBody?.isNotFetched == false } }
        }.isSuccess
        val bodies = store.value.transactions.value.map { "id ${it.id}: ${it.responseBody?.let { b -> if (b.isNotFetched) "summary" else "${b.capturedBytes} B truncated=${b.truncated}" }}" }
        record(out, "selecting fetches only that body", fetched && bodies.count { !it.endsWith("summary") } == 1, bodies.joinToString("; "))
        waitForIdle()
        screenshot("ios-live-rows.png")
        viewer.close()
    }

    // --- harness ---

    private var exportTo: Path = evidence.resolve("export")

    /** Closed even when a check throws: its `adb track-devices` would otherwise outlive the test JVM. */
    private var viewer: Viewer? = null

    @AfterTest
    fun closeViewer() {
        viewer?.close()
    }

    private fun ComposeUiTest.compose(): Viewer {
        val viewer = Viewer(Targets(HostEnvironment())).also { this@DeviceViewerUiTest.viewer = it }
        viewer.start()
        setContent {
            @Suppress("DEPRECATION")
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                Box(Modifier.fillMaxSize()) { ViewerContent(viewer, chooseExportFile = { _, _ -> exportTo }) }
            }
        }
        return viewer
    }

    private fun ComposeUiTest.waitFor(text: String, timeoutMs: Long = 30_000) {
        waitUntil(timeoutMillis = timeoutMs) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun KeyInjectionScope.command(key: Key) = withKeyDown(if (IS_MAC) Key.MetaLeft else Key.CtrlLeft) { pressKey(key) }

    private fun ComposeUiTest.screenshot(name: String) {
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", evidence.resolve(name).toFile())
    }

    private fun record(out: Path, check: String, pass: Boolean, detail: String) {
        val line = "{\"check\":${Json.encodeToString(check)},\"result\":\"${if (pass) "PASS" else "FAIL"}\",\"detail\":${Json.encodeToString(detail)}}\n"
        Files.writeString(out, line, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        System.err.print(line)
        if (!pass) failures += "$check: $detail"
    }

    private fun ms(since: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since)

    private fun run(vararg command: String, check: Boolean = true): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (check) assertEquals(0, code, "${command.joinToString(" ")}: $output")
        return output
    }

    private fun python(script: String, vararg args: String): String {
        val builder = ProcessBuilder("python3", repo.resolve("benchmark/device/scripts/$script").toString(), *args).redirectErrorStream(true)
        builder.environment()["EVIDENCE"] = evidence.resolve("ui").toString()
        Files.createDirectories(evidence.resolve("ui"))
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    /** Connected (state 03) sockets bound to the sample's bridge name: the app's side of each connection. */
    private fun bridgeConnections(): Int =
        run(adb, "shell", "cat", "/proc/net/unix", check = false).lines().count { it.contains(" 03 ") && it.trimEnd().endsWith("@hopi.$PKG") }

    private fun connections(out: Path, at: String) {
        val count = bridgeConnections()
        record(out, "connections on the app side ($at)", count == 2, "$count connected sockets on @hopi.$PKG")
    }

    /** Thread id to (name, utime + stime) of every thread of [pid]. */
    private fun threadTicks(pid: String): Map<String, Pair<String, Long>> =
        run(adb, "shell", "cat /proc/$pid/task/*/stat").lines().filter { it.isNotBlank() }.associate { line ->
            val name = line.substringAfter('(').substringBeforeLast(')')
            val fields = line.substringAfterLast(") ").split(' ')
            // After the name: state is field 3, so utime (14) and stime (15) are at 11 and 12.
            line.substringBefore(' ') to (name to fields[11].toLong() + fields[12].toLong())
        }

    /** Taps "Create 500 items" and waits for the dev server to log the 500 POSTs; the app's own result line. */
    private fun createItems(): String {
        val log = evidence.resolve("devserver.ndjson")
        fun posts() = Files.readAllLines(log).count { it.contains("\"POST\"") && it.contains("\"/items\"") }
        val start = posts()
        run(adb, "logcat", "-c")
        python("ui.py", "tap", "desktop create", "Create 500 items")
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3)
        while (posts() < start + 500 && System.nanoTime() < deadline) Thread.sleep(500)
        Thread.sleep(1_500)
        // The result line sits below the fold of the Diagnostics tab, out of a UI dump; the sample logs it too.
        val logged = run(adb, "logcat", "-d", "-v", "raw", "-s", "HopiSample:I")
        return Regex("Created \\d+ of 500 items \\(\\d+ failed\\) in \\d+ ms").find(logged)?.value ?: "no result line"
    }

    /** Samples the app's bridge connections every 250 ms on the host (never during the idle window). */
    private inner class ConnectionSampler : AutoCloseable {
        val max = AtomicInteger(0)
        val samples = AtomicInteger(0)

        @Volatile
        var pause = false
        private val running = AtomicBoolean(true)
        private var worker: Thread? = null

        fun start() {
            worker = thread(isDaemon = true, name = "connection-sampler") {
                while (running.get()) {
                    if (!pause) {
                        max.accumulateAndGet(bridgeConnections(), ::maxOf)
                        samples.incrementAndGet()
                    }
                    Thread.sleep(250)
                }
            }
        }

        override fun close() {
            running.set(false)
            worker?.join()
        }
    }

    @Suppress("DEPRECATION")
    private class RecordingClipboard : ClipboardManager {
        @Volatile
        var text: String? = null

        override fun getText(): AnnotatedString? = text?.let(::AnnotatedString)

        override fun setText(annotatedString: AnnotatedString) {
            text = annotatedString.text
        }
    }

    private companion object {
        const val PKG = "dev.koga.hopi.sample"
    }
}
