package dev.koga.hopi.connect

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.json.parseFlatJsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A bridge reachable on host loopback ([port]), plus whatever must be undone when the command ends
 * (the adb forward, a USB iPhone's relay). [token] is sent as the first line on token transports
 * and never printed.
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
    private val devicectl: Devicectl? by lazy {
        if (environment.isMac && environment.isExecutable(Simctl.XCRUN)) Devicectl(Simctl.XCRUN, environment.processes, environment.shutdownHooks) else null
    }
    private val usbmuxd: Usbmuxd? by lazy {
        val socket = environment.usbmuxdSocket
        if (environment.isMac && environment.isExecutable(Simctl.XCRUN) && Files.exists(socket)) Usbmuxd(socket) else null
    }

    /** The devices devicectl listed, by [udidKey]. One it did not list, or a listing that failed, is asked about again. */
    private val described = HashMap<String, CoreDevice>()

    /** Null when adb is not installed. */
    public fun androidDevices(): List<AdbDevice>? = adb?.devices()

    /** Null when this host has no simulators (not macOS, or no Xcode). */
    public fun simulators(): List<Simulator>? = simctl?.booted()

    /**
     * The iPhones (and iPads) attached over USB: usbmuxd's `USB` entries, one per UDID. devicectl
     * names them; it runs only while an attached one is not named yet (a [Targets] keeps every
     * name devicectl gave), so a phone it did not list at first (not yet trusted, say) is named
     * once it does. Null when this host cannot reach them (not macOS, no Xcode, no usbmuxd socket).
     */
    public fun usbIphones(): List<UsbIphone>? {
        val attached = usbDevices() ?: return null
        val known = describe(attached.map { it.udid })
        return attached.map { device -> known[udidKey(device.udid)].let { UsbIphone(device.udid, it?.name, it?.os) } }
    }

    /** usbmuxd's USB entries whose UDID may reach a subprocess's arguments, one per UDID; null as [usbIphones]. */
    private fun usbDevices(): List<UsbmuxDevice>? = usbmuxd?.devices()
        ?.filter { it.connection == "USB" && TargetIds.DEVICE.matches(it.udid) }
        ?.distinctBy { udidKey(it.udid) }

    private fun describe(udids: List<String>): Map<String, CoreDevice> = synchronized(described) {
        if (udids.any { udidKey(it) !in described }) {
            // Names only: a devicectl that fails, or does not list a device, leaves it unnamed until a later call.
            val listed = try {
                devicectl?.devices().orEmpty()
            } catch (_: CliException) {
                emptyList()
            }
            for (device in listed) described[udidKey(device.udid)] = device
        }
        HashMap(described)
    }

    private fun udidKey(udid: String): String = udid.replace("-", "").uppercase()

    /** The Hopi bridges listening on Android device [serial] (see [Adb.bridges]); empty when adb cannot tell. */
    public fun androidBridges(serial: String): List<String> = adb?.bridges(serial).orEmpty()

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

    /**
     * The platform that has a target: the one listing [device], else the only one with a ready
     * target. A USB iPhone counts only when no adb device is ready and no simulator is booted, so
     * attaching one never changes which target a command finds.
     */
    private fun inferPlatform(device: String?): Platform {
        val androids = discover { androidDevices() }
        val simulators = discover { simulators() }
        if (device != null) {
            if (androids?.any { it.serial == device } == true) return Platform.Android
            if (simulators?.any { it.udid == device } == true) return Platform.Ios
            if (discover { usbDevices() }?.any { sameUdid(it.udid, device) } == true) return Platform.Ios
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
            !discover { usbDevices() }.isNullOrEmpty() -> Platform.Ios
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
        val devices = adb.devices().let { if (device == null) onePerPhone(it) else it }
        val serial = pickDevice(
            device,
            devices.map { Candidate(it.serial, it.serial, it.isReady, it.state) },
            kind = "Android device",
            none = "no Android device is ready: start an emulator or connect a phone (adb devices)",
        )
        val socketName = "hopi.$app"
        val unreachable = "app not running or agentBridge = false: nothing serves localabstract:$socketName on $serial"
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
        // The key is the connection's first line, so it is read before connecting. No file, no bridge.
        val secrets = try {
            adb.readProof(serial, app) ?: throw notReachable(unreachable)
        } catch (e: Throwable) {
            remove()
            environment.shutdownHooks.remove(hook)
            throw e
        }
        return BridgeEndpoint(
            port = port.get(),
            token = secrets.key,
            platform = Platform.Android,
            app = app,
            unreachableMessage = unreachable,
            transportLost = {
                if (adb.hasForward(serial, port.get()) == false) {
                    "adb lost its connection to $serial and dropped the forward; the app may still be running: run the command again"
                } else {
                    null
                }
            },
            proof = ProofCheck(
                expected = { secrets.proof },
                mismatch = "localabstract:$socketName on $serial is not $app's bridge (its hello lacks the proof $app wrote): " +
                    "another app may hold the socket name; if $app just restarted, run the command again",
            ),
        ) {
            remove()
            environment.shutdownHooks.remove(hook)
        }
    }

    private fun openIos(app: String, device: String?): BridgeEndpoint {
        val simctl = simctl ?: throw notReachable(NO_XCODE)
        val simulators = simctl.booted()
        usbIphoneTarget(device, simulators)?.let { return openUsb(app, it) }
        val (file, simulator) = simulatorBridge(simctl, simulators, app, device)
        val unreachable = "app not running or agentBridge = false: no bridge answers for $app on ${simulator.name}"
        val (port, token) = readBridgeFile(file, missing = unreachable)
        return BridgeEndpoint(port, token, Platform.Ios, app, unreachable)
    }

    /**
     * The USB iPhone to use: the one [device] names when no booted simulator has that UDID, or
     * the only one attached when [device] is null and no simulator is booted. Null leaves the
     * choice, and its errors, to the simulators exactly as before.
     */
    private fun usbIphoneTarget(device: String?, simulators: List<Simulator>): UsbmuxDevice? {
        if (device != null && simulators.any { it.udid == device }) return null
        if (device == null && simulators.isNotEmpty()) return null
        val attached = discover { usbDevices() }.orEmpty()
        if (attached.isEmpty()) return null
        // As usbmuxd writes the UDID, so --device may differ in case or in the dash.
        val requested = device?.let { named -> attached.firstOrNull { sameUdid(it.udid, named) }?.udid }
        if (device != null && requested == null) {
            val available = simulators.map { "${it.name} (${it.udid})" } + attached.map { usbLabel(it.udid) }
            throw notReachable("iOS device $device not found (available: ${available.joinToString()})")
        }
        val udid = pickDevice(
            requested,
            attached.map { Candidate(it.udid, usbLabel(it.udid), ready = true, "attached") },
            kind = "USB iPhone",
            none = "no USB iPhone is attached",
        )
        return attached.first { it.udid == udid }
    }

    /**
     * Reads the app's `bridge.json` from its data container on the device with devicectl (the
     * same path as on a simulator), then serves a relay on host loopback whose every connection
     * usbmuxd carries to the bridge's port on the device. The token check is the bridge's, as ever.
     */
    private fun openUsb(app: String, device: UsbmuxDevice): BridgeEndpoint {
        val usbmuxd = usbmuxd ?: throw notReachable("usbmuxd is not running: no socket at ${environment.usbmuxdSocket}")
        val devicectl = devicectl ?: throw notReachable(NO_XCODE_USB)
        val label = usbLabel(device.udid)
        val unreachable = "app not running or agentBridge = false: no bridge answers for $app on $label"
        val text = devicectl.readAppFile(device.udid, app, IOS_BRIDGE_FILE) { reason ->
            "$unreachable (devicectl could not copy $IOS_BRIDGE_FILE: $reason)"
        }
        val (port, token) = parseBridgeFile(text) ?: throw notReachable("$IOS_BRIDGE_FILE of $app on $label is not a Hopi bridge file")
        val relay = try {
            UsbmuxRelay(usbmuxd, device.deviceId, port)
        } catch (e: IOException) {
            throw notReachable("cannot listen on host loopback to relay to $label: ${e.message}")
        }
        return BridgeEndpoint(
            port = relay.localPort,
            token = token,
            platform = Platform.Ios,
            app = app,
            unreachableMessage = unreachable,
            transportLost = {
                if (detached(device.udid)) {
                    "$label is no longer attached (usbmuxd does not list it); the app may still be running: reconnect it and run the command again"
                } else {
                    null
                }
            },
        ) { relay.close() }
    }

    /** Whether usbmuxd answers and no longer lists [udid] over USB. */
    private fun detached(udid: String): Boolean = try {
        usbDevices()?.none { sameUdid(it.udid, udid) } == true
    } catch (_: CliException) {
        false
    }

    private fun usbLabel(udid: String): String = "$udid (USB)"

    private fun simulatorBridge(simctl: Simctl, simulators: List<Simulator>, app: String, device: String?): Pair<Path, Simulator> {
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

    /**
     * The apps whose bridge answers on this host: every ready adb device's `hopi.<app>` sockets,
     * every booted simulator's apps with a `bridge.json` in their data container, and the JVM
     * processes under [HostEnvironment.jvmBridgeRoot]. A simulator's or JVM process's file is kept
     * only when its bridge answers `hello` (a killed app leaves its file behind), and names the app.
     * iPhones on USB are not listed: reading their files takes seconds per app (`--device` names one).
     * [platform] and [device] narrow the scan; a tool that fails lists nothing for its platform.
     */
    public fun apps(platform: Platform? = null, device: String? = null): List<FoundApp> {
        val found = ArrayList<FoundApp>()
        if (platform == null || platform == Platform.Android) {
            discover { androidDevices() }?.filter { it.isReady && (device == null || it.serial == device) }?.let(::onePerPhone)?.forEach { d ->
                androidBridges(d.serial).forEach { app -> found += FoundApp(Platform.Android, d.serial, d.model ?: d.serial, app, null) }
            }
        }
        if (platform == null || platform == Platform.Ios) {
            discover { simulators() }?.filter { device == null || it.udid == device }?.forEach { sim ->
                val containers = Paths.get(environment.userHome, "Library/Developer/CoreSimulator/Devices", sim.udid, "data/Containers/Data/Application")
                childDirectories(containers).forEach { container ->
                    val file = container.resolve(IOS_BRIDGE_FILE)
                    probe(file, Platform.Ios)?.let { app -> found += FoundApp(Platform.Ios, sim.udid, sim.name, app, null) }
                }
            }
        }
        if ((platform == null || platform == Platform.Jvm) && device == null) {
            childDirectories(environment.jvmBridgeRoot).forEach { dir ->
                val file = dir.resolve("bridge.json")
                probe(file, Platform.Jvm)?.let { app -> found += FoundApp(Platform.Jvm, null, "JVM ${dir.fileName}", app, file) }
            }
        }
        return found
    }

    /**
     * [devices] with each phone's ready transports reduced to the first listed: adb lists a phone
     * twice once it is reached both by address and by mDNS name (or USB and Wi-Fi), which would
     * read as two devices. Asked (one `getprop` each) only when several are ready.
     */
    private fun onePerPhone(devices: List<AdbDevice>): List<AdbDevice> {
        val adb = adb ?: return devices
        if (devices.count { it.isReady } < 2) return devices
        val seen = HashSet<String>()
        return devices.filter { d -> !d.isReady || seen.add(adb.hardwareSerial(d.serial) ?: "transport:${d.serial}") }
    }

    private fun childDirectories(dir: Path): List<Path> = try {
        Files.list(dir).use { stream -> stream.filter { Files.isDirectory(it) }.sorted().toList() }
    } catch (_: IOException) {
        emptyList()
    }

    /** The app a `bridge.json`'s bridge names in `hello`; null when there is no file or nothing answers. */
    private fun probe(file: Path, platform: Platform): String? {
        if (!Files.isRegularFile(file)) return null
        val (port, token) = try {
            readBridgeFile(file, missing = "")
        } catch (_: CliException) {
            return null
        }
        return try {
            BridgeClient.connect(BridgeEndpoint(port, token, platform, null, ""), PROBE_TIMEOUT_MS).use { client ->
                client.handshake(null).json.str("app")?.takeIf { TargetIds.APP.matches(it) }
            }
        } catch (_: CliException) {
            null
        } catch (_: IOException) {
            null
        }
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

        private const val PROBE_TIMEOUT_MS = 1_000

        private const val NO_XCODE = "iOS simulators need macOS with Xcode: /usr/bin/xcrun not found"
        private const val NO_XCODE_USB = "iPhones on USB need macOS with Xcode 15 or later: /usr/bin/xcrun not found"
    }
}

/** An app whose bridge answers ([Targets.apps]): what `--platform`, `--device`, `--app` and `--bridge-file` name. */
@HopiInternalApi
public class FoundApp(
    public val platform: Platform,
    /** The adb serial or simulator UDID; null for a JVM process. */
    public val device: String?,
    /** The device's model, the simulator's name, or `JVM <pid>`. */
    public val deviceName: String,
    public val app: String,
    /** A JVM process's `bridge.json`. */
    public val bridgeFile: Path?,
)

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
    return parseBridgeFile(text) ?: throw notReachable("$file is not a Hopi bridge file")
}

/** The port and token of a `bridge.json` [text]; null when it is not one. */
internal fun parseBridgeFile(text: String): Pair<Int, String>? {
    val fields = parseFlatJsonObject(text)
    val port = (fields?.get("port") as? Long)?.takeIf { it in 1..65535 }?.toInt()
    val token = (fields?.get("token") as? String)?.takeIf { TOKEN.matches(it) }
    if (port == null || token == null) return null
    return port to token
}
