package dev.koga.hopi.cli

import dev.koga.hopi.connect.ExitCode
import dev.koga.hopi.connect.ProcessResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewerCommandTest {
    private val commands = ArrayList<List<String>>()

    private fun viewer(vararg argv: String, exitCode: Int = 0, isMac: Boolean = true): CliRun = runCapturing(
        "viewer",
        *argv,
        processes = { command ->
            commands += command
            ProcessResult(exitCode, "", "")
        },
        isMac = isMac,
    )

    @Test
    fun withoutATargetItBringsUpTheApp() {
        assertEquals(ExitCode.OK, viewer().exitCode)
        assertEquals(listOf(listOf("/usr/bin/open", "-a", "Hopi")), commands)
    }

    @Test
    fun aTargetOpensItsOwnWindow() {
        assertEquals(ExitCode.OK, viewer("--app", "dev.koga.grifo", "--platform", "ios", "--device", "UDID-1").exitCode)
        assertEquals(
            listOf(listOf("/usr/bin/open", "-n", "-a", "Hopi", "--args", "--app", "dev.koga.grifo", "--platform", "ios", "--device", "UDID-1")),
            commands,
        )
    }

    @Test
    fun aMissingAppSaysHowToInstallIt() {
        val run = viewer(exitCode = 1)
        assertEquals(ExitCode.NOT_REACHABLE, run.exitCode)
        assertTrue("packageDmg" in run.stderr, run.toString())
    }

    @Test
    fun itIsMacOnly() {
        val run = viewer(isMac = false)
        assertEquals(ExitCode.USAGE, run.exitCode)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun aMalformedAppIsRefusedBeforeOpen() {
        assertEquals(ExitCode.USAGE, viewer("--app", "--evil").exitCode)
        assertTrue(commands.isEmpty())
    }
}
