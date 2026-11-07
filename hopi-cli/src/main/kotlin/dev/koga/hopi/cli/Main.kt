package dev.koga.hopi.cli

import dev.koga.hopi.connect.BridgeClient
import dev.koga.hopi.connect.CliException
import dev.koga.hopi.connect.ExitCode
import dev.koga.hopi.connect.Platform
import dev.koga.hopi.connect.RemoteRule
import dev.koga.hopi.connect.Reply
import dev.koga.hopi.connect.PerfClient
import dev.koga.hopi.connect.RemoteFrameSecond
import dev.koga.hopi.connect.RemotePerf
import dev.koga.hopi.connect.RemotePerfEvent
import dev.koga.hopi.connect.RemotePerfSample
import dev.koga.hopi.connect.RemotePerfSeries
import dev.koga.hopi.connect.RemotePerfStatus
import dev.koga.hopi.connect.RemotePerfSummary
import dev.koga.hopi.connect.RemotePerfThread
import dev.koga.hopi.connect.RuleClient
import dev.koga.hopi.connect.SlowClientException
import dev.koga.hopi.connect.str
import dev.koga.hopi.connect.long
import dev.koga.hopi.connect.usageError
import dev.koga.hopi.connect.notReachable
import dev.koga.hopi.connect.visible
import dev.koga.hopi.core.bridge.BridgeSession
import dev.koga.hopi.core.curl.toCurl
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.redact.Redactor
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import kotlin.system.exitProcess

public fun main(args: Array<String>) {
    exitProcess(runCli(args.asList(), CliEnvironment.system()))
}

/** The whole CLI behind [main], with its streams and subprocesses injected; returns the exit code. */
internal fun runCli(argv: List<String>, environment: CliEnvironment): Int {
    val out = LineOutput(environment.stdout)
    return try {
        when (val invocation = parseInvocation(argv)) {
            Invocation.Help -> out.line(USAGE_TEXT)
            is Invocation.Run -> Cli(environment, out, invocation.args).run()
        }
        out.flush()
        ExitCode.OK
    } catch (e: CliException) {
        try {
            out.flush()
        } catch (_: StdoutClosedException) {
            // Nobody reads stdout any more; the error still goes to stderr.
        }
        val hint = if (e.exitCode == ExitCode.USAGE) " (see hopi --help)" else ""
        printError(environment.stderr, "hopi: ${e.message}$hint")
        e.exitCode
    } catch (_: StdoutClosedException) {
        // `hopi tail | head -1`: the reader has what it wanted.
        ExitCode.OK
    }
}

/** Precedes a `curl` command whose request held control characters, now shown as `\uXXXX`. */
internal const val CONTROL_CHARACTERS_WARNING: String =
    "# warning: the request contains control characters, shown as \\uXXXX escapes; this command does not send them as captured"

private fun printError(stderr: OutputStream, line: String) {
    try {
        stderr.write("$line\n".encodeToByteArray())
        stderr.flush()
    } catch (_: IOException) {
        // stderr is gone too; the exit code still tells.
    }
}

private class Cli(private val environment: CliEnvironment, private val out: LineOutput, private val args: CliArgs) {
    fun run() {
        when (args.command) {
            Command.Devices -> devices()
            Command.List -> list()
            Command.Tail -> tail()
            Command.Get -> get()
            Command.Curl -> curl()
            Command.Clear -> clear()
            Command.Viewer -> viewer()
            Command.MockAdd, Command.FailAdd, Command.DelayAdd, Command.EditAdd -> addRule()
            Command.RulesLs -> listRules()
            Command.RulesRm -> removeRules()
            Command.RulesClear -> clearRules()
            Command.RulesEnable -> updateRules(enabled = true)
            Command.RulesDisable -> updateRules(enabled = false)
            Command.RulesReset -> updateRules(resetHits = true)
            Command.PerfStart -> perfStart()
            Command.PerfStop -> perfStop()
            Command.PerfStatus -> perfStatus()
            Command.PerfSnapshot -> perfSnapshot()
            Command.PerfTail -> perfTail()
            Command.Hangs -> hangs()
        }
    }

