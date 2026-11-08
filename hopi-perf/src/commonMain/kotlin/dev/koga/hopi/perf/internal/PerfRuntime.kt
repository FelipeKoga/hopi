package dev.koga.hopi.perf.internal

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.api.HopiRuntimes
import dev.koga.hopi.core.perf.PerfController
import dev.koga.hopi.core.perf.PerfData
import dev.koga.hopi.core.perf.PerfHook
import dev.koga.hopi.core.perf.PerfLimits
import dev.koga.hopi.core.perf.PerfJson
import dev.koga.hopi.core.perf.PerfLogSink
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfMonitorStatus
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfOwners
import dev.koga.hopi.core.perf.PerfPss
import dev.koga.hopi.core.perf.PerfResult
import dev.koga.hopi.core.perf.PerfStartRequest
import dev.koga.hopi.core.perf.PerfStatus
import dev.koga.hopi.core.perf.PerfThread
import dev.koga.hopi.core.perf.PerfTimer
import dev.koga.hopi.core.perf.emitPerfLogLine
import dev.koga.hopi.core.perf.perfWallClockMs
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/**
 * The settings in force, the runtime's whole configuration: perf's tool sets them all at start (its
 * own settings and the start's `summaryLog`), and a `perf.start` with `hangMs` or `intervalMs` or a
 * `HopiPerf.start` changes only those it gives. Nothing else builds them: the runtime never sees the
 * API's `HopiPerfConfig`.
 */
@HopiInternalApi
public class PerfSettings(
    public val hangThresholdMs: Long,
    public val hangHeartbeat: Boolean,
    public val sampleIntervalMs: Long,
    public val remoteControl: Boolean,
    public val summaryLog: Boolean,
) {
    /** These settings with each value given instead (null: keep this one's). */
    internal fun with(hangThresholdMs: Long? = null, sampleIntervalMs: Long? = null, hangHeartbeat: Boolean? = null): PerfSettings =
        PerfSettings(hangThresholdMs ?: this.hangThresholdMs, hangHeartbeat ?: this.hangHeartbeat, sampleIntervalMs ?: this.sampleIntervalMs, remoteControl, summaryLog)

    internal companion object {
        val DEFAULT = PerfSettings(PerfLimits.DEFAULT_HANG_THRESHOLD_MS, false, PerfLimits.DEFAULT_SAMPLE_INTERVAL_MS, remoteControl = true, summaryLog = true)
    }
}

/**
 * One platform's monitors. Every call but [threads] and [measurePss] is made under the runtime's
 * lock, so a backend sees starts and stops in order; those two come from bridge threads without
 * it (a PSS read and a thread scan are slow, and a stop must never wait on one). Each returns at
 * once (a stop never waits on a hung main thread).
 */
@HopiInternalApi
public interface MonitorBackend {
    public val platform: String
    public val available: Set<PerfMonitor>

    /** Monotonic milliseconds, for leases. */
    public fun nowMs(): Long

    /** Called once, before any start: where to write, and the lease deadline to fold into a timed wait. */
    public fun attach(data: PerfData, lease: PerfTimer)

    /** Starts [monitor]; returns a notice when it runs with less than asked (frames unavailable, printer pending), else null. */
    public fun start(monitor: PerfMonitor, settings: PerfSettings): String?

    /**
     * Whether [monitor], just [start]ed, runs at all (false: its notice says why: frames without the
     * Application, iOS run-loop observers that could not be created). The runtime then gives the
     * start back, so status never shows a monitor nothing measures, and no lease waits on a thread
     * that does not exist.
     */
    public fun started(monitor: PerfMonitor): Boolean = true

    public fun stop(monitor: PerfMonitor)

    /** The settings changed while [running] monitors run. */
    public fun apply(settings: PerfSettings, running: Set<PerfMonitor>)

    /** Facts for `perf.status` (threshold, printer, input bracket, attached windows, …). */
    public fun details(monitor: PerfMonitor): List<Pair<String, Any?>>

    /** The earliest lease changed: a thread that carries it re-reads [PerfTimer.msUntilDue]. */
    public fun leaseChanged(running: Set<PerfMonitor>)

    public fun threads(limit: Int): List<PerfThread>?

    public fun watchThreads(watching: Boolean)

    public fun measurePss(): PerfPss

