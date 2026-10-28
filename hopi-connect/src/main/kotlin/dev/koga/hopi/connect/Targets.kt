package dev.koga.hopi.connect

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.json.parseFlatJsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A bridge reachable on host loopback ([port]), plus whatever must be undone when the command ends
 * (the adb forward). [token] is sent as the first line on token transports and never printed.
 */
@HopiInternalApi
public class BridgeEndpoint(
    public val port: Int,
    public val token: String?,
    public val platform: Platform,
    /** The app `hello` must report; null accepts any (a JVM bridge file without `--app`). */
    public val app: String?,
    /** Why a connection that closes without a byte means nothing serves the app. */
    public val unreachableMessage: String,
    /**
     * Asked when an established connection ends: why the host side lost it, or null when nothing
     * says so (then the app closed it). Android checks that adb still holds the forward.
     */
    public val transportLost: () -> String? = { null },
    /** Android: `hello` must carry the proof the app wrote to its private storage. */
    public val proof: ProofCheck? = null,
    private val cleanup: () -> Unit = {},
) : AutoCloseable {
    override fun close(): Unit = cleanup()

    override fun toString(): String = "BridgeEndpoint(port=$port, platform=${platform.id}, app=$app)"
}

/** Finds the attached targets and opens the bridge endpoint a command asked for. */
@HopiInternalApi
public class Targets(private val environment: HostEnvironment) {
    private val adb: Adb? by lazy { locateAdb(environment)?.let { Adb(it, environment.processes) } }
    private val simctl: Simctl? by lazy {
        if (environment.isMac && environment.isExecutable(Simctl.XCRUN)) Simctl(Simctl.XCRUN, environment.processes) else null
    }

    /** Null when adb is not installed. */
    public fun androidDevices(): List<AdbDevice>? = adb?.devices()

    /** Null when this host has no simulators (not macOS, or no Xcode). */
    public fun simulators(): List<Simulator>? = simctl?.booted()

    /** The Hopi bridges listening on Android device [serial] (see [Adb.bridges]); empty when adb cannot tell. */
    public fun androidBridges(serial: String): List<String> = adb?.bridges(serial).orEmpty()

    /**
     * Follows `adb track-devices`: [onDevices] gets adb's device list at once and whenever it
     * changes (a device attached, detached, gone offline, an adb reset), on one thread blocked on
     * adb's output, never a poll. [onEnd] is called when adb ends the stream (the adb server
     * stopped), not after [AutoCloseable.close]. Null when adb is not installed.
     */
    public fun trackAndroidDevices(onDevices: (List<AdbDevice>) -> Unit, onEnd: () -> Unit): AutoCloseable? {
        val adb = adb ?: return null
        // `-l` as `adb devices -l`: with the model, so an update does not drop what the list showed.
        return track(adb.command("track-devices", "-l"), "hopi-adb-track-devices", onEnd) { frame ->
            onDevices(parseAdbDevices(frame))
        }
    }

    /**
     * Follows `adb -s <serial> track-jdwp`, the debuggable processes of [serial]: [onChange] is
     * called whenever one starts or ends after the first list (an app restart; Hopi's bridge
     * only serves debuggable builds). The process may not have bound its bridge yet when it is
     * listed. [onEnd] as in [trackAndroidDevices]. Null when adb is not installed.
     */
    public fun trackDebuggableProcesses(serial: String, onChange: () -> Unit, onEnd: () -> Unit): AutoCloseable? {
        val adb = adb ?: return null
        var last: Set<Int>? = null
        return track(adb.command("-s", serial, "track-jdwp"), "hopi-adb-track-jdwp", onEnd) { frame ->
            val pids = parseJdwpPids(frame)
            val previous = last
            last = pids
            if (previous != null && previous != pids) onChange()
        }
    }

