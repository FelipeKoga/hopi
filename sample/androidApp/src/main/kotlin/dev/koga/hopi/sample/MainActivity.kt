package dev.koga.hopi.sample

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.koga.fakesdk.FakeSdk
import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.api.HopiRuntimes
import dev.koga.hopi.rules.HopiRules
import dev.koga.hopi.rules.HopiSource
import java.io.IOException
import java.net.URL
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
 * Giant-body measurements: `--es giant <spec>` runs [giantJsonAction] at launch, e.g.
 * `--es giant 'mb=1,5,20;shape=minified;runs=5'`. Capture's caps are build settings here: install
 * the debug build with `-Psample.maxBodyBytes=N` (and `-Psample.maxTotalBytes=N`), which the
 * `hopi { }` block reads. `--el maxBodyBytes N` sets only the Ktor plugin's own cap
 * ([SampleHttp.maxBodyBytes], before the client exists, so force-stop the app first), for the
 * requests the plugin records itself, those that name their own `Accept-Encoding`; OkHttp capture
 * records the rest at the build's cap.
 */
private const val MAX_BODY_BYTES_EXTRA = "maxBodyBytes"

/**
 * `--el maxTotalBytes N`: the Ktor plugin's store cap ([SampleHttp.maxTotalBytes]), which the store
 * no longer takes: the start presets the build's caps first. Kept for the run's header line.
 */
private const val MAX_TOTAL_BYTES_EXTRA = "maxTotalBytes"
private const val GIANT_EXTRA = "giant"

/** `--es capture all` runs [captureAction] at launch: the fake SDK's requests and `URL.readText()`. */
private const val CAPTURE_EXTRA = "capture"

/**
 * `--es captureBench 'kinds=okhttp,urlconnection;path=/json;n=300;warmup=50;gapUs=2000'` runs
 * [captureBench] at launch: the capture A/B workload (benchmark/android-capture/), one JSON line per
 * kind. `gapUs` (default 0, back to back) idles the thread that long before each request.
 */
private const val CAPTURE_BENCH_EXTRA = "captureBench"

/**
 * `--es verify all` runs [verifyAction] at launch: the end-to-end capture check
 * (benchmark/android-capture/e2e), Ktor then the fake SDK's OkHttp and HttpURLConnection scenarios.
 * `--es verifyHttps <url>` names the public HTTPS URL its HTTPS scenarios fetch.
 */
private const val VERIFY_EXTRA = "verify"
private const val VERIFY_HTTPS_EXTRA = "verifyHttps"
private const val DEFAULT_VERIFY_HTTPS = "https://example.com/"

/**
 * `--es rulesVerify all` runs [rulesVerifyAction] at launch: the rules self-test on every Android
 * source, then the pinned client. `--es rulesTargets all` runs [rulesTargetsAction] instead: the
 * requests the host's own rules (`hopi mock add …`) are checked against. Both take `verifyHttps`.
 */
private const val RULES_VERIFY_EXTRA = "rulesVerify"
private const val RULES_TARGETS_EXTRA = "rulesTargets"

/**
 * `--es perfVerify all` (or a comma list of steps: `jank,hang,dialog,cpu,alloc,memory`) opens the
 * perf lab at launch and runs its injections once ([PerfLabRun]), one `{"perfLab":…}` line each
 * under `HopiSample`; benchmark/perf/verify.py checks Hopi's record against them. No dev server needed.
 */
private const val PERF_VERIFY_EXTRA = "perfVerify"

/**
 * `--es perfBench 'monitors=hangs;n=10000;runs=3'` runs [runPerfBench] at launch: the perf
 * monitors' overhead A/B workload (benchmark/perf/bench.py), or with `mode=idle` the idle arm
 * (benchmark/perf/idle.py).
 */
private const val PERF_BENCH_EXTRA = "perfBench"

/** The host dev server, through `adb reverse tcp:8765 tcp:8765`. */
private const val DEV_SERVER_URL = "http://127.0.0.1:8765"

/**
 * Hosts the shared sample UI (`sample/shared`, which also owns the HTTP client and Hopi) and
 * adds what only Android has: the bridge self-check and the squatter that the CLI's security
 * checks drive through intent extras.
 */
