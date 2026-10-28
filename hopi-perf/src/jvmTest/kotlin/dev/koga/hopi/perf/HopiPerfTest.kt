package dev.koga.hopi.perf

import dev.koga.hopi.core.perf.PerfHook
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfResult
import dev.koga.hopi.core.perf.PerfStartRequest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HopiPerf on the JVM (CPU and memory only): off is free, on runs one sampler thread, owners and
 * leases decide when it stops. The runtime is process-wide, so each test stops everything.
 */
class HopiPerfTest {
    @AfterTest
    fun tearDown() {
        HopiPerf.stop()
        awaitNoPerfThread()
    }

    private fun perfThreads(): List<String> = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("hopi-perf") }.map { it.name }

    private fun awaitNoPerfThread() {
        val deadline = System.nanoTime() + 3_000_000_000
        while (perfThreads().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
    }

    @Test
    fun installStartsNothing() {
        HopiPerf.install { summaryLog = false }
        assertEquals(emptyList(), perfThreads(), "no hopi-perf thread while every monitor is off")
        assertEquals(emptySet(), HopiPerf.running)
        assertNotNull(PerfHook.controller, "registered for remote control")
    }

    @Test
    fun startRunsOneSamplerForCpuAndMemoryAndStopEndsIt() {
        HopiPerf.start {
            cpu = true
            memory = true
            sampleIntervalMs = 250
            summaryLog = false
        }
        assertEquals(setOf(HopiMonitor.Cpu, HopiMonitor.Memory), HopiPerf.running)
        assertEquals(listOf("hopi-perf-samp"), perfThreads())
        val controller = assertNotNull(PerfHook.controller)
        val deadline = System.nanoTime() + 3_000_000_000
        while (controller.data.cpu.count < 2 && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(controller.data.cpu.count >= 2, "cpu samples")
        assertTrue(controller.data.memory.count >= 2, "memory samples")
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
    fun invalidValuesStartNothing() {
        val err = captureStderr {
            HopiPerf.start {
                cpu = true
                sampleIntervalMs = 10
            }
        }
        assertTrue(err.contains("sampleIntervalMs must be in 250..10000"), err)
        assertEquals(emptySet(), HopiPerf.running)
        assertEquals(emptyList(), perfThreads())
    }

    @Test
    fun aLeaseEndsWithoutAThreadOfItsOwnAndNeverStopsWhatCodeHolds() {
        HopiPerf.install { summaryLog = false }
        val controller = assertNotNull(PerfHook.controller)
        controller.start(PerfStartRequest(setOf(PerfMonitor.CPU, PerfMonitor.MEMORY)), PerfOwner.Lease(1))
        HopiPerf.start {
            memory = true
            summaryLog = false
        }
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
        HopiPerf.install { summaryLog = false }
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
        HopiPerf.install { summaryLog = false }
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
        HopiPerf.start {
            cpu = true
            summaryLog = false
        }
        HopiPerf.stop()
        remove()
        HopiPerf.start {
            cpu = true
            summaryLog = false
        }
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
        HopiPerf.install { summaryLog = false }
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
