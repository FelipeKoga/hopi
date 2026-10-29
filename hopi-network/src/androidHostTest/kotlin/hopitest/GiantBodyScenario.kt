package hopitest

import dev.koga.hopi.android.internal.AndroidCapture
import dev.koga.hopi.android.internal.NetworkTool
import dev.koga.hopi.bench.Measure
import dev.koga.hopi.core.store.HopiStore
import java.io.InputStream
import java.net.URL
import java.util.function.Function
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The app code of AndroidGiantBodyBenchmark, loaded through the plugin's rewriter as the other
 * scenarios are, so its OkHttp is the rewritten one and its `URL.openConnection()` call goes to
 * Hopi's hook. [apply] makes `runs` requests to `url` with the app's own client (`client`: one
 * OkHttp client for the scenario's life, or `HttpURLConnection`), reads each body to its end, and
 * returns per request the wall time and the bytes every live thread allocated (`ThreadMXBean`).
 * Given a `store`, the first call starts capture with it and the `maxBodyBytes` cap; without one,
 * capture never starts in this loader, so the hooks only pass through. Once capture is started,
 * each request's allocation also waits for the store to apply its record ([HopiStore.awaitApplied],
 * after the wall time is taken): the store's thread builds the record, joining the body's blocks,
 * in a pass that would otherwise fall in a later request's window, or another arm's.
 */
class GiantBodyScenario : Function<Map<String, Any?>, Map<String, Any?>> {
    private val client by lazy { OkHttpClient() }
    private val buffer = ByteArray(64 * 1024)

    /** The store capture records to, kept for the calls after the first, which pass none. */
    private var store: HopiStore? = null

    override fun apply(args: Map<String, Any?>): Map<String, Any?> {
        val given = args["store"] as HopiStore?
        if (given != null && !AndroidCapture.started) {
            AndroidCapture.start(
                AndroidCapture.Settings(maxTotalBytes = given.maxTotalBytes, maxBodyBytes = args["maxBodyBytes"] as Long, summaryLog = false),
                NetworkTool.okHttpState(javaClass.classLoader),
                injectedStore = given,
            )
            store = given
        }
        val store = this.store
        val url = args["url"] as String
        val okHttp = args["client"] == "okhttp"
        val runs = args["runs"] as Int
        val nanos = LongArray(runs)
        val allocated = LongArray(runs)
        var received = 0L
        for (i in 0 until runs) {
            val before = Measure.allocatedBytes()
            val start = System.nanoTime()
            received = if (okHttp) okHttpRead(url) else urlRead(url)
            nanos[i] = System.nanoTime() - start
            // Inside the allocation window, outside the timed one: the store's pass for this request.
            if (store != null) check(store.awaitApplied(5_000)) { "the store did not apply request $i within 5 s" }
            allocated[i] = Measure.allocatedBytes() - before
        }
        return mapOf("nanos" to nanos, "allocated" to allocated, "received" to received, "started" to AndroidCapture.started)
    }

    private fun okHttpRead(url: String): Long =
        client.newCall(Request.Builder().url(url).build()).execute().use { response -> drain(response.body!!.byteStream()) }

    private fun urlRead(url: String): Long = URL(url).openConnection().getInputStream().use(::drain)

    private fun drain(input: InputStream): Long {
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return total
            total += n
        }
    }
}
