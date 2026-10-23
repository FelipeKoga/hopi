package dev.koga.hopi.cli

import dev.koga.hopi.connect.BridgeClient
import dev.koga.hopi.connect.CliException
import dev.koga.hopi.connect.ExitCode
import dev.koga.hopi.connect.Platform
import dev.koga.hopi.connect.Reply
import dev.koga.hopi.connect.long
import dev.koga.hopi.connect.notReachable
import dev.koga.hopi.connect.visible
import dev.koga.hopi.core.bridge.BridgeSession
import dev.koga.hopi.core.curl.toCurl
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.redact.Redactor
import java.io.IOException
import java.io.OutputStream
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
        }
    }

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
