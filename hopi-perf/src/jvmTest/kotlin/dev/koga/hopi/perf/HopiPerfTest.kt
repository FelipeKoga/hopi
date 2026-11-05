package dev.koga.hopi.perf

import dev.koga.hopi.api.HopiRuntimes
import dev.koga.hopi.core.perf.PerfHook
import dev.koga.hopi.core.perf.PerfLogSink
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfResult
import dev.koga.hopi.core.perf.PerfStartRequest
import dev.koga.hopi.core.start.HopiToolContext
import dev.koga.hopi.core.start.StartConfig
import dev.koga.hopi.perf.internal.PerfEntry
import dev.koga.hopi.perf.internal.PerfRuntime
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * HopiPerf on the JVM (CPU and memory only), over the runtime perf's JVM tool registers: off is
 * free, on runs one sampler thread, owners and leases decide when it stops, and a start applies
 * only what its block set. The runtime is process-wide (its controller lives as long as the
 * process), so each test starts the tool on an empty registry and stops everything after.
 */
class HopiPerfTest {
    @BeforeTest
    fun setUp() {
        HopiRuntimes.resetForTests()
        startTool()
    }

    @AfterTest
    fun tearDown() {
        // Through the controller, not HopiPerf.stop(): a test may have emptied the registry.
        PerfHook.controller?.stop(emptySet())
        awaitNoPerfThread()
        HopiRuntimes.resetForTests()
    }

    /** What the start sequence does with perf's JVM tool. */
    private fun startTool(remoteControl: Boolean = true, summaryLog: Boolean = false) {
        assertNull(PerfEntry(remoteControl).start(HopiToolContext(StartConfig(summaryLog = summaryLog), platform = null)), "no note in the start line")
    }

