package dev.koga.hopi.perf.internal

import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfPss
import dev.koga.hopi.core.perf.PerfThread
import dev.koga.hopi.core.perf.PerfTimer
import kotlin.test.Test
import kotlin.test.assertEquals

/** The per-thread scan is counted: one viewer leaving (a tail ending) never stops it for another (the Perf tab). */
class ThreadWatchersTest {
    private class RecordingBackend : MonitorBackend {
        override val platform = "test"
        override val available = setOf(PerfMonitor.CPU)
        val calls = ArrayList<Boolean>()

        override fun nowMs(): Long = 0

        override fun attach(data: PerfData, lease: PerfTimer) = Unit

        override fun start(monitor: PerfMonitor, settings: PerfSettings): String? = null

        override fun stop(monitor: PerfMonitor) = Unit

        override fun apply(settings: PerfSettings, running: Set<PerfMonitor>) = Unit

        override fun details(monitor: PerfMonitor): List<Pair<String, Any?>> = emptyList()

        override fun leaseChanged(running: Set<PerfMonitor>) = Unit

        override fun threads(limit: Int): List<PerfThread>? = null

        override fun watchThreads(watching: Boolean) {
            calls += watching
        }

        override fun measurePss(): PerfPss = PerfPss(null, 0, "test")
    }

    @Test
    fun theScanRunsWhileAnyViewerIsLeft() {
        val backend = RecordingBackend()
        val controller = PerfRuntimeController(backend)
        controller.watchThreads(true)
        controller.watchThreads(true)
        controller.watchThreads(false)
        assertEquals(listOf(true), backend.calls, "the second viewer keeps the scan")
        controller.watchThreads(false)
        assertEquals(listOf(true, false), backend.calls)
        // An unmatched false never goes below zero nor stops what is not running.
        controller.watchThreads(false)
        controller.watchThreads(true)
        assertEquals(listOf(true, false, true), backend.calls)
    }
}
