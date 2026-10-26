package dev.koga.hopi.perf.internal

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Printer
import android.view.Window
import dev.koga.hopi.core.perf.BusyState
import dev.koga.hopi.core.perf.HangDetector
import dev.koga.hopi.core.perf.HangPlatform
import dev.koga.hopi.core.perf.HangWatchdog
import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfLimits
import dev.koga.hopi.core.perf.PerfTimer
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The Android hang monitor (docs/design/perf-monitors.md, "Hangs"): a Looper printer that
 * publishes the main thread's busy state, an input bracket on each started activity's window, and
 * the watchdog thread `hopi-perf-hang` at the default priority, asleep with no timeout while the
 * main thread is idle. With [heartbeat], no printer: the watchdog posts an empty message every
 * `threshold / 2` instead (8 wakes per second at 250 ms on both threads, idle included).
 *
 * The printer is set and cleared by a message posted to the main thread, so neither waits on a hung
 * main thread: [stop] stops the watchdog and the bracket at once, and the stop message clears the
 * printer only if this monitor's printer saw that very message's dispatch, so a stop never removes
 * another tool's printer.
 */
internal class AndroidHangMonitor(
    private val data: PerfData,
    thresholdMs: Long,
    private val heartbeat: Boolean,
    private val lease: PerfTimer,
    private val context: Context?,
    private val selfCpu: SelfCpu,
) {
    private val busy = BusyState()
    private val main = Handler(Looper.getMainLooper())
    private val printer = HopiPrinter(busy)
    private val platform = object : HangPlatform {
        override fun nowMs(): Long = SystemClock.uptimeMillis()

        override fun monotonicNs(): Long = System.nanoTime()

        override fun captureMainStack(): List<String> {
            // ART suspends the main thread at a suspend point for the walk; empty when that timed out.
            val trace = Looper.getMainLooper().thread.stackTrace
            val frames = minOf(trace.size, PerfLimits.MAX_STACK_FRAMES)
            return List(frames) { trace[it].toString() }
        }

        override fun debuggerAttached(): Boolean = Debug.isDebuggerConnected()

        override fun postHeartbeat() {
            main.post(heartbeatEnd)
        }
    }
    private val heartbeatEnd = Runnable { busy.end(SystemClock.uptimeMillis()) }
    val detector = HangDetector(busy, platform, data, thresholdMs, heartbeat)
    private val watchdog = HangWatchdog(busy, detector, SystemClock::uptimeMillis, lease, beforeSleep = selfCpu::account)
    private val thread = Thread(::runWatchdog, "hopi-perf-hang").apply { isDaemon = true }

    /** `pending` until the posted message set it, then `set`; `heartbeat` without one; `cleared` after stop. */
    @Volatile var printerState: String = if (heartbeat) "heartbeat" else "pending"
        private set

    /** True once frames arrived while the printer's sequence did not move: another tool's printer replaced it. */
    @Volatile var displaced: Boolean? = null
        private set

    private val bracket = object : InputBracketCallback.Bracket {
        override fun enter(): Boolean = active.get() && busy.enterInput(SystemClock.uptimeMillis())

        override fun exit() = busy.exitInput(SystemClock.uptimeMillis())
    }
    private val active = AtomicBoolean(true)

    // Main thread only, process-wide: Hopi's wrappers by window, so a restart re-enables one left
    // disabled under another SDK's wrapper instead of stacking a second.
    private val windows: WeakHashMap<Window, InputBracketCallback> get() = knownWrappers

    /** Written on the main thread, read by `perf.status` from any. */
    @Volatile var inputBracketed: Boolean = false
        private set

    fun start() {
        thread.start()
        if (!heartbeat) {
            main.post {
                if (active.get()) {
                    Looper.getMainLooper().setMessageLogging(printer)
                    printer.active = true
                    printerState = "set"
                }
            }
        }
    }

    /** Main thread: brackets [window]'s input. */
    fun bracket(window: Window) {
        if (active.get() && InputBracketCallback.install(window, bracket, windows) != null) inputBracketed = true
    }

    /** Returns at once; the printer is cleared when the main thread next runs a message. */
    fun stop() {
        active.set(false)
        watchdog.stop()
        if (heartbeat) return
        printer.stopping(STOP_MARKER_ID.incrementAndGet())
        main.post(printer.stopRunnable())
        main.post {
            // Only this monitor's: a monitor started since has re-enabled the ones it uses.
            for ((window, wrapper) in windows.entries.toList()) {
                if (wrapper.bracket === bracket) InputBracketCallback.uninstall(window, wrapper, windows)
            }
            inputBracketed = false
        }
        printerState = "cleared"
    }

    fun poke() = watchdog.poke()

    /** Frames thread: a frame arrived; every 120 frames, an unmoved sequence means the printer was displaced. */
    fun onFrame() {
        if (heartbeat || printerState != "set") return
        if (framesSinceCheck.incrementAndGet() < DISPLACED_CHECK_FRAMES) return
        framesSinceCheck.set(0)
        val seq = BusyState.sequenceOf(busy.load())
        displaced = seq == lastCheckedSeq.getAndSet(seq)
    }

    private val framesSinceCheck = AtomicInteger(0)
    private val lastCheckedSeq = AtomicLong(-1)

    private fun runWatchdog() {
        readPreviousAnr()
        watchdog.run()
        selfCpu.account()
    }

    /**
     * API 30+: the single most recent exit of this package; only if it was an ANR, the `"main"`
     * thread's section of its trace (at most 16 KB). Once per process, on the watchdog thread.
     */
    private fun readPreviousAnr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || context == null || !anrRead.compareAndSet(false, true)) return
        try {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return
            if (exit.reason != ApplicationExitInfo.REASON_ANR) return
            val trace = exit.traceInputStream?.use { MainThreadTrace.read(it, PerfLimits.MAX_ANR_TRACE_BYTES) }
            data.recordPreviousAnr(exit.timestamp, exit.description, trace)
        } catch (_: Exception) {
            // A missing permission or a vendor quirk: no previous ANR, never a crash.
        }
    }

    private companion object {
        const val DISPLACED_CHECK_FRAMES = 120
        val STOP_MARKER_ID = AtomicLong(0)
        val knownWrappers = WeakHashMap<Window, InputBracketCallback>()
        val anrRead = AtomicBoolean(false)
    }
}