    /**
     * Opens Hopi.app. `open` hands arguments only to a process it starts, so a named target gets its
     * own window (`-n`), and with none it brings up the viewer already running.
     */
    private fun viewer() {
        if (!environment.host.isMac) throw usageError("viewer is macOS only: run ./gradlew :hopi-desktop:run in Hopi's repository")
        val target = listOfNotNull(
            args.app?.let { listOf("--app", it) },
            args.platform?.let { listOf("--platform", it.id) },
            args.device?.let { listOf("--device", it) },
        ).flatten()
        val open = if (target.isEmpty()) listOf("/usr/bin/open", "-a", "Hopi") else listOf("/usr/bin/open", "-n", "-a", "Hopi", "--args") + target
        val opened = try {
            environment.host.processes.run(open).exitCode == 0
        } catch (_: IOException) {
            false
        }
        if (!opened) {
            throw notReachable("Hopi.app is not installed: run ./gradlew :hopi-desktop:packageDmg in Hopi's repository and drag the app to Applications")
        }
    }

    // ---- perf (docs/design/perf-monitors.md, "CLI")

    /** Validated before connecting; the app holds the monitors for the lease (10 minutes unless --for or --no-expiry). */
    private fun perfStart() {
        val start = PerfArgs.start(args)
        withPerf { perf ->
            val reply = perf.start(start.monitors, start.hangMs, start.intervalMs, start.ttlSec)
            printStatus(perf, reply)
        }
    }

    private fun perfStop() {
        val monitors = PerfArgs.monitors(args)
        withPerf { perf -> printStatus(perf, perf.stop(monitors)) }
    }

    private fun perfStatus() {
        withPerf { perf -> printStatus(perf, perf.status()) }
    }

    /** `--json`: the bridge's line as received; else one line per monitor, with the counts of a one-point snapshot. */
    private fun printStatus(perf: PerfClient, reply: Reply) {
        if (args.json) {
            out.line(reply.raw)
            return
        }
        val status = RemotePerfStatus.decode(reply.json) ?: throw notReachable("the bridge sent a malformed perf status")
        val summary = perf.snapshot(series = listOf("cpu"), points = 1, events = false).firstNotNullOfOrNull { RemotePerfSummary.decode(it.json) }
        PerfRender.status(status, summary, System.currentTimeMillis()).forEach(out::line)
    }

    private fun perfSnapshot() {
        val threads = "--threads" in args.switches
        withPerf { perf ->
            val lines = perf.snapshot(points = PerfClient.DEFAULT_POINTS, threads = threads, pss = "--pss" in args.switches)
            if (args.json) {
                lines.forEach { out.line(it.raw) }
                return@withPerf
            }
            val series = lines.mapNotNull { RemotePerfSeries.decode(it.json) }.associateBy { it.name }
            val pss = lines.firstOrNull { it.type == "perf.pss" }?.let { it.json.long("kb") to it.json.str("refused") }
            PerfRender.snapshot(
                status = lines.firstNotNullOfOrNull { RemotePerfStatus.decode(it.json) },
                summary = lines.firstNotNullOfOrNull { RemotePerfSummary.decode(it.json) },
                events = lines.mapNotNull { RemotePerfEvent.decode(it.json) },
                threads = lines.firstOrNull { it.type == "perf.threads" }?.let { RemotePerfThread.decodeAll(it.json) },
                threadsAsked = threads,
                pss = pss,
                lastSample = RemotePerf.samplesOf(series["cpu"], series["memory"]).lastOrNull(),
            ).forEach(out::line)
        }
    }