    /** A viewer asks for, or no longer asks for, iOS frames tier 2 (counted); nothing elsewhere. */
    public fun watchCadence(watching: Boolean) {}
}

/** The platform's backend; created by the first start of perf's tool, never before. */
internal expect fun createBackend(): MonitorBackend

/**
 * Monitors the process was launched to start (iOS: `-hopiPerf frames,hangs`, so an agent can
 * measure a cold start); read once, by the first start of perf's tool. Empty elsewhere.
 */
internal expect fun launchArgumentMonitors(): Set<PerfMonitor>

/** [block] under [lock]'s monitor (JVM `synchronized`; iOS a spin lock): starts and stops are rare. */
internal expect fun <T> perfLocked(lock: Any, block: () -> T): T

/**
 * The [PerfController] that `hopi-perf` registers in core's `PerfHook`: owners, leases, settings
 * and status, over the platform's [MonitorBackend]. Created by perf's tool when a debug build's
 * start starts it ([PerfRuntime.install]), never by a `HopiPerf` call, which reaches it through
 * `hopi-api`'s registry ([ApiPerf]); an app whose start lists no perf tool loads nothing more.
 */
@HopiInternalApi
public class PerfRuntimeController internal constructor(private val backend: MonitorBackend) : PerfController {
    override val platform: String get() = backend.platform
    override val available: Set<PerfMonitor> get() = backend.available
    override val data: PerfData = PerfData()

    private val lock = Any()
    private val owners = PerfOwners()
    private val since = HashMap<PerfMonitor, Long>()
    private val settings = AtomicReference(PerfSettings.DEFAULT)
    private val lastNotices = AtomicReference<List<String>>(emptyList())

    /** [addRunningListener]'s listeners, and the set they were last given; under [lock]. */
    private val runningListeners = ArrayList<(Set<PerfMonitor>) -> Unit>()
    private var announced: Set<PerfMonitor> = emptySet()

    override val remoteControl: Boolean get() = settings.load().remoteControl

    internal val currentSettings: PerfSettings get() = settings.load()

    private val lease = object : PerfTimer {
        override fun msUntilDue(): Long {
            val next = perfLocked(lock) { owners.nextExpiry() } ?: return Long.MAX_VALUE
            return (next - backend.nowMs()).coerceAtLeast(0)
        }

        override fun fire() {
            expireLeases()
        }
    }

    init {
        backend.attach(data, lease)
    }

    /** A tool's start: [next] replaces the settings in force whole, `summaryLog` included. */
    internal fun configure(next: PerfSettings) {
        data.setLogSink(if (next.summaryLog) data.log ?: PerfLogSink() else null)
        // Under the lock, as update and start merge theirs: neither can store a merge of the old settings over these.
        perfLocked(lock) {
            val previous = settings.exchange(next)
            if (previous.hangThresholdMs != next.hangThresholdMs || previous.sampleIntervalMs != next.sampleIntervalMs) {
                backend.apply(next, owners.heldMonitors)
            }
        }
    }

    /**
     * A `HopiPerf.start`'s settings: each one given (null: keep the value in force) is merged into
     * the settings in force under the lock, as a remote start's are, so a concurrent change is never
     * lost. The threshold and the interval apply at once, the heartbeat at the next start of hangs.
     */
    internal fun update(hangThresholdMs: Long?, hangHeartbeat: Boolean?, sampleIntervalMs: Long?) {
        if (hangThresholdMs == null && hangHeartbeat == null && sampleIntervalMs == null) return
        perfLocked(lock) {
            val current = settings.load()
            val next = current.with(hangThresholdMs, sampleIntervalMs, hangHeartbeat)
            settings.store(next)
            if (next.hangThresholdMs != current.hangThresholdMs || next.sampleIntervalMs != current.sampleIntervalMs) {
                backend.apply(next, owners.heldMonitors)
            }
        }
    }

