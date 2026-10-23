package dev.koga.hopi.core.bridge

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * An in-memory [BridgeConnection]: the test plays the client through [send] and [nextLine].
 * After [blockWritesAfterLines] lines, writes park until [shutdown] or [releaseWrites], like a
 * socket whose peer stopped reading (or is only slow).
 */
internal class MemoryConnection(private var blockWritesAfterLines: Int = Int.MAX_VALUE) : BridgeConnection {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val inbound = ArrayDeque<ByteArray>()
    private var inputEnded = false
    private var isShutdown = false
    private val partial = ByteArrayOutputStream()
    private var linesWritten = 0
    private val received = LinkedBlockingQueue<String>()
    private val shutdownLatch = CountDownLatch(1)
    private val closedLatch = CountDownLatch(1)
    private val parkedLatch = CountDownLatch(1)

    @Volatile var bytesWritten: Long = 0
        private set

    @Volatile var closeCount: Int = 0
        private set

    override fun read(buffer: ByteArray): Int = lock.withLock {
        while (inbound.isEmpty() && !inputEnded && !isShutdown) changed.await()
        if (isShutdown) return -1
        val chunk = inbound.removeFirstOrNull() ?: return -1
        val count = minOf(chunk.size, buffer.size)
        chunk.copyInto(buffer, 0, 0, count)
        if (count < chunk.size) inbound.addFirst(chunk.copyOfRange(count, chunk.size))
        count
    }

    override fun write(bytes: ByteArray, length: Int) = lock.withLock {
        while (!isShutdown && linesWritten >= blockWritesAfterLines) {
            parkedLatch.countDown()
            changed.await()
        }
        if (isShutdown) throw IOException("connection shut down")
        bytesWritten += length
        for (i in 0 until length) {
            if (bytes[i] == '\n'.code.toByte()) {
                received.add(partial.toByteArray().decodeToString())
                partial.reset()
                linesWritten++
            } else {
                partial.write(bytes[i].toInt())
            }
        }
    }

    override fun shutdown() {
        lock.withLock {
            isShutdown = true
            changed.signalAll()
        }
        shutdownLatch.countDown()
    }

    override fun close() {
        closeCount++
        received.add(CLOSED)
        closedLatch.countDown()
    }

    fun send(line: String) = sendBytes((line + "\n").encodeToByteArray())

    fun sendBytes(bytes: ByteArray) = lock.withLock {
        inbound.addLast(bytes)
        changed.signalAll()
    }

    /** Lets parked and later writes through, as a slow peer that resumes reading. */
    fun releaseWrites() = lock.withLock {
        blockWritesAfterLines = Int.MAX_VALUE
        changed.signalAll()
    }

    /** Waits until a write is parked on the block. */
    fun awaitParkedWrite() = assertTrue(parkedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "no write parked")

    /** The client half-closes: the server reads end of input. */
    fun endInput() = lock.withLock {
        inputEnded = true
        changed.signalAll()
    }

    fun nextLine(): String = nextLineOrEnd() ?: fail("connection closed instead of sending a line")

    /** The next line, or null once the session has closed the connection and every line was taken. */
    fun nextLineOrEnd(): String? {
        val line = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: fail("no line within $TIMEOUT_SECONDS s")
        if (line === CLOSED) {
            received.add(CLOSED)
            return null
        }
        return line
    }

    fun awaitShutdown() = assertTrue(shutdownLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "connection was not shut down")

    fun awaitClosed() = assertTrue(closedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "connection was not closed")

    private companion object {
        const val TIMEOUT_SECONDS = 10L

        /** Queued after the last line once the session closes the connection; compared by identity. */
        val CLOSED = String(charArrayOf('<', 'c', 'l', 'o', 's', 'e', 'd', '>'))
    }
}
