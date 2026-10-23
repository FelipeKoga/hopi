package dev.koga.hopi.cli

import dev.koga.hopi.connect.CliException
import dev.koga.hopi.connect.ExitCode
import dev.koga.hopi.connect.Platform
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliArgsTest {
    private fun parse(vararg argv: String): CliArgs = assertIs<Invocation.Run>(parseInvocation(argv.toList())).args

    private fun usage(vararg argv: String): String {
        val failure = assertFailsWith<CliException> { parseInvocation(argv.toList()) }
        assertEquals(ExitCode.USAGE, failure.exitCode)
        return failure.message.orEmpty()
    }

    @Test
    fun helpForms() {
        assertIs<Invocation.Help>(parseInvocation(emptyList()))
        assertIs<Invocation.Help>(parseInvocation(listOf("--help")))
        assertIs<Invocation.Help>(parseInvocation(listOf("help")))
        assertIs<Invocation.Help>(parseInvocation(listOf("list", "-h")))
    }

    @Test
    fun listWithEveryOption() {
        val args = parse("list", "--app", "com.example.app", "--platform=android", "--device", "emulator-5554", "--json", "--limit", "20", "--since=7")
        assertEquals(Command.List, args.command)
        assertEquals("com.example.app", args.app)
        assertEquals(Platform.Android, args.platform)
        assertEquals("emulator-5554", args.device)
        assertTrue(args.json)
        assertEquals(20, args.limit)
        assertEquals(7, args.since)
    }

    @Test
    fun defaults() {
        val args = parse("tail", "--app", "com.example:remote")
        assertNull(args.platform)
        assertEquals(DEFAULT_LIMIT, args.limit)
        assertEquals(0, args.since)
        assertEquals(false, args.json)
    }

    @Test
    fun bridgeFileImpliesJvmAndMakesAppOptional() {
        val args = parse("get", "42", "--bridge-file", "/tmp/x/bridge.json")
        assertEquals(Platform.Jvm, args.platform)
        assertEquals(Paths.get("/tmp/x/bridge.json"), args.bridgeFile)
        assertEquals(42, args.id)
        assertNull(args.app)
    }

    @Test
    fun usageErrors() {
        assertTrue("unknown command" in usage("lst"))
        assertTrue("needs --app" in usage("list"))
        assertTrue("--limit" in usage("list", "--app", "a.b", "--limit", "0"))
        assertTrue("--limit" in usage("list", "--app", "a.b", "--limit", "10001"))
        assertTrue("--since" in usage("list", "--app", "a.b", "--since", "-1"))
        assertTrue("does not take --limit" in usage("tail", "--app", "a.b", "--limit", "5"))
        assertTrue("does not take --json" in usage("curl", "1", "--app", "a.b", "--json"))
        assertTrue("needs a transaction id" in usage("get", "--app", "a.b"))
        assertTrue("transaction id" in usage("get", "0", "--app", "a.b"))
        assertTrue("transaction id" in usage("get", "abc", "--app", "a.b"))
        assertTrue("unexpected argument" in usage("get", "1", "2", "--app", "a.b"))
        assertTrue("unexpected argument" in usage("list", "5", "--app", "a.b"))
        assertTrue("needs a value" in usage("list", "--app"))
        assertTrue("given twice" in usage("list", "--app", "a.b", "--app", "c.d"))
        assertTrue("--platform" in usage("list", "--app", "a.b", "--platform", "web"))
        assertTrue("--bridge-file" in usage("list", "--platform", "jvm"))
        assertTrue("only for --platform jvm" in usage("list", "--app", "a.b", "--platform", "ios", "--bridge-file", "/x"))
        assertTrue("--device does not apply" in usage("list", "--bridge-file", "/x", "--device", "emulator-5554"))
        assertTrue("--json takes no value" in usage("list", "--app", "a.b", "--json=1"))
    }

    @Test
    fun valuesThatCouldReachAnArgvAsOptionsOrPathsAreRefused() {
        usage("list", "--app", "-s")
        usage("list", "--app", "../../etc")
        usage("list", "--app", "a b")
        usage("list", "--app", "a;rm")
        usage("list", "--app", "")
        usage("list", "--app", "a.b", "--device", "-d")
        usage("list", "--app", "a.b", "--device", "x y")
        usage("list", "--app", "a.b", "--device", "/dev/x")
    }
}
