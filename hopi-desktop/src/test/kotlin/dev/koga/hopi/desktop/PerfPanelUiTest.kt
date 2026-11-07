package dev.koga.hopi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.koga.hopi.core.perf.PerfHook
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfThread
import dev.koga.hopi.core.perf.StallEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The perf panel against a real JVM bridge and an app-side controller in this process: the tests
 * read the controller to see what the app was asked; the panel only ever reads the session.
 */
@OptIn(ExperimentalTestApi::class)
class PerfPanelUiTest {
    private val root: Path = Files.createTempDirectory("hopi-perf-ui")
    private val closeables = mutableListOf<AutoCloseable>()

    @BeforeTest
    fun setUp() = PerfHook.reset()

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { it.close() }
        PerfHook.reset()
        root.toFile().deleteRecursively()
    }

    private fun SemanticsNodeInteractionsProvider.exists(text: String): Boolean =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.waitForText(text: String) = waitUntil(timeoutMillis = 15_000) { exists(text) }

    private fun ComposeUiTest.connected(app: FakeApp): PerfPanelState {
        val viewer = Viewer(jvmOnlyTargets(), jvmBridgeRoot = root).also { closeables += it }
        viewer.start()
        lateinit var perf: PerfPanelState
        setContent {
            perf = remember { PerfPanelState() }
            Box(Modifier.fillMaxSize()) {
                ViewerContent(viewer, chooseExportFile = { _, _ -> root.resolve("x.har") }, perf = perf)
            }
        }
        waitForText("JVM ${app.bridgeFile.parent.fileName}")
        onNodeWithTag(ViewerTags.CONNECT).performClick()
        waitForText("Connected to $APP")
        return perf
    }

    private fun ComposeUiTest.openPanel() {
        onNodeWithTag(ViewerTags.ROOT).performKeyInput { withKeyDown(if (IS_MAC) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.P) } }
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasTestTag(PerfTags.PANEL)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun switchesChartsHangsAndThePanelHoldsWhatItStarted() = runComposeUiTest {
        val controller = FakePerfController().also { PerfHook.register(it) }
        controller.sample(System.currentTimeMillis() - 2_000, cpu = 12.5, residentMb = 140)
        val app = FakeApp(root.resolve("1")).also { closeables += it }
        val perf = connected(app)
        assertTrue(controller.starts.isEmpty(), "nothing is asked of the app before the panel opens")
        openPanel()
        // The backfill: what the app held before the panel opened.
        waitForText("12.5% (Hopi 0.5%)")
        waitForText("resident 140.0 MB")
        onNodeWithText("not available on jvm").assertExists()
        onNodeWithContentDescription(PerfTags.toggle("hangs")).assertIsOff()

        onNodeWithContentDescription(PerfTags.toggle("hangs")).performClick()
        waitFor { controller.held(PerfMonitor.HANGS) }
        assertIs<PerfOwner.Tail>(controller.starts.last().second)
        waitForText("a stream (this panel or an agent)")
        onNodeWithContentDescription(PerfTags.toggle("hangs")).assertIsOn()

        val hang = controller.hang(612)
        waitForText("#${hang.seq}  612 ms")
        onNodeWithText("#${hang.seq}  612 ms", substring = true).performScrollTo().performClick()
        waitForText("  at dev.koga.sample.PerfLab.frame11(PerfLab.kt:12)")
        controller.sample(System.currentTimeMillis(), cpu = 48.0, residentMb = 150)
        waitForText("48.0% (Hopi 0.5%)")
        screenshot("viewer-perf.png")

        // The screen asks for the threads by itself; the list is what each used between two scans.
        assertTrue(controller.watchingThreads)
        controller.threadList = listOf(PerfThread(7, "main", 1_120, 42.5, false), PerfThread(9, "hopi-perf-samp", 3, 0.1, true))
        controller.sample(System.currentTimeMillis() + 1_000, cpu = 20.0, residentMb = 150)
        waitForText("  main")

        // Closing the panel closes its stream: the app drops what only the panel held.
        perf.open = false
        // The UI clock must run for the panel to leave the composition.
        waitUntil(timeoutMillis = 15_000) { !controller.held(PerfMonitor.HANGS) && !controller.watchingThreads }
    }

    /** The recorte: a drag over one chart selects a stretch of every series, a hang's Inspect jumps to its own. */
    @Test
    fun aDragSelectsAStretchAndTheSideSumsItUp() = runComposeUiTest {
        val controller = FakePerfController().also { PerfHook.register(it) }
        val app = FakeApp(root.resolve("3")).also { closeables += it }
        connected(app)
        openPanel()
        waitFor { controller.watchingThreads }
        val end = System.currentTimeMillis()
        // Thirty seconds of quiet, one peak at the middle of the last minute, on the GC thread.
        var gc = 0L
        var main = 0L
        for (second in 60 downTo 0) {
            val peak = second in 28..32
            gc += if (peak) 800 else 10
            main += 50
            controller.threadList = listOf(PerfThread(7, "main", main, 1.0, false), PerfThread(11, "Main GC thread", gc, 1.0, false))
            controller.sample(end - second * 1000, cpu = if (peak) 85.0 else 6.0, residentMb = if (second > 30) 180 else 196)
            Thread.sleep(5)
        }
        waitForText("6.0% (Hopi 0.5%)")
        controller.data.recordStall(StallEvent(controller.data.nextSeq(), end - 30_000, 260, null))
        waitFor { controller.data.nextSeq() > 0 }
        onNodeWithText("1 min").performClick()
        waitForIdle()
        screenshot("viewer-perf-timeline.png")

        onNodeWithTag(PerfTags.CPU_CHART).performMouseInput {
            moveTo(Offset(width * 0.40f, height / 2f))
            press()
            moveTo(Offset(width * 0.60f, height / 2f))
            release()
        }
        onNodeWithTag(PerfTags.SELECTION).assertExists()
        waitForText("CPU peak 85.0%")
        waitForText("Memory 180.0 MB to 196.0 MB")
        waitForText("Main GC thread")
        waitForText("Hangs 0, stalls 1")
        screenshot("viewer-perf-selection.png")

        onNodeWithText("Zoom").performClick()
        waitForText("Zoom out")
        onNodeWithText("Live").performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasTestTag(PerfTags.SELECTION)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun anAppWithoutPerfSaysSo() = runComposeUiTest {
        val app = FakeApp(root.resolve("2")).also { closeables += it }
        connected(app)
        openPanel()
        waitForText("This app's Hopi has no perf monitors")
        assertFalse(exists("Frames per second"))
    }

    @Test
    fun withoutRemoteControlTheSwitchesSayWhy() = runComposeUiTest {
        PerfHook.register(FakePerfController(remoteControl = false))
        val app = FakeApp(root.resolve("3")).also { closeables += it }
        connected(app)
        openPanel()
        waitForText("refuses remote starts and stops: remote control is off")
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val directory = System.getProperty("hopi.desktop.screenshots") ?: return
        waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()
        Files.createDirectories(Paths.get(directory))
        ImageIO.write(image, "png", Paths.get(directory, name).toFile())
    }
}
