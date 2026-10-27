package dev.koga.hopi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.koga.hopi.core.perf.PerfHook
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfStartRequest
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ui.list.ListTags
import dev.koga.hopi.ui.perf.HUD_DESCRIPTION
import dev.koga.hopi.ui.perf.PerfHudState
import dev.koga.hopi.ui.perf.PerfTags
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The in-app Perf tab and HUD over a perf controller registered in core's PerfHook (docs/design/perf-monitors.md, "In-app"). */
@OptIn(ExperimentalTestApi::class)
class PerfUiTest {
    private val store = HopiStore(scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @BeforeTest
    fun setUp() {
        PerfHook.reset()
        PerfHudState.shown = false
        PerfHudState.cadence = false
    }

    @AfterTest
    fun tearDown() {
        PerfHook.reset()
        PerfHudState.shown = false
        store.close()
    }

    private fun SemanticsNodeInteractionsProvider.exists(text: String): Boolean = onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun SemanticsNodeInteractionsProvider.hud(): Boolean = onAllNodes(hasContentDescription(HUD_DESCRIPTION)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.waitForText(text: String) = waitUntil(timeoutMillis = 5_000) { exists(text) }

    /** Scrolls the Perf tab's list to [matcher]: a lazy list composes only what fits. */
    private fun ComposeUiTest.reveal(matcher: SemanticsMatcher) {
        onAllNodes(hasScrollToNodeAction()).onLast().performScrollToNode(matcher)
    }

    @Test
    fun withoutAControllerThereIsNoPerfButtonAndNoHud() = runComposeUiTest {
        setContent { HopiLauncher(store = { store }) { Box(Modifier.fillMaxSize()) } }
        // Without hopi-perf the button is a plain one: a long press opens Hopi, as a tap does.
        onNodeWithContentDescription(LAUNCHER_DESCRIPTION).performTouchInput { longClick() }
        waitForText("No requests yet")
        assertFalse(PerfHudState.shown)
        assertFalse(hud())
        assertTrue(onAllNodes(hasContentDescription(ListTags.PERF)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun thePerfTabStartsInCodeShowsHangsAndFlagsTheInspector() = runComposeUiTest {
        val controller = FakePerfController().also { PerfHook.register(it) }
        controller.sample(1_700_000_000_000, cpu = 12.5, residentMb = 140)
        var shown by mutableStateOf(true)
        setContent { if (shown) HopiScreen(store, onClose = {}) }
        waitForIdle()
        assertTrue(controller.data.inspectorOpen, "frames drawn under the inspector are left out")

        onNodeWithContentDescription(ListTags.PERF).performClick()
        waitForText("not available on jvm")
        waitForText("avg 12.5% · max 12.5%")
        onNodeWithContentDescription(PerfTags.monitor("cpu")).assertIsOff().performClick()
        waitUntil(timeoutMillis = 5_000) { controller.held(PerfMonitor.CPU) }
        assertIs<PerfOwner.Code>(controller.starts.last().second, "started in the app, as HopiPerf.start would")
        waitForText("started in the app")
        onNodeWithContentDescription(PerfTags.monitor("cpu")).assertIsOn()

        val hang = controller.hang(612)
        waitForText("#${hang.seq}  612 ms")
        reveal(hasText("#${hang.seq}  612 ms", substring = true))
        onNodeWithText("#${hang.seq}  612 ms", substring = true).performClick()
        waitForText("  at dev.koga.sample.PerfLab.frame11(PerfLab.kt:12)")

        // The thread list scans only while shown.
        reveal(hasContentDescription(PerfTags.THREADS))
        onNodeWithContentDescription(PerfTags.THREADS).performClick()
        waitUntil(timeoutMillis = 5_000) { controller.watchingThreads }
        controller.sample(1_700_000_001_000, cpu = 20.0, residentMb = 141)
        waitForText("42.5%  main")
        reveal(hasContentDescription(PerfTags.THREADS))
        onNodeWithContentDescription(PerfTags.THREADS).performClick()
        waitUntil(timeoutMillis = 5_000) { !controller.watchingThreads }

        reveal(hasText(PerfTags.PSS))
        onNodeWithText(PerfTags.PSS).performClick()
        waitForText("PSS 53.0 MB (12 ms)")

        reveal(hasContentDescription(PerfTags.monitor("cpu")))
        onNodeWithContentDescription(PerfTags.monitor("cpu")).performClick()
        waitUntil(timeoutMillis = 5_000) { !controller.held(PerfMonitor.CPU) }

        shown = false
        waitForIdle()
        assertFalse(controller.data.inspectorOpen)
        // Closed: the tab listens to nothing, the running set included.
        waitUntil(timeoutMillis = 5_000) { !controller.hasRunningListeners && !controller.data.hasListeners }
    }

    @Test
    fun aMonitorStoppedFromOutsideTurnsItsSwitchOffWhileTheTabIsOpen() = runComposeUiTest {
        val controller = FakePerfController().also { PerfHook.register(it) }
        setContent { HopiScreen(store, onClose = {}) }
        onNodeWithContentDescription(ListTags.PERF).performClick()
        waitForText("not available on jvm")
        onNodeWithContentDescription(PerfTags.monitor("cpu")).assertIsOff().performClick()
        waitUntil(timeoutMillis = 5_000) { controller.held(PerfMonitor.CPU) }
        // The switch follows the rebuilt model, which comes after the start returned.
        waitForText("started in the app")
        onNodeWithContentDescription(PerfTags.monitor("cpu")).assertIsOn()

        // A CLI or the desktop stops it through the bridge, with no sample or event to follow.
        controller.stop(setOf(PerfMonitor.CPU))
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithContentDescription(PerfTags.monitor("cpu")).assertIsOff() }.isSuccess
        }
    }

    @Test
    fun aLongPressTogglesTheHudWhichDrawsOnlyWhenItsTextChanges() = runComposeUiTest {
        val controller = FakePerfController().also { PerfHook.register(it) }
        // Over nothing running the HUD reads "perf: off" and drops what arrives.
        controller.start(PerfStartRequest(setOf(PerfMonitor.CPU, PerfMonitor.MEMORY)), PerfOwner.Code)
        var recompositions = 0
        setContent {
            HopiLauncher(store = { store }, recompositionProbe = { recompositions++ }) {
                Box(Modifier.fillMaxSize()) { BasicText("The app") }
            }
        }
        waitForIdle()
        val before = recompositions
        onNodeWithContentDescription(LAUNCHER_DESCRIPTION).performTouchInput { longClick() }
        waitUntil(timeoutMillis = 5_000) { hud() }
        assertTrue(PerfHudState.shown)
        assertEquals(before, recompositions, "showing the HUD recomposed the launcher or its button")
        onNodeWithContentDescription(HUD_DESCRIPTION).assertExists()
        waitForText("perf: waiting for data")

        controller.sample(1_700_000_000_000, cpu = 12.4, residentMb = 142)
        waitForText("CPU 12% · 142 MB")
        // The app keeps every touch: the HUD has no click action of its own.
        onNodeWithText("The app").assertExists()

        onNodeWithContentDescription(LAUNCHER_DESCRIPTION).performTouchInput { longClick() }
        waitUntil(timeoutMillis = 5_000) { !hud() }
        assertFalse(controller.data.hasListeners || controller.hasRunningListeners, "a hidden HUD listens to nothing")
    }
}