    override fun start(request: PerfStartRequest, owner: PerfOwner): PerfResult {
        val missing = request.monitors - backend.available
        if (missing.isNotEmpty()) {
            return PerfResult.Failed("UNSUPPORTED", "not available on ${backend.platform}: ${missing.joinToString { it.wireName }}")
        }
        val notices = ArrayList<String>()
        perfLocked(lock) {
            val current = settings.load()
            val next = current.with(request.hangThresholdMs, request.sampleIntervalMs)
            if (next !== current && (next.hangThresholdMs != current.hangThresholdMs || next.sampleIntervalMs != current.sampleIntervalMs)) {
                settings.store(next)
                backend.apply(next, owners.heldMonitors)
            }
            val now = backend.nowMs()
            for (monitor in request.monitors) {
                if (owners.add(monitor, owner, now)) {
                    since[monitor] = perfWallClockMs()
                    // Before it starts, so its first item never reaches a viewer that thinks it is off.
                    announceRunning()
                    backend.start(monitor, settings.load())?.let { notices += "${monitor.wireName}: $it" }
                    if (!backend.started(monitor)) {
                        owners.clear(monitor)
                        stopLocked(monitor)
                        notices += "${monitor.wireName}: not started"
                    }
                }
            }
            announceRunning()
            backend.leaseChanged(owners.heldMonitors)
        }
        lastNotices.store(notices)
        notices.forEach(::notice)
        return PerfResult.Ok(status())
    }

    override fun stop(monitors: Set<PerfMonitor>): PerfStatus {
        perfLocked(lock) {
            for (monitor in monitors.ifEmpty { PerfMonitor.entries.toSet() }) {
                if (owners.clear(monitor)) stopLocked(monitor)
            }
            announceRunning()
            backend.leaseChanged(owners.heldMonitors)
        }
        return status()
    }

    override fun releaseTail(session: Long) {
        perfLocked(lock) {
            owners.removeTail(session).forEach(::stopLocked)
            announceRunning()
            backend.leaseChanged(owners.heldMonitors)
        }
    }

    /** On the thread that carries the lease (the watchdog's, the sampler's, the frames handler's). */
    internal fun expireLeases() {
        perfLocked(lock) {
            owners.expire(backend.nowMs()).forEach(::stopLocked)
            announceRunning()
            backend.leaseChanged(owners.heldMonitors)
        }
    }

    private fun stopLocked(monitor: PerfMonitor) {
        since.remove(monitor)
        backend.stop(monitor)
    }

    override fun addRunningListener(listener: (Set<PerfMonitor>) -> Unit): () -> Unit {
        perfLocked(lock) {
            runningListeners += listener
            listener(announced)
        }
        return { perfLocked(lock) { runningListeners.remove(listener) } }
    }

    /** Under [lock], after the owners changed: tells the listeners when the set of running monitors did. */
    private fun announceRunning() {
        val now = owners.heldMonitors.toSet()
        if (now == announced) return
        announced = now
        for (listener in runningListeners.toList()) listener(now)
    }

    internal val running: Set<PerfMonitor> get() = perfLocked(lock) { owners.heldMonitors.toSet() }

    override fun status(): PerfStatus = perfLocked(lock) {
        val wallNow = perfWallClockMs()
        val monoNow = backend.nowMs()
        val running = PerfMonitor.entries.filter { owners.held(it) }.map { monitor ->
            PerfMonitorStatus(
                monitor = monitor,
                sinceMs = since[monitor] ?: wallNow,
                owners = owners.describe(monitor),
                expiresAtMs = owners.leaseUntil(monitor)?.let { wallNow + (it - monoNow) },
                details = backend.details(monitor),
            )
        }
        PerfStatus(backend.platform, PerfMonitor.entries.filter { it in backend.available }, remoteControl, running, lastNotices.load())
    }

    override fun threads(limit: Int): List<PerfThread>? = backend.threads(limit)

    /** Viewers of the per-thread list (tail sessions): the scan runs while one is left. */
    private var threadWatchers = 0

    override fun watchThreads(watching: Boolean) {
        perfLocked(lock) {
            val before = threadWatchers
            threadWatchers = if (watching) before + 1 else (before - 1).coerceAtLeast(0)
            if ((before == 0) != (threadWatchers == 0)) backend.watchThreads(threadWatchers > 0)
        }
    }

    override fun measurePss(): PerfPss = backend.measurePss()

    override fun watchCadence(watching: Boolean) {
        perfLocked(lock) { backend.watchCadence(watching) }
    }

    /** A notice line, whatever `summaryLog` says: a start that did less than asked must be visible. */
    internal fun notice(message: String) {
        val sink = data.log
        if (sink != null) sink.notice(message) else emitPerfLogLine(PerfJson.noticeLine(message))
    }
}

