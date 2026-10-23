package dev.koga.hopi.sample

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koga.hopi.ktor.Hopi
import dev.koga.hopi.ktor.HopiLogSink
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The host dev server (`sample/devserver/devserver.py`), reached through
 * `adb reverse tcp:8765 tcp:8765`; the debug network security config allows cleartext to it only.
 */
private const val DEV_SERVER = "http://127.0.0.1:8765"

/** A name under the reserved `.invalid` TLD, which never resolves (RFC 2606). */
private const val UNRESOLVABLE = "https://hopi-sample.invalid/"

/** Intent extra naming the process whose bridge the self-check probes at launch. */
private const val SELF_CHECK_EXTRA = "selfCheck"
private val PROOF_VALUE = Regex("\"proof\":\"[^\"]*\"")

/**
 * Intent extra naming a process whose bridge socket name this install binds first, playing the
 * squatter the CLI must refuse: it answers like a bridge, with that app id but no proof.
 */
private const val SQUAT_EXTRA = "squat"

/** Placeholder credential the sample sends; Hopi must never show it in any outward channel. */
private const val SAMPLE_AUTHORIZATION = "Bearer sample-secret-6f1d2c"

class MainActivity : ComponentActivity() {
    // Created with the activity so the bridge is up before the first request.
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(Hopi) {
            logSink = HopiLogSink.Summary
            agentBridge = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // `am start … --es selfCheck <process name>` runs the self-check against that app's bridge
        // at launch: from a second install (another uid) it must be refused.
        val selfCheckTarget = intent.getStringExtra(SELF_CHECK_EXTRA)
        // `am start … --es squat <process name>` from a second install: the CLI must refuse it.
        intent.getStringExtra(SQUAT_EXTRA)?.let(::squat)
        setContent { SampleScreen(client, selfCheckTarget ?: packageName, runSelfCheckNow = selfCheckTarget != null) }
    }

    override fun onDestroy() {
        client.close()
        super.onDestroy()
    }
}

@Composable
private fun SampleScreen(client: HttpClient, selfCheckTarget: String, runSelfCheckNow: Boolean) {
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateListOf<String>() }
    if (runSelfCheckNow) LaunchedEffect(Unit) { results.add(bridgeSelfCheck(selfCheckTarget)) }
    Column(
        modifier = Modifier.fillMaxSize().background(Color.White).safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SampleButton("Fire requests") {
            results.clear()
            scope.launch { fireRequests(client) { results.add(it) } }
        }
        SampleButton("Bridge self-check") {
            results.clear()
            scope.launch { results.add(bridgeSelfCheck(selfCheckTarget)) }
        }
        results.forEach { BasicText(it, style = TextStyle(fontSize = 14.sp)) }
    }
}

@Composable
private fun SampleButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color.Black)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        BasicText(label, style = TextStyle(color = Color.White, fontSize = 18.sp))
    }
}

/**
 * Connects to the bridge of [processName] as this app's uid, sends `hello` and returns the reply
 * line. Its own bridge must answer; another app's must close the connection without a byte.
 * Logged under `HopiSample` so a device check can read it.
 */
private suspend fun bridgeSelfCheck(processName: String): String = withContext(Dispatchers.IO) {
    val result = try {
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress("hopi.$processName", LocalSocketAddress.Namespace.ABSTRACT))
            socket.outputStream.write("{\"cmd\":\"hello\"}\n".encodeToByteArray())
            socket.outputStream.flush()
            // The proof is a secret like a token: it shows that one was sent, never its value.
            val reply = socket.inputStream.bufferedReader().readLine()?.replace(PROOF_VALUE, "\"proof\":\"<redacted>\"")
            "self-check hopi.$processName: ${reply ?: "closed without a reply"}"
        }
    } catch (e: IOException) {
        "self-check hopi.$processName: ${e::class.simpleName}: ${e.message}"
    }
    Log.i("HopiSample", result)
    result
}

/**
 * Binds `hopi.<processName>` and answers every connection with a forged `hello` naming
 * [processName], one forged transaction and `end`, as a malicious app could. Logs whether the bind
 * worked under `HopiSample`.
 */
private fun squat(processName: String) {
    val name = "hopi.$processName"
    thread(isDaemon = true, name = "sample-squat") {
        val server = try {
            LocalServerSocket(name)
        } catch (e: IOException) {
            Log.i("HopiSample", "squat $name: ${e::class.simpleName}: ${e.message}")
            return@thread
        }
        Log.i("HopiSample", "squat $name: bound")
        while (true) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                Log.i("HopiSample", "squat $name: ${e::class.simpleName}: ${e.message}")
                return@thread
            }
            try {
                client.use {
                    it.inputStream.bufferedReader().readLine()
                    val forged = "{\"type\":\"hello\",\"protocol\":1,\"app\":\"$processName\",\"platform\":\"android\",\"count\":1,\"dropped\":0}\n" +
                        "{\"type\":\"transaction\",\"id\":1,\"method\":\"GET\",\"url\":\"https://forged.invalid/\",\"code\":200," +
                        "\"ms\":1,\"reqBytes\":0,\"resBytes\":0,\"state\":\"complete\"}\n" +
                        "{\"type\":\"end\"}\n"
                    it.outputStream.write(forged.encodeToByteArray())
                    it.outputStream.flush()
                    Log.i("HopiSample", "squat $name: answered a client")
                }
            } catch (e: IOException) {
                Log.i("HopiSample", "squat $name: ${e::class.simpleName}: ${e.message}")
            }
        }
    }
}

/** One request of each kind the inspector must handle, one after the other. */
private suspend fun fireRequests(client: HttpClient, report: (String) -> Unit) {
    suspend fun attempt(label: String, block: suspend () -> String) {
        report(
            try {
                "$label: ${block()}"
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
