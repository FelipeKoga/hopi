package dev.koga.hopi.connect

import dev.koga.hopi.api.HopiInternalApi
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Paths

/** One line of `adb devices -l`. [state] is adb's word: `device` (ready), `unauthorized`, `offline`, … */
@HopiInternalApi
public class AdbDevice(public val serial: String, public val state: String, public val model: String?) {
    public val isReady: Boolean get() = state == "device"
}

/** `$ANDROID_HOME/platform-tools/adb`, then `~/Library/Android/sdk/platform-tools/adb`, then `PATH`. */
internal fun locateAdb(environment: HostEnvironment): Path? {
    val candidates = buildList {
        environment.env["ANDROID_HOME"]?.takeIf { it.isNotEmpty() }?.let { add(Paths.get(it, "platform-tools", "adb")) }
        if (environment.userHome.isNotEmpty()) {
            add(Paths.get(environment.userHome, "Library", "Android", "sdk", "platform-tools", "adb"))
        }
        pathEntries(environment.env).forEach { add(it.resolve("adb")) }
    }
    return candidates.firstOrNull(environment.isExecutable)
}

/** Parses `adb devices -l`; skips the header, daemon start-up chatter and blank lines. */
internal fun parseAdbDevices(output: String): List<AdbDevice> = output.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") && !it.startsWith("adb ") }
    .mapNotNull { line ->
        val fields = line.split(WHITESPACE)
        if (fields.size < 2) return@mapNotNull null
        val model = fields.drop(2).firstOrNull { it.startsWith("model:") }?.removePrefix("model:")
        AdbDevice(fields[0], fields[1], model)
    }
    .toList()

/** `adb forward tcp:0 …` prints the host port it picked, alone on a line. */
internal fun parseForwardPort(output: String): Int? =
    output.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 5 }
        ?.toInt()?.takeIf { it in 1..65535 }

/** `adb forward --list`: one `<serial> <local> <remote>` line per forward, as (serial, local) pairs. */
internal fun parseForwards(output: String): List<Pair<String, String>> = output.lineSequence()
    .map { it.trim().split(WHITESPACE) }
    .filter { it.size == 3 }
    .map { it[0] to it[1] }
    .toList()

/**
 * The process names of `@hopi.<name>` sockets in `/proc/net/unix` (the path is the last
 * field; a connected client repeats it), sorted, without duplicates.
 */
internal fun parseUnixSocketBridges(output: String): List<String> = output.lineSequence()
    .mapNotNull { line -> line.trim().split(WHITESPACE).lastOrNull()?.takeIf { it.startsWith(BRIDGE_SOCKET_PREFIX) } }
    .map { it.removePrefix(BRIDGE_SOCKET_PREFIX) }
    .filter { TargetIds.APP.matches(it) }
    .distinct()
    .sorted()
    .toList()

/**
 * One message of an adb tracking service (`track-devices`, `track-jdwp`): four hex digits giving
 * the length, then that many bytes. Null at the end of the stream or on anything else.
 */
internal fun readTrackFrame(input: InputStream): String? {
    val header = input.readExactly(4) ?: return null
    val length = header.decodeToString().toIntOrNull(16)?.takeIf { it in 0..MAX_TRACK_FRAME } ?: return null
    return input.readExactly(length)?.decodeToString()
}

/** `track-jdwp`: one pid per line. */
internal fun parseJdwpPids(frame: String): Set<Int> =
    frame.lineSequence().mapNotNull { it.trim().toIntOrNull() }.toSet()

private fun InputStream.readExactly(count: Int): ByteArray? {
    val bytes = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(bytes, read, count - read)
        if (n < 0) return null
        read += n
    }
    return bytes
}

private const val BRIDGE_SOCKET_PREFIX = "@hopi."
private const val MAX_TRACK_FRAME = 0xffff

private val WHITESPACE = Regex("\\s+")
private val PROOF = Regex("[0-9a-f]{64}")

/** The proof file: the proof `hello` shows, then the key a client sends first. */
private val PROOF_FILE = Regex("([0-9a-f]{64})\\n([0-9a-f]{64})")

/** What the app's bridge wrote to its private storage; never printed. */
public class BridgeSecrets(public val proof: String, public val key: String) {
    override fun toString(): String = "BridgeSecrets(<redacted>)"
}

