package dev.koga.hopi.cli

import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

/** One line of `adb devices -l`. [state] is adb's word: `device` (ready), `unauthorized`, `offline`, … */
internal class AdbDevice(val serial: String, val state: String, val model: String?) {
    val isReady: Boolean get() = state == "device"
}

/** `$ANDROID_HOME/platform-tools/adb`, then `~/Library/Android/sdk/platform-tools/adb`, then `PATH`. */
internal fun locateAdb(environment: CliEnvironment): Path? {
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

private val WHITESPACE = Regex("\\s+")
private val PROOF = Regex("[0-9a-f]{64}")

/** adb, located once; every call names the program by its absolute path. */
internal class Adb(private val path: Path, private val processes: ProcessRunner) {
    fun devices(): List<AdbDevice> {
        val result = run("devices", "-l")
        if (result.exitCode != 0) throw notReachable("adb devices failed: ${firstLine(result.stderr)}")
        return parseAdbDevices(result.stdout)
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
     * The proof the bridge of process [app] wrote to its app's private storage, read as that app
     * with `run-as` (debuggable builds only). adbd runs the arguments through the device's shell,
     * so only validated values reach it: the package (the process name up to `:`) and a fixed path
     * around the process name, both matching the `--app` pattern. Never printed.
     */
    fun readProof(serial: String, app: String): String {
        val packageName = app.substringBefore(':')
        val result = run("-s", serial, "exec-out", "run-as", packageName, "cat", "cache/hopi/$app.proof")
        val proof = result.stdout.trim()
        if (result.exitCode == 0 && PROOF.matches(proof)) return proof
        // Whatever went wrong, a proof-like value is never echoed into the message.
        val reason = visible(firstLine(result.stdout.takeUnless { PROOF.containsMatchIn(it) }.orEmpty() + "\n" + result.stderr))
        throw notReachable(
            "cannot verify the bridge of $app on $serial: run-as $packageName could not read its proof ($reason); " +
                "the app must be a debuggable build whose bridge started (another app may hold the socket name)",
        )
    }

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
