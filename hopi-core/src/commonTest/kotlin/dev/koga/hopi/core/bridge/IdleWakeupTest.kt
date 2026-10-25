package dev.koga.hopi.core.bridge

import dev.koga.hopi.core.complete
import dev.koga.hopi.core.concurrent.WakeSignal
import dev.koga.hopi.core.store.HopiStore
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * A connected, idle `tail` wakes nothing: the bridge writer and the store's consumer block with no
 * timer (on iOS a coroutine event loop parked on a Kotlin/Native worker woke on a keep-alive
 * timer, about 0.35 ms of CPU a second). Runs on the JVM and on the iOS simulator.
 */
class IdleWakeupTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = HopiStore()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        store.close()
    }

    /** A connection the test plays the client of: lines in, lines out, blocking reads with no timer. */
    private class FakeConnection : BridgeConnection {
        private val inbound = AtomicReference(ByteArray(0))
        private val readable = WakeSignal()
        private val ended = AtomicBoolean(false)
        private val written = AtomicReference("")

        val lines: List<String> get() = written.load().split('\n').dropLast(1)

        fun send(line: String) {
            while (true) {
                val current = inbound.load()
                if (inbound.compareAndSet(current, current + (line + "\n").encodeToByteArray())) break
            }
            readable.wake()
        }

        override fun read(buffer: ByteArray): Int {
            while (true) {
                val current = inbound.load()
                if (current.isNotEmpty()) {
                    val count = minOf(current.size, buffer.size)
                    if (!inbound.compareAndSet(current, current.copyOfRange(count, current.size))) continue
                    current.copyInto(buffer, 0, 0, count)
                    return count
                }
                if (ended.load()) return -1
                readable.await()
            }
        }

        override fun write(bytes: ByteArray, length: Int) {
            if (ended.load()) throw IllegalStateException("shut down")
            val text = bytes.decodeToString(0, length)
            while (true) {
                val current = written.load()
                if (written.compareAndSet(current, current + text)) return
            }
        }

        override fun shutdown() {
            ended.store(true)
            readable.wake()
        }

        override fun close() = Unit
    }

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        val mark = TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (mark.elapsedNow() > 10_000.milliseconds) fail("timed out waiting for $what")
            delay(5)
        }
    }

    @Test
    fun aConnectedIdleTailWakesNeitherTheWriterNorTheStore() {
        store.submit(complete(store.nextId()))
        val connection = FakeConnection()
        val session = BridgeSession(connection, store, BridgeInfo("dev.koga.test", "test"), token = null, scope)
        startBridgeThread("test-session") { session.run() }
        connection.send("{\"cmd\":\"tail\"}")
        waitFor("the backlog line") { connection.lines.size == 1 }
        waitFor("the writer to sleep") { session.writerSleeping && store.consumerSleeping }
        val writerWakes = session.writerWakeups
        val storeWakes = store.consumerWakeups

        runBlocking { delay(1500) }
        assertEquals(writerWakes, session.writerWakeups, "the writer woke while nothing was sent")
        assertEquals(storeWakes, store.consumerWakeups, "the store's consumer woke while nothing was written")
        assertTrue(session.writerSleeping && store.consumerSleeping)

        store.submit(complete(store.nextId()))
        waitFor("the live line") { connection.lines.size == 2 }
        waitFor("the writer to sleep again") { session.writerSleeping }
        assertTrue(session.writerWakeups - writerWakes in 1..2, "one event wakes the writer once: ${session.writerWakeups - writerWakes}")
        connection.shutdown()
    }
}