/**
 * What perf's tool calls ([PerfEntry] on each platform): [install] creates the controller once per
 * process and applies the tool's settings, [registerApi] registers `HopiPerf`'s runtime. On Android
 * the tool runs on the main thread before `Application.onCreate`, and a debug build runs from its
 * APK, so ART loads every class a method names when it verifies its class: nothing here names a
 * monitor class, a store class or [ApiPerfCalls], and the tool's path adds no lambda and no Kotlin
 * collection extension.
 */
internal object PerfRuntime {
    private val instance = AtomicReference<PerfRuntimeController?>(null)

    private val launchRead = AtomicBoolean(false)

    fun controller(): PerfRuntimeController {
        val existing = instance.load()
        if (existing != null) return existing
        val created = PerfRuntimeController(createBackend())
        if (instance.compareAndSet(null, created)) PerfHook.register(created)
        return instance.load() ?: created
    }

    /** The controller, once a tool start created it; null before. */
    fun existing(): PerfRuntimeController? = instance.load()

    /**
     * A tool's start: an out-of-range [settings] writes a notice line and starts nothing (null);
     * otherwise it creates the controller or reuses the process's, applies [settings] whole, and the
     * first time starts the monitors the launch arguments name, as owner `code`.
     */
    fun install(settings: PerfSettings): PerfRuntimeController? {
        val problem = invalid(settings.hangThresholdMs, settings.sampleIntervalMs)
        if (problem != null) {
            emitPerfLogLine(PerfJson.noticeLine("HopiPerf: $problem; nothing started"))
            return null
        }
        val controller = controller()
        controller.configure(settings)
        if (launchRead.compareAndSet(false, true)) {
            val launched = LinkedHashSet<PerfMonitor>()
            for (monitor in launchArgumentMonitors()) if (monitor in controller.available) launched.add(monitor)
            if (launched.isNotEmpty()) controller.start(PerfStartRequest(launched), PerfOwner.Code)
        }
        return controller
    }

    /**
     * The step every tool takes after [install]: registers `HopiPerf`'s runtime ([ApiPerf]) with
     * `hopi-api`'s registry and, when that succeeds, applies the last label `HopiPerf.screen` kept
     * and writes one notice line with the number of `HopiPerf.start` calls dropped before it (the
     * count only), in perf's channel (logcat tag `HopiPerf`, os_log category `perf`, stderr on the
     * JVM), whatever `summaryLog` says. A later tool start finds it registered and does neither.
     */
    fun registerApi(controller: PerfRuntimeController) {
        val dropped = ApiPerf.register()
        if (dropped < 0) return
        // HopiPerf.screen keeps its label before it reads the slot, which is written now: applied
        // until two reads agree, so a label set meanwhile is not overwritten by an older one.
        var label = HopiRuntimes.perfScreen
        while (true) {
            controller.data.setScreen(label)
            val latest = HopiRuntimes.perfScreen
            if (latest == label) break
            label = latest
        }
        if (dropped > 0) controller.notice(droppedStartsNotice(dropped))
    }

    /** What is out of range among the values given (null: not given), or null when nothing is. */
    fun invalid(hangThresholdMs: Long?, sampleIntervalMs: Long?): String? = when {
        hangThresholdMs != null && hangThresholdMs !in PerfLimits.MIN_HANG_THRESHOLD_MS..PerfLimits.MAX_HANG_THRESHOLD_MS ->
            "hangThresholdMs must be in ${PerfLimits.MIN_HANG_THRESHOLD_MS}..${PerfLimits.MAX_HANG_THRESHOLD_MS}"
        sampleIntervalMs != null && sampleIntervalMs !in PerfLimits.MIN_SAMPLE_INTERVAL_MS..PerfLimits.MAX_SAMPLE_INTERVAL_MS ->
            "sampleIntervalMs must be in ${PerfLimits.MIN_SAMPLE_INTERVAL_MS}..${PerfLimits.MAX_SAMPLE_INTERVAL_MS}"
        else -> null
    }

    /** The count only, as the rules' notice. */
    private fun droppedStartsNotice(count: Int): String =
        if (count == 1) {
            "HopiPerf: 1 start made before Hopi started was dropped"
        } else {
            "HopiPerf: $count starts made before Hopi started were dropped"
        }
}
