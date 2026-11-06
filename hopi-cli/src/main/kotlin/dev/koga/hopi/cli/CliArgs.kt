package dev.koga.hopi.cli

import dev.koga.hopi.connect.Platform
import dev.koga.hopi.connect.TargetIds
import dev.koga.hopi.connect.usageError
import java.nio.file.Path
import java.nio.file.Paths

private fun parsePlatform(value: String): Platform =
    Platform.entries.firstOrNull { it.id == value } ?: throw usageError("--platform must be android, ios or jvm, not '$value'")

/** How many positional ids a command takes. */
internal enum class Ids { NONE, ONE, MANY }

internal enum class Command(val wireName: String, val ids: Ids, val flags: Set<String>) {
    Devices("devices", Ids.NONE, setOf("--json", "--platform")),
    List("list", Ids.NONE, TARGET_FLAGS + setOf("--json", "--limit", "--since")),
    Tail("tail", Ids.NONE, TARGET_FLAGS + setOf("--json", "--since")),
    Get("get", Ids.ONE, TARGET_FLAGS + "--json"),
    Curl("curl", Ids.ONE, TARGET_FLAGS),
    Clear("clear", Ids.NONE, TARGET_FLAGS + "--json"),
    MockAdd("mock add", Ids.NONE, RULE_FLAGS + setOf("--status", "--header", "--body", "--from", "--delay")),
    FailAdd("fail add", Ids.NONE, RULE_FLAGS + setOf("--with", "--after")),
    DelayAdd("delay add", Ids.NONE, RULE_FLAGS + setOf("--before-request", "--before-response")),
    EditAdd(
        "edit add",
        Ids.NONE,
        RULE_FLAGS + REQUEST_EDIT_FLAGS + setOf("--allow-other-origin", "--status", "--set-response-header", "--add-response-header", "--remove-response-header", "--response-body"),
    ),
    RulesLs("rules ls", Ids.NONE, TARGET_FLAGS + "--json"),
    RulesRm("rules rm", Ids.MANY, TARGET_FLAGS + "--json"),
    RulesClear("rules clear", Ids.NONE, TARGET_FLAGS + "--json"),
    RulesEnable("rules enable", Ids.MANY, TARGET_FLAGS + "--json"),
    RulesDisable("rules disable", Ids.MANY, TARGET_FLAGS + "--json"),
    RulesReset("rules reset", Ids.MANY, TARGET_FLAGS + "--json"),
    PerfStart("perf start", Ids.NONE, TARGET_FLAGS + MONITOR_FLAGS + setOf("--json", "--hang-ms", "--interval", "--for", "--no-expiry")),
    PerfStop("perf stop", Ids.NONE, TARGET_FLAGS + MONITOR_FLAGS + "--json"),
    PerfStatus("perf status", Ids.NONE, TARGET_FLAGS + "--json"),
    PerfSnapshot("perf snapshot", Ids.NONE, TARGET_FLAGS + setOf("--json", "--threads", "--pss")),
    PerfTail("perf tail", Ids.NONE, TARGET_FLAGS + setOf("--json", "--since", "--samples", "--fps", "--threads", "--start")),
    Hangs("hangs", Ids.NONE, TARGET_FLAGS + setOf("--json", "--since")),
    ;

    val takesId: Boolean get() = ids != Ids.NONE

    /** A command that changes or reads the app's rules: write lines go out only after `hello` was verified. */
    val isRuleCommand: Boolean get() = ordinal in MockAdd.ordinal..RulesReset.ordinal

    /** A perf command (docs/design/perf-monitors.md, "CLI"): sent only after `hello` was verified, like the rules. */
    val isPerfCommand: Boolean get() = ordinal >= PerfStart.ordinal

    val isAdd: Boolean get() = this == MockAdd || this == FailAdd || this == DelayAdd || this == EditAdd
}

/** The nouns whose commands are two words (`mock add`, `rules ls`). */
private val NOUNS = setOf("mock", "fail", "delay", "edit", "rules", "perf")

/** Flags that may be given more than once; their values are kept in order. */
internal val REPEATABLE: Set<String> = setOf(
    "--header", "--query", "--match-header", "--set-query", "--remove-query", "--set-header", "--add-header",
    "--remove-header", "--set-response-header", "--add-response-header", "--remove-response-header",
)

