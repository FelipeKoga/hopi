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
import java.security.MessageDigest
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException

/**
 * The Ktor (OkHttp engine) half of the Android end-to-end capture check
 * (benchmark/android-capture/e2e), the Android twin of the iOS `ktorVerifyAction`: one request of
 * each shape through the sample's client, each tagged `X-Hopi-Verify: <name>`, logging one
 * `{"verify":…}` line under `HopiSample` with the status, the bytes the app read and their SHA-256,
 * so a capture and a noop build can be compared byte for byte. [https] is a public HTTPS URL.
 * Returns the number of scenarios that failed with an exception.
 */
public suspend fun runKtorVerify(https: String): Int {
    val client: HttpClient = SampleHttp.client
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
    scenario("kt-refused") { client.get("http://127.0.0.1:9/", it) }
    scenario("kt-unresolvable") { client.get("https://hopi-sample.invalid/", it) }
    scenario("kt-large") { client.get("$DEV_SERVER/large", it) }
    scenario("kt-chunked") { client.get("$DEV_SERVER/chunked", it) }
    scenario("kt-redirect") { client.get("$DEV_SERVER/redirect?to=/items/1", it) }
    scenario("kt-giant-20") { client.get("$DEV_SERVER/giant?mb=20", it) }
    scenario("kt-giant-20-chunked") { client.get("$DEV_SERVER/giant?mb=20&chunked=1", it) }
    scenario("kt-gzip") { client.get("$DEV_SERVER/gzip?kb=512", it) }
    scenario("kt-drop") { client.get("$DEV_SERVER/drop?kb=64", it) }
    scenario("kt-post-1mb") { tag ->
        client.post("$DEV_SERVER/sink") {
            tag()
            setBody(ByteArrayContent(ByteArray(1024 * 1024) { (it % 251).toByte() }, ContentType.Application.OctetStream))
        }
    }
    scenario("kt-https") { client.get(https, it) }
    return failed
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