    /** Runs until interrupted or until the app goes away; a monitor named in --start runs exactly as long. */
    private fun perfTail() {
        val start = PerfArgs.tailStart(args)
        withPerf { perf ->
            perf.tail(
                since = args.since,
                samples = "--samples" in args.switches,
                fps = "--fps" in args.switches,
                threads = "--threads" in args.switches,
                start = start,
            )
            val closed = "the app closed the bridge connection (app stopped?)"
            while (true) {
                val reply = try {
                    perf.next(closed)
                } catch (e: SlowClientException) {
                    throw CliException(
                        e.exitCode,
                        "the bridge disconnected this client for reading too slowly (SLOW_CLIENT): " +
                            "run hopi perf tail --since ${e.since ?: "<the last seq shown>"} to resume without losing an event",
                    )
                }
                if (args.json) {
                    out.line(reply.raw)
                } else {
                    val line = when (reply.type) {
                        "perf.status" -> RemotePerfStatus.decode(reply.json)?.let { PerfRender.status(it, null, System.currentTimeMillis()).joinToString("\n") }
                        "perf.event" -> RemotePerfEvent.decode(reply.json)?.let(PerfRender::event)
                        "perf.frames" -> RemoteFrameSecond.decode(reply.json)?.let(PerfRender::frames)
                        "perf.sample" -> RemotePerfSample.decode(reply.json)?.let(PerfRender::sample)
                        "perf.threads" -> RemotePerfThread.decodeAll(reply.json)?.let(PerfRender::threads)
                        else -> null
                    } ?: continue
                    out.line(line)
                }
                out.flush()
            }
        }
    }

    /** Every hang the app holds after --since, with all its stacks (a snapshot with full stacks). */
    private fun hangs() {
        withPerf { perf ->
            val lines = perf.snapshot(series = listOf("cpu"), points = 1, events = true, since = args.since, fullStacks = true)
                .filter { it.type == "perf.event" && it.json.str("kind") == "hang" }
            if (args.json) {
                lines.forEach { out.line(it.raw) }
            } else if (lines.isEmpty()) {
                out.line("no hangs" + if (args.since > 0) " after #${args.since}" else "")
            } else {
                lines.mapNotNull { RemotePerfEvent.decode(it.json) }.forEach { hang -> PerfRender.hang(hang).forEach(out::line) }
            }
        }
    }

    /** Perf commands send nothing before `hello` was verified, and nothing at all to an app without perf (exit 4). */
    private inline fun withPerf(block: (PerfClient) -> Unit) = withBridge(request = null) { client -> block(PerfClient(client)) }

    /** Validated before connecting; bodies are read now, uploaded (inline or as blobs) once connected. */
    private fun addRule() {
        val draft = RuleDraft.rule(args, ::readBody)
        withRules { rules ->
            val reply = rules.add(draft.json(rules::body))
            val rule = RemoteRule.decode(reply.json) ?: throw notReachable("the bridge sent a malformed rule")
            out.line(if (args.json) reply.raw else Render.ruleLine(rule))
            out.flush()
            for (entry in rule.unsupported) printError(environment.stderr, "hopi: warning: ${visible(entry.toString())}")
        }
    }

    private fun listRules() {
        withRules { rules ->
            val listed = rules.list()
            if (args.json) {
                listed.forEach { out.line(it.raw) }
            } else if (listed.isEmpty()) {
                out.line("no rules")
            } else {
                Render.rulesTable(listed.mapNotNull { RemoteRule.decode(it.json) }).forEach(out::line)
            }
        }
    }

    /** Removes each id in order; the first that is missing stops with exit 3, after the ones before it. */
    private fun removeRules() {
        withRules { rules ->
            for (id in args.ids) {
                try {
                    rules.remove(id)
                } catch (e: CliException) {
                    if (e.exitCode == ExitCode.NOT_FOUND) throw CliException(ExitCode.NOT_FOUND, "rule $id not found (hopi rules ls shows the rules)")
                    throw e
                }
                out.line(if (args.json) okLine(id) else "removed #$id")
            }
        }
    }

