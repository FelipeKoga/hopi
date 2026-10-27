package dev.koga.hopi.ui.perf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfStartRequest
import dev.koga.hopi.ui.FakePerfController
import kotlin.test.Test

/**
 * The HUD over a controller that reports its running set (docs/design/perf-monitors.md, "In-app"):
 * a stop takes its figures off at once, with no item to bring the news.
 */
@OptIn(ExperimentalTestApi::class)
class PerfHudTest {
    private fun ComposeUiTest.waitForHud(text: String) = waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aStopTakesItsFiguresOffAndNoneRunningReadsOff() = runComposeUiTest {
        val controller = FakePerfController()
        controller.start(PerfStartRequest(setOf(PerfMonitor.CPU, PerfMonitor.MEMORY)), PerfOwner.Code)
        var shown by mutableStateOf(true)
        setContent { if (shown) PerfHud(controller) }
        waitForHud("perf: waiting for data")
        controller.sample(1_700_000_000_000, cpu = 12.4, residentMb = 142)
        waitForHud("CPU 12% · 142 MB")

        // A CLI `perf stop --cpu`: the CPU figure goes without waiting for the next sample.
        controller.stop(setOf(PerfMonitor.CPU))
        waitForHud("142 MB")
        // The last monitor stops: nothing will ever update the HUD again, and it says so.
        controller.stop(emptySet())
        waitForHud("perf: off")

        // Hidden: it listens to nothing, the running set included.
        shown = false
        waitUntil(timeoutMillis = 5_000) { !controller.hasRunningListeners && !controller.data.hasListeners }
    }

    @Test
    fun shownWithNothingRunningItReadsOffAtOnce() = runComposeUiTest {
        val controller = FakePerfController()
        setContent { PerfHud(controller) }
        waitForHud("perf: off")
        controller.start(PerfStartRequest(setOf(PerfMonitor.CPU)), PerfOwner.Code)
        waitForHud("perf: waiting for data")
    }
}