class MainActivity : ComponentActivity() {
    @OptIn(HopiInternalApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // The shared UI draws behind the system bars (safeDrawingPadding); this keeps their icons
        // readable on its light and dark backgrounds.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The plugin adds Hopi's runtimes to debuggable variants only, whatever -Phopi.enabled says:
        // the network status says whether capture runs (the provider started it before the
        // application was created), for the Diagnostics caption and the evidence lines.
        SampleBuild.androidCaptureStarted = HopiRuntimes.networkStarted
        if (intent.hasExtra(MAX_BODY_BYTES_EXTRA)) {
            SampleHttp.maxBodyBytes = intent.getLongExtra(MAX_BODY_BYTES_EXTRA, 0L).takeIf { it > 0 }
        }
        if (intent.hasExtra(MAX_TOTAL_BYTES_EXTRA)) {
            SampleHttp.maxTotalBytes = intent.getLongExtra(MAX_TOTAL_BYTES_EXTRA, 0L).takeIf { it > 0 }
        }
        // Created with the first activity, so the first request does not pay for creating the client.
        SampleHttp.start()
        // `am start … --es selfCheck <process name>` runs the self-check against that app's bridge
        // at launch: from a second install (another uid) it must be refused.
        val selfCheckTarget = intent.getStringExtra(SELF_CHECK_EXTRA) ?: packageName
        val selfCheck = PlatformAction("Bridge self-check") { bridgeSelfCheck(selfCheckTarget) }
        val runSelfCheckNow = intent.hasExtra(SELF_CHECK_EXTRA)
        // `am start … --es squat <process name>` from a second install: the CLI must refuse it.
        intent.getStringExtra(SQUAT_EXTRA)?.let(::squat)
        val capture = captureAction()
        val https = intent.getStringExtra(VERIFY_HTTPS_EXTRA) ?: DEFAULT_VERIFY_HTTPS
        val rulesVerify = rulesVerifyAction(https)
        val rulesTargets = rulesTargetsAction()
        val launchAction = intent.getStringExtra(GIANT_EXTRA)?.let(::giantJsonAction)
            ?: intent.getStringExtra(CAPTURE_BENCH_EXTRA)?.let(::captureBench)
            ?: intent.getStringExtra(PERF_BENCH_EXTRA)?.let { spec -> PlatformAction("Perf bench") { runPerfBench(spec) } }
            ?: verifyAction(https).takeIf { intent.hasExtra(VERIFY_EXTRA) }
            ?: rulesVerify.takeIf { intent.hasExtra(RULES_VERIFY_EXTRA) }
            ?: rulesTargets.takeIf { intent.hasExtra(RULES_TARGETS_EXTRA) }
            ?: capture.takeIf { intent.hasExtra(CAPTURE_EXTRA) }
            ?: selfCheck.takeIf { runSelfCheckNow }
        setContent {
            SampleApp(
                platformActions = listOf(selfCheck, capture, rulesVerify, rulesTargets),
                launchAction = launchAction,
                perfVerify = intent.getStringExtra(PERF_VERIFY_EXTRA),
            )
        }
    }
}

/**
 * Requests Hopi records with no Ktor and no setup: the fake third-party SDK's OkHttp client and
 * HttpURLConnection calls (`sample/fakesdk`, which knows nothing of Hopi) and Kotlin's
 * `URL.readText()`. The debug build records each of them once, through the io.github.felipekoga.hopi plugin's
 * instrumentation; the release build sends the same requests and records nothing. Logged under
 * `HopiSample`, one line per request, then the capture status.
 */
@OptIn(HopiInternalApi::class)
private fun captureAction() = PlatformAction("Third-party SDK and URL.readText()") {
    withContext(Dispatchers.IO) {
        val lines = try {
            FakeSdk.run(DEV_SERVER_URL) + "kotlin URL.readText ${URL("$DEV_SERVER_URL/json?sdk=readText").readText().length} chars"
        } catch (e: Exception) {
            listOf("capture: ${e::class.simpleName}: ${e.message}")
        }
        val status = "capture: started=${HopiRuntimes.networkStarted} okHttpInstrumented=${HopiRuntimes.okHttpInstrumented}"
        (lines + status).forEach { Log.i("HopiSample", it) }
        (lines + status).joinToString("\n")
    }
}

/**
 * One request of each shape through Ktor, the fake SDK's OkHttp client and HttpURLConnection, one
 * `{"verify":…}` line each under `HopiSample` (status, bytes read, SHA-256), then
 * `{"verify":"done",…}` with the capture status. The host matches the lines to Hopi's records and
 * compares them with those of a build with no runtime (the `nohopi` arm).
 */
@OptIn(HopiInternalApi::class)
private fun verifyAction(https: String) = PlatformAction("Capture verify") {
    withContext(Dispatchers.IO) {
        val ktorFailed = runKtorVerify(https)
        val lines = FakeSdk.verify(DEV_SERVER_URL, https)
        lines.forEach { Log.i("HopiSample", it) }
        val done = "{\"verify\":\"done\",\"ktorFailed\":$ktorFailed,\"started\":${HopiRuntimes.networkStarted},\"okHttpInstrumented\":${HopiRuntimes.okHttpInstrumented}}"
        Log.i("HopiSample", done)
        done
    }
}

/**
 * Every Android source the rules scenarios drive: the Ktor plugin (a request that names its own
 * `Accept-Encoding`, which the plugin keeps), Ktor's OkHttp engine (ruled by the instrumented
 * interceptor, not the plugin), and the fake SDK's own OkHttp client and HttpURLConnection, which
 * know nothing of Hopi.
 */
