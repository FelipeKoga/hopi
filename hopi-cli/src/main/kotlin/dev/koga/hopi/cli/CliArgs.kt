package dev.koga.hopi.cli

import java.nio.file.Path
import java.nio.file.Paths

/** Process exit codes; part of the CLI's contract with scripts and agents. */
internal object ExitCode {
    const val OK: Int = 0
    const val USAGE: Int = 1
    const val NOT_REACHABLE: Int = 2
    const val NOT_FOUND: Int = 3
}

/** Ends the command with [exitCode]; [message] is the one line printed to stderr. */
internal class CliException(val exitCode: Int, message: String) : Exception(message)

internal fun usageError(message: String): CliException = CliException(ExitCode.USAGE, message)

internal fun notReachable(message: String): CliException = CliException(ExitCode.NOT_REACHABLE, message)

internal enum class Platform(val id: String) {
    Android("android"),
    Ios("ios"),
    Jvm("jvm"),
    ;

    companion object {
        fun parse(value: String): Platform =
            entries.firstOrNull { it.id == value } ?: throw usageError("--platform must be android, ios or jvm, not '$value'")
    }
}

internal enum class Command(val wireName: String, val takesId: Boolean, val flags: Set<String>) {
    Devices("devices", takesId = false, setOf("--json", "--platform")),
    List("list", takesId = false, TARGET_FLAGS + setOf("--json", "--limit", "--since")),
    Tail("tail", takesId = false, TARGET_FLAGS + setOf("--json", "--since")),
    Get("get", takesId = true, TARGET_FLAGS + "--json"),
    Curl("curl", takesId = true, TARGET_FLAGS),
    Clear("clear", takesId = false, TARGET_FLAGS + "--json"),
}

private val TARGET_FLAGS: Set<String> get() = setOf("--app", "--platform", "--device", "--bridge-file")

/** A parsed command line. [platform] is null when it is to be inferred from the attached targets. */
internal class CliArgs(
    val command: Command,
    val app: String?,
    val platform: Platform?,
    val device: String?,
    val bridgeFile: Path?,
    val json: Boolean,
    val limit: Int,
    val since: Long,
    val id: Long,
)

internal sealed interface Invocation {
    object Help : Invocation

    class Run(val args: CliArgs) : Invocation
}

internal const val DEFAULT_LIMIT: Int = 100
internal const val MAX_LIMIT: Int = 10_000

/**
 * Application ids, bundle ids and Android process names (`com.example:remote`). Values reach
 * subprocess argument arrays, so anything that could read as an option or a path is refused.
 */
private val APP_ID = Regex("[A-Za-z0-9_][A-Za-z0-9._:-]{0,254}")

/** adb serials (`emulator-5554`, `192.168.1.20:5555`) and simulator UDIDs. */
private val DEVICE_ID = Regex("[A-Za-z0-9_][A-Za-z0-9._:-]{0,127}")

internal fun parseInvocation(argv: kotlin.collections.List<String>): Invocation {
    if (argv.isEmpty() || argv.any { it == "-h" || it == "--help" || it == "help" && argv.size == 1 }) {
        return Invocation.Help
    }
    val command = Command.entries.firstOrNull { it.wireName == argv[0] }
        ?: throw usageError("unknown command '${argv[0]}'")
    val values = HashMap<String, String>()
    var json = false
    var id: String? = null
    var i = 1
    while (i < argv.size) {
        val arg = argv[i++]
        if (!arg.startsWith("--")) {
            if (!command.takesId || id != null) throw usageError("unexpected argument '$arg' for ${command.wireName}")
            id = arg
            continue
        }
        val name = arg.substringBefore('=')
        if (name !in command.flags) throw usageError("${command.wireName} does not take $name")
        if (name == "--json") {
            if (arg.contains('=')) throw usageError("--json takes no value")
            json = true
            continue
        }
        val value = if (arg.contains('=')) {
            arg.substringAfter('=')
        } else {
            if (i >= argv.size) throw usageError("$name needs a value")
            argv[i++]
        }
        if (values.put(name, value) != null) throw usageError("$name given twice")
    }

    val bridgeFile = values["--bridge-file"]?.let {
        if (it.isEmpty()) throw usageError("--bridge-file needs a path")
        Paths.get(it)
    }
    val platform = values["--platform"]?.let(Platform::parse) ?: if (bridgeFile != null) Platform.Jvm else null
    if (command == Command.Devices && platform == Platform.Jvm) throw usageError("devices lists android and ios targets only")
    if (platform == Platform.Jvm && bridgeFile == null && command != Command.Devices) {
        throw usageError("--platform jvm needs --bridge-file <path>")
    }
    if (bridgeFile != null && platform != Platform.Jvm) throw usageError("--bridge-file is only for --platform jvm")
    val device = values["--device"]?.also {
        if (platform == Platform.Jvm) throw usageError("--device does not apply to --platform jvm")
        if (!DEVICE_ID.matches(it)) throw usageError("--device '$it' is not an adb serial or simulator UDID")
    }
    val app = values["--app"]?.also {
        if (!APP_ID.matches(it)) throw usageError("--app '$it' is not an application id, bundle id or process name")
    }
    if (app == null && command != Command.Devices && platform != Platform.Jvm) {
        throw usageError("${command.wireName} needs --app <applicationId or bundleId>")
    }
    val limit = values["--limit"]?.let {
        it.toIntOrNull()?.takeIf { n -> n in 1..MAX_LIMIT } ?: throw usageError("--limit must be 1..$MAX_LIMIT")
    } ?: DEFAULT_LIMIT
    val since = values["--since"]?.let {
        it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw usageError("--since must be a transaction id ≥ 0")
    } ?: 0L
    val transactionId = if (command.takesId) {
        val raw = id ?: throw usageError("${command.wireName} needs a transaction id")
        raw.toLongOrNull()?.takeIf { it >= 1 } ?: throw usageError("transaction id must be a number ≥ 1, not '$raw'")
    } else {
        0L
    }
    return Invocation.Run(CliArgs(command, app, platform, device, bridgeFile, json, limit, since, transactionId))
}

internal val USAGE_TEXT: String = """
    |Usage: hopi <command> [options]
    |
    |Reads the HTTP traffic an app records with Hopi (agentBridge = true).
    |
    |Commands:
    |  devices                 list reachable targets: adb devices and booted iOS simulators
    |  list                    the newest transactions (--limit, default $DEFAULT_LIMIT), oldest first
    |  tail                    retained transactions, then live events until interrupted
    |  get <id>                full detail of one transaction, bodies included
    |  curl <id>               the request as a cURL command
    |  clear                   drop every recorded transaction
    |
    |Options:
    |  --app <id>              applicationId (Android) or bundle id (iOS); required except for jvm
    |  --platform <p>          android, ios or jvm; inferred when only one of adb/simctl has a target
    |  --device <id>           adb serial or simulator UDID, when several are attached
    |  --bridge-file <path>    bridge.json of a JVM process (implies --platform jvm)
    |  --json                  NDJSON output, one object per line (the stable contract for agents)
    |  --limit <n>             list: at most n transactions, 1..$MAX_LIMIT
    |  --since <id>            list, tail: only transactions with a greater id
    |  -h, --help              this help
    |
    |Exit codes: 0 ok, 1 usage, 2 not reachable (no device, app not running, bridge off), 3 not found.
""".trimMargin()