/** Flags without a value. */
private val SWITCHES = setOf(
    "--json", "--disabled", "--allow-other-origin", "--frames", "--hangs", "--cpu", "--memory", "--no-expiry", "--threads", "--pss", "--samples", "--fps",
)

/** `perf start` and `perf stop`: one switch per monitor, named as on the wire. */
private val MONITOR_FLAGS: Set<String> get() = setOf("--frames", "--hangs", "--cpu", "--memory")

private val RULE_FLAGS: Set<String>
    get() = TARGET_FLAGS + setOf("--json", "--match", "--query", "--match-header", "--times", "--priority", "--source", "--label", "--disabled")

private val REQUEST_EDIT_FLAGS: Set<String>
    get() = setOf(
        "--method", "--host", "--port", "--path", "--set-query", "--remove-query", "--set-header", "--add-header", "--remove-header", "--body",
    )

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
    /** Every positional id, for the commands that take several (`rules rm 3 4`). */
    val ids: kotlin.collections.List<Long> = if (id > 0) listOf(id) else emptyList(),
    /** The other single-valued flags, by name (`--status` → `500`). */
    val values: Map<String, String> = emptyMap(),
    /** The repeatable flags' values, in order. */
    val lists: Map<String, kotlin.collections.List<String>> = emptyMap(),
    /** The switches given (`--disabled`). */
    val switches: Set<String> = emptySet(),
)

internal sealed interface Invocation {
    object Help : Invocation

    class Run(val args: CliArgs) : Invocation
}

internal const val DEFAULT_LIMIT: Int = 100
internal const val MAX_LIMIT: Int = 10_000

/** Shared with the desktop viewer's picker (see [TargetIds]). */
private val APP_ID = TargetIds.APP

private val DEVICE_ID = TargetIds.DEVICE

internal fun parseInvocation(argv: kotlin.collections.List<String>): Invocation {
    if (argv.isEmpty() || argv.any { it == "-h" || it == "--help" || it == "help" && argv.size == 1 }) {
        return Invocation.Help
    }
    val twoWords = argv[0] in NOUNS
    val name = if (twoWords) argv[0] + " " + (argv.getOrNull(1) ?: throw usageError("${argv[0]} needs a subcommand")) else argv[0]
    val command = Command.entries.firstOrNull { it.wireName == name }
        ?: throw usageError("unknown command '$name'")
    val values = HashMap<String, String>()
    val lists = HashMap<String, MutableList<String>>()
    val switches = HashSet<String>()
    var json = false
    val positional = ArrayList<String>()
    var i = if (twoWords) 2 else 1
    while (i < argv.size) {
        val arg = argv[i++]
        if (!arg.startsWith("--")) {
            if (command.ids == Ids.NONE || command.ids == Ids.ONE && positional.isNotEmpty()) {
                throw usageError("unexpected argument '$arg' for ${command.wireName}")
            }
            positional += arg
            continue
        }
        val flag = arg.substringBefore('=')
        if (flag !in command.flags) throw usageError("${command.wireName} does not take $flag")
        if (flag in SWITCHES) {
            if (arg.contains('=')) throw usageError("$flag takes no value")
            if (flag == "--json") json = true else switches += flag
            continue
        }
        val value = if (arg.contains('=')) {
            arg.substringAfter('=')
        } else {
            if (i >= argv.size) throw usageError("$flag needs a value")
            argv[i++]
        }
        if (flag in REPEATABLE) {
            lists.getOrPut(flag) { ArrayList() } += value
        } else if (values.put(flag, value) != null) {
            throw usageError("$flag given twice")
        }
    }

    val bridgeFile = values["--bridge-file"]?.let {
        if (it.isEmpty()) throw usageError("--bridge-file needs a path")
        Paths.get(it)
    }
    val platform = values["--platform"]?.let(::parsePlatform) ?: if (bridgeFile != null) Platform.Jvm else null
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
        it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw usageError(if (command.isPerfCommand) "--since must be a perf event seq ≥ 0" else "--since must be a transaction id ≥ 0")
    } ?: 0L
    val ids = positional.map { raw ->
        raw.toLongOrNull()?.takeIf { it >= 1 } ?: throw usageError("${if (command.isRuleCommand) "rule" else "transaction"} id must be a number ≥ 1, not '$raw'")
    }
    if (command.takesId && ids.isEmpty()) {
        throw usageError("${command.wireName} needs a ${if (command.ids == Ids.MANY) "rule id" else "transaction id"}")
    }
    if (command.isAdd && values["--match"] == null) throw usageError("${command.wireName} needs --match '<METHODS> <URL-PATTERN>'")
    return Invocation.Run(
        CliArgs(command, app, platform, device, bridgeFile, json, limit, since, ids.firstOrNull() ?: 0L, ids, values, lists, switches),
    )
}

