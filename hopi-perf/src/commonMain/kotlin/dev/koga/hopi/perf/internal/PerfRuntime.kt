package dev.koga.hopi.perf.internal

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.bridge.AgentBridgeHook
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
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.perf.HopiMonitor
import dev.koga.hopi.perf.HopiPerfConfig
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/** The settings in force; a `perf.start` with `hangMs` or `intervalMs` changes them. */
@HopiInternalApi
public class PerfSettings(
    public val hangThresholdMs: Long,
    public val hangHeartbeat: Boolean,
    public val sampleIntervalMs: Long,
    public val remoteControl: Boolean,
    public val summaryLog: Boolean,
) {
    internal fun with(hangThresholdMs: Long? = null, sampleIntervalMs: Long? = null): PerfSettings =
        PerfSettings(hangThresholdMs ?: this.hangThresholdMs, hangHeartbeat, sampleIntervalMs ?: this.sampleIntervalMs, remoteControl, summaryLog)

    internal companion object {
        val DEFAULT = PerfSettings(PerfLimits.DEFAULT_HANG_THRESHOLD_MS, false, PerfLimits.DEFAULT_SAMPLE_INTERVAL_MS, remoteControl = true, summaryLog = true)
    }
}

/**
 * One platform's monitors. Every call is made under the runtime's lock, so a backend sees starts
 * and stops in order; each returns at once (a stop never waits on a hung main thread).
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

/** The platform's backend; created on the first install, never before. */
internal expect fun createBackend(): MonitorBackend

/**
 * Monitors the process was launched to start (iOS: `-hopiPerf frames,hangs`, so an agent can
 * measure a cold start); read once, by the first install. Empty elsewhere.
 */
internal expect fun launchArgumentMonitors(): Set<PerfMonitor>

/** [block] under [lock]'s monitor (JVM `synchronized`; iOS a spin lock): starts and stops are rare. */
internal expect fun <T> perfLocked(lock: Any, block: () -> T): T

/**
 * The [PerfController] that `hopi-perf` registers in core's `PerfHook`: owners, leases, settings
 * and status, over the platform's [MonitorBackend]. Created on the first [HopiPerf] call that needs
 * it (install, start, or the Android provider), so an app that never calls it loads nothing more.
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

    internal fun configure(next: PerfSettings) {
        val previous = settings.exchange(next)
        data.setLogSink(if (next.summaryLog) data.log ?: PerfLogSink() else null)
        if (previous.hangThresholdMs != next.hangThresholdMs || previous.sampleIntervalMs != next.sampleIntervalMs) {
            perfLocked(lock) { backend.apply(next, owners.heldMonitors) }
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
                }
            }
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

    /** Viewers of the per-thread list (tail sessions, the in-app Perf tab): the scan runs while one is left. */
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

/** What [dev.koga.hopi.perf.HopiPerf] calls. */
internal object PerfRuntime {
    private val instance = AtomicReference<PerfRuntimeController?>(null)

    /** A label set before install, applied when the runtime exists. */
    private val pendingScreen = AtomicReference<String?>(null)

    private val launchRead = AtomicBoolean(false)

    fun controller(): PerfRuntimeController {
        instance.load()?.let { return it }
        val created = PerfRuntimeController(createBackend())
        if (instance.compareAndSet(null, created)) {
            PerfHook.register(created)
            pendingScreen.load()?.let { created.data.setScreen(it) }
        }
        return instance.load() ?: created
    }

    fun existing(): PerfRuntimeController? = instance.load()

    fun install(config: HopiPerfConfig): PerfRuntimeController? {
        invalid(config)?.let {
            emitPerfLogLine(PerfJson.noticeLine("HopiPerf: $it; nothing started"))
            return null
        }
        val controller = controller()
        controller.configure(
            PerfSettings(config.hangThresholdMs, config.hangHeartbeat, config.sampleIntervalMs, config.remoteControl, config.summaryLog),
        )
        if (config.agentBridge) AgentBridgeHook.requestStart(HopiStore.default)
        if (launchRead.compareAndSet(false, true)) {
            val launched = launchArgumentMonitors().filterTo(LinkedHashSet()) { it in controller.available }
            if (launched.isNotEmpty()) controller.start(PerfStartRequest(launched), PerfOwner.Code)
        }
        return controller
    }

    fun start(config: HopiPerfConfig) {
        val controller = install(config) ?: return
        val monitors = buildSet {
            if (config.frames) add(PerfMonitor.FRAMES)
            if (config.hangs) add(PerfMonitor.HANGS)
            if (config.cpu) add(PerfMonitor.CPU)
            if (config.memory) add(PerfMonitor.MEMORY)
        }
        if (monitors.isEmpty()) return
        val available = monitors.filterTo(LinkedHashSet()) { it in controller.available }
        (monitors - available).forEach { controller.notice("${it.wireName} is not available on ${controller.platform}") }
        if (available.isNotEmpty()) controller.start(PerfStartRequest(available), PerfOwner.Code)
    }

    fun stop(monitors: Set<HopiMonitor>) {
        val controller = existing() ?: return
        controller.stop(monitors.map { it.monitor }.toSet())
    }

    fun running(): Set<HopiMonitor> = existing()?.running?.map { it.consumer }?.toSet() ?: emptySet()

    fun screen(name: String?) {
        pendingScreen.store(PerfLimits.label(name))
        existing()?.data?.setScreen(name)
    }

    private fun invalid(config: HopiPerfConfig): String? = when {
        config.hangThresholdMs !in PerfLimits.MIN_HANG_THRESHOLD_MS..PerfLimits.MAX_HANG_THRESHOLD_MS ->
            "hangThresholdMs must be in ${PerfLimits.MIN_HANG_THRESHOLD_MS}..${PerfLimits.MAX_HANG_THRESHOLD_MS}"
        config.sampleIntervalMs !in PerfLimits.MIN_SAMPLE_INTERVAL_MS..PerfLimits.MAX_SAMPLE_INTERVAL_MS ->
            "sampleIntervalMs must be in ${PerfLimits.MIN_SAMPLE_INTERVAL_MS}..${PerfLimits.MAX_SAMPLE_INTERVAL_MS}"
        else -> null
    }

    private val HopiMonitor.monitor: PerfMonitor
        get() = when (this) {
            HopiMonitor.Frames -> PerfMonitor.FRAMES
            HopiMonitor.Hangs -> PerfMonitor.HANGS
            HopiMonitor.Cpu -> PerfMonitor.CPU
            HopiMonitor.Memory -> PerfMonitor.MEMORY
        }

    private val PerfMonitor.consumer: HopiMonitor
        get() = when (this) {
            PerfMonitor.FRAMES -> HopiMonitor.Frames
            PerfMonitor.HANGS -> HopiMonitor.Hangs
            PerfMonitor.CPU -> HopiMonitor.Cpu
            PerfMonitor.MEMORY -> HopiMonitor.Memory
        }
}
