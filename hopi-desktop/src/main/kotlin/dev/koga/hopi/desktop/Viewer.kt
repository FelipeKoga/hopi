package dev.koga.hopi.desktop

import dev.koga.hopi.connect.AdbDevice
import dev.koga.hopi.connect.BridgeEndpoint
import dev.koga.hopi.connect.CliException
import dev.koga.hopi.connect.Platform
import dev.koga.hopi.connect.RemoteSession
import dev.koga.hopi.connect.RemoteState
import dev.koga.hopi.connect.Targets
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Why the link is down, which says what brings it back. */
internal enum class WaitKind {
    /** The link dropped; attempts are under way. */
    Reconnecting,

    /** Nothing serves the app: it reconnects when the app starts (a bridge file or a debuggable process appears). */
    AppStopped,

    /** adb does not list the device as ready: it reconnects when adb does. */
    DeviceOffline,

    /** Anything else (a proof mismatch, an app not installed): Reconnect tries again. */
    Failed,
}

internal sealed interface ViewerStatus {
    data object Idle : ViewerStatus

    data class Connecting(val target: Target) : ViewerStatus

    /** [app] and [platform] as the app's `hello` reported them. */
    data class Connected(val target: Target, val app: String, val platform: String) : ViewerStatus

    /** [message] is the connection's own error (never the token). */
    data class Waiting(val target: Target, val kind: WaitKind, val message: String) : ViewerStatus
}

/**
 * The desktop viewer's connection logic, without UI: what can be reached ([discovery]), the one
 * [Target] being viewed, its [RemoteSession] and [status], and the events that reconnect it.
 *
 * Every reconnect is an event, never a poll: `adb track-devices` (a device becomes ready again
 * after an adb reset), `adb track-jdwp` on the target's device (a debuggable process started:
 * the app restarted), a watch on the bridge file's directory while waiting for the app (iOS
 * simulator and JVM: a restarted app writes a new `bridge.json`), and the session's own
 * disconnect (one attempt at once, which is all an adb forward dropped under a running app
 * needs). The JDK's watch service polls on macOS ([PathWatcher]), so both watches run only while
 * there is something to wait for: the bridge file's while [ViewerStatus.Waiting], the JVM bridge
 * root's (new JVM processes in the picker) while the window has focus and nothing is connected.
 * Connected, or in the background, the viewer runs no watch at all. Android's process event comes before
 * the app binds its bridge, so on Android an event is followed by at most [settleDelaysMs]`.size`
 * further attempts at those delays, cancelled by success or by any newer event; nothing is
 * scheduled while no event happened. Each attempt is one [RemoteSession.connect], which closes the
 * previous connections and adb forward first, so the app never serves more than this session's two.
 *
 * All state changes run on one thread ("hopi-desktop-connect"); callers never block.
 */
