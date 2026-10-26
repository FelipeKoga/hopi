package dev.koga.hopi.perf.internal

import dev.koga.hopi.core.perf.HangDetector
import dev.koga.hopi.core.perf.HangPlatform
import dev.koga.hopi.core.perf.HangWatchdog
import dev.koga.hopi.core.perf.MainBusy
import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfJson
import dev.koga.hopi.core.perf.PerfLimits
import dev.koga.hopi.core.perf.PerfTimer
import dev.koga.hopi.core.perf.emitPerfLogLine
import dev.koga.hopi.perf.internal.native.hopi_perf_busy_begin
import dev.koga.hopi.perf.internal.native.hopi_perf_busy_load
import dev.koga.hopi.perf.internal.native.hopi_perf_capture_main
import dev.koga.hopi.perf.internal.native.hopi_perf_debugger_attached
import dev.koga.hopi.perf.internal.native.hopi_perf_now_ms
import dev.koga.hopi.perf.internal.native.hopi_perf_now_ns
import dev.koga.hopi.perf.internal.native.hopi_perf_set_hang_ongoing
import dev.koga.hopi.perf.internal.native.hopi_perf_symbolicate
import dev.koga.hopi.perf.internal.native.hopi_perf_take_last_end
import dev.koga.hopi.perf.internal.native.hopi_perf_watchdog_attach
import dev.koga.hopi.perf.internal.native.hopi_perf_watchdog_await
import dev.koga.hopi.perf.internal.native.hopi_perf_watchdog_create
import dev.koga.hopi.perf.internal.native.hopi_perf_watchdog_detach
import dev.koga.hopi.perf.internal.native.hopi_perf_watchdog_doze
import dev.koga.hopi.perf.internal.native.hopi_perf_watchdog_park
import dev.koga.hopi.perf.internal.native.hopi_perf_watchdog_release
import dev.koga.hopi.perf.internal.native.hopi_perf_watchdog_unparked
import dev.koga.hopi.perf.internal.native.hopi_perf_watchdog_wake
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSQualityOfServiceUserInitiated
import platform.Foundation.NSThread

/**
 * The main thread's busy state as the run-loop observers publish it in C (hopi_perf.h), read by
 * core's [HangDetector] unchanged, and this watchdog's own semaphore: the observers signal it on
 * an idle-to-busy transition while it is parked and at a hang's end; [wake] signals it directly.
 */
internal class NativeBusy(private val semaphore: COpaquePointer) : MainBusy {
    override fun load(): Long = hopi_perf_busy_load().toLong()

    override fun begin(nowMs: Long) {
        hopi_perf_busy_begin(nowMs)
    }

    override fun parkWatchdog(): Boolean = hopi_perf_watchdog_park()

    override fun unparkedWatchdog() {
        hopi_perf_watchdog_unparked()
    }

    override fun setHangOngoing(ongoing: Boolean) {
        hopi_perf_set_hang_ongoing(ongoing)
    }

    override fun takeLastEnd(): Long = hopi_perf_take_last_end()

    // In a C call the thread is in the native state, so the Kotlin/Native GC never waits on it.
    override fun await() {
        hopi_perf_watchdog_await(semaphore)
    }

    override fun doze(nanos: Long) {
        hopi_perf_watchdog_doze(semaphore, nanos)
    }

    override fun wake() {
        hopi_perf_watchdog_wake(semaphore)
    }
}

/**
 * The iOS hang monitor (docs/design/perf-monitors.md, "Hangs"): the run-loop observers (shared
 * with frames, installed by [IosBackend]) publish the busy state, and the watchdog thread
 * `hopi-perf-hang` at the user-initiated QoS runs core's [HangWatchdog] over it, asleep with no
 * timeout while the main thread is idle. A hang's stacks are captured in C (suspend, registers,
 * frame-pointer walk, resume), recorded as raw addresses and then named with `dladdr` on this
 * thread. [stop] returns at once.
 */
internal class IosHangMonitor(
    data: PerfData,
    thresholdMs: Long,
    lease: PerfTimer,
    private val selfCpu: IosSelfCpu,
) {
    private val semaphore: COpaquePointer = hopi_perf_watchdog_create() ?: error("no semaphore")
    private val busy = NativeBusy(semaphore)

    // Watchdog thread only: the capture's output and the name buffer, allocated once, freed when it ends.
    private val addresses = nativeHeap.allocArray<ULongVar>(PerfLimits.MAX_STACK_FRAMES)
    private val suspendedNs = nativeHeap.alloc<LongVar>()
    private val name = nativeHeap.allocArray<ByteVar>(PerfLimits.MAX_FRAME_CHARS)
    private val cpuState = LongArray(1)

    /** The suspension of the latest capture, nanoseconds (tests). */
    var lastSuspendedNs: Long = 0
        private set

    private val platform = object : HangPlatform {
        override fun nowMs(): Long = hopi_perf_now_ms()

        override fun monotonicNs(): Long = hopi_perf_now_ns()

        override fun captureMainStack(): List<String> {
            val count = hopi_perf_capture_main(addresses, PerfLimits.MAX_STACK_FRAMES, suspendedNs.ptr)
            lastSuspendedNs = suspendedNs.value
            if (count <= 0) return emptyList()
            return List(count) { raw(addresses[it]) }
        }

        override fun symbolicate(stack: List<String>): List<String> = stack.mapIndexed { index, frame ->
            val address = frame.removePrefix("0x").toULongOrNull(16) ?: return@mapIndexed frame
            // The first frame is the pc; every other one follows a call.
            val length = hopi_perf_symbolicate(address, index > 0, name, PerfLimits.MAX_FRAME_CHARS)
            if (length > 0) name.toKString() else frame
        }

        override fun debuggerAttached(): Boolean = hopi_perf_debugger_attached()

        // iOS has no heartbeat: the observers are the only signal.
        override fun postHeartbeat() = Unit
    }

    val detector = HangDetector(busy, platform, data, thresholdMs)
    private val watchdog = HangWatchdog(busy, detector, ::hopi_perf_now_ms, lease, beforeSleep = { selfCpu.account(cpuState) })

    fun start() {
        hopi_perf_watchdog_attach(semaphore)
        val thread = NSThread {
            selfCpu.begin(cpuState)
            try {
                watchdog.run()
            } catch (e: Throwable) {
                // An exception escaping a Kotlin/Native thread terminates the app; report it instead.
                emitPerfLogLine(PerfJson.noticeLine("$THREAD_NAME thread failed: $e"))
            } finally {
                selfCpu.account(cpuState)
                // The thread's own; the monitor's goes in stop, after its wake.
                hopi_perf_watchdog_release(semaphore)
                nativeHeap.free(addresses)
                nativeHeap.free(suspendedNs)
                nativeHeap.free(name)
            }
        }
        thread.name = THREAD_NAME
        thread.qualityOfService = NSQualityOfServiceUserInitiated
        thread.start()
    }

    /** Returns at once: the watchdog ends at its next wake, which this is. Once per monitor; no [poke] after it. */
    fun stop() {
        hopi_perf_watchdog_detach(semaphore)
        watchdog.stop()
        hopi_perf_watchdog_release(semaphore)
    }

    /** Before [stop] only (the backend drops the monitor when it stops it). */
    fun poke() = watchdog.poke()

    internal companion object {
        const val THREAD_NAME = "hopi-perf-hang"

        fun raw(address: ULong): String = "0x" + address.toString(16).padStart(16, '0')
    }
}
