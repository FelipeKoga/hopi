package dev.koga.hopi.perf.internal

import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfPss
import dev.koga.hopi.core.perf.PerfResult
import dev.koga.hopi.core.perf.PerfStartRequest
import dev.koga.hopi.core.perf.PerfThread
import dev.koga.hopi.core.perf.PerfTimer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A monitor the backend could not start (frames without the Application) is not reported running. */
class UnstartedMonitorTest {
    private class NoFramesBackend : MonitorBackend {
        override val platform = "test"
        override val available = setOf(PerfMonitor.FRAMES, PerfMonitor.CPU)
        val stopped = ArrayList<PerfMonitor>()

        override fun nowMs(): Long = 0

        override fun attach(data: PerfData, lease: PerfTimer) = Unit

        override fun start(monitor: PerfMonitor, settings: PerfSettings): String? =
            if (monitor == PerfMonitor.FRAMES) "unavailable without the Application" else null

        override fun started(monitor: PerfMonitor): Boolean = monitor != PerfMonitor.FRAMES

        override fun stop(monitor: PerfMonitor) {
            stopped += monitor
        }

        override fun apply(settings: PerfSettings, running: Set<PerfMonitor>) = Unit

        override fun details(monitor: PerfMonitor): List<Pair<String, Any?>> = emptyList()

        override fun leaseChanged(running: Set<PerfMonitor>) = Unit

        override fun threads(limit: Int): List<PerfThread>? = null

        override fun watchThreads(watching: Boolean) = Unit

        override fun measurePss(): PerfPss = PerfPss(null, 0, "test")
    }

    @Test
    fun aMonitorThatDidNotStartIsGivenBack() {
        val backend = NoFramesBackend()
        val controller = PerfRuntimeController(backend)
        val announced = ArrayList<Set<PerfMonitor>>()
        controller.addRunningListener { announced += it }

        val result = controller.start(PerfStartRequest(setOf(PerfMonitor.FRAMES, PerfMonitor.CPU)), PerfOwner.Lease(60))

        val status = assertIs<PerfResult.Ok>(result).status
        assertEquals(listOf(PerfMonitor.CPU), status.running.map { it.monitor })
        assertTrue(status.notices.any { it == "frames: not started" }, status.notices.toString())
        assertEquals(setOf(PerfMonitor.CPU), controller.running)
        assertEquals(listOf(PerfMonitor.FRAMES), backend.stopped, "its partial start is undone")
        assertEquals(setOf(PerfMonitor.CPU), announced.last())
    }
}
