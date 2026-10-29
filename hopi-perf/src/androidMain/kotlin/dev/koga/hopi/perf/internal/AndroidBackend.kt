package dev.koga.hopi.perf.internal

import android.app.Application
import android.os.SystemClock
import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfPss
import dev.koga.hopi.core.perf.PerfThread
import dev.koga.hopi.core.perf.PerfTimer
import java.util.EnumSet

/**
 * The Android backend, created by perf's tool on the main thread before `Application.onCreate`
 * ([PerfEntry]): it keeps the [Application] the start sequence hands over, the model, the lease and
 * the settings, and forwards every monitor operation to [AndroidMonitors], which it creates at the
 * first call that needs a monitor (a start, a running monitor's details, the thread list, PSS).
 * Nothing here runs until a monitor starts: no thread, no listener, no lifecycle callback while
 * every monitor is off. Without the [Application], hangs run with the printer only, CPU and memory
 * samples run, and frames and memory events say they are unavailable.
 *
 * Split so because a debug build runs from its APK, unoptimized: ART verifies a class when it is
 * first initialized and loads every class its methods name. This class names no monitor class, no
 * `Handler` and no lifecycle callback, so the tool's start loads none of them; [AndroidMonitors] is
 * loaded there, not initialized, and the classes it names do not follow until a monitor starts.
 *
 * Every call but [threads] and [measurePss] comes under the runtime's lock (see [MonitorBackend]);
 * those two come from bridge threads without it.
 */
internal class AndroidBackend private constructor() : MonitorBackend {
    override val platform: String = "android"
    override val available: Set<PerfMonitor> = EnumSet.allOf(PerfMonitor::class.java)

    @Volatile var application: Application? = null
        private set

    lateinit var data: PerfData
        private set

    lateinit var lease: PerfTimer
        private set

    /** The settings of the last start or apply. */
    var settings: PerfSettings = PerfSettings.DEFAULT
        private set

    /** Whether a viewer shows the per-thread list (a sampler started later scans it too). */
    var watchingThreads: Boolean = false
        private set

    /** Set while [PerfEntry] starts the `autoStart` monitors, before any activity: frames attaches at the first start. */
    @Volatile var autoStarting: Boolean = false

    @Volatile private var monitors: AndroidMonitors? = null

    /**
     * The one [AndroidMonitors], created at the first call that needs it. [threads] and
     * [measurePss] come without the runtime's lock, so it is created under this object's monitor:
     * a second instance would hold monitors that no stop reaches. Its constructor takes no lock, so
     * holding this one cannot deadlock with the runtime's.
     */
    private fun monitors(): AndroidMonitors =
        monitors ?: synchronized(this) { monitors ?: AndroidMonitors(this).also { monitors = it } }

    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override fun attach(data: PerfData, lease: PerfTimer) {
        this.data = data
        this.lease = lease
    }

    override fun start(monitor: PerfMonitor, settings: PerfSettings): String? {
        this.settings = settings
        return monitors().start(monitor)
    }

    override fun stop(monitor: PerfMonitor) {
        monitors?.stop(monitor)
    }

    /** With no monitor started yet (the tool's start, with the plugin's threshold), it only keeps [settings]. */
    override fun apply(settings: PerfSettings, running: Set<PerfMonitor>) {
        this.settings = settings
        monitors?.apply()
    }

    override fun details(monitor: PerfMonitor): List<Pair<String, Any?>> = monitors().details(monitor)

    override fun leaseChanged(running: Set<PerfMonitor>) {
        monitors?.leaseChanged()
    }

    override fun threads(limit: Int): List<PerfThread> = monitors().threads(limit)

    override fun watchThreads(watching: Boolean) {
        watchingThreads = watching
        monitors?.watchThreads(watching)
    }

    override fun measurePss(): PerfPss = monitors().measurePss()

    internal companion object {
        /** The process's one backend; no `lazy`, whose classes the main thread would load. */
        val instance: AndroidBackend = AndroidBackend()

        fun application(app: Application) {
            instance.application = app
        }
    }
}