    private fun clearRules() {
        withRules { rules ->
            val removed = rules.clear()
            out.line(if (args.json) JsonWriter().beginObject().field("type", "ok").field("removed", removed).endObject().toString() else "removed $removed rules")
        }
    }

    private fun updateRules(enabled: Boolean? = null, resetHits: Boolean = false) {
        withRules { rules ->
            for (id in args.ids) {
                val reply = try {
                    rules.update(id, enabled = enabled, resetHits = resetHits)
                } catch (e: CliException) {
                    if (e.exitCode == ExitCode.NOT_FOUND) throw CliException(ExitCode.NOT_FOUND, "rule $id not found (hopi rules ls shows the rules)")
                    throw e
                }
                val rule = RemoteRule.decode(reply.json) ?: throw notReachable("the bridge sent a malformed rule")
                out.line(if (args.json) reply.raw else Render.ruleLine(rule))
            }
        }
    }

    private fun okLine(id: Long) = JsonWriter().beginObject().field("type", "ok").field("removed", id).endObject().toString()

    /** `@file` (a host file), `@-` (stdin) or the literal text, as curl reads `--data`. */
    private fun readBody(value: String): ByteArray = when {
        value == "@-" -> environment.stdin.readAllBytes()
        value.startsWith("@") -> try {
            Files.readAllBytes(Paths.get(value.substring(1)))
        } catch (_: NoSuchFileException) {
            throw usageError("no such file: ${value.substring(1)}")
        } catch (e: IOException) {
            throw usageError("cannot read ${value.substring(1)}: ${e.message}")
        }
        else -> value.encodeToByteArray()
    }

    /** Rule commands send nothing before `hello` was verified: no request is pipelined with it. */
    private inline fun withRules(block: (RuleClient) -> Unit) = withBridge(request = null) { client -> block(RuleClient(client)) }

    private fun devices() {
        val targets = Targets(environment)
        val lines = mutableListOf<List<String?>>()
        if (args.platform != Platform.Ios) {
            listOrNote("adb") { targets.androidDevices() }?.forEach {
                lines += listOf(Platform.Android.id, it.serial, it.state, it.model, null)
            }
        }
        if (args.platform != Platform.Android) {
            listOrNote("simctl") { targets.simulators() }?.forEach {
                lines += listOf(Platform.Ios.id, it.udid, "booted", it.name, it.os)
            }
            // After the simulators, so their lines keep their places.
            listOrNote("usbmuxd") { targets.usbIphones() }?.forEach {
                lines += listOf(Platform.Ios.id, it.udid, "usb", it.name, it.os)
            }
        }
        if (args.json) {
            lines.forEach { (platform, id, state, name, os) ->
                out.line(
                    JsonWriter().beginObject()
                        .field("type", "device")
                        .field("platform", platform)
                        .field("id", id)
                        .field("state", state)
                        .field("name", name)
                        .field("os", os)
                        .endObject()
                        .toString(),
                )
            }
        } else if (lines.isEmpty()) {
            out.line("no devices: start an emulator, connect a phone, or boot an iOS simulator")
        } else {
            val rows = lines.map { (platform, id, state, name, os) ->
                listOf(platform.orEmpty(), id.orEmpty(), state.orEmpty(), listOfNotNull(name, os?.let { "($it)" }).joinToString(" "))
            }
            Render.devices(rows).forEach(out::line)
        }
    }

    /** A tool that fails is noted on stderr, so the other platform's devices are still listed. */
    private fun <T> listOrNote(tool: String, list: () -> List<T>?): List<T>? = try {
        list()
    } catch (e: CliException) {
        printError(environment.stderr, "hopi: note: $tool: ${e.message}")
        null
    }