internal val USAGE_TEXT: String = """
    |Usage: hopi <command> [options]
    |
    |Reads what Hopi records in a running app (HTTP traffic, rules, performance) and drives its
    |rules and monitors. A debug build serves it unless the app set agentBridge = false.
    |
    |Commands:
    |  devices                 list reachable targets: adb devices, booted iOS simulators, iPhones on USB
    |  list                    the newest transactions (--limit, default $DEFAULT_LIMIT), oldest first
    |  tail                    retained transactions, then live events until interrupted
    |  get <id>                full detail of one transaction, bodies included
    |  curl <id>               the request as a cURL command
    |  clear                   drop every recorded transaction
    |
    |Rules (in the app's memory only; gone when it restarts):
    |  mock add  --match M --status N [--header 'K: V']... [--body B] [--from <id>] [--delay D]
    |  fail add  --match M --with timeout|reset|unknown-host|cancel [--after D]
    |  delay add --match M [--before-request D] [--before-response D]
    |  edit add  --match M [--method X] [--host H --port N --allow-other-origin] [--path P] [--set-query k=v]...
    |            [--remove-query k]... [--set-header 'K: V']... [--add-header 'K: V']...
    |            [--remove-header K]... [--body B] [--status N] [--set-response-header 'K: V']...
    |            [--add-response-header 'K: V']... [--remove-response-header K]... [--response-body B]
    |  rules ls | rm <id>... | clear | enable <id>... | disable <id>... | reset <id>...
    |
    |  M is '[METHODS ]URL-PATTERN': GET or GET|POST or *, then [scheme://]host[:port]path[?query];
    |  in a host * matches any run, in a path * one segment and ** any; ?page=2&debug&!trace adds
    |  query predicates. Every add also takes --query, --match-header 'K: glob', --times N,
    |  --priority N, --source ktor,okhttp,urlconnection,urlsession, --label TEXT and --disabled.
    |  --host and --port send the matched requests elsewhere (credentials stay behind): they need
    |  --allow-other-origin, which an AI agent adds only once its user has agreed.
    |  B is @file, @- (stdin) or the text itself. D is a duration: 500ms, 2s (at most 120s).
    |
    |Performance (the app needs hopi-perf; every monitor is off until started):
    |  perf start    [--frames] [--hangs [--hang-ms 250]] [--cpu] [--memory] [--interval 1s]
    |                [--for 10m | --no-expiry]   the app stops them when the lease ends (10m)
    |  perf stop     [--frames] [--hangs] [--cpu] [--memory]   none named: all, whoever started them
    |  perf status   one line per monitor: setting, owners and lease, counts
    |  perf snapshot [--threads] [--pss]   summaries, recent events, the top threads
    |  perf tail     [--since SEQ] [--samples] [--fps] [--threads] [--start hangs,frames]
    |                events (and samples) as they happen; --start holds monitors until it ends
    |  hangs         [--since SEQ]   every hang the app holds, with its main-thread stacks
    |
    |Options:
    |  --app <id>              applicationId (Android) or bundle id (iOS); required except for jvm
    |  --platform <p>          android, ios or jvm; inferred when only one of adb/simctl has a target
    |  --device <id>           adb serial, simulator UDID or USB iPhone UDID, when several are attached;
    |                          an iPhone is picked unnamed only when no adb device or simulator is ready
    |  --bridge-file <path>    bridge.json of a JVM process (implies --platform jvm)
    |  --json                  NDJSON output, one object per line (the stable contract for agents)
    |  --limit <n>             list: at most n transactions, 1..$MAX_LIMIT
    |  --since <id>            list, tail: only transactions with a greater id;
    |                          perf tail, hangs: only events with a greater seq
    |  -h, --help              this help
    |
    |Exit codes: 0 ok, 1 usage (or rejected by the app), 2 not reachable (no device, app not running,
    |bridge off), 3 not found, 4 unsupported (rules off or absent, no perf monitors in the app, or
    |a monitor the platform lacks).
""".trimMargin()
