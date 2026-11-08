package dev.koga.hopi.perf.internal

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import dev.koga.hopi.core.perf.CurrentActivity
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfPss
import dev.koga.hopi.core.perf.PerfThread
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Android monitors (docs/design/perf-monitors.md) behind [AndroidBackend], which creates this at
 * the first call that needs a monitor and forwards to it: the frames monitor and the input bracket
 * on each started activity's window, the hang monitor, the sampler for CPU and memory, the OS's
 * trim callbacks, and the activity callbacks while frames or hangs runs. It reads the backend's
 * [Application], model, lease and settings.
 *
 * Every call but [threads] and [measurePss] comes under the runtime's lock (see [MonitorBackend]);
 * those two come from bridge threads without it.
 */
internal class AndroidMonitors(private val backend: AndroidBackend) {
    private val selfCpu = SelfCpu()

    // Written under the runtime's lock on a bridge thread, read by activity callbacks on the main thread.
    @Volatile private var hang: AndroidHangMonitor? = null
    @Volatile private var frames: FramesMonitor? = null
    @Volatile private var sampler: AndroidSampler? = null
    private var memoryCallbacks: ComponentCallbacks2? = null
    private var lifecycle: Application.ActivityLifecycleCallbacks? = null
    private val pss by lazy { PssProbe(backend.data) }
    private val threadsOnDemand by lazy { ThreadScan() }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Frames attaches posted to the main thread that have not run yet: frames' details say
     * `attaching` meanwhile, so a start's reply does not report an attach that is one message away
     * as missing. A count, written under the lock and on the main thread: a frames stopped and
     * started again has two in flight, and the first one's end must not clear the second's.
     */
    private val framesAttaching = AtomicInteger(0)

    /** Frames need the Application; the others always run once started. */
    fun started(monitor: PerfMonitor): Boolean = when (monitor) {
        PerfMonitor.FRAMES -> frames != null
        PerfMonitor.HANGS -> hang != null
        else -> true
    }

    fun start(monitor: PerfMonitor): String? {
        val app = backend.application
        val settings = backend.settings
        return when (monitor) {
            PerfMonitor.FRAMES -> {
                if (app == null) return "unavailable without the Application (Hopi's start passes it)"
                frames = FramesMonitor(backend.data, app, backend.lease, selfCpu) { hang?.onFrame() }
                updateLifecycle()
                if (backend.autoStarting || attachCurrentActivity(forFrames = true)) {
                    null
                } else {
                    "frames attach when an activity next starts"
                }
            }
            PerfMonitor.HANGS -> {
                hang = AndroidHangMonitor(backend.data, settings.hangThresholdMs, settings.hangHeartbeat, backend.lease, app, selfCpu).also { it.start() }
                updateLifecycle()
                if (app != null) attachCurrentActivity(forFrames = false)
                when {
                    settings.hangHeartbeat -> null
                    app == null -> "no input bracket without the Application: hangs inside input dispatch (a Compose onClick) are not seen"
                    else -> "the Looper printer replaces any printer another tool set"
                }
            }
            PerfMonitor.CPU -> {
                sampler().cpu = true
                null
            }
            PerfMonitor.MEMORY -> {
                sampler().memory = true
                if (app == null) return "no trim or low-memory events without the Application"
                val data = backend.data
                val callbacks = object : ComponentCallbacks2 {
                    override fun onTrimMemory(level: Int) = data.recordMemoryEvent("trim", level)

                    @Deprecated("Deprecated in Java")
                    override fun onLowMemory() = data.recordMemoryEvent("low", null)

                    override fun onConfigurationChanged(newConfig: Configuration) = Unit
                }
                app.registerComponentCallbacks(callbacks)
                memoryCallbacks = callbacks
                if (Build.VERSION.SDK_INT >= 34) "Android 14+ sends no foreground trim levels: no pressure event in the foreground" else null
            }
        }
    }

    fun stop(monitor: PerfMonitor) {
        when (monitor) {
            PerfMonitor.FRAMES -> {
                frames?.stop()
                frames = null
            }
            PerfMonitor.HANGS -> {
                hang?.stop()
                hang = null
            }
            PerfMonitor.CPU -> sampler?.cpu = false
            PerfMonitor.MEMORY -> {
                sampler?.memory = false
                memoryCallbacks?.let { backend.application?.unregisterComponentCallbacks(it) }
                memoryCallbacks = null
            }
        }
        sampler?.let { if (!it.cpu && !it.memory) { it.stop(); sampler = null } }
        updateLifecycle()
    }

