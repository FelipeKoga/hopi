package dev.koga.hopi.connect

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun frame(body: String): String = "%04x".format(body.length) + body

class AdbTrackingTest {
    @Test
    fun trackFramesAreLengthPrefixed() {
        // What `adb track-devices` printed with no device attached (platform-tools 35.0.2): "0000".
        val stream = ByteArrayInputStream(("0000" + frame("emulator-5554\tdevice\n") + frame("emulator-5554\toffline\n")).toByteArray())
        assertEquals("", readTrackFrame(stream))
        assertEquals("emulator-5554\tdevice\n", readTrackFrame(stream))
        assertEquals(listOf("offline"), parseAdbDevices(readTrackFrame(stream)!!).map { it.state })
        assertNull(readTrackFrame(stream), "end of stream")
        assertNull(readTrackFrame(ByteArrayInputStream("zz12abc".toByteArray())), "not a length")
        assertNull(readTrackFrame(ByteArrayInputStream("0010short".toByteArray())), "cut short")
    }

    @Test
    fun jdwpFramesAreOnePidPerLine() {
        assertEquals(setOf(1234, 5678), parseJdwpPids("1234\n5678\n"))
        assertEquals(emptySet(), parseJdwpPids(""))
    }

    @Test
    fun bridgesComeFromHopiSocketsInProcNetUnix() {
        // As an API 35 emulator listed them: the listening socket, then a connected client's line.
        val output = """
            Num       RefCount Protocol Flags    Type St Inode Path
            0000000000000000: 00000002 00000000 00010000 0001 01 81234 @hopi.dev.koga.hopi.sample
            0000000000000000: 00000003 00000000 00000000 0001 03 81240 @hopi.dev.koga.hopi.sample
            0000000000000000: 00000002 00000000 00010000 0001 01 81300 @hopi.com.example:remote
            0000000000000000: 00000002 00000000 00010000 0001 01 81301 @hopi.-rf
            0000000000000000: 00000002 00000000 00010000 0001 01 900 /dev/socket/zygote
            0000000000000000: 00000002 00000000 00010000 0001 01 901
        """.trimIndent()
        assertEquals(listOf("com.example:remote", "dev.koga.hopi.sample"), parseUnixSocketBridges(output))
    }

    @Test
    fun trackDevicesDeliversEverySnapshotAndReportsTheEnd() {
        val adb = ScriptedAdb()
        val snapshots = LinkedBlockingQueue<List<String>>()
        val ended = CountDownLatch(1)
        val tracking = adb.targets.trackAndroidDevices({ list -> snapshots.put(list.map { "${it.serial}=${it.state}:${it.model}" }) }, ended::countDown)
        assertNotNull(tracking)
        assertEquals(listOf(adb.path, "track-devices", "-l"), adb.started.take())
        adb.send(frame(""))
        // `track-devices -l` on platform-tools 35.0.2, so an update keeps the model.
        adb.send(frame("emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1\n"))
        assertEquals(emptyList(), snapshots.poll(5, TimeUnit.SECONDS))
        assertEquals(listOf("emulator-5554=device:sdk_gphone64_arm64"), snapshots.poll(5, TimeUnit.SECONDS))
        adb.end()
        assertTrue(ended.await(5, TimeUnit.SECONDS), "adb ended the stream")
    }

    @Test
    fun trackJdwpCallsOnlyOnChangesAndCloseIsNotAnEnd() {
        val adb = ScriptedAdb()
        val changes = LinkedBlockingQueue<Unit>()
        val ended = AtomicBoolean(false)
        val tracking = adb.targets.trackDebuggableProcesses("emulator-5554", { changes.put(Unit) }, { ended.set(true) })!!
        assertEquals(listOf(adb.path, "-s", "emulator-5554", "track-jdwp"), adb.started.take())
        adb.send(frame("100\n200\n"))
        adb.send(frame("100\n200\n"))
        adb.send(frame("100\n300\n"))
        assertNotNull(changes.poll(5, TimeUnit.SECONDS), "the restart was reported")
        assertNull(changes.poll(200, TimeUnit.MILLISECONDS), "the first list and a repeat are not changes")
        tracking.close()
        assertTrue(adb.closed.await(5, TimeUnit.SECONDS), "close ended the process")
        Thread.sleep(100)
        assertTrue(!ended.get(), "a close is not reported as adb ending the stream")
    }
}

/** adb found at a fake path, its tracking output written by the test through a pipe. */
private class ScriptedAdb {
    val path = "/sdk/platform-tools/adb"
    val started = LinkedBlockingQueue<List<String>>()
    val closed = CountDownLatch(1)
    private val sink = PipedOutputStream()
    private val source = PipedInputStream(sink, 64 * 1024)

    val targets = Targets(
        HostEnvironment(
            processes = { throw IOException("no run in this test") },
            env = mapOf("ANDROID_HOME" to "/sdk"),
            userHome = "",
            isMac = false,
            isExecutable = { it.toString() == path },
            shutdownHooks = object : ShutdownHooks {
                override fun add(hook: Thread) = Unit

                override fun remove(hook: Thread) = Unit
            },
            streams = { command ->
                started.put(command)
                object : StreamingProcess {
                    override val input: InputStream = source

                    override fun close() {
                        source.close()
                        closed.countDown()
                    }
                }
            },
        ),
    )

    fun send(text: String) {
        sink.write(text.toByteArray())
        sink.flush()
    }

    fun end() = sink.close()
}
