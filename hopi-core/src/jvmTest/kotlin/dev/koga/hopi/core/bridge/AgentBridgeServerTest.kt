package dev.koga.hopi.core.bridge

import dev.koga.hopi.core.json.parseFlatJsonObject
import dev.koga.hopi.core.store.HopiStore
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AgentBridgeServerTest {
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = HopiStore(scope = storeScope)

    @AfterTest
    fun tearDown() {
        storeScope.cancel()
    }

    /** Answers each `accept` with the next scripted step: a connection, a failure, or null (closed). */
    private class ScriptedListener(vararg steps: () -> BridgeConnection?) : BridgeListener {
        private val steps = LinkedBlockingQueue(steps.toList())
        val accepts = AtomicInteger(0)
        val closed = AtomicBoolean(false)

        override fun accept(): BridgeConnection? {
            accepts.incrementAndGet()
            val step = steps.poll(10, TimeUnit.SECONDS) ?: return null
            return step()
        }

        override fun close() {
            closed.set(true)
        }
    }

    @Test
    fun anAcceptFailureWithNoClientClosesTheListenerAndRemovesWhatAdvertisesIt() {
        val listener = ScriptedListener({ throw IOException("Too many open files") })
        val stopped = AtomicBoolean(false)
        val server = AgentBridgeServer(listener, store, BridgeInfo("app", "jvm"), token = null, onStop = { stopped.set(true) })
        val loop = thread { server.acceptLoop() }
        loop.join(10_000)
        assertFalse(loop.isAlive, "the loop kept going with nothing to wake it")
        // Clients now get connection refused instead of waiting in a backlog nobody accepts.
        assertTrue(listener.closed.get())
        assertTrue(stopped.get())
        assertEquals(1, listener.accepts.get())
    }

    @Test
    fun anAcceptFailureWhileAClientIsConnectedRetriesWhenASessionEnds() {
        val client = MemoryConnection()
        val failed = CountDownLatch(1)
        val clientEnded = AtomicBoolean(false)
        val retriedAfterTheEnd = LinkedBlockingQueue<Boolean>()
        val listener = ScriptedListener(
            { client },
            {
                failed.countDown()
                throw IOException("Too many open files")
            },
            {
                retriedAfterTheEnd.put(clientEnded.get())
                null
            },
        )
        val stopped = AtomicBoolean(false)
        val server = AgentBridgeServer(listener, store, BridgeInfo("app", "jvm"), token = null, onStop = { stopped.set(true) })
        val loop = thread { server.acceptLoop() }
        assertTrue(failed.await(10, TimeUnit.SECONDS))
        assertEquals("hello", parseType(client.request("{\"cmd\":\"hello\"}")), "the connected client keeps its session")

        clientEnded.set(true)
        client.endInput()
        assertEquals(true, retriedAfterTheEnd.poll(10, TimeUnit.SECONDS), "accept was not retried once the session ended")
        loop.join(10_000)
        assertFalse(loop.isAlive)
        assertFalse(stopped.get())
        assertFalse(listener.closed.get())
        server.close()
    }

    private fun MemoryConnection.request(line: String): String {
        send(line)
        return nextLine()
    }

    private fun parseType(line: String): Any? = parseFlatJsonObject(line, Int.MAX_VALUE)?.get("type")
}