private val RULES_TARGETS = listOf(
    ktorRulesTarget("ktor", HopiSource.Ktor, passThrough = true),
    ktorRulesTarget("ktor-okhttp", HopiSource.OkHttp),
    fakeSdkTarget("sdk-okhttp", "okhttp", HopiSource.OkHttp),
    fakeSdkTarget("sdk-urlconnection", "urlconnection", HopiSource.UrlConnection),
)

private fun fakeSdkTarget(name: String, engine: String, source: HopiSource) = RulesTarget(name, source) { call ->
    withContext(Dispatchers.IO) {
        val headers = call.headers.flatMap { listOf(it.first, it.second) }.toTypedArray()
        val result = FakeSdk.call(engine, call.method, call.url, headers, call.body, RULES_HEADER)
        RulesOutcome(result.status, result.body, result.header, result.error)
    }
}

/**
 * The rules self-test ([runRulesVerify]) on [RULES_TARGETS], then the pinned client: the fake
 * SDK's client that pins [https]'s host to a key no server has fails the pin without a rule and
 * still fails it under a request edit (a rule never weakens the app's TLS), and a mock answers it
 * without any connection. Needs the emulator's network for the pinned part.
 */
private fun rulesVerifyAction(https: String) = PlatformAction("Rules self-test") {
    val verify = runRulesVerify(RULES_TARGETS)
    val host = java.net.URL(https).host
    val match = "GET https://$host/**"
    val pinned = withContext(Dispatchers.IO) {
        val plain = FakeSdk.pinned(https)
        val editId = HopiRules.edit(match) {
            request { setHeader("X-Hopi-Rule", "edited") }
            sources(HopiSource.OkHttp)
        }
        val edited = try {
            FakeSdk.pinned(https)
        } finally {
            HopiRules.remove(editId)
        }
        val mockId = HopiRules.mock(match) {
            status = 200
            body("pinned host, mocked")
            sources(HopiSource.OkHttp)
        }
        val mocked = try {
            FakeSdk.pinned(https)
        } finally {
            HopiRules.remove(mockId)
        }
        val pinFailure = "SSLPeerUnverifiedException"
        val ok = plain.error.orEmpty().startsWith(pinFailure) && edited.error.orEmpty().startsWith(pinFailure) && mocked.status == 200
        fun seen(result: FakeSdk.Result) = (result.error ?: result.status.toString()).replace('"', '\'')
        "{\"rules\":\"pinned\",\"ok\":$ok,\"plain\":\"${seen(plain)}\",\"edited\":\"${seen(edited)}\",\"mocked\":\"${seen(mocked)}\"}"
            .also { Log.i("HopiSample", it) }
    }
    "$verify\n$pinned"
}

/** [runRulesTargets] on [RULES_TARGETS]: the requests the host's own rules act on. */
private fun rulesTargetsAction() = PlatformAction("Rules targets (for host rules)") {
    runRulesTargets(RULES_TARGETS)
}

/**
 * Sequential requests through the fake SDK's OkHttp client and HttpURLConnection, on one worker
 * thread: per kind, the median and p90 wall time per request (call to body end) and the thread's
 * CPU per request (`Debug.threadCpuTimeNanos`), after `warmup` requests that are not counted.
 * Logged under `HopiSample` as `{"captureBench":…}`.
 */
@OptIn(HopiInternalApi::class)
private fun captureBench(spec: String) = PlatformAction("Capture benchmark") {
    val fields = spec.split(';').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    val kinds = fields["kinds"]?.split(',') ?: listOf("okhttp", "urlconnection")
    val path = fields["path"] ?: "/json"
    val count = fields["n"]?.toInt() ?: 300
    val warmup = fields["warmup"]?.toInt() ?: 50
    val gapUs = fields["gapUs"]?.toLong() ?: 0L
    withContext(Dispatchers.IO) {
        kinds.joinToString("\n") { kind ->
            FakeSdk.bench(DEV_SERVER_URL, kind, path, warmup, gapUs)
            val cpuBefore = android.os.Debug.threadCpuTimeNanos()
            val nanos = FakeSdk.bench(DEV_SERVER_URL, kind, path, count, gapUs).sorted()
            val cpuPerRequest = (android.os.Debug.threadCpuTimeNanos() - cpuBefore) / count
            val line = "{\"captureBench\":\"$kind\",\"path\":\"$path\",\"n\":$count,\"gapUs\":$gapUs,\"capture\":${HopiRuntimes.networkStarted}," +
                "\"medianUs\":${nanos[count / 2] / 1000},\"p90Us\":${nanos[count * 9 / 10] / 1000},\"cpuUs\":${cpuPerRequest / 1000}}"
            Log.i("HopiSample", line)
            line
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
