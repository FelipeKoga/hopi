package dev.koga.hopi.cli

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
internal class BridgeEndpoint(
    val port: Int,
    val token: String?,
    val platform: Platform,
    /** The app `hello` must report; null accepts any (a JVM bridge file without `--app`). */
    val app: String?,
    /** Why a connection that closes without a byte means nothing serves the app. */
    val unreachableMessage: String,
    /**
     * Asked when an established connection ends: why the host side lost it, or null when nothing
     * says so (then the app closed it). Android checks that adb still holds the forward.
     */
    val transportLost: () -> String? = { null },
    /** Android: `hello` must carry the proof the app wrote to its private storage. */
    val proof: ProofCheck? = null,
    private val cleanup: () -> Unit = {},
) : AutoCloseable {
    override fun close() = cleanup()

    override fun toString(): String = "BridgeEndpoint(port=$port, platform=${platform.id}, app=$app)"
}

/** Finds the attached targets and opens the bridge endpoint a command asked for. */
internal class Targets(private val environment: CliEnvironment) {
    private val adb: Adb? by lazy { locateAdb(environment)?.let { Adb(it, environment.processes) } }
    private val simctl: Simctl? by lazy {
        if (environment.isMac && environment.isExecutable(Simctl.XCRUN)) Simctl(Simctl.XCRUN, environment.processes) else null
    }

    /** Null when adb is not installed. */
    fun androidDevices(): List<AdbDevice>? = adb?.devices()

    /** Null when this host has no simulators (not macOS, or no Xcode). */
    fun simulators(): List<Simulator>? = simctl?.booted()

    fun open(args: CliArgs): BridgeEndpoint = when (args.platform ?: inferPlatform(args.device)) {
        Platform.Android -> openAndroid(requireApp(args), args.device)
        Platform.Ios -> openIos(requireApp(args), args.device)
        Platform.Jvm -> openJvm(args.bridgeFile ?: throw usageError("--platform jvm needs --bridge-file <path>"), args.app)
    }

    private fun requireApp(args: CliArgs): String = args.app ?: throw usageError("${args.command.wireName} needs --app <id>")

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
        val simctl = simctl ?: throw notReachable("iOS simulators need macOS with Xcode: /usr/bin/xcrun not found")
        val simulators = simctl.booted()
        val udid = pickDevice(
            device,
            simulators.map { Candidate(it.udid, "${it.name} (${it.udid})", ready = true, it.state) },
            kind = "booted simulator",
            none = "no iOS simulator is booted: boot one (xcrun simctl boot <udid>) and launch the app",
        )
        val simulator = simulators.first { it.udid == udid }
        val file = simctl.dataContainer(simulator, app).resolve(IOS_BRIDGE_FILE)
        val unreachable = "app not running or agentBridge = false: no bridge answers for $app on ${simulator.name}"
        val (port, token) = readBridgeFile(file, missing = unreachable)
        return BridgeEndpoint(port, token, Platform.Ios, app, unreachable)
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

    companion object {
        const val IOS_BRIDGE_FILE: String = "Library/Caches/hopi/bridge.json"
    }
}

private val TOKEN = Regex("[0-9a-f]{64}")

/** `{"port":N,"token":"…"}` as the iOS and JVM bridges write it. */
internal fun readBridgeFile(file: Path, missing: String): Pair<Int, String> {
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
