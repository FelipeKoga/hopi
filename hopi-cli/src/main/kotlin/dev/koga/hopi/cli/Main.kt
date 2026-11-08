package dev.koga.hopi.cli

import dev.koga.hopi.connect.BridgeClient
import dev.koga.hopi.connect.BridgeDecoder
import dev.koga.hopi.connect.BridgeEndpoint
import dev.koga.hopi.connect.ExportFormat
import dev.koga.hopi.connect.SessionExport
import dev.koga.hopi.connect.FoundApp
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
import dev.koga.hopi.connect.bool
import dev.koga.hopi.connect.usageError
import dev.koga.hopi.connect.notReachable
import dev.koga.hopi.connect.visible
import dev.koga.hopi.core.bridge.BridgeSession
import dev.koga.hopi.core.curl.toCurl
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.core.rules.RuleLimits
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonObject

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

private const val NO_APPS = "no app with a Hopi bridge found: run a debug build of the app (hopi devices lists the targets)"

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
            Command.Apps -> apps()
            Command.Export -> export()
            Command.Wait -> waitFor()
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
            Command.RulesSet -> setRules()
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
        if (!environment.host.isMac) throw usageError("viewer is macOS only: the viewer is a macOS app; on other systems use list, tail and get")
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
            throw notReachable("Hopi.app is not installed: download the .dmg from https://github.com/FelipeKoga/hopi/releases (or build it with scripts/mac-dmg.sh in Hopi's repository) and drag the app to Applications")
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
        val parsed = RuleDraft.rule(args, ::readBody)
        withBridge(request = null) { client ->
            val rules = RuleClient(client)
            val draft = if (parsed.needsMatch) {
                // --from alone: match that request exactly, as the viewers' "Mock This Response".
                val from = args.values.getValue("--from").toLong()
                client.send(BridgeClient.request("get").field("id", from).endObject().toString())
                val detail = try {
                    client.reply().json
                } catch (e: CliException) {
                    if (e.exitCode == ExitCode.NOT_FOUND) throw CliException(ExitCode.NOT_FOUND, "transaction $from not found (hopi list shows what is retained)")
                    throw e
                }
                val method = detail.str("method")
                val url = detail.str("url")
                if (method == null || url == null) throw notReachable("the bridge sent a transaction without its method or URL")
                parsed.matchingExactly(method, url)
            } else {
                parsed
            }
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

    /** `rules set`: --times N or none (no limit), --priority N; checked before connecting. */
    private fun setRules() {
        val timesValue = args.values["--times"]
        val clearTimes = timesValue == "none"
        val times = if (timesValue == null || clearTimes) {
            null
        } else {
            timesValue.toIntOrNull()?.takeIf { it in 1..RuleLimits.MAX_TIMES }
                ?: throw usageError("--times must be 1..${RuleLimits.MAX_TIMES} or none")
        }
        val priority = args.values["--priority"]?.let { it.toIntOrNull() ?: throw usageError("--priority must be a whole number") }
        if (timesValue == null && priority == null) throw usageError("rules set needs --times N|none or --priority N")
        withRules { rules ->
            for (id in args.ids) {
                val reply = try {
                    rules.update(id, priority = priority, times = times, clearTimes = clearTimes)
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
        val filter = TransactionFilter.of(args)
        var kept = ArrayDeque<Reply>()
        var dropped = 0L
        withBridge(listRequest(args.since)) { client ->
            dropped = client.hello.json.long("dropped") ?: 0
            kept = summaries(client, filter, args.limit)
        }
        // Lost terminal writes leave transactions pending forever; say so, as tail's notice does.
        val notice = if (dropped > 0) BridgeSession.droppedNotice(dropped) else null
        if (args.json) {
            notice?.let(out::line)
            kept.forEach { out.line(it.raw) }
        } else {
            if (kept.isEmpty()) {
                out.line(if (filter != null) "no matching transactions" else "no transactions")
            } else {
                Render.table(listOf(Render.SUMMARY_HEADER) + kept.map { Render.summaryCells(it.json) }).forEach(out::line)
            }
            if (notice != null) out.line(Render.droppedNotice(dropped))
        }
    }

    /** The newest [limit] summaries above `--since` that pass [filter]; `list` was sent with the handshake. */
    private fun summaries(client: BridgeClient, filter: TransactionFilter?, limit: Int): ArrayDeque<Reply> {
        val kept = ArrayDeque<Reply>()
        var since = args.since
        while (true) {
            var page = 0
            while (true) {
                val reply = client.reply()
                if (reply.type == "end") break
                if (reply.type != "transaction") continue
                page++
                since = reply.json.long("id") ?: since
                if (filter != null && !filter.matches(reply.json)) continue
                kept.addLast(reply)
                if (kept.size > limit) kept.removeFirst()
            }
            if (page < MAX_LIMIT) return kept
            client.send(listRequest(since))
        }
    }

    /**
     * HAR 1.2 or NDJSON on stdout, as the viewers export: every retained transaction (or the
     * newest --limit) that passes the filters, each completed with `get` within the export budget.
     */
    private fun export() {
        val format = when (val name = args.values["--format"] ?: "har") {
            "har" -> ExportFormat.Har
            "ndjson" -> ExportFormat.Ndjson
            else -> throw usageError("--format must be har or ndjson, not '$name'")
        }
        val filter = TransactionFilter.of(args)
        val limit = if (args.values["--limit"] != null) args.limit else MAX_LIMIT
        val result = withBridge(listRequest(args.since)) { client ->
            val transactions = summaries(client, filter, limit).mapNotNull { BridgeDecoder.summary(it.json, startedAtFallback = 0) }
            val fetch = { id: Long ->
                client.send(BridgeClient.request("get").field("id", id).endObject().toString())
                try {
                    BridgeDecoder.detail(client.reply().json)
                } catch (e: CliException) {
                    if (e.exitCode != ExitCode.NOT_FOUND) throw e
                    null
                }
            }
            // The bridge redacted every line already, as for curl.
            SessionExport.write(format, transactions, Redactor.Disabled, out.writer(), fetch, creatorVersion = "hopi-cli")
        }
        if (result.withoutBodies > 0) {
            printError(environment.stderr, "hopi: note: ${result.withoutBodies} of ${result.transactions} exported without bodies: over the export budget or gone from the app")
        }
    }

    private fun listRequest(since: Long) =
        BridgeClient.request("list").field("since", since).field("limit", MAX_LIMIT).endObject().toString()

    /** Runs until interrupted or until the app goes away; every line is flushed as it arrives. */
    private fun tail() {
        val filter = TransactionFilter.of(args)
        val request = BridgeClient.request("tail").field("since", args.since).endObject().toString()
        val full = "--full-bodies" in args.switches
        withBridge(if (full) null else request) { client ->
            if (full) {
                // An app whose hello does not offer it never gets the command, as in the viewers.
                if (client.hello.json.bool("fullBodies") != true) {
                    throw CliException(ExitCode.UNSUPPORTED, "this app's Hopi does not offer full bodies (an older version): tail without --full-bodies")
                }
                client.sendLines(BridgeClient.request("capture.set").field("fullBodies", true).endObject().toString() + "\n" + request + "\n")
            }
            client.waitForever()
            if (!args.json) {
                out.line(Render.tailRow(Render.SUMMARY_HEADER))
                out.flush()
            }
            while (true) {
                val reply = client.reply(closedMessage = "the app closed the bridge connection (app stopped?)")
                when {
                    // capture.set's answer, not part of the stream.
                    reply.type == "capture" -> continue
                    reply.type == "transaction" && filter != null && !filter.matches(reply.json) -> continue
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
        val part = args.values["--body"]?.also {
            if (it != "request" && it != "response") throw usageError("--body must be request or response, not '$it'")
            if (args.json) throw usageError("--body prints raw bytes: leave out --json")
        }
        val detail = fetch()
        if (part == null) {
            out.line(if (args.json) detail.raw else Render.detail(detail.json))
            return
        }
        val body = ((detail.json[part] as? JsonObject)?.get("body") as? JsonObject)?.let(Render::capturedBody)
        when {
            body == null -> printError(environment.stderr, "hopi: note: no $part body")
            body.skippedReason != null -> printError(environment.stderr, "hopi: note: the $part body was not captured: ${visible(body.skippedReason!!)}")
            body.truncated -> printError(environment.stderr, "hopi: note: the $part body is truncated: ${body.bytes.size} of ${body.totalBytes ?: "unknown"} bytes captured (tail --full-bodies keeps whole ones)")
        }
        body?.let { out.bytes(it.bytes) }
    }

    /**
     * Blocks until a transaction that passes the filters ends (complete or failed), prints it and
     * exits 0; exit 3 when --timeout passes first. Without --since only transactions newer than
     * the newest one when it starts count, so an agent starts it, then triggers the request.
     */
    private fun waitFor() {
        val timeoutMs = args.values["--timeout"]?.let(::waitTimeout) ?: 30_000L
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val filter = TransactionFilter.of(args)
        val explicit = args.values["--since"] != null
        withBridge(if (explicit) null else listRequest(0)) { client ->
            val since = if (explicit) args.since else summaries(client, null, 1).lastOrNull()?.json?.long("id") ?: 0
            client.send(BridgeClient.request("tail").field("since", since).endObject().toString())
            while (true) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) throw timedOut(timeoutMs)
                client.readTimeout(left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                val reply = try {
                    client.reply(closedMessage = "the app closed the bridge connection (app stopped?)")
                } catch (e: CliException) {
                    if (System.nanoTime() >= deadline) throw timedOut(timeoutMs)
                    throw e
                }
                if (reply.type != "transaction" || reply.json.str("state") == "pending") continue
                if (filter != null && !filter.matches(reply.json)) continue
                out.line(if (args.json) reply.raw else Render.table(listOf(Render.SUMMARY_HEADER, Render.summaryCells(reply.json))).joinToString("\n"))
                return@withBridge
            }
        }
    }

    private fun timedOut(timeoutMs: Long) =
        CliException(ExitCode.NOT_FOUND, "no matching transaction ended within ${timeoutMs / 1000.0}s".replace(".0s", "s"))

    /** 500ms, 30s, 2m; at most an hour. */
    private fun waitTimeout(text: String): Long {
        val ms = when {
            text.endsWith("ms") -> text.dropLast(2).toLongOrNull()
            text.endsWith("s") -> text.dropLast(1).toLongOrNull()?.times(1000)
            text.endsWith("m") -> text.dropLast(1).toLongOrNull()?.times(60_000)
            else -> null
        }
        if (ms == null || ms !in 1..3_600_000) throw usageError("--timeout takes 500ms, 30s or 2m (at most 60m), not '$text'")
        return ms
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

    private fun apps() {
        val found = Targets(environment).apps(args.platform, args.device)
        if (args.json) {
            found.forEach { out.line(appLine(it)) }
        } else if (found.isEmpty()) {
            out.line(NO_APPS)
        } else {
            Render.apps(found.map { listOf(it.app, it.platform.id, it.device ?: it.bridgeFile.toString(), it.deviceName) }).forEach(out::line)
        }
    }

    private fun appLine(app: FoundApp): String = JsonWriter().beginObject()
        .field("type", "app")
        .field("app", app.app)
        .field("platform", app.platform.id)
        .field("device", app.device)
        .field("deviceName", app.deviceName)
        .field("bridgeFile", app.bridgeFile?.toString())
        .endObject()
        .toString()

    /**
     * The endpoint the command line names; without --app (and no --bridge-file), the only app
     * [Targets.apps] finds, narrowed by --platform and --device. A JVM app without --bridge-file is
     * found the same way, by --app when given. Several or none stop with the list.
     */
    private fun openTarget(): BridgeEndpoint {
        val targets = Targets(environment)
        if (args.bridgeFile != null || args.app != null && args.platform != Platform.Jvm) return targets.open(args)
        val found = targets.apps(args.platform, args.device).filter { args.app == null || it.app == args.app }
        val app = when (found.size) {
            1 -> found[0]
            0 -> throw notReachable("${args.command.wireName}: $NO_APPS; an iPhone on USB needs --app")
            else -> throw usageError(
                "several apps have a Hopi bridge, pass one: " +
                    found.joinToString("; ") { "--app ${it.app} " + if (it.bridgeFile != null) "--bridge-file ${it.bridgeFile}" else "--platform ${it.platform.id} --device ${it.device}" },
            )
        }
        return targets.open(app.platform, app.app, app.device, app.bridgeFile)
    }

    private inline fun <T> withBridge(request: String?, block: (BridgeClient) -> T): T =
        openTarget().use { endpoint ->
            BridgeClient.connect(endpoint, environment.readTimeoutMs).use { client ->
                client.handshake(request)
                block(client)
            }
        }
}
