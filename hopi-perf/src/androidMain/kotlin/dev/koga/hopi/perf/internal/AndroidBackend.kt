package dev.koga.hopi.perf.internal

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.perf.PerfBootstrap
import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfLauncher
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfPss
import dev.koga.hopi.core.perf.PerfStartRequest
import dev.koga.hopi.core.perf.PerfThread
import dev.koga.hopi.core.perf.PerfTimer

/**
 * The Android monitors. Nothing here runs until a monitor starts: no thread, no listener, no
 * lifecycle callback while every monitor is off. The [Application] (from `hopi-android`'s provider
 * or `HopiPerf.install(application)`) gives frames and the input bracket each started activity's
 * window and memory the OS's trim callbacks; without it, hangs run with the printer only, CPU and
 * memory samples run, and frames and memory events say they are unavailable.
 *
 * Every call comes under the runtime's lock (see [MonitorBackend]).
 */
internal class AndroidBackend private constructor() : MonitorBackend {
    override val platform: String = "android"
    override val available: Set<PerfMonitor> = PerfMonitor.entries.toSet()

    @Volatile private var application: Application? = null
    private lateinit var data: PerfData
    private lateinit var lease: PerfTimer
    private val selfCpu = SelfCpu()
    private var settings = PerfSettings.DEFAULT

    private var hang: AndroidHangMonitor? = null
    private var frames: FramesMonitor? = null
    private var sampler: AndroidSampler? = null
    private var memoryCallbacks: ComponentCallbacks2? = null
    private var lifecycle: Application.ActivityLifecycleCallbacks? = null
    private var watchingThreads = false
    private val pss by lazy { PssProbe(data) }
    private val threadsOnDemand by lazy { ThreadScan() }

    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override fun attach(data: PerfData, lease: PerfTimer) {
        this.data = data
        this.lease = lease
    }

