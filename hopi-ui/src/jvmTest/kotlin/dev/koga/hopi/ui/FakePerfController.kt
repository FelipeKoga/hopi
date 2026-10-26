package dev.koga.hopi.ui

import dev.koga.hopi.core.perf.CpuSample
import dev.koga.hopi.core.perf.HangEvent
import dev.koga.hopi.core.perf.MemorySample
import dev.koga.hopi.core.perf.PerfController
import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfMonitorStatus
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfOwners
import dev.koga.hopi.core.perf.PerfPss
import dev.koga.hopi.core.perf.PerfResult
import dev.koga.hopi.core.perf.PerfStartRequest
import dev.koga.hopi.core.perf.PerfStatus
import dev.koga.hopi.core.perf.PerfThread

/** An app's perf side without monitors: owners kept as the real runtime keeps them, data written by the test. */
internal class FakePerfController(override val remoteControl: Boolean = true) : PerfController {
    override val platform = "jvm"
    override val available = setOf(PerfMonitor.CPU, PerfMonitor.MEMORY, PerfMonitor.HANGS)
    override val data = PerfData()
    private val owners = PerfOwners()
    val starts: MutableList<Pair<PerfStartRequest, PerfOwner>> = java.util.Collections.synchronizedList(ArrayList())
    @Volatile var watchingThreads = false

    override fun start(request: PerfStartRequest, owner: PerfOwner): PerfResult = synchronized(this) {
        starts += request to owner
        request.monitors.forEach { owners.add(it, owner, 0) }
        PerfResult.Ok(status())
    }

    override fun stop(monitors: Set<PerfMonitor>): PerfStatus = synchronized(this) {
        monitors.ifEmpty { PerfMonitor.entries.toSet() }.forEach { owners.clear(it) }
        status()
    }

    override fun releaseTail(session: Long) {
        synchronized(this) { owners.removeTail(session) }
    }

    fun held(monitor: PerfMonitor): Boolean = synchronized(this) { owners.held(monitor) }

    override fun status(): PerfStatus = synchronized(this) {
        PerfStatus(
            platform, available.toList(), remoteControl,
            owners.heldMonitors.map { PerfMonitorStatus(it, 1_000, owners.describe(it), null, listOf("thresholdMs" to 250L)) },
        )
    }

    override fun threads(limit: Int): List<PerfThread> = listOf(PerfThread(7, "main", 120, 42.5, false), PerfThread(9, "hopi-perf-samp", 3, 0.1, true))

    override fun watchThreads(watching: Boolean) {
        watchingThreads = watching
    }

    override fun measurePss(): PerfPss = PerfPss(54_321, 12)

    fun sample(atMs: Long, cpu: Double, residentMb: Long) {
        data.recordSample(
            atMs,
            CpuSample(cpu, 0.5, 8),
            MemorySample(40L shl 20, 256L shl 20, 10L shl 20, residentMb shl 20, null, 3, 1, perturbed = false),
        )
    }

    fun hang(ms: Long, ended: Boolean = true): HangEvent {
        val event = HangEvent(
            seq = data.nextSeq(), atMs = 1_700_000_000_000, thresholdMs = 250, ended = ended, durationMs = ms, screen = "CheckoutActivity",
            stacks = listOf(List(12) { "dev.koga.sample.PerfLab.frame$it(PerfLab.kt:${it + 1})" }), captureUs = listOf(800),
            stackMissed = false, debugger = false, frozenMs = 0, heartbeat = false,
        )
        // As the detector does: written at detection, completed at the end (which the summary counts).
        data.recordHang(if (ended) event.copy(ended = false) else event)
        if (ended) data.updateHang(event)
        return event
    }
}
