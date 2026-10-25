package dev.koga.hopi.cli

import dev.koga.hopi.connect.CliException
import dev.koga.hopi.connect.ExitCode
import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.json.parseFlatJsonObject
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.rules.ReplayHandoff
import dev.koga.hopi.core.rules.ReplayRequest
import dev.koga.hopi.core.rules.ReplayTarget
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleSource
import dev.koga.hopi.core.rules.UrlRuleRequest
import dev.koga.hopi.core.store.HopiStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The rule commands: their parsing, and end to end against the real JVM bridge and engine. */
class RuleCliTest {
    private val store = HopiStore.default
    private val root: Path = Files.createTempDirectory("hopi-cli-rules")
    private val bridge = JvmAgentBridge.start(store, root.resolve("bridge"), app = APP)
    private val bridgeFile = bridge.bridgeFile.toString()

    @BeforeTest
    fun setUp() = RuleEngine.resetForTests()

    @AfterTest
    fun tearDown() {
        RuleEngine.resetForTests()
        bridge.close()
        root.toFile().deleteRecursively()
    }

    private fun parse(vararg argv: String): CliArgs = assertIs<Invocation.Run>(parseInvocation(argv.toList())).args

    private fun usage(vararg argv: String): String {
        val failure = assertFailsWith<CliException> { parseInvocation(argv.toList()) }
        assertEquals(ExitCode.USAGE, failure.exitCode)
        return failure.message.orEmpty()
    }