    private fun track(command: List<String>, name: String, onEnd: () -> Unit, onFrame: (String) -> Unit): AutoCloseable {
        val process = try {
            environment.streams.start(command)
        } catch (e: IOException) {
            throw notReachable("cannot run adb at ${command.first()}: ${e.message}")
        }
        val closed = AtomicBoolean(false)
        Thread({
            try {
                while (true) onFrame(readTrackFrame(process.input) ?: break)
            } catch (_: IOException) {
                // Closed, or adb went away: both end the stream.
            }
            process.close()
            if (!closed.get()) onEnd()
        }, name).apply { isDaemon = true }.start()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) process.close()
        }
    }

    /**
     * Where [app]'s bridge on the booted simulator [device] (the only one when null) writes its
     * `bridge.json`; the file need not exist. Throws [CliException] like [open].
     */
    public fun iosBridgeFile(app: String, device: String?): Path = iosBridge(app, device).first

    /**
     * The bridge of [app] on [platform] (inferred from the attached targets when null), on [device]
     * when several are attached; a JVM bridge is found by its [bridgeFile] and accepts any app when
     * [app] is null. The caller closes the endpoint (Android: it removes the adb forward).
     */
    public fun open(platform: Platform?, app: String?, device: String?, bridgeFile: Path?): BridgeEndpoint =
        when (platform ?: inferPlatform(device)) {
            Platform.Android -> openAndroid(requireApp(app), device)
            Platform.Ios -> openIos(requireApp(app), device)
            Platform.Jvm -> openJvm(bridgeFile ?: throw usageError("--platform jvm needs --bridge-file <path>"), app)
        }

    private fun requireApp(app: String?): String = app ?: throw usageError("android and ios targets need --app <id>")

    /** The platform that has a target: the one listing [device], else the only one with a ready target. */
    private fun inferPlatform(device: String?): Platform {
        val androids = discover { androidDevices() }
        val simulators = discover { simulators() }
        if (device != null) {
            if (androids?.any { it.serial == device } == true) return Platform.Android
            if (simulators?.any { it.udid == device } == true) return Platform.Ios
            throw notReachable("device $device is neither listed by adb devices nor a booted simulator")
        }
        val hasAndroid = androids?.any(AdbDevice::isReady) == true
        val hasSimulator = !simulators.isNullOrEmpty()
        return when {
            hasAndroid && hasSimulator -> throw usageError(
                "an Android device and a booted iOS simulator are both available: pass --platform android or --platform ios",
            )
            hasAndroid -> Platform.Android
            hasSimulator -> Platform.Ios
            androids == null && simulators == null -> throw notReachable(
                "no device: adb not found (set ANDROID_HOME or put adb on PATH) and no iOS simulator tools",
            )
            else -> throw notReachable("no device: no adb device is ready and no iOS simulator is booted")
        }
    }

    /** A tool that fails while inferring counts as having no targets; an explicit platform reports it. */
    private fun <T> discover(list: () -> List<T>?): List<T>? = try {
        list()
    } catch (_: CliException) {
        emptyList()
    }

    private fun openAndroid(app: String, device: String?): BridgeEndpoint {
        val adb = adb ?: throw notReachable("adb not found: set ANDROID_HOME, or put adb on PATH")
        val devices = adb.devices()
        val serial = pickDevice(
            device,
            devices.map { Candidate(it.serial, it.serial, it.isReady, it.state) },
            kind = "Android device",
            none = "no Android device is ready: start an emulator or connect a phone (adb devices)",
        )
        val socketName = "hopi.$app"
        // The hook is registered before the forward exists, so a SIGINT right after adb printed the
        // port still removes it; it does nothing until the port is known.
        val port = AtomicInteger(0)
        val removed = AtomicBoolean(false)
        val remove = {
            val forwarded = port.get()
            if (forwarded != 0 && removed.compareAndSet(false, true)) adb.removeForward(serial, forwarded)
        }
        val hook = Thread(remove, "hopi-cli-unforward")
        environment.shutdownHooks.add(hook)
        try {
            port.set(adb.forward(serial, socketName))
        } catch (e: Throwable) {
            environment.shutdownHooks.remove(hook)
            throw e
        }
        return BridgeEndpoint(
            port = port.get(),
            token = null,
            platform = Platform.Android,
            app = app,
            unreachableMessage = "app not running or agentBridge = false: nothing serves localabstract:$socketName on $serial",
            transportLost = {
                if (adb.hasForward(serial, port.get()) == false) {
                    "adb lost its connection to $serial and dropped the forward; the app may still be running: run the command again"
                } else {
                    null
                }
            },
            // Read only once hello arrived, so an app that is not running still reads as not running.
            proof = ProofCheck(
                expected = { adb.readProof(serial, app) },
                mismatch = "localabstract:$socketName on $serial is not $app's bridge (its hello lacks the proof $app wrote): " +
                    "another app may hold the socket name; if $app just restarted, run the command again",
            ),
        ) {
            remove()
            environment.shutdownHooks.remove(hook)
        }
    }

    private fun openIos(app: String, device: String?): BridgeEndpoint {
        val (file, simulator) = iosBridge(app, device)
        val unreachable = "app not running or agentBridge = false: no bridge answers for $app on ${simulator.name}"
        val (port, token) = readBridgeFile(file, missing = unreachable)
        return BridgeEndpoint(port, token, Platform.Ios, app, unreachable)
    }

    private fun iosBridge(app: String, device: String?): Pair<Path, Simulator> {
        val simctl = simctl ?: throw notReachable("iOS simulators need macOS with Xcode: /usr/bin/xcrun not found")
        val simulators = simctl.booted()
        val udid = pickDevice(
            device,
            simulators.map { Candidate(it.udid, "${it.name} (${it.udid})", ready = true, it.state) },
            kind = "booted simulator",
            none = "no iOS simulator is booted: boot one (xcrun simctl boot <udid>) and launch the app",
        )
        val simulator = simulators.first { it.udid == udid }
        return simctl.dataContainer(simulator, app).resolve(IOS_BRIDGE_FILE) to simulator
    }

    private fun openJvm(file: Path, app: String?): BridgeEndpoint {
        val unreachable = "process not running or agentBridge = false: no bridge answers for $file"
        val (port, token) = readBridgeFile(file, missing = "bridge file $file not found: process not running or agentBridge = false")
        return BridgeEndpoint(port, token, Platform.Jvm, app, unreachable)
    }

    private class Candidate(val id: String, val label: String, val ready: Boolean, val state: String)

    private fun pickDevice(requested: String?, candidates: List<Candidate>, kind: String, none: String): String {
        if (requested != null) {
            val match = candidates.firstOrNull { it.id == requested }
                ?: throw notReachable("$kind $requested not found${listing(candidates)}")
            if (!match.ready) throw notReachable("$kind $requested is ${match.state}")
            return match.id
        }
        val ready = candidates.filter { it.ready }
        return when (ready.size) {
            1 -> ready[0].id
            0 -> throw notReachable(none + notReadyListing(candidates))
            else -> throw usageError("several ${kind}s: pass --device <id> (${ready.joinToString { it.label }})")
        }
    }

    private fun listing(candidates: List<Candidate>): String =
        if (candidates.isEmpty()) "" else " (available: ${candidates.joinToString { it.label }})"

    private fun notReadyListing(candidates: List<Candidate>): String =
        if (candidates.isEmpty()) "" else " (${candidates.joinToString { "${it.label} is ${it.state}" }})"

    public companion object {
        /** Where the iOS bridge writes its `bridge.json`, relative to the app's data container. */
        public const val IOS_BRIDGE_FILE: String = "Library/Caches/hopi/bridge.json"
    }
}