    private fun perfThreads(): List<String> = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("hopi-perf") }.map { it.name }

    private fun awaitNoPerfThread() {
        val deadline = System.nanoTime() + 3_000_000_000
        while (perfThreads().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
    }

    private fun intervalOf(monitor: PerfMonitor): Any? =
        assertNotNull(PerfHook.controller).status().running.single { it.monitor == monitor }.details.toMap()["intervalMs"]

    @Test
    fun theToolStartsNothing() {
        assertEquals(emptyList(), perfThreads(), "no hopi-perf thread while every monitor is off")
        assertEquals(emptySet(), HopiPerf.running)
        assertNotNull(PerfHook.controller, "registered for remote control")
        assertNotNull(HopiRuntimes.perf, "registered as HopiPerf's runtime")
    }

    @Test
    fun startRunsOneSamplerForCpuAndMemoryAndStopEndsIt() {
        HopiPerf.start {
            cpu = true
            memory = true
            sampleIntervalMs = 250
        }
        assertEquals(setOf(HopiMonitor.Cpu, HopiMonitor.Memory), HopiPerf.running)
        assertEquals(listOf("hopi-perf-samp"), perfThreads())
        val controller = assertNotNull(PerfHook.controller)
        // The model is the process's: earlier tests' samples are in it.
        val cpuBefore = controller.data.cpu.count
        val memoryBefore = controller.data.memory.count
        val deadline = System.nanoTime() + 3_000_000_000
        while ((controller.data.cpu.count < cpuBefore + 2 || controller.data.memory.count < memoryBefore + 2) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(controller.data.cpu.count >= cpuBefore + 2, "cpu samples")
        assertTrue(controller.data.memory.count >= memoryBefore + 2, "memory samples")
        HopiPerf.stop(HopiMonitor.Cpu)
        assertEquals(setOf(HopiMonitor.Memory), HopiPerf.running)
        assertEquals(listOf("hopi-perf-samp"), perfThreads(), "memory still samples")
        HopiPerf.stop()
        awaitNoPerfThread()
        assertEquals(emptyList(), perfThreads())
    }

    @Test
    fun aMonitorThePlatformLacksIsANoticeNotAStart() {
        val err = captureStderr {
            HopiPerf.start {
                hangs = true
                frames = true
            }
        }
        assertEquals(emptySet(), HopiPerf.running)
        assertTrue(err.contains("\"perf\":\"notice\"") && err.contains("hangs is not available on jvm"), err)
        val result = assertNotNull(PerfHook.controller).start(PerfStartRequest(setOf(PerfMonitor.HANGS)), PerfOwner.Lease(10))
        assertEquals("UNSUPPORTED", assertIs<PerfResult.Failed>(result).code)
    }

    @Test
    fun invalidValuesStartNothingAndChangeNothing() {
        val err = captureStderr {
            HopiPerf.start {
                cpu = true
                sampleIntervalMs = 10
            }
        }
        assertTrue(err.contains("sampleIntervalMs must be in 250..10000"), err)
        assertEquals(emptySet(), HopiPerf.running)
        assertEquals(emptyList(), perfThreads())
        HopiPerf.start { cpu = true }
        assertEquals(1_000L, intervalOf(PerfMonitor.CPU), "the refused value was not applied")
    }

    @Test
    fun anOutOfRangeStartIsWrittenPastTheSinksCap() {
        val data = assertNotNull(PerfHook.controller).data
        val sunk = CopyOnWriteArrayList<String>()
        // A sink whose clock stands still, so one window, with its 5 lines used up.
        data.setLogSink(PerfLogSink(emit = { sunk += it }, clockMs = { 0L }))
        try {
            repeat(5) { assertNotNull(data.log).notice("filler") }
            assertEquals(5, sunk.size, "the cap is used up")
            val err = captureStderr {
                HopiPerf.start {
                    cpu = true
                    sampleIntervalMs = 10
                }
            }
            assertTrue(err.contains("sampleIntervalMs must be in 250..10000"), err)
            assertEquals(emptySet(), HopiPerf.running)
            assertEquals(emptyList(), perfThreads())
        } finally {
            data.setLogSink(null)
        }
    }

    @Test
    fun onlyWhatABlockSetsApplies() {
        val controller = assertNotNull(PerfHook.controller)
        // A remote start's interval, then a start from code that sets none: the interval stays.
        assertIs<PerfResult.Ok>(controller.start(PerfStartRequest(setOf(PerfMonitor.CPU), sampleIntervalMs = 500), PerfOwner.Lease(60)))
        HopiPerf.start { memory = true }
        assertEquals(500L, intervalOf(PerfMonitor.MEMORY))
        // A block changes what it sets and nothing else: the threshold and the heartbeat of the
        // first block stay through the second, which sets the interval only.
        HopiPerf.start {
            hangThresholdMs = 300
            hangHeartbeat = true
        }
        HopiPerf.start { sampleIntervalMs = 2_000 }
        assertEquals(2_000L, intervalOf(PerfMonitor.CPU))
        assertEquals(2_000L, intervalOf(PerfMonitor.MEMORY))
        val settings = assertNotNull(PerfRuntime.existing()).currentSettings
        assertEquals(300L, settings.hangThresholdMs)
        assertTrue(settings.hangHeartbeat)
        assertEquals(2_000L, settings.sampleIntervalMs)
    }

    @Test
    fun theStartsSummaryLogDecidesPerfsLogLines() {
        val controller = assertNotNull(PerfHook.controller)
        assertNull(controller.data.log, "summaryLog = false: no perf line")
        startTool(summaryLog = true)
        // The tool's settings are applied whole at each of its starts.
        assertNotNull(controller.data.log, "summaryLog = true: perf's rate-limited lines")
        startTool(summaryLog = false)
        assertNull(controller.data.log)
    }

    @Test
    fun remoteControlComesFromTheToolsStart() {
        val controller = assertNotNull(PerfHook.controller)
        assertTrue(controller.remoteControl)
        startTool(remoteControl = false)
        assertFalse(controller.remoteControl)
        HopiPerf.start { cpu = true }
        assertFalse(controller.remoteControl, "no HopiPerf block sets it")
    }

    @Test
    fun startsBeforeTheRegistrationAreDroppedAndCountedInOneNotice() {
        HopiRuntimes.resetForTests()
        HopiPerf.start { cpu = true }
        HopiPerf.start { memory = true }
        assertEquals(emptySet(), HopiPerf.running)
        assertEquals(emptyList(), perfThreads(), "a dropped start starts nothing")
        val first = captureStderr { startTool() }
        assertEquals(1, Regex("starts made before Hopi started").findAll(first).count(), first)
        assertTrue("HopiPerf: 2 starts made before Hopi started were dropped" in first, first)
        assertEquals(emptyList(), perfThreads(), "and the registration starts none of them")
        val second = captureStderr { startTool() }
        assertFalse("before Hopi started" in second, "a later tool start finds it registered: $second")
    }

    @Test
    fun aLabelSetBeforeTheRegistrationReachesTheController() {
        HopiRuntimes.resetForTests()
        HopiPerf.screen("Checkout")
        startTool()
        assertEquals("Checkout", assertNotNull(PerfHook.controller).data.screen)
        HopiPerf.screen(null)
        assertNull(assertNotNull(PerfHook.controller).data.screen)
    }

    @Test
    fun theToolReusesTheProcesssController() {
        val controller = assertNotNull(PerfHook.controller)
        HopiRuntimes.resetForTests()
        startTool()
        assertSame(controller, PerfHook.controller, "one controller per process")
    }

    @Test
    fun aLeaseEndsWithoutAThreadOfItsOwnAndNeverStopsWhatCodeHolds() {
        val controller = assertNotNull(PerfHook.controller)
        controller.start(PerfStartRequest(setOf(PerfMonitor.CPU, PerfMonitor.MEMORY)), PerfOwner.Lease(1))
        HopiPerf.start { memory = true }
        val running = controller.status().running.associate { it.monitor to it.owners }
        assertEquals(listOf("lease"), running[PerfMonitor.CPU])
        assertEquals(listOf("code", "lease"), running[PerfMonitor.MEMORY])
        assertNotNull(controller.status().running.first { it.monitor == PerfMonitor.CPU }.expiresAtMs)
        assertEquals(listOf("hopi-perf-samp"), perfThreads(), "the lease rides the sampler's timed wait")
        val deadline = System.nanoTime() + 4_000_000_000
        while (HopiMonitor.Cpu in HopiPerf.running && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(setOf(HopiMonitor.Memory), HopiPerf.running, "the lease expired; code still holds memory")
        assertEquals(listOf("code"), controller.status().running.single().owners)
    }

    @Test
    fun aTailSessionReleasesOnlyItsOwnOwner() {
        val controller = assertNotNull(PerfHook.controller)
        controller.start(PerfStartRequest(setOf(PerfMonitor.CPU)), PerfOwner.Tail(41))
        controller.start(PerfStartRequest(setOf(PerfMonitor.CPU)), PerfOwner.Tail(42))
        controller.releaseTail(41)
        assertEquals(setOf(HopiMonitor.Cpu), HopiPerf.running)
        controller.releaseTail(42)
        assertEquals(emptySet(), HopiPerf.running)
    }

    /** What a viewer of the figures learns a stop from: no item says it, so the controller does. */
    @Test
    fun everyChangeOfTheRunningSetIsAnnouncedOnceInOrder() {
        val controller = assertNotNull(PerfHook.controller)
        val announced = CopyOnWriteArrayList<Set<PerfMonitor>>()
        val remove = controller.addRunningListener { announced += it }
        controller.start(PerfStartRequest(setOf(PerfMonitor.CPU)), PerfOwner.Tail(41))
        // Another owner of a running monitor, or one leaving while another holds it: no change.
        controller.start(PerfStartRequest(setOf(PerfMonitor.CPU)), PerfOwner.Tail(42))
        controller.releaseTail(41)
        controller.start(PerfStartRequest(setOf(PerfMonitor.MEMORY)), PerfOwner.Lease(1))
        controller.releaseTail(42)
        // The lease's expiry, on the sampler's thread.
        val deadline = System.nanoTime() + 4_000_000_000
        while (announced.last().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
        HopiPerf.start { cpu = true }
        HopiPerf.stop()
        remove()
        HopiPerf.start { cpu = true }
        assertEquals(
            listOf(
                emptySet<PerfMonitor>(),
                setOf(PerfMonitor.CPU),
                setOf(PerfMonitor.CPU, PerfMonitor.MEMORY),
                setOf(PerfMonitor.MEMORY),
                emptySet(),
                setOf(PerfMonitor.CPU),
                emptySet(),
            ),
            announced.toList(),
        )
    }

    @Test
    fun aStartWithSettingsAppliesThemAndReportsTheValuesInForce() {
        val controller = assertNotNull(PerfHook.controller)
        val ok = assertIs<PerfResult.Ok>(controller.start(PerfStartRequest(setOf(PerfMonitor.CPU), sampleIntervalMs = 500), PerfOwner.Lease(60)))
        assertEquals(listOf("intervalMs" to 500L), ok.status.running.single().details)
        assertIs<PerfResult.Ok>(controller.start(PerfStartRequest(setOf(PerfMonitor.CPU)), PerfOwner.Lease(60)))
        assertEquals(listOf("intervalMs" to 500L), controller.status().running.single().details, "a start without settings keeps them")
    }

    private fun captureStderr(block: () -> Unit): String {
        val previous = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setErr(previous)
        }
        return buffer.toString()
    }
}
