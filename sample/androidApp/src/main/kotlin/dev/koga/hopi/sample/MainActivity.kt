package dev.koga.hopi.sample

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Intent extra naming the process whose bridge the self-check probes at launch. */
private const val SELF_CHECK_EXTRA = "selfCheck"
private val PROOF_VALUE = Regex("\"proof\":\"[^\"]*\"")

/**
 * Intent extra naming a process whose bridge socket name this install binds first, playing the
 * squatter the CLI must refuse: it answers like a bridge, with that app id but no proof.
 */
private const val SQUAT_EXTRA = "squat"

/**
 * Hosts the shared sample UI (`sample/shared`, which also owns the HTTP client and Hopi) and
 * adds what only Android has: the bridge self-check and the squatter that the CLI's security
 * checks drive through intent extras.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The shared UI draws behind the system bars (safeDrawingPadding); this keeps their icons
        // readable on its light and dark backgrounds.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Created with the first activity so the bridge is up before the first request.
        SampleHttp.start()
        // `am start … --es selfCheck <process name>` runs the self-check against that app's bridge
        // at launch: from a second install (another uid) it must be refused.
        val selfCheckTarget = intent.getStringExtra(SELF_CHECK_EXTRA) ?: packageName
        val selfCheck = PlatformAction("Bridge self-check") { bridgeSelfCheck(selfCheckTarget) }
        val runSelfCheckNow = intent.hasExtra(SELF_CHECK_EXTRA)
        // `am start … --es squat <process name>` from a second install: the CLI must refuse it.
        intent.getStringExtra(SQUAT_EXTRA)?.let(::squat)
        setContent {
            SampleApp(platformActions = listOf(selfCheck), launchAction = selfCheck.takeIf { runSelfCheckNow })
        }
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
