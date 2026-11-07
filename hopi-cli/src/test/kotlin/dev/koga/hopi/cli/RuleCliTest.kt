package dev.koga.hopi.cli

import dev.koga.hopi.connect.CliException
import dev.koga.hopi.connect.ExitCode
import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.json.parseFlatJsonObject
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.rules.RuleEngine
import dev.koga.hopi.core.rules.RuleSource
import dev.koga.hopi.core.rules.UrlRuleRequest
import dev.koga.hopi.core.store.HopiStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
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
    }

    @Test
    fun ruleUsageErrors() {
        assertTrue("needs a subcommand" in usage("mock"))
        assertTrue("unknown command 'mock list'" in usage("mock", "list", "--app", "a.b"))
        assertTrue("needs --match" in usage("mock", "add", "--app", "a.b", "--status", "500"))
        assertTrue("(or --from <id> alone)" in usage("mock", "add", "--app", "a.b", "--status", "500"))
        assertEquals(Command.MockAdd, parse("mock", "add", "--app", "a.b", "--from", "3").command)
        assertTrue("needs a rule id" in usage("rules", "rm", "--app", "a.b"))
        assertTrue("rule id" in usage("rules", "rm", "x", "--app", "a.b"))
        assertTrue("does not take --status" in usage("fail", "add", "--app", "a.b", "--match", "/a", "--status", "500"))
        assertTrue("given twice" in usage("mock", "add", "--app", "a.b", "--match", "/a", "--match", "/b"))
        // Removed from the product (docs/ROADMAP.md, "Replay (no priority)").
        assertTrue("unknown command" in usage("replay", "1", "--app", "a.b"))
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
        assertTrue("--allow-other-origin" in draftError("edit", "add", "--match", "/a", "--host", "staging.example.com"))
        assertTrue("--allow-other-origin" in draftError("edit", "add", "--match", "/a", "--port", "8443"))
        RuleDraft.rule(parse("edit", "add", "--match", "/a", "--host", "staging.example.com", "--allow-other-origin", "--app", "a.b")) { it.encodeToByteArray() }
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
        val run = cli("edit", "add", "--match", "*/v1/**", "--status", "503", "--source", "ktor,urlsession", "--json")
        assertEquals(ExitCode.OK, run.exitCode, run.toString())
        val rule = json(run.lines.single())
        assertEquals("rule", rule["type"]?.jsonPrimitive?.content)
        assertEquals("false", rule["unsupported"]?.jsonArray?.single()?.jsonObject?.get("applies")?.jsonPrimitive?.content)
        assertTrue(run.stderr.startsWith("hopi: warning: urlsession: response edits are unsupported"), run.stderr)
        // Naming no sources: checked against the app's platform, here a JVM, whose only source (Ktor) applies it.
        val unnamed = cli("edit", "add", "--match", "*/v2/**", "--status", "503")
        assertEquals(ExitCode.OK, unnamed.exitCode, unnamed.toString())
        assertEquals("", unnamed.stderr)
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

    private fun recorded(ruleId: Long? = null, applied: String? = null): Long {
        val id = store.nextId()
        store.submit(
            HttpTransaction.pending(id, 0, "GET", "https://api.example.com/users/1", listOf("Authorization" to "Bearer real"), null)
                .copy(ruleId = ruleId, ruleApplied = applied, source = "okhttp")
                .completed(500, emptyList(), CapturedBody("x".encodeToByteArray(), 1, false, null, null), 2, null),
        )
        runBlocking { withTimeout(10_000) { store.transactions.first { list -> list.any { it.id == id } } } }
        return id
    }

    @Test
    fun mockFromARequestAloneMatchesItExactly() {
        val id = recorded()
        val run = cli("mock", "add", "--from", id.toString(), "--json")
        assertEquals(ExitCode.OK, run.exitCode, run.toString())
        val rule = assertNotNull(first("GET", "https://api.example.com/users/1?page=2"))
        assertEquals("x", rule.mock?.body?.bytes?.decodeToString())
        assertEquals(500, rule.mock?.status)
        assertEquals(null, first("GET", "https://api.example.com/users/2"))
        assertEquals(null, first("POST", "https://api.example.com/users/1"))
        // --status still overrides the captured one.
        RuleEngine.resetForTests()
        assertEquals(ExitCode.OK, cli("mock", "add", "--from", id.toString(), "--status", "200").exitCode)
        assertEquals(200, first("GET", "https://api.example.com/users/1")?.mock?.status)

        val missing = cli("mock", "add", "--from", "999999")
        assertEquals(ExitCode.NOT_FOUND, missing.exitCode, missing.toString())
        assertTrue("transaction 999999 not found" in missing.stderr, missing.stderr)
        assertEquals("GET https://[::1]:8443/a%20b", exactShorthand("GET", "https://[::1]:8443/a%20b?q=1"))
        assertEquals("GET https://h.example/", exactShorthand("GET", "https://H.example"))
    }

    @Test
    fun rulesSetChangesTimesAndPriority() {
        cli("mock", "add", "--match", "/a", "--status", "500", "--times", "2")
        val id = RuleEngine.list().single().id
        val set = cli("rules", "set", id.toString(), "--times", "5", "--priority", "7", "--json")
        assertEquals(ExitCode.OK, set.exitCode, set.toString())
        assertEquals(5, RuleEngine.get(id)?.spec?.times)
        assertEquals(7, RuleEngine.get(id)?.spec?.priority)
        assertEquals(ExitCode.OK, cli("rules", "set", id.toString(), "--times", "none").exitCode)
        assertEquals(null, RuleEngine.get(id)?.spec?.times)
        assertEquals(7, RuleEngine.get(id)?.spec?.priority)
        assertTrue("needs --times" in cli("rules", "set", id.toString()).stderr)
        assertTrue("--times must be" in cli("rules", "set", id.toString(), "--times", "0").stderr)
        assertEquals(ExitCode.NOT_FOUND, cli("rules", "set", "999", "--priority", "1").exitCode)
    }

    @Test
    fun listTailAndGetShowTheMarks() {
        val ruled = recorded(ruleId = 3, applied = "delay,mock")
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