/** What a target id may look like before it reaches a subprocess's argument array. */
@HopiInternalApi
public object TargetIds {
    /**
     * Application ids, bundle ids and Android process names (`com.example:remote`). Values reach
     * subprocess argument arrays, so anything that could read as an option or a path is refused.
     */
    public val APP: Regex = Regex("[A-Za-z0-9_][A-Za-z0-9._:-]{0,254}")

    /** adb serials (`emulator-5554`, `192.168.1.20:5555`) and simulator UDIDs. */
    public val DEVICE: Regex = Regex("[A-Za-z0-9_][A-Za-z0-9._:-]{0,127}")
}

private val TOKEN = Regex("[0-9a-f]{64}")

/** `{"port":N,"token":"…"}` as the iOS and JVM bridges write it. Errors name the file, never its content. */
@HopiInternalApi
public fun readBridgeFile(file: Path, missing: String): Pair<Int, String> {
    val text = try {
        Files.readString(file)
    } catch (_: NoSuchFileException) {
        throw notReachable(missing)
    } catch (e: IOException) {
        throw notReachable("cannot read $file: ${e.message}")
    }
    val fields = parseFlatJsonObject(text)
    val port = (fields?.get("port") as? Long)?.takeIf { it in 1..65535 }?.toInt()
    val token = (fields?.get("token") as? String)?.takeIf { TOKEN.matches(it) }
    if (port == null || token == null) throw notReachable("$file is not a Hopi bridge file")
    return port to token
}
