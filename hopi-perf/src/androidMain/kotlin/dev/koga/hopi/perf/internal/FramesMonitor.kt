package dev.koga.hopi.perf.internal

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.FrameMetrics
import android.view.Window
import android.view.WindowManager
import dev.koga.hopi.core.perf.FrameClassifier
import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfTimer
import dev.koga.hopi.core.perf.perfWallClockMs

/**
 * The Android frames monitor (docs/design/perf-monitors.md, "Frames"): one `HandlerThread`
 * `hopi-perf-frame` at nice 5 and a FrameMetrics listener on each started activity's window. The
 * framework posts one message per rendered frame to that thread, so it wakes once per frame the app
 * draws and never while the app draws nothing. Per frame it reads primitive metrics into reused
 * fields: no allocation unless the frame is janky.
 *
 * Window pitfalls, each handled: a window without a decor view is attached at its next start (Hopi
 * never creates the decor); a window that is not hardware accelerated never reports metrics and is
 * reported as `unmeasured: software`; only listeners Hopi attached are removed. Dialogs, popups and
 * other windows are not seen (`windows: activities only`).
 */
internal class FramesMonitor(
    private val data: PerfData,
    private val context: Context?,
    private val lease: PerfTimer,
    private val selfCpu: SelfCpu,
    /** The hang monitor's displaced-printer check, when it runs. */
    private val onFrame: () -> Unit,
) {
    private val thread = HandlerThread("hopi-perf-frame", FRAME_THREAD_PRIORITY).apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    // nanoTime to epoch: frames are bucketed by their vsync, in System.nanoTime's base.
    private val epochOffsetNs = perfWallClockMs() * 1_000_000 - System.nanoTime()

    @Volatile private var active = true

    // Main thread only.
    private val windows = HashMap<Window, WindowRecord>()
    private var displayListener: DisplayManager.DisplayListener? = null

    @Volatile var attachedWindows: Int = 0
        private set

    @Volatile var software: Int = 0
        private set

    init {
        // Hopi's own CPU, accounted each time the frames thread goes idle (once per burst of frames).
        handler.post { Looper.myQueue().addIdleHandler { selfCpu.account(); true } }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && context != null) {
            // Below API 31 the deadline comes from the display's refresh rate, which may change.
            main.post {
                if (!active) return@post
                val manager = context.getSystemService(DisplayManager::class.java) ?: return@post
                val listener = object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) = Unit

                    override fun onDisplayRemoved(displayId: Int) = Unit

                    override fun onDisplayChanged(displayId: Int) {
                        main.post { windows.values.forEach { it.refreshDeadline() } }
                    }
                }
                manager.registerDisplayListener(listener, handler)
                displayListener = listener
            }
        }
    }

    /** Main thread: an activity started. */
    fun attach(activity: Activity) {
        if (!active) return
        val window = activity.window ?: return
        if (windows.containsKey(window)) return
        // getDecorView() would create a decor on a window that has none: attach at its next start.
        if (window.peekDecorView() == null) return
        if (window.attributes.flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED == 0) {
            software++
            return
        }
        val record = WindowRecord(activity, window)
        try {
            window.addOnFrameMetricsAvailableListener(record.listener, handler)
            windows[window] = record
            attachedWindows = windows.size
        } catch (_: RuntimeException) {
            // A window that refuses the listener: unmeasured, and nothing to remove at stop.
        }
    }

    /** Main thread: an activity stopped (not paused: a paused activity may be visible and still draw). */
    fun detach(activity: Activity) {
        val window = activity.window ?: return
        val record = windows.remove(window) ?: return
        attachedWindows = windows.size
        remove(window, record)
    }

    private fun remove(window: Window, record: WindowRecord) {
        try {
            window.removeOnFrameMetricsAvailableListener(record.listener)
        } catch (_: RuntimeException) {
            // Only Hopi's own records are removed; a window that already dropped it is fine.
        }
    }

    /** Lease expiry when frames is the only monitor running: one delayed message, rescheduled on renewal. */
    fun scheduleLease(ms: Long) {
        handler.removeCallbacks(leaseCheck)
        if (ms == Long.MAX_VALUE || !active) return
        handler.postAtTime(leaseCheck, SystemClock.uptimeMillis() + ms)
    }

    private val leaseCheck = Runnable { if (lease.msUntilDue() <= 0) lease.fire() else scheduleLease(lease.msUntilDue()) }

    /** Returns at once: the listeners are removed on the main thread, the thread quits after. */
    fun stop() {
        active = false
        handler.removeCallbacks(leaseCheck)
        main.post {
            for ((window, record) in windows) remove(window, record)
            windows.clear()
            attachedWindows = 0
            displayListener?.let { listener -> context?.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(listener) }
            displayListener = null
            handler.post { selfCpu.account() }
            thread.quitSafely()
        }
    }

    private inner class WindowRecord(activity: Activity, window: Window) {
        private val display: Display? = displayOf(activity)
        @Volatile private var deadlineNs = FrameClassifier.deadlineNs(display?.refreshRate ?: 60f)

        // Frames thread only.
        private var lastVsyncNs = 0L
        private var onTimeDeadlineNs = deadlineNs
        private val stages = LongArray(8)

        fun refreshDeadline() {
            deadlineNs = FrameClassifier.deadlineNs(display?.refreshRate ?: 60f)
        }

        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropCount -> record(metrics, dropCount) }

        private fun record(metrics: FrameMetrics, dropCount: Int) {
            if (!active) return
            val sdk = Build.VERSION.SDK_INT
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val first = metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L
            val vsync = if (sdk >= Build.VERSION_CODES.O) metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) else System.nanoTime() - total
            // A frame that waited for the main thread (an earlier frame's or message's work) starts
            // late: Choreographer moves it to a later vsync, and TOTAL_DURATION (from the intended vsync)
            // and, on API 31+, DEADLINE (the later frame timeline's, several vsyncs long) both carry that
            // wait. Read that way, 30 frames that each block 100 ms were one janky frame and then frames
            // of 200 ms "within" deadlines of 50 to 70 ms (seen on the emulator, API 35). So a frame is
            // measured from the vsync it was drawn for (its own work), and a late frame is held to the
            // deadline of the last frame that started on time (still the OS's per-frame deadline, which
            // follows variable refresh rates). The wait shows as missed vsyncs, and a long one as a hang.
            val skew = if (sdk >= Build.VERSION_CODES.O) (metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP) - vsync).coerceIn(0, total) else 0
            val own = total - skew
            val deadline = if (sdk >= Build.VERSION_CODES.S) {
                if (skew == 0L) metrics.getMetric(FrameMetrics.DEADLINE).also { if (it > 0) onTimeDeadlineNs = it } else onTimeDeadlineNs
            } else {
                deadlineNs
            }
            val missed = FrameClassifier.missedVsyncs(lastVsyncNs, vsync, deadline)
            lastVsyncNs = vsync
            stages[0] = metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)
            stages[1] = metrics.getMetric(FrameMetrics.ANIMATION_DURATION)
            stages[2] = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
            stages[3] = metrics.getMetric(FrameMetrics.DRAW_DURATION)
            stages[4] = metrics.getMetric(FrameMetrics.SYNC_DURATION)
            stages[5] = metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
            stages[6] = metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
            stages[7] = if (sdk >= Build.VERSION_CODES.S) metrics.getMetric(FrameMetrics.GPU_DURATION) else -1
            data.recordFrame((vsync + epochOffsetNs) / 1_000_000_000, vsync + skew, own, deadline, first, missed, dropCount, stages)
            onFrame()
        }
    }

    @Suppress("DEPRECATION")
    private fun displayOf(activity: Activity): Display? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay
    } catch (_: RuntimeException) {
        null
    }

    private companion object {
        /** Nice 5, the store's choice: below the app's threads, in its own scheduling group. */
        const val FRAME_THREAD_PRIORITY = 5
    }
}
