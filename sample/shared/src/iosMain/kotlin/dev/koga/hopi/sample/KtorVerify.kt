package dev.koga.hopi.sample

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

/**
 * The Ktor (Darwin engine) half of the end-to-end capture check, run at launch with `-ktorVerify`:
 * one request of each shape, each tagged `X-Hopi-Verify: <name>` so the host can match records to
 * requests, logging one `{"verify":…}` line with the status, the bytes the app read and their
 * SHA-256, so a build with Hopi's runtimes and one without can be compared byte for byte. Ends with
 * `{"verify":"done"}`.
 */
internal fun ktorVerifyAction(): PlatformAction = PlatformAction("Ktor verify") {
    withContext(Dispatchers.Default) { runKtorVerify(SampleHttp.client) }
}

private suspend fun runKtorVerify(client: HttpClient): String {
    var failed = 0
    suspend fun scenario(name: String, request: suspend (HttpRequestBuilder.() -> Unit) -> HttpResponse) {
        val started = TimeSource.Monotonic.markNow()
        val line = try {
            val response = request { header("X-Hopi-Verify", name) }
            val bytes = response.readRawBytes()
            "{\"verify\":\"$name\",\"engine\":\"ktor\",\"status\":${response.status.value},\"bytes\":${bytes.size}," +
                "\"sha256\":\"${sha256(bytes)}\",\"ms\":${started.elapsedNow().inWholeMilliseconds}}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed++
            "{\"verify\":\"$name\",\"engine\":\"ktor\",\"error\":\"${e::class.simpleName}\",\"ms\":${started.elapsedNow().inWholeMilliseconds}}"
        }
        sampleLog(line)
    }
    scenario("kt-get-json") { client.get("$DEV_SERVER/json", it) }
    scenario("kt-post-echo") { tag ->
        client.post("$DEV_SERVER/echo") {
            tag()
            header(HttpHeaders.Authorization, "Bearer sample-secret-6f1d2c")
            setBody(TextContent("""{"name":"Ada","tags":["kmp","ktor"],"n":3}""", ContentType.Application.Json))
        }
    }
    scenario("kt-404") { client.get("$DEV_SERVER/missing", it) }
    scenario("kt-unresolvable") { client.get("https://hopi-sample.invalid/", it) }
    scenario("kt-large") { client.get("$DEV_SERVER/large", it) }
    scenario("kt-chunked") { client.get("$DEV_SERVER/chunked", it) }
    scenario("kt-redirect") { client.get("$DEV_SERVER/redirect?to=/items/1", it) }
    scenario("kt-giant-20") { client.get("$DEV_SERVER/giant?mb=20", it) }
    scenario("kt-post-1mb") { tag ->
        client.post("$DEV_SERVER/sink") {
            tag()
            setBody(ByteArrayContent(ByteArray(1024 * 1024) { (it % 251).toByte() }, ContentType.Application.OctetStream))
        }
    }
    sampleLog("{\"verify\":\"done\",\"engine\":\"ktor\",\"failed\":$failed}")
    return "Ktor verify: done, $failed failed"
}

@OptIn(ExperimentalForeignApi::class)
private fun sha256(bytes: ByteArray): String = memScoped {
    val digest = allocArray<UByteVar>(CC_SHA256_DIGEST_LENGTH)
    if (bytes.isEmpty()) {
        CC_SHA256(null, 0u, digest)
    } else {
        bytes.usePinned { CC_SHA256(it.addressOf(0), bytes.size.convert(), digest) }
    }
    (0 until CC_SHA256_DIGEST_LENGTH).joinToString("") { digest[it].toString(16).padStart(2, '0') }
}