    private fun cli(vararg argv: String, stdin: ByteArray = ByteArray(0)): CliRun {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val environment = CliEnvironment(stdout, stderr, FakeProcessRunner(), emptyMap(), "/home/test", isMac = false, isExecutable = { false }, RecordingShutdownHooks(), 10_000, ByteArrayInputStream(stdin))
        val code = runCli(argv.toList() + listOf("--bridge-file", bridgeFile), environment)
        return CliRun(code, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
    }

    private fun json(line: String): JsonObject = Json.parseToJsonElement(line).jsonObject

    private fun first(method: String, url: String) = RuleEngine.state.firstMatch(RuleSource.OKHTTP, UrlRuleRequest(method, url, emptyList()))

    @Test
    fun parsesTwoWordCommandsRepeatableFlagsAndSwitches() {
        val args = parse("mock", "add", "--app", "a.b", "--match", "GET */users/*", "--status", "500", "--header", "A: 1", "--header", "B: 2", "--disabled", "--json")
        assertEquals(Command.MockAdd, args.command)
        assertEquals(listOf("A: 1", "B: 2"), args.lists["--header"])
        assertEquals("500", args.values["--status"])
        assertTrue("--disabled" in args.switches)
        assertTrue(args.json)
        assertEquals(listOf(3L, 4L, 9L), parse("rules", "rm", "3", "4", "9", "--app", "a.b").ids)
        assertEquals(42, parse("replay", "42", "--app", "a.b", "--wait").id)
    }

    @Test
    fun ruleUsageErrors() {
        assertTrue("needs a subcommand" in usage("mock"))
        assertTrue("unknown command 'mock list'" in usage("mock", "list", "--app", "a.b"))
        assertTrue("needs --match" in usage("mock", "add", "--app", "a.b", "--status", "500"))
        assertTrue("needs a rule id" in usage("rules", "rm", "--app", "a.b"))
        assertTrue("rule id" in usage("rules", "rm", "x", "--app", "a.b"))
        assertTrue("does not take --status" in usage("fail", "add", "--app", "a.b", "--match", "/a", "--status", "500"))
        assertTrue("given twice" in usage("mock", "add", "--app", "a.b", "--match", "/a", "--match", "/b"))
        assertTrue("--wait takes no value" in usage("replay", "1", "--app", "a.b", "--wait=1"))
        assertTrue("unexpected argument" in usage("replay", "1", "2", "--app", "a.b"))
    }

    @Test
    fun draftErrorsAreUsageErrorsBeforeConnecting() {
        fun draftError(vararg argv: String): String {
            val args = parse(*argv, "--app", "a.b")
            return assertFailsWith<CliException> { RuleDraft.rule(args) { it.encodeToByteArray() } }.message.orEmpty()
        }
        assertTrue("--match" in draftError("mock", "add", "--match", "ftp://h/a", "--status", "200"))
        assertTrue("--status" in draftError("mock", "add", "--match", "/a"))
        assertTrue("--status" in draftError("mock", "add", "--match", "/a", "--status", "abc"))
        assertTrue("'Name: value'" in draftError("mock", "add", "--match", "/a", "--status", "200", "--header", "nocolon"))
        assertTrue("--with" in draftError("fail", "add", "--match", "/a"))
        assertTrue("--with must be" in draftError("fail", "add", "--match", "/a", "--with", "explode"))
        assertTrue("duration" in draftError("delay", "add", "--match", "/a", "--before-request", "5 minutes"))
        assertTrue("0..120s" in draftError("delay", "add", "--match", "/a", "--before-request", "121s"))
        assertTrue("needs --before-request" in draftError("delay", "add", "--match", "/a"))
        assertTrue("at least one" in draftError("edit", "add", "--match", "/a"))
        assertTrue("--source" in draftError("fail", "add", "--match", "/a", "--with", "reset", "--source", "cronet"))
        assertEquals(1500, RuleDraft.duration("1.5s", "--x"))
        assertEquals(250, RuleDraft.duration("250ms", "--x"))
    }

    @Test
    fun mockAddPrintsTheRuleAndTheAppAppliesIt() {
        val run = cli("mock", "add", "--match", "GET */users/*", "--status", "500", "--header", "Content-Type: application/json", "--body", """{"error":"x"}""", "--times", "3", "--delay", "2s")
        assertEquals(ExitCode.OK, run.exitCode, run.toString())
        assertTrue(Regex("""^#\d+ {2}GET \*/users/\* {2}mock 500 \(13 B\), delay 2000ms before response {2}times 3$""").matches(run.lines.single()), run.stdout)
        assertEquals("", run.stderr)
        val rule = assertNotNull(first("GET", "https://api.example.com/users/42"))
        assertEquals("""{"error":"x"}""", rule.mock?.body?.bytes?.decodeToString())
        assertEquals(listOf("Content-Type" to "application/json"), rule.mock?.headers)
    }

    @Test
    fun unsupportedSourcesAreWarnedOnStderr() {
        val run = cli("edit", "add", "--match", "*/v1/**", "--status", "503", "--json")
        assertEquals(ExitCode.OK, run.exitCode, run.toString())
        assertEquals("rule", json(run.lines.single())["type"]?.jsonPrimitive?.content)
        assertTrue(run.stderr.startsWith("hopi: warning: urlsession: response edits are unsupported"), run.stderr)
    }

    @Test
    fun bodiesFromAFileStdinAndLargeOnesAsBlobs() {
        val file = root.resolve("body.bin")
        val big = ByteArray(300_000) { (it % 7).toByte() }
        Files.write(file, big)
        assertEquals(ExitCode.OK, cli("mock", "add", "--match", "/big", "--status", "200", "--body", "@$file").exitCode)
        assertTrue(big.contentEquals(first("GET", "https://h/big")?.mock?.body?.bytes))
        assertEquals(ExitCode.OK, cli("mock", "add", "--match", "/stdin", "--status", "200", "--body", "@-", stdin = "from stdin".encodeToByteArray()).exitCode)
        assertEquals("from stdin", first("GET", "https://h/stdin")?.mock?.body?.bytes?.decodeToString())
        val missing = cli("mock", "add", "--match", "/x", "--status", "200", "--body", "@/no/such/file")
        assertEquals(ExitCode.USAGE, missing.exitCode)
        assertTrue("no such file" in missing.stderr, missing.stderr)
    }

    @Test
    fun failDelayAndEditRules() {
        assertEquals(ExitCode.OK, cli("fail", "add", "--match", "POST /upload", "--with", "timeout", "--after", "30s", "--source", "okhttp").exitCode)
        assertEquals(ExitCode.OK, cli("delay", "add", "--match", "/slow", "--before-request", "500ms").exitCode)
        assertEquals(
            ExitCode.OK,
            cli("edit", "add", "--match", "/v1/users", "--path", "/v2/users", "--set-header", "X-Env: staging", "--remove-header", "X-Debug", "--set-query", "page=2").exitCode,
        )
        val fail = assertNotNull(first("POST", "https://h/upload"))
        assertEquals("timeout", fail.fail?.wireName)
        assertEquals(30_000, fail.delay?.beforeResponseMs)
        assertEquals(500, first("GET", "https://h/slow")?.delay?.beforeRequestMs)
        val edit = assertNotNull(first("GET", "https://h/v1/users")?.requestEdit)
        assertEquals("/v2/users", edit.path)
        assertEquals(listOf("X-Env" to "staging"), edit.setHeaders)
        assertEquals(listOf("X-Debug"), edit.removeHeaders)
        assertEquals(listOf("page" to "2"), edit.setQuery)
    }

    @Test
    fun rulesLsRmEnableDisableResetClear() {
        cli("mock", "add", "--match", "GET */users/*", "--status", "500", "--times", "2", "--label", "users")
        cli("fail", "add", "--match", "/upload", "--with", "reset", "--priority", "3")
        val ids = RuleEngine.list().map { it.id }
        val ls = cli("rules", "ls")
        assertEquals(ExitCode.OK, ls.exitCode, ls.toString())
        assertEquals(listOf("ID", "ON", "PRI", "HITS", "MATCH", "ACTION"), ls.lines[0].split(Regex(" +")))
        assertTrue(ls.lines[1].contains("0/2") && ls.lines[1].contains("\"users\""), ls.stdout)
        assertTrue(ls.lines[2].contains("fail reset"), ls.stdout)
        assertEquals(2, cli("rules", "ls", "--json").lines.count { json(it)["type"]?.jsonPrimitive?.content == "rule" })

        assertTrue("(disabled)" in cli("rules", "disable", ids[0].toString()).stdout)
        assertEquals(false, RuleEngine.get(ids[0])?.spec?.enabled)
        cli("rules", "enable", ids[0].toString())
        first("GET", "https://h/users/1")
        assertEquals(1, RuleEngine.get(ids[0])?.hits)
        cli("rules", "reset", ids[0].toString())
        assertEquals(0, RuleEngine.get(ids[0])?.hits)

        assertEquals("removed #${ids[1]}", cli("rules", "rm", ids[1].toString()).lines.single())
        val missing = cli("rules", "rm", ids[1].toString())
        assertEquals(ExitCode.NOT_FOUND, missing.exitCode)
        assertTrue("rule ${ids[1]} not found" in missing.stderr, missing.stderr)
        assertEquals("removed 1 rules", cli("rules", "clear").lines.single())
        assertEquals("no rules", cli("rules", "ls").lines.single())
    }

    @Test
    fun appValidationIsExitOneAndRulesOffIsExitFour() {
        val rejected = cli("mock", "add", "--match", "/a", "--status", "200", "--header", "Set-Cookie: a=b")
        assertEquals(ExitCode.USAGE, rejected.exitCode)
        assertTrue("mock.headers" in rejected.stderr, rejected.stderr)
        RuleEngine.recorderStarted(rules = false)
        val off = cli("rules", "ls")
        assertEquals(ExitCode.UNSUPPORTED, off.exitCode)
        assertTrue("does not support rules" in off.stderr, off.stderr)
    }

    private class FakeTarget(private val store: HopiStore) : ReplayTarget {
        val sent = LinkedBlockingQueue<Pair<ReplayRequest, ReplayHandoff>>()
        override val source: String = "okhttp"

        override fun refusal(): String? = null

        /** Records the replay as a recorder would, then finishes the hand-off. */
        override fun send(request: ReplayRequest, handoff: ReplayHandoff) {
            sent += request to handoff
            check(handoff.take())
            store.submit(
                HttpTransaction.pending(handoff.id, 0, request.method, request.url, request.headers, null)
                    .copy(replayOf = handoff.replayOf, source = source)
                    .completed(201, emptyList(), null, 3, "h2"),
            )
            handoff.finish(null)
        }
    }

    private fun recorded(target: ReplayTarget?, ruleId: Long? = null, applied: String? = null): Long {
        val id = store.nextId()
        store.submit(
            HttpTransaction.pending(id, 0, "GET", "https://api.example.com/users/1", listOf("Authorization" to "Bearer real"), null)
                .copy(ruleId = ruleId, ruleApplied = applied, source = "okhttp")
                .completed(500, emptyList(), CapturedBody("x".encodeToByteArray(), 1, false, null, null), 2, null)
                .copy(replayTarget = target),
        )
        runBlocking { withTimeout(10_000) { store.transactions.first { list -> list.any { it.id == id } } } }
        return id
    }

    @Test
    fun replayPrintsTheNewIdAndWaitPrintsTheRecord() {
        val target = FakeTarget(store)
        val of = recorded(target)
        val run = cli("replay", of.toString(), "--set-header", "X-Replay: 1")
        assertEquals(ExitCode.OK, run.exitCode, run.toString())
        val newId = run.lines.single().toLong()
        val (request, handoff) = assertNotNull(target.sent.poll(10, TimeUnit.SECONDS))
        assertEquals(newId, handoff.id)
        assertEquals(listOf("Authorization" to "Bearer real", "X-Replay" to "1"), request.headers)

        val waited = cli("replay", of.toString(), "--wait")
        assertEquals(ExitCode.OK, waited.exitCode, waited.toString())
        assertTrue(waited.lines[1].contains("201") && waited.lines[1].contains("[replay of $of]"), waited.stdout)
        val waitedJson = cli("replay", of.toString(), "--wait", "--json")
        val line = json(waitedJson.lines.single())
        assertEquals(of, line["replayOf"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun replayRefusalsAreExitFourAndAMissingRecordExitThree() {
        val refused = cli("replay", recorded(null).toString())
        assertEquals(ExitCode.UNSUPPORTED, refused.exitCode, refused.toString())
        assertTrue("cannot be replayed" in refused.stderr, refused.stderr)
        val missing = cli("replay", "999999")
        assertEquals(ExitCode.NOT_FOUND, missing.exitCode, missing.toString())
    }

    @Test
    fun listTailAndGetShowTheMarks() {
        val ruled = recorded(null, ruleId = 3, applied = "delay,mock")
        val listed = cli("list", "--since", (ruled - 1).toString())
        assertTrue(listed.lines[1].endsWith("[okhttp]  [delay,mock #3]"), listed.stdout)
        val listedJson = parseFlatJsonObject(cli("list", "--since", (ruled - 1).toString(), "--json").lines.single())
        assertEquals(3L, listedJson?.get("rule"))
        val detail = cli("get", ruled.toString())
        assertTrue(detail.stdout.lines().any { it.trim() == "Rule      #3: delay, mock" }, detail.stdout)
        val detailJson = json(cli("get", ruled.toString(), "--json").lines.single())
        assertEquals("delay,mock", detailJson["applied"]?.jsonPrimitive?.content)
        assertTrue(detailJson["request"]!!.jsonObject["headers"]!!.jsonArray.isNotEmpty())
    }

    private companion object {
        const val APP = "dev.koga.cli.rules"
    }
}