    private fun sampler(): AndroidSampler = sampler ?: AndroidSampler(backend.data, backend.lease, selfCpu) { hang?.printerState == "set" }.also {
        it.intervalMs = backend.settings.sampleIntervalMs
        it.watchingThreads = backend.watchingThreads
        sampler = it
        it.start()
    }

    /** The backend's settings changed while monitors run. */
    fun apply() {
        val settings = backend.settings
        hang?.detector?.thresholdMs = settings.hangThresholdMs
        hang?.poke()
        sampler?.let {
            it.intervalMs = settings.sampleIntervalMs
            it.poke()
        }
    }

    fun details(monitor: PerfMonitor): List<Pair<String, Any?>> = when (monitor) {
        PerfMonitor.FRAMES -> frames.let { f ->
            // The count before the windows, each read once: the posted block attaches and only then
            // counts down, so a reply whose reads straddle the block sees it pending or attached.
            val attaching = f != null && framesAttaching.get() > 0
            val windows = f?.attachedWindows ?: 0
            listOf(
                "attached" to (windows > 0),
                "attaching" to attaching,
                "windows" to "activities only",
                "attachedWindows" to windows,
            ) + (if ((f?.software ?: 0) > 0) listOf("unmeasured" to "software") else emptyList()) +
                (if (backend.application == null) listOf("unavailable" to "no Application") else emptyList())
        }
        PerfMonitor.HANGS -> hang.let { h ->
            listOf(
                "thresholdMs" to backend.settings.hangThresholdMs,
                "printer" to h?.printerState,
                "input" to (h?.inputBracketed == true),
                // A dialog's, sheet's or popup's touches go through its own window's callback, which
                // no public API lets Hopi wrap: input there is unseen unless the heartbeat runs.
                "inputWindows" to if (h?.printerState == "heartbeat") "all (heartbeat)" else "activities only",
                "displaced" to h?.displaced,
            )
        }
        PerfMonitor.CPU -> listOf("intervalMs" to backend.settings.sampleIntervalMs, "threads" to backend.watchingThreads)
        PerfMonitor.MEMORY -> listOf("intervalMs" to backend.settings.sampleIntervalMs, "events" to (memoryCallbacks != null))
    }

    fun leaseChanged() {
        sampler?.poke()
        hang?.poke()
        // Frames alone carries the lease on its own thread: one delayed message per lease.
        frames?.scheduleLease(if (sampler == null && hang == null) backend.lease.msUntilDue() else Long.MAX_VALUE)
    }

    fun threads(limit: Int): List<PerfThread> = sampler?.threads(limit) ?: threadsOnDemand.top(limit, scanIfStale = true)

    fun watchThreads(watching: Boolean) {
        sampler?.watchingThreads = watching
    }

    fun measurePss(): PerfPss = pss.measure()

    /**
     * The resumed activity, if any (core's [CurrentActivity], which core's start keeps): frames and
     * the input bracket attach to it at once, with one main-thread message, instead of at its next
     * start (a single-activity app would otherwise wait for a return from the background). The
     * lifecycle callbacks' own attach at a later start skips a window already attached. A frames
     * start counts its message until the message ran ([framesAttaching]).
     */
    private fun attachCurrentActivity(forFrames: Boolean): Boolean {
        val activity = CurrentActivity.activity ?: return false
        val f = frames
        val h = hang
        if (forFrames) framesAttaching.incrementAndGet()
        mainHandler.post {
            try {
                if (f != null && frames === f) f.attach(activity)
                if (h != null && hang === h) activity.window?.let(h::bracket)
            } finally {
                if (forFrames) framesAttaching.decrementAndGet()
            }
        }
        return true
    }

    /** The activity callbacks, registered only while frames or hangs runs and the Application is known. */
    private fun updateLifecycle() {
        val app = backend.application ?: return
        val wanted = frames != null || hang != null
        val current = lifecycle
        if (wanted && current == null) {
            val data = backend.data
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    frames?.attach(activity)
                    activity.window?.let { hang?.bracket(it) }
                }

                override fun onActivityStopped(activity: Activity) {
                    frames?.detach(activity)
                }

                override fun onActivityResumed(activity: Activity) {
                    data.setActivity(activity.javaClass.simpleName)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    activity.window?.let(AndroidHangMonitor::forget)
                }
            }
            app.registerActivityLifecycleCallbacks(callbacks)
            lifecycle = callbacks
        } else if (!wanted && current != null) {
            app.unregisterActivityLifecycleCallbacks(current)
            lifecycle = null
        }
    }
}
