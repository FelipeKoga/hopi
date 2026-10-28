package dev.koga.hopi.sample

import dev.koga.hopi.ktor.Hopi
import dev.koga.hopi.ktor.HopiLogSink
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.TimeSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The host dev server (`sample/devserver/devserver.py`). The Android emulator reaches it through
 * `adb reverse tcp:8765 tcp:8765`; the iOS simulator shares the host's loopback. Cleartext is
 * allowed to this host only, and only in debug builds (Android network security config, iOS ATS).
 */
internal const val DEV_SERVER = "http://127.0.0.1:8765"

/** A name under the reserved `.invalid` TLD, which never resolves (RFC 2606). */
private const val UNRESOLVABLE = "https://hopi-sample.invalid/"

/** Placeholder credential the sample sends; Hopi must never show it in any outward channel. */
private const val SAMPLE_AUTHORIZATION = "Bearer sample-secret-6f1d2c"

/** The platform's Ktor engine: OkHttp on Android, Darwin on iOS. */
internal expect fun platformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient

/** The sample's one process-wide HTTP client, as a real app keeps it. */
public object SampleHttp {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The Ktor plugin's per-body capture cap for this process, or null for its default (256 KB).
     * A debug setting for the giant-body measurements (`--el maxBodyBytes N` on Android, the
     * `-maxBodyBytes N` launch argument on iOS); it applies only when set before [start]. On
     * Android it reaches only the requests the plugin records itself: OkHttp capture records the
     * rest at the debug build's cap (`-Psample.maxBodyBytes=N`). On iOS the launch argument also
     * reaches `HopiUrlSession.start`.
     */
    public var maxBodyBytes: Long? = null

    /**
     * The Ktor plugin's store byte cap, or null for the default (16 MB), which clamps the body cap
     * to half of it: a 16 MB body cap needs 32 MB here. A debug setting like [maxBodyBytes]
     * (`--el maxTotalBytes N`, `-maxTotalBytes N`); on Android the store takes the debug build's
     * cap instead (`-Psample.maxTotalBytes=N`), which the start presets before any client exists.
     */
    public var maxTotalBytes: Long? = null

    internal val client: HttpClient by lazy {
        platformHttpClient {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
            install(Hopi) {
                logSink = HopiLogSink.Summary
                agentBridge = true
                this@SampleHttp.maxBodyBytes?.let { maxBodyBytes = it }
                this@SampleHttp.maxTotalBytes?.let { maxTotalBytes = it }
            }
        }
    }

    internal val api: ItemsApi by lazy { ItemsApi(client) }

    /** Creates the client now, so the agent bridge is up before the first request. */
    public fun start() {
        client
    }
}

@Serializable
internal class ItemSummary(val id: Long, val title: String, val price: Double)

@Serializable
internal class Item(val id: Long, val title: String, val price: Double, val description: String)

@Serializable
internal class ItemDraft(val title: String, val price: Double, val description: String)

/** A request that did not produce what the screen needs, with a message to show. */
internal class ApiException(message: String) : Exception(message)

/** The dev server's item routes, as the "Items" and "New item" screens use them. */
internal class ItemsApi(private val client: HttpClient) {
    suspend fun items(): List<ItemSummary> = client.get("$DEV_SERVER/items").bodyOrThrow(HttpStatusCode.OK)

    suspend fun item(id: Long): Item = client.get("$DEV_SERVER/items/$id").bodyOrThrow(HttpStatusCode.OK)

    suspend fun create(draft: ItemDraft): Item = client.post("$DEV_SERVER/items") {
        header(HttpHeaders.Authorization, SAMPLE_AUTHORIZATION)
        contentType(ContentType.Application.Json)
        setBody(draft)
    }.bodyOrThrow(HttpStatusCode.Created)

    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(expected: HttpStatusCode): T {
        if (status != expected) throw ApiException("HTTP ${status.value} ${status.description}")
        return body()
    }
}

/** What a failed request shows: the server's status, or the exception's class and message. */
internal fun Throwable.describe(): String = when (this) {
    is ApiException -> message.orEmpty()
    else -> listOfNotNull(this::class.simpleName, message).joinToString(": ")
}

/**
 * One request of each kind the inspector must handle, one after the other: JSON, a POST with a
 * credential, a 404, an unresolvable host, a body over the 256 KB capture cap, a chunked body.
 */
internal suspend fun fireRequests(client: HttpClient, report: (String) -> Unit) {
    suspend fun attempt(label: String, block: suspend () -> String) {
        report(
            try {
                "$label: ${block()}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "$label: ${e::class.simpleName}"
            },
        )
    }
    attempt("GET /json") { client.get("$DEV_SERVER/json").status.value.toString() }
    attempt("POST /echo") {
        client.post("$DEV_SERVER/echo") {
            header(HttpHeaders.Authorization, SAMPLE_AUTHORIZATION)
            header("X-Sample", "hopi")
            setBody(TextContent("""{"name":"Ada","tags":["kmp","ktor"],"n":3}""", ContentType.Application.Json))
        }.status.value.toString()
    }
    attempt("GET /missing") { client.get("$DEV_SERVER/missing").status.value.toString() }
    attempt("GET unresolvable") { client.get(UNRESOLVABLE).status.value.toString() }
    attempt("GET /large") {
        val response = client.get("$DEV_SERVER/large")
        "${response.status.value}, ${response.bodyAsText().length} chars"
    }
    attempt("GET /chunked") {
        val response = client.get("$DEV_SERVER/chunked")
        "${response.status.value}, ${response.bodyAsText().length} chars"
    }
}

/**
 * A request that stays pending for [seconds]: the dev server's `/slow` sends a chunk every few
 * seconds (so no read timeout fires) and ends the body only then.
 */
internal suspend fun slowRequest(client: HttpClient, seconds: Int): String = try {
    val response = client.get("$DEV_SERVER/slow?s=$seconds")
    "GET /slow: ${response.status.value}, ${response.bodyAsText().length} chars"
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    "GET /slow: ${e.describe()}"
}

/**
 * Creates [count] items, [parallel] requests at a time, for load measurements: it fills
 * Hopi's store (500 entries by default) and gives the app's own item list as many rows.
 */
internal suspend fun createItems(api: ItemsApi, count: Int, parallel: Int = 8): String {
    val started = TimeSource.Monotonic.markNow()
    // Every request resumes on the caller's dispatcher (the UI's main thread), so this needs no lock.
    var failed = 0
    val permits = Semaphore(parallel)
    coroutineScope {
        repeat(count) { index ->
            launch {
                permits.withPermit {
                    try {
                        api.create(ItemDraft("Load item ${index + 1}", (index % 100) + 0.99, "Created by the load test."))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                    }
                }
            }
        }
    }
    return "Created ${count - failed} of $count items ($failed failed) in ${started.elapsedNow().inWholeMilliseconds} ms"
}