internal class Viewer(
    private val targets: Targets,
    private val jvmBridgeRoot: Path? = defaultJvmBridgeRoot(),
    private val newSession: (open: () -> BridgeEndpoint) -> RemoteSession = { RemoteSession(it) },
    private val settleDelaysMs: List<Long> = ANDROID_SETTLE_DELAYS_MS,
) : AutoCloseable {
    private val worker = ScheduledThreadPoolExecutor(1) { Thread(it, "hopi-desktop-connect").apply { isDaemon = true } }.apply {
        removeOnCancelPolicy = true
    }
    private val exports = Executors.newSingleThreadExecutor { Thread(it, "hopi-desktop-export").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _discovery = MutableStateFlow(Discovery.Empty)
    private val _status = MutableStateFlow<ViewerStatus>(ViewerStatus.Idle)
    private val _session = MutableStateFlow<RemoteSession?>(null)
    private val _notice = MutableStateFlow<String?>(null)

    val discovery: StateFlow<Discovery> = _discovery.asStateFlow()
    val status: StateFlow<ViewerStatus> = _status.asStateFlow()

    /** The session of the current target, or null; its `store` holds what the list shows. */
    val session: StateFlow<RemoteSession?> = _session.asStateFlow()

    /** The last export's outcome, for the status bar. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    // Written on the worker thread only. The closeables are also read by [close], from any thread.
    private var target: Target? = null

    @Volatile
    private var current: RemoteSession? = null
    private var stateWatch: Job? = null

    /** Android: `track-jdwp` on the target's device. */
    @Volatile
    private var targetWatch: AutoCloseable? = null

    /** iOS simulator and JVM: the target's `bridge.json`, watched only while waiting ([bridgeFileWatch]). */
    private var bridgeFile: Path? = null

    @Volatile
    private var bridgeFileWatch: AutoCloseable? = null

    @Volatile
    private var deviceTracking: AutoCloseable? = null

    @Volatile
    private var jvmRootWatch: AutoCloseable? = null
    private var windowFocused = false

    /** adb's latest device list from `track-devices`; null before the first one or without adb. */
    private var androids: List<AdbDevice>? = null

    /** Bumped by every event and command; a scheduled settle attempt of an older epoch does nothing. */
    private var epoch = 0L

    @Volatile
    private var closed = false

    /** Starts device tracking and the first discovery. */
    fun start(): Unit = run {
        startDeviceTracking(restartOnEnd = true)
        discoverNow()
    }

    /** The window gained or lost focus: the picker's JVM processes are watched only while it has it. */
    fun windowFocused(focused: Boolean): Unit = run {
        windowFocused = focused
        updateWatches()
    }

    /** Lists the targets again (the picker's refresh). */
    fun refresh(): Unit = run { discoverNow() }

    /** Views [target] from now on: a fresh session (or the same one when it is the target already shown). */
    fun connect(target: Target): Unit = run {
        epoch++
        if (!target.sameAs(this.target) || current == null) {
            closeTarget()
            this.target = target
            val session = newSession { open(target) }
            current = session
            _session.value = session
            stateWatch = scope.launch { session.state.collect { run { onSessionState(session) } } }
            watchTarget(target)
        }
        attempt()
    }

    /** Tries the current target again now. */
    fun reconnect(): Unit = run {
        epoch++
        attempt()
    }

    /** Stops viewing: both connections, the adb forward and the watchers are closed, the list is emptied. */
    fun disconnect(): Unit = run {
        epoch++
        closeTarget()
        setStatus(ViewerStatus.Idle)
    }

    fun select(id: Long?) {
        _session.value?.select(id)
    }

    /** Clears the app's store, then the viewer's (the bridge's `clear`). */
    fun clear() {
        _session.value?.clear()
    }

    /** Writes the session to [file] as [format] on the export thread; [notice] reports the outcome. */
    fun export(format: ExportFormat, file: Path) {
        val session = _session.value ?: return
        try {
            exports.execute {
                _notice.value = "Exporting to ${file.fileName}…"
                _notice.value = try {
                    val result = Files.newBufferedWriter(file).use { out ->
                        SessionExport.write(format, session.store.value, out, fetch = session::fetch)
                    }
                    result.describe(file)
                } catch (e: IOException) {
                    "Export failed: ${e.message}"
                }
            }
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
    }

    /** Shows [text] in the status bar, where exports report too (a replay's outcome). */
    fun notify(text: String?) {
        _notice.value = text
    }

    override fun close() {
        if (closed) return
        closed = true
        // Here, not on the worker: a connect can hold it longer than anyone waits (an app stopped
        // at a breakpoint answers `hello` only at the read timeout), and each of these closes from
        // any thread. The adb tracking subprocesses must not outlive the viewer.
        current?.close()
        targetWatch?.close()
        bridgeFileWatch?.close()
        deviceTracking?.close()
        jvmRootWatch?.close()
        try {
            // Anything the worker opened meanwhile (no new task starts once closed).
            worker.submit {
                closeTarget()
                deviceTracking?.close()
                jvmRootWatch?.close()
            }.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // The worker is still in a connect, of a session closed above.
        }
        worker.shutdownNow()
        exports.shutdownNow()
        scope.cancel()
    }

    /** Worker: every status change goes through here, since it decides which watches run. */
    private fun setStatus(status: ViewerStatus) {
        _status.value = status
        updateWatches()
    }

    /**
     * Worker: holds each polling watch ([PathWatcher]) only while it is needed. The bridge file's
     * starts with a look ([PathWatcher] `notifyOnStart`), since the app may have written it
     * between the failed attempt and the watch.
     */
    private fun updateWatches() {
        val status = _status.value
        val root = jvmBridgeRoot
        if (!closed && root != null && windowFocused && status !is ViewerStatus.Connected) {
            if (jvmRootWatch == null) {
                jvmRootWatch = PathWatcher("hopi-desktop-watch-jvm", { jvmWatchDirectories(root) }, notifyOnStart = true) {
                    run { _discovery.value = Discovery(nonJvm() + jvmBridges(root), _discovery.value.notes) }
                }
            }
        } else {
            jvmRootWatch?.close()
            jvmRootWatch = null
        }
        val file = bridgeFile
        if (!closed && file != null && status is ViewerStatus.Waiting) {
            if (bridgeFileWatch == null) bridgeFileWatch = watchBridgeFile(file)
        } else {
            bridgeFileWatch?.close()
            bridgeFileWatch = null
        }
    }

    private fun run(task: () -> Unit) {
        if (closed) return
        try {
            worker.execute(task)
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
    }

    private fun open(target: Target): BridgeEndpoint {
        val device = target.device
        return when (device) {
            is AndroidDevice -> targets.open(Platform.Android, target.app, device.serial, bridgeFile = null)
            is SimulatorDevice -> targets.open(Platform.Ios, target.app, device.udid, bridgeFile = null)
            is JvmBridge -> targets.open(Platform.Jvm, target.app, device = null, bridgeFile = device.file)
        }
    }

    /** Worker: one connect of the current session, then [status] from its outcome. */
    private fun attempt() {
        val session = current ?: return
        val target = target ?: return
        val before = _status.value
        setStatus(if (before is ViewerStatus.Waiting) ViewerStatus.Waiting(target, WaitKind.Reconnecting, before.message) else ViewerStatus.Connecting(target))
        session.connect()
        if (session !== current) return
        setStatus(
            when (val state = session.state.value) {
                is RemoteState.Connected -> ViewerStatus.Connected(target, state.app, state.platform)
                is RemoteState.Disconnected -> ViewerStatus.Waiting(target, classify(target, state.message), state.message)
                else -> _status.value
            },
        )
    }

    /** Worker: [attempt] now and, on Android, after each of [settleDelaysMs] while this is the newest event. */
    private fun settle() {
        val mine = ++epoch
        attempt()
        if (target?.device !is AndroidDevice) return
        var at = 0L
        for (delay in settleDelaysMs) {
            at += delay
            worker.schedule({
                if (epoch == mine && !closed && _status.value is ViewerStatus.Waiting) attempt()
            }, at, TimeUnit.MILLISECONDS)
        }
    }

    /** Worker: a connected session that reports a disconnect lost its link (the app or adb closed it). */
    private fun onSessionState(session: RemoteSession) {
        if (session !== current) return
        val state = session.state.value
        val status = _status.value
        if (state is RemoteState.Disconnected && status is ViewerStatus.Connected) {
            setStatus(ViewerStatus.Waiting(status.target, WaitKind.Reconnecting, state.message))
            settle()
        }
    }

    private fun waiting(): Boolean = _status.value is ViewerStatus.Waiting

    /** Worker: the events that may bring [target] back: Android's process tracking, else the bridge file to watch while waiting. */
    private fun watchTarget(target: Target) {
        when (val device = target.device) {
            is AndroidDevice -> targetWatch = trackProcesses(device.serial)
            is SimulatorDevice -> {
                val app = target.app
                bridgeFile = try {
                    if (app != null) targets.iosBridgeFile(app, device.udid) else null
                } catch (_: CliException) {
                    // Not installed, say: connecting reports it, and Reconnect tries again.
                    null
                }
            }
            is JvmBridge -> bridgeFile = device.file
        }
    }

    private fun watchBridgeFile(file: Path): AutoCloseable =
        PathWatcher("hopi-desktop-watch-bridge", { PathWatcher.nearestExisting(file) }, notifyOnStart = true) {
            run { if (waiting()) settle() }
        }

    private fun trackProcesses(serial: String): AutoCloseable? = if (closed) null else try {
        targets.trackDebuggableProcesses(
            serial,
            onChange = {
                run {
                    // A process started or ended: its bridge may have come or gone in the picker too.
                    discoverNow()
                    if (waiting()) settle()
                }
            },
            // The device went away; track-devices restarts this when it is back.
            onEnd = { run { if ((target?.device as? AndroidDevice)?.serial == serial) targetWatch = null } },
        )
    } catch (_: CliException) {
        null
    }

    /** Worker. `track-devices` ends when the adb server stops; it is started again once, which starts a new server. */
    private fun startDeviceTracking(restartOnEnd: Boolean) {
        if (closed) return
        var received = false
        deviceTracking = try {
            targets.trackAndroidDevices(
                onDevices = { list ->
                    received = true
                    run { onDevices(list) }
                },
                onEnd = { run { if (restartOnEnd || received) startDeviceTracking(restartOnEnd = false) } },
            )
        } catch (_: CliException) {
            null
        }
    }

    /** Worker: adb's device list changed. */
    private fun onDevices(list: List<AdbDevice>) {
        androids = list
        _discovery.value = discover(targets, jvmBridgeRoot, androids = list)
        val device = target?.device as? AndroidDevice ?: return
        val now = list.firstOrNull { it.serial == device.serial }
        val status = _status.value
        if (now?.isReady == true) {
            if (targetWatch == null) targetWatch = trackProcesses(device.serial)
            if (status is ViewerStatus.Waiting) settle()
        } else if (status is ViewerStatus.Waiting) {
            epoch++
            setStatus(ViewerStatus.Waiting(status.target, WaitKind.DeviceOffline, "${device.serial} is ${now?.state ?: "not attached"}"))
        }
    }

    private fun discoverNow() {
        _discovery.value = discover(targets, jvmBridgeRoot, androids)
    }

    private fun nonJvm(): List<Device> = _discovery.value.devices.filter { it !is JvmBridge }

    /** Worker: closes the session (both connections, its adb forward) and the target's watcher. */
    private fun closeTarget() {
        targetWatch?.close()
        targetWatch = null
        bridgeFileWatch?.close()
        bridgeFileWatch = null
        bridgeFile = null
        stateWatch?.cancel()
        stateWatch = null
        current?.close()
        current = null
        target = null
        _session.value = null
    }

    /**
     * What the connection's error means for reconnecting. The messages are [Targets]' own: "app
     * not running" (Android, iOS), "process not running" and a missing bridge file (JVM).
     */
    private fun classify(target: Target, message: String): WaitKind {
        val device = target.device
        if (device is AndroidDevice) {
            val listed = androids?.firstOrNull { it.serial == device.serial }
            if (androids != null && listed?.isReady != true) return WaitKind.DeviceOffline
            if (message.startsWith("adb lost its connection")) return WaitKind.DeviceOffline
        }
        return when {
            message.startsWith("app not running") -> WaitKind.AppStopped
            message.startsWith("process not running") -> WaitKind.AppStopped
            message.startsWith("bridge file ") && "not found" in message -> WaitKind.AppStopped
            else -> WaitKind.Failed
        }
    }

    private fun jvmWatchDirectories(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return PathWatcher.nearestExisting(root.resolve("x"))
        return listOf(root) + subdirectories(root)
    }

    private fun subdirectories(root: Path): List<Path> = try {
        Files.list(root).use { children -> children.filter { Files.isDirectory(it) }.toList() }
    } catch (_: IOException) {
        emptyList()
    }

    companion object {
        /**
         * After an Android event, the app may not have bound its bridge yet: attempts follow at these
         * delays (cumulative: 0.3, 1, 2.5 and 5.5 s after the first). Empty turns them off.
         */
        val ANDROID_SETTLE_DELAYS_MS: List<Long> = listOf(300, 700, 1_500, 3_000)
    }
}
