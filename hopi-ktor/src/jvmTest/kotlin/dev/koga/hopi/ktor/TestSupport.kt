package dev.koga.hopi.ktor

import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.store.HopiStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