/** adb, located once; every call names the program by its absolute path. */
internal class Adb(private val path: Path, private val processes: ProcessRunner) {
    fun devices(): List<AdbDevice> {
        val result = run("devices", "-l")
        if (result.exitCode != 0) throw notReachable("adb devices failed: ${firstLine(result.stderr)}")
        return parseAdbDevices(result.stdout)
    }

    /**
     * The device's own serial number (`ro.serialno`): one phone that adb lists twice (USB and
     * Wi-Fi, or Wi-Fi by address and by its mDNS name) has one. Null when adb cannot tell.
     */
    fun hardwareSerial(serial: String): String? = try {
        run("-s", serial, "shell", "getprop", "ro.serialno").takeIf { it.exitCode == 0 }?.stdout?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: CliException) {
        null
    }

    /** Forwards a free host loopback port to the app's abstract socket and returns that port. */
    fun forward(serial: String, socketName: String): Int {
        val result = run("-s", serial, "forward", "tcp:0", "localabstract:$socketName")
        if (result.exitCode != 0) throw notReachable("adb forward on $serial failed: ${firstLine(result.stderr)}")
        return parseForwardPort(result.stdout)
            ?: throw notReachable("adb forward on $serial printed no port; update platform-tools")
    }

    /**
     * Whether adb still holds the forward of host [port] on [serial]: adb drops every forward of a
     * device whose transport resets (an emulator's adb connection can, while the app keeps running).
     * Null when adb cannot tell.
     */
    fun hasForward(serial: String, port: Int): Boolean? {
        val result = try {
            run("-s", serial, "forward", "--list")
        } catch (_: CliException) {
            return null
        }
        if (result.exitCode != 0) return null
        return parseForwards(result.stdout).any { it.first == serial && it.second == "tcp:$port" }
    }

    /**
     * Best effort: a forward that outlives the CLI only points at a closed or foreign socket. Never
     * throws, so it cannot fail a command that already succeeded or escape a shutdown hook.
     */
    fun removeForward(serial: String, port: Int) {
        try {
            run("-s", serial, "forward", "--remove", "tcp:$port")
        } catch (_: CliException) {
            // adb could not start or did not finish; the forward goes when the adb server or device does.
        }
    }

    /**
     * The secrets the bridge of process [app] wrote to its app's private storage (null when there
     * is no file: the bridge is not running), read as that app
     * with `run-as` (debuggable builds only). adbd runs the arguments through the device's shell,
     * so only validated values reach it: the package (the process name up to `:`) and a fixed path
     * around the process name, both matching the `--app` pattern. Never printed.
     */
    fun readProof(serial: String, app: String): BridgeSecrets? {
        val packageName = app.substringBefore(':')
        val result = run("-s", serial, "exec-out", "run-as", packageName, "cat", "cache/hopi/$app.proof")
        PROOF_FILE.matchEntire(result.stdout.trim())?.takeIf { result.exitCode == 0 }?.let { return BridgeSecrets(it.groupValues[1], it.groupValues[2]) }
        // The app deletes the file when its bridge stops, and never wrote one if it never started.
        if ("No such file" in result.stdout || "No such file" in result.stderr) return null
        // Whatever went wrong, a proof-like value is never echoed into the message.
        val reason = visible(firstLine(result.stdout.takeUnless { PROOF.containsMatchIn(it) }.orEmpty() + "\n" + result.stderr))
        throw notReachable(
            "cannot verify the bridge of $app on $serial: run-as $packageName could not read its proof ($reason); " +
                "the app must be a debuggable build whose bridge started (another app may hold the socket name)",
        )
    }

    /**
     * The bridges listening on [serial]: the `@hopi.<process name>` entries of
     * `/proc/net/unix` (readable by the shell on API 35; other levels unverified, then empty).
     * A fixed command; only names that pass [TargetIds.APP] are returned.
     */
    fun bridges(serial: String): List<String> {
        val result = run("-s", serial, "exec-out", "cat", "/proc/net/unix")
        if (result.exitCode != 0) return emptyList()
        return parseUnixSocketBridges(result.stdout)
    }

    /** The argument array of a long-running adb service (`track-devices`, `track-jdwp`). */
    fun command(vararg args: String): List<String> = listOf(path.toString()) + args

    private fun run(vararg args: String): ProcessResult {
        val command = listOf(path.toString()) + args
        return try {
            processes.run(command)
        } catch (e: IOException) {
            throw notReachable("cannot run adb at $path: ${e.message}")
        }
    }
}

internal fun firstLine(text: String): String =
    text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "no output"