    override fun start(monitor: PerfMonitor, settings: PerfSettings): String? {
        this.settings = settings
        val app = application
        return when (monitor) {
            PerfMonitor.FRAMES -> {
                if (app == null) return "unavailable without the Application (apply the dev.koga.hopi plugin, or call HopiPerf.install(application))"
                frames = FramesMonitor(data, app, lease, selfCpu) { hang?.onFrame() }
                updateLifecycle()
                if (autoStarting || attachLauncherActivity()) {
                    null
                } else {
                    "frames attach when an activity next starts (single-activity app: send it to the background and back, or open the Hopi launcher)"
                }
            }
            PerfMonitor.HANGS -> {
                hang = AndroidHangMonitor(data, settings.hangThresholdMs, settings.hangHeartbeat, lease, app, selfCpu).also { it.start() }
                updateLifecycle()
                if (app != null) attachLauncherActivity()
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

    override fun stop(monitor: PerfMonitor) {
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
                memoryCallbacks?.let { application?.unregisterComponentCallbacks(it) }
                memoryCallbacks = null
            }
        }
        sampler?.let { if (!it.cpu && !it.memory) { it.stop(); sampler = null } }
        updateLifecycle()
    }

    private fun sampler(): AndroidSampler = sampler ?: AndroidSampler(data, lease, selfCpu) { hang?.printerState == "set" }.also {
        it.intervalMs = settings.sampleIntervalMs
        it.watchingThreads = watchingThreads
        sampler = it
        it.start()
    }

    override fun apply(settings: PerfSettings, running: Set<PerfMonitor>) {
        this.settings = settings
        hang?.detector?.thresholdMs = settings.hangThresholdMs
        hang?.poke()
        sampler?.let {
            it.intervalMs = settings.sampleIntervalMs
            it.poke()
        }
    }

    override fun details(monitor: PerfMonitor): List<Pair<String, Any?>> = when (monitor) {
        PerfMonitor.FRAMES -> frames.let { f ->
            listOf(
                "attached" to ((f?.attachedWindows ?: 0) > 0),
                "windows" to "activities only",
                "attachedWindows" to (f?.attachedWindows ?: 0),
            ) + (if ((f?.software ?: 0) > 0) listOf("unmeasured" to "software") else emptyList()) +
                (if (application == null) listOf("unavailable" to "no Application") else emptyList())
        }
        PerfMonitor.HANGS -> hang.let { h ->
            listOf(
                "thresholdMs" to settings.hangThresholdMs,
                "printer" to h?.printerState,
                "input" to (h?.inputBracketed == true),
                // A dialog's, sheet's or popup's touches go through its own window's callback, which
                // no public API lets Hopi wrap: input there is unseen unless the heartbeat runs.
                "inputWindows" to if (h?.printerState == "heartbeat") "all (heartbeat)" else "activities only",
                "displaced" to h?.displaced,
            )
        }
        PerfMonitor.CPU -> listOf("intervalMs" to settings.sampleIntervalMs, "threads" to watchingThreads)
        PerfMonitor.MEMORY -> listOf("intervalMs" to settings.sampleIntervalMs, "events" to (memoryCallbacks != null))
    }

    override fun leaseChanged(running: Set<PerfMonitor>) {
        sampler?.poke()
        hang?.poke()
        // Frames alone carries the lease on its own thread: one delayed message per lease.
        frames?.scheduleLease(if (sampler == null && hang == null) lease.msUntilDue() else Long.MAX_VALUE)
    }

    override fun threads(limit: Int): List<PerfThread> = sampler?.threads(limit) ?: threadsOnDemand.top(limit, scanIfStale = true)

    override fun watchThreads(watching: Boolean) {
        watchingThreads = watching
        sampler?.watchingThreads = watching
    }

    override fun measurePss(): PerfPss = pss.measure()

    /**
     * The activity `HopiLauncher` is composed in, if any (core's [PerfLauncher]): frames and the
     * input bracket attach to it at once, with one main-thread message, instead of at its next
     * start (a single-activity app would otherwise wait for a return from the background). The
     * lifecycle callbacks' own attach at a later start skips a window already attached.
     */
    private fun attachLauncherActivity(): Boolean {
        val activity = PerfLauncher.activity ?: return false
        val f = frames
        val h = hang
        mainHandler.post {
            if (f != null && frames === f) f.attach(activity)
            if (h != null && hang === h) activity.window?.let(h::bracket)
        }
        return true
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** The activity callbacks, registered only while frames or hangs runs and the Application is known. */
    private fun updateLifecycle() {
        val app = application ?: return
        val wanted = frames != null || hang != null
        val current = lifecycle
        if (wanted && current == null) {
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

                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            app.registerActivityLifecycleCallbacks(callbacks)
            lifecycle = callbacks
        } else if (!wanted && current != null) {
            app.unregisterActivityLifecycleCallbacks(current)
            lifecycle = null
        }
    }

    /** Set while the provider's `autoStart` runs (before any activity): frames will attach at the first start. */
    @Volatile private var autoStarting = false

    internal fun autoStart(block: () -> Unit) {
        autoStarting = true
        try {
            block()
        } finally {
            autoStarting = false
        }
    }

    internal companion object {
        val instance: AndroidBackend by lazy { AndroidBackend() }

        fun application(app: Application) {
            instance.application = app
        }
    }
}

/**
 * What `hopi-android`'s provider loads by name when the Gradle plugin put `hopi-perf` in the
 * variant (`hopi { perf { enabled = true } }`): registers the controller and starts the plugin's
 * `autoStart` monitors, on the main thread before `Application.onCreate`. Nothing else.
 */
@HopiInternalApi
public class PerfEntry : PerfBootstrap {
    override fun boot(application: Application?, autoStart: Int, hangThresholdMs: Long, hangHeartbeat: Boolean) {
        application?.let(AndroidBackend::application)
        val controller = PerfRuntime.install(
            dev.koga.hopi.perf.HopiPerfConfig().apply {
                this.hangThresholdMs = hangThresholdMs
                this.hangHeartbeat = hangHeartbeat
            },
        ) ?: return
        val monitors = PerfMonitor.entries.filterTo(LinkedHashSet()) { autoStart and PerfBootstrap.bit(it) != 0 }
        if (monitors.isNotEmpty()) AndroidBackend.instance.autoStart { controller.start(PerfStartRequest(monitors), PerfOwner.Code) }
    }
}
