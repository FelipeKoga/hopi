package dev.koga.hopi.perf.internal

import dev.koga.hopi.core.perf.MemoryEvent
import dev.koga.hopi.core.perf.PerfHook
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfStartRequest
import dev.koga.hopi.perf.HopiMonitor
import dev.koga.hopi.perf.HopiPerf
import dev.koga.hopi.perf.internal.native.hopi_perf_cadence_running
import dev.koga.hopi.perf.internal.native.hopi_perf_footprint
import dev.koga.hopi.perf.internal.native.hopi_perf_port_count
import dev.koga.hopi.perf.internal.native.hopi_perf_process_cpu_ns
import dev.koga.hopi.perf.internal.native.hopi_perf_thread_cpu_ns
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification

/**
 * CPU, memory, the thread scan, memory warnings, the frames-only lease and the tier 2 switch on
 * the simulator.
 */
class IosSamplingTest {
    @BeforeTest
    fun setUp() {
        startPerfTool()
    }

    @AfterTest
    fun tearDown() {
        stopAll()
    }

    @Test
    fun processCpuCountsABusyThread() {
        // Burns by this thread's own CPU clock, so a loaded host that preempts it changes nothing.
        val before = hopi_perf_process_cpu_ns()
        val threadStart = hopi_perf_thread_cpu_ns()
        var spins = 0L
        while (hopi_perf_thread_cpu_ns() - threadStart < 200_000_000) spins++
        burnSink.store(spins)
        val used = (hopi_perf_process_cpu_ns() - before) / 1_000_000
        assertTrue(used in 195..2_000, "200 ms of this thread's CPU counted as $used ms of the process's")
    }

    @Test
    fun footprintGrowsByTouchedMemory() {
        val size = 64 * 1024 * 1024
        val before = hopi_perf_footprint()
        val block = nativeHeap.allocArray<ByteVar>(size)
        try {
            for (offset in 0 until size step 4096) block[offset] = 1
            val grown = hopi_perf_footprint() - before
            assertTrue(grown in (size * 9L / 10)..(size * 11L / 10), "footprint grew by ${grown / 1024 / 1024} MB for 64 MB touched")
        } finally {
            nativeHeap.free(block)
        }
    }

    @Test
    fun theThreadScanLeaksNoPort() {
        IosThreadScan().scan()
        val before = hopi_perf_port_count()
        repeat(10_000) { IosThreadScan().scan() }
        val after = hopi_perf_port_count()
        assertTrue(after - before <= 8, "ports before $before, after 10 000 scans $after")
    }

    @Test
    fun oneSamplerForCpuAndMemoryNamedAndCounted() {
        HopiPerf.start {
            cpu = true
            memory = true
            sampleIntervalMs = 250
        }
        assertTrue(runMainLoopUntil(1_000) { perfThreads().isNotEmpty() })
        assertEquals(listOf("hopi-perf-samp"), perfThreads())
        val data = perfData()
        val cpuBefore = data.cpu.count
        assertTrue(runMainLoopUntil(3_000) { data.cpu.count >= cpuBefore + 2 && data.memory.count >= 2 }, "samples")
        val memory = assertNotNull(data.summary().memoryLast)
        assertTrue((memory.residentBytes ?: 0) > 1024 * 1024, "footprint ${memory.residentBytes}")
        val threads = assertNotNull(PerfHook.controller).threads(50).orEmpty()
        assertTrue(threads.any { it.name == "hopi-perf-samp" && it.hopi }, "the sampler is listed as Hopi's: ${threads.map { it.name }}")
        HopiPerf.stop(HopiMonitor.Cpu)
        assertEquals(listOf("hopi-perf-samp"), perfThreads(), "memory still samples")
        HopiPerf.stop(HopiMonitor.Memory)
        assertTrue(runMainLoopUntil(2_000) { perfThreads().isEmpty() }, "the sampler ended")
    }

    @Test
    fun memoryWarningsAreEventsOnlyWhileMemoryRuns() {
        HopiPerf.start { memory = true }
        val data = perfData()
        val since = data.lastSeq
        NSNotificationCenter.defaultCenter.postNotificationName(UIApplicationDidReceiveMemoryWarningNotification, null)
        val events = data.eventsSince(since).filterIsInstance<MemoryEvent>()
        assertEquals(listOf("warning"), events.map { it.event })
        HopiPerf.stop(HopiMonitor.Memory)
        NSNotificationCenter.defaultCenter.postNotificationName(UIApplicationDidReceiveMemoryWarningNotification, null)
        assertEquals(1, data.eventsSince(since).filterIsInstance<MemoryEvent>().size, "no observer after stop")
    }

    @Test
    fun aFramesOnlyLeaseExpiresWithNoThread() {
        val controller = assertNotNull(PerfHook.controller)
        controller.start(PerfStartRequest(setOf(PerfMonitor.FRAMES)), PerfOwner.Lease(1))
        assertEquals(setOf(HopiMonitor.Frames), HopiPerf.running)
        assertEquals(emptyList(), perfThreads())
        assertTrue(runMainLoopUntil(3_000) { HopiPerf.running.isEmpty() }, "the lease ended frames: ${HopiPerf.running}")
    }

    @Test
    fun cadenceRunsOnlyWhileFramesRunsAndAViewerAsks() {
        HopiPerf.start { frames = true }
        val controller = assertNotNull(PerfHook.controller)
        assertFalse(hopi_perf_cadence_running(), "showing frames does not start the display link")
        controller.watchCadence(true)
        assertTrue(hopi_perf_cadence_running())
        runMainLoop(0.2)
        controller.watchCadence(false)
        assertFalse(hopi_perf_cadence_running(), "the last viewer gone")
        controller.watchCadence(true)
        HopiPerf.stop(HopiMonitor.Frames)
        assertFalse(hopi_perf_cadence_running(), "frames stopped")
        controller.watchCadence(false)
        runMainLoop(0.1)
    }

    @Test
    fun launchArgumentsNameMonitors() {
        assertEquals(setOf(PerfMonitor.FRAMES, PerfMonitor.HANGS), parseLaunchMonitors("frames, Hangs,bogus"))
        assertEquals(emptySet(), parseLaunchMonitors(""))
    }
}
