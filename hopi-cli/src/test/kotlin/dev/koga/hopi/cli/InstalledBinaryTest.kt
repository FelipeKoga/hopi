package dev.koga.hopi.cli

import dev.koga.hopi.core.bridge.JvmAgentBridge
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.store.HopiStore
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `bin/hopi` from `installDist`, run as a real subprocess against an in-process JVM bridge. */
class InstalledBinaryTest {
    private val binary: Path = Paths.get(checkNotNull(System.getProperty("hopi.cli.install")), "bin", "hopi")
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = HopiStore(scope = storeScope)
    private val root: Path = Files.createTempDirectory("hopi-cli-binary")
    private val bridge = JvmAgentBridge.start(store, root.resolve("bridge"), app = "dev.koga.desktop")

    @AfterTest
    fun tearDown() {
        bridge.close()
        storeScope.cancel()
        root.toFile().deleteRecursively()
    }

    private fun start(vararg args: String): Process = ProcessBuilder(listOf(binary.toString()) + args)
        .also { it.environment()["JAVA_HOME"] = System.getProperty("java.home") }
        .start()
        .also { it.outputStream.close() }

    private fun run(vararg args: String): CliRun {
        val process = start(*args)
        val stdout = process.inputStream.readBytes().decodeToString()
        val stderr = process.errorStream.readBytes().decodeToString()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "hopi did not exit")
        return CliRun(process.exitValue(), stdout, stderr)
    }

    private fun record(): HttpTransaction {
        val id = store.nextId()
        val transaction = HttpTransaction.pending(id, 1_700_000_000_000, "DELETE", "https://api.example.com/items/$id", emptyList(), null)
            .completed(204, emptyList(), null, 3, "HTTP/2")
        store.submit(transaction)
        runBlocking { withTimeout(10_000) { store.transactions.first { list -> list.any { it.id == id } } } }
        return transaction
    }

    @Test
    fun helpAndUsage() {
        val help = run("--help")
        assertEquals(ExitCode.OK, help.exitCode, help.toString())
        assertTrue(help.stdout.startsWith("Usage: hopi <command> [options]"), help.stdout)

        val usage = run("list")
        assertEquals(ExitCode.USAGE, usage.exitCode, usage.toString())
        assertEquals("", usage.stdout)
        assertEquals("hopi: list needs --app <applicationId or bundleId> (see hopi --help)\n", usage.stderr)
    }

    @Test
    fun listAndNotFoundOverTheRealBridge() {
        val transaction = record()
        val file = bridge.bridgeFile.toString()
        val list = run("list", "--bridge-file", file, "--json")
        assertEquals(ExitCode.OK, list.exitCode, list.toString())
        val line = Json.parseToJsonElement(list.lines.single()).jsonObject
        assertEquals(transaction.id.toString(), line["id"]!!.jsonPrimitive.content)
        assertEquals("DELETE", line["method"]!!.jsonPrimitive.content)

        assertEquals(ExitCode.NOT_FOUND, run("get", "999", "--bridge-file", file).exitCode)
        assertEquals(ExitCode.NOT_REACHABLE, run("get", "1", "--bridge-file", root.resolve("none.json").toString()).exitCode)
    }

    @Test
    fun tailExitsQuietlyWhenItsReaderGoesAway() {
        record()
        val process = start("tail", "--bridge-file", bridge.bridgeFile.toString(), "--json")
        val reader: BufferedReader = process.inputStream.bufferedReader()
        assertTrue(reader.readLine().orEmpty().startsWith("""{"type":"transaction""""))
        // `hopi tail | head -1`: the reader is gone, so the next event's write fails.
        process.inputStream.close()
        record()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "tail kept running after its stdout closed")
        assertEquals(ExitCode.OK, process.exitValue())
        assertEquals("", process.errorStream.readBytes().decodeToString())
    }
}
