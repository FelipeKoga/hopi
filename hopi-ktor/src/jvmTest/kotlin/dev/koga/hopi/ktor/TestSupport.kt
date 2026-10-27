package dev.koga.hopi.ktor

import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionEvent
import dev.koga.hopi.core.store.HopiStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

internal class TestStore(channelCapacity: Int = HopiStore.DEFAULT_CHANNEL_CAPACITY) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = HopiStore(maxTotalBytes = 1L shl 30, scope = scope, channelCapacity = channelCapacity)

    /** Waits until at least [count] transactions are terminal and returns all of them. */
    suspend fun awaitTerminal(count: Int = 1): List<HttpTransaction> = withTimeout(10_000) {
        store.transactions.first { list -> list.count { it.state.isTerminal } >= count }
    }

    override fun close() {
        store.close()
        scope.cancel()
    }
}

/**
 * A store of [maxTotalBytes] whose consumer runs on one thread of its own, which [hold] blocks until
 * [release], as a starved `hopi-store` thread would be; [ends] takes the records as their ends are
 * published, also those the byte cap then evicts.
 */
internal class HeldStore(maxTotalBytes: Long) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val gate = CountDownLatch(1)
    private val published = LinkedBlockingQueue<HttpTransaction>()
    val store = HopiStore(maxTotalBytes = maxTotalBytes, scope = scope)

    init {
        store.addEventListener { if (it is TransactionEvent.Completed) published += it.transaction }
    }

    /** From now on the consumer runs nothing until [release]. */
    fun hold() {
        executor.execute { gate.await() }
    }

    fun release() {
        gate.countDown()
    }

    /** The next [count] records whose end was published, in order. */
    fun ends(count: Int): List<HttpTransaction> = List(count) { assertNotNull(published.poll(10, TimeUnit.SECONDS), "end ${it + 1} published") }

    override fun close() {
        gate.countDown()
        store.close()
        scope.cancel()
        executor.shutdown()
    }
}

internal fun mockClient(
    store: HopiStore,
    configure: HopiConfig.() -> Unit = {},
    handler: MockRequestHandler,
): HttpClient = HttpClient(MockEngine(handler)) {
    install(Hopi) {
        this.store = store
        logSink = HopiLogSink.Off
        configure()
    }
}

internal fun asciiBytes(size: Int, seed: Int = 7): ByteArray {
    val random = kotlin.random.Random(seed)
    return ByteArray(size) { ('a'.code + random.nextInt(26)).toByte() }
}