/**
 * The Looper printer: `>` starts a unit, `<` ends it, told apart by the first character; it builds
 * nothing (the framework already built the string). While a stop is pending it also looks for the
 * stop message's marker in the dispatch line, which proves the printer in force is this one.
 */
internal class HopiPrinter(private val busy: BusyState) : Printer {
    @Volatile var active = false

    @Volatile private var pendingStop = 0L

    @Volatile private var stopSeen = 0L

    override fun println(x: String) {
        if (!active || x.isEmpty()) return
        when (x[0]) {
            '>' -> {
                busy.begin(SystemClock.uptimeMillis())
                val pending = pendingStop
                if (pending != 0L && x.contains(StopRunnable.marker(pending))) stopSeen = pending
            }
            '<' -> busy.end(SystemClock.uptimeMillis())
        }
    }

    fun stopping(id: Long) {
        pendingStop = id
    }

    fun stopRunnable(): Runnable = StopRunnable(pendingStop, this)

    /** Runs on the main thread; clears the printer only when this printer dispatched it. */
    private class StopRunnable(private val id: Long, private val printer: HopiPrinter) : Runnable {
        override fun run() {
            val mine = printer.stopSeen == id
            printer.active = false
            if (mine) Looper.getMainLooper().setMessageLogging(null)
        }

        override fun toString(): String = marker(id)

        companion object {
            fun marker(id: Long): String = "HopiPerfStop#$id"
        }
    }
}

/** Reads the `"main"` thread's section of an ANR trace, stopping after it, bounded. */
internal object MainThreadTrace {
    fun read(input: java.io.InputStream, maxBytes: Int): String? {
        val reader = input.bufferedReader()
        val out = StringBuilder()
        var inMain = false
        var read = 0L
        while (true) {
            val line = reader.readLine() ?: break
            read += line.length + 1
            if (!inMain && line.startsWith("\"main\"")) inMain = true
            if (inMain) {
                if (line.isEmpty()) break
                if (out.length + line.length + 1 > maxBytes) break
                out.append(line).append('\n')
            }
            // A trace of every thread can be megabytes: give up long before reading all of it.
            if (!inMain && read > 8L * 1024 * 1024) break
        }
        return out.toString().takeIf { it.isNotEmpty() }
    }
}