    /** The newest [CliArgs.limit] transactions above `--since`, paging through the bridge oldest first. */
    private fun list() {
        val kept = ArrayDeque<Reply>()
        var since = args.since
        var dropped = 0L
        withBridge(listRequest(since)) { client ->
            dropped = client.hello.json.long("dropped") ?: 0
            while (true) {
                var page = 0
                while (true) {
                    val reply = client.reply()
                    if (reply.type == "end") break
                    if (reply.type != "transaction") continue
                    page++
                    since = reply.json.long("id") ?: since
                    kept.addLast(reply)
                    if (kept.size > args.limit) kept.removeFirst()
                }
                if (page < MAX_LIMIT) break
                client.send(listRequest(since))
            }
        }
        // Lost terminal writes leave transactions pending forever; say so, as tail's notice does.
        val notice = if (dropped > 0) BridgeSession.droppedNotice(dropped) else null
        if (args.json) {
            notice?.let(out::line)
            kept.forEach { out.line(it.raw) }
        } else {
            if (kept.isEmpty()) {
                out.line("no transactions")
            } else {
                Render.table(listOf(Render.SUMMARY_HEADER) + kept.map { Render.summaryCells(it.json) }).forEach(out::line)
            }
            if (notice != null) out.line(Render.droppedNotice(dropped))
        }
    }

    private fun listRequest(since: Long) =
        BridgeClient.request("list").field("since", since).field("limit", MAX_LIMIT).endObject().toString()

    /** Runs until interrupted or until the app goes away; every line is flushed as it arrives. */
    private fun tail() {
        withBridge(BridgeClient.request("tail").field("since", args.since).endObject().toString()) { client ->
            client.waitForever()
            if (!args.json) {
                out.line(Render.tailRow(Render.SUMMARY_HEADER))
                out.flush()
            }
            while (true) {
                val reply = client.reply(closedMessage = "the app closed the bridge connection (app stopped?)")
                when {
                    args.json -> out.line(reply.raw)
                    reply.type == "transaction" -> out.line(Render.tailRow(Render.summaryCells(reply.json)))
                    reply.type == "notice" -> out.line(Render.droppedNotice(reply.json.long("dropped") ?: 0))
                    else -> continue
                }
                out.flush()
            }
        }
    }

    private fun get() {
        val detail = fetch()
        out.line(if (args.json) detail.raw else Render.detail(detail.json))
    }

    private fun curl() {
        // The bridge redacted the detail already; redacting again would hide what the app chose to show.
        val command = toCurl(Render.requestOf(fetch().json), Redactor.Disabled)
        val shown = visible(command, multiline = true)
        if (shown != command) out.line(CONTROL_CHARACTERS_WARNING)
        out.line(shown)
    }

    private fun fetch(): Reply = withBridge(BridgeClient.request("get").field("id", args.id).endObject().toString()) { client ->
        val reply = try {
            client.reply()
        } catch (e: CliException) {
            if (e.exitCode != ExitCode.NOT_FOUND) throw e
            throw CliException(
                ExitCode.NOT_FOUND,
                "transaction ${args.id} not found: cleared, evicted, or never recorded (hopi list shows what is retained)",
            )
        }
        if (reply.type != "transaction") throw notReachable("unexpected reply from the bridge: ${reply.type?.let(::visible)}")
        reply
    }

    private fun clear() {
        withBridge(request = null) { client ->
            // Sent only after hello confirmed the app: clearing the wrong one cannot be undone.
            client.send(BridgeClient.request("clear").endObject().toString())
            val reply = client.reply()
            if (reply.type != "ok") throw notReachable("unexpected reply from the bridge: ${reply.type?.let(::visible)}")
            out.line(if (args.json) reply.raw else "cleared")
        }
    }

    private inline fun <T> withBridge(request: String?, block: (BridgeClient) -> T): T =
        Targets(environment).open(args).use { endpoint ->
            BridgeClient.connect(endpoint, environment.readTimeoutMs).use { client ->
                client.handshake(request)
                block(client)
            }
        }
}
