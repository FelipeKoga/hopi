package dev.koga.hopi.sample

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One line of the device measurement log: `HopiSample` on Android, stdout on iOS. */
internal expect fun sampleLog(line: String)

/**
 * Counters of the platform runtime, read before and after each run: on Android ART's GC counters
 * (`Debug.getRuntimeStat`) and the Java and native heaps; empty where the platform has none (iOS
 * is measured from the host with `footprint`).
 */
internal expect fun runtimeStats(): Map<String, Long>

/** A full garbage collection, so each run starts from the same heap. */
internal expect fun collectGarbage()

/**
 * What a giant-JSON run fetches: `mb` sizes (MiB), the dev server's `shape`, `chunked` (no
 * Content-Length), `runs` per size, and `hold`, seconds the frame monitor keeps running after the
 * last run (so a host tool, like `hopi get`, can be measured against the app's frames).
 * `post` sends a body of each size to `/sink` instead of fetching one. `delay` waits that many
 * seconds before the first run, so the host can read the app's memory first. `window` is the length
 * in seconds of each `hold` line (5 by default; 1 lines the gaps up with a host's taps).
 *
 * Text form (an Android intent extra, an iOS launch argument):
 * `mb=1,5,20;shape=minified;chunked=0;runs=5;hold=0;window=5;post=0;delay=0`.
 */
internal class GiantSpec(
    val sizesMb: List<Int>,
    val shape: String,
    val chunked: Boolean,
    val runs: Int,
    val holdSeconds: Int,
    val post: Boolean,
    val delaySeconds: Int,
    val windowSeconds: Int = 5,
) {
    companion object {
        val SHAPES = listOf("minified", "pretty", "string", "nested")

        fun parse(text: String): GiantSpec {
            val fields = text.split(';').mapNotNull { part ->
                val key = part.substringBefore('=', "").trim()
                if (key.isEmpty()) null else key to part.substringAfter('=').trim()
            }.toMap()
            val sizes = fields["mb"]?.split(',')?.mapNotNull { it.trim().toIntOrNull()?.takeIf { mb -> mb in 1..200 } }
                ?.takeIf { it.isNotEmpty() } ?: listOf(1)
            val shape = fields["shape"]?.takeIf { it in SHAPES } ?: "minified"
            return GiantSpec(
                sizesMb = sizes,
                shape = shape,
                chunked = fields["chunked"] == "1",
                runs = fields["runs"]?.toIntOrNull()?.coerceIn(1, 50) ?: 1,
                holdSeconds = fields["hold"]?.toIntOrNull()?.coerceIn(0, 600) ?: 0,
                post = fields["post"] == "1",
                delaySeconds = fields["delay"]?.toIntOrNull()?.coerceIn(0, 120) ?: 0,
                windowSeconds = fields["window"]?.toIntOrNull()?.coerceIn(1, 60) ?: 5,
            )
        }
    }
}

/**
 * Main-thread frame gaps while a measurement runs. [active] keeps [FrameTicker] drawing a frame
 * on every vsync, as an animating app does, so a stalled main thread shows as a gap between two
 * frame times. Touched on the main thread only.
 */
internal object FrameGaps {
    var active by mutableStateOf(false)
    private val gaps = ArrayList<Long>(4096)
    private var last = 0L

    fun onFrame(frameNanos: Long) {
        if (last != 0L) gaps += frameNanos - last
        last = frameNanos
    }

    fun begin() {
        gaps.clear()
        last = 0L
        active = true
    }

    /** The gaps since [begin], as JSON fields; [keepActive] leaves the ticker running. */
    fun end(keepActive: Boolean = false): String {
        active = keepActive
        val sorted = gaps.sorted()
        gaps.clear()
        last = 0L
        if (sorted.isEmpty()) return "\"frames\":0"
        fun ms(nanos: Long) = (nanos / 10_000) / 100.0
        val p95 = sorted[((sorted.size - 1) * 95) / 100]
        return "\"frames\":${sorted.size},\"gapP50Ms\":${ms(sorted[sorted.size / 2])},\"gapP95Ms\":${ms(p95)}," +
            "\"gapMaxMs\":${ms(sorted.last())},\"gapsOver100Ms\":${sorted.count { it > 100_000_000L }}," +
            "\"gapsOver33Ms\":${sorted.count { it > 33_000_000L }}"
    }
}

/** Draws on every frame while [FrameGaps.active], recording each frame's time. */
@Composable
internal fun FrameTicker(modifier: Modifier = Modifier) {
    if (!FrameGaps.active) return
    var frames by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { time ->
                FrameGaps.onFrame(time)
                frames++
            }
        }
    }
    BasicText("frame $frames", modifier.semantics { testTag = "frame-ticker" })
}

/**
 * Fetches giant JSON bodies from the dev server and parses each into a [JsonElement] the way an app
 * does (`body<JsonElement>()` through ContentNegotiation), on [Dispatchers.Default], logging one
 * JSON line per run: time to the response (headers and, for a saved body, the whole body), time to
 * parse, GC and heap counters, and the main thread's frame gaps meanwhile. Runs on the main thread.
 */
internal suspend fun runGiant(client: HttpClient, spec: GiantSpec): String {
    val build = if (SampleBuild.realHopi) "real" else "noop"
    val header = "\"giant\":1,\"build\":\"$build\",\"capBytes\":${SampleHttp.maxBodyBytes ?: -1},\"storeBytes\":${SampleHttp.maxTotalBytes ?: -1}," +
        "\"shape\":\"${spec.shape}\",\"chunked\":${spec.chunked},\"post\":${spec.post}"
    val totals = ArrayList<String>()
    if (spec.delaySeconds > 0) {
        sampleLog("{$header,\"waiting\":${spec.delaySeconds}}")
        delay(spec.delaySeconds * 1_000L)
    }
    sampleLog("{$header,\"start\":true}")
    for (mb in spec.sizesMb) {
        val millis = ArrayList<Long>(spec.runs)
        repeat(spec.runs) { run ->
            withContext(Dispatchers.Default) { collectGarbage() }
            val before = runtimeStats()
            FrameGaps.begin()
            // Lets the ticker draw its first frame before the request starts.
            delay(100)
            val line = try {
                val (fetchMs, parseMs, elements) = withContext(Dispatchers.Default) {
                    val started = TimeSource.Monotonic.markNow()
                    if (spec.post) {
                        val payload = giantPayload(mb)
                        val sendStart = TimeSource.Monotonic.markNow()
                        val response = client.post("$DEV_SERVER/sink") {
                            setBody(ByteArrayContent(payload, ContentType.Application.Json))
                        }
                        val sent = sendStart.elapsedNow().inWholeMilliseconds
                        val parseStart = TimeSource.Monotonic.markNow()
                        val element: JsonElement = response.body()
                        Triple(sent, parseStart.elapsedNow().inWholeMilliseconds, countElements(element))
                    } else {
                        val response = client.get("$DEV_SERVER/giant?mb=$mb&shape=${spec.shape}&chunked=${if (spec.chunked) 1 else 0}")
                        val fetched = started.elapsedNow().inWholeMilliseconds
                        val parseStart = TimeSource.Monotonic.markNow()
                        val element: JsonElement = response.body()
                        Triple(fetched, parseStart.elapsedNow().inWholeMilliseconds, countElements(element))
                    }
                }
                val after = runtimeStats()
                millis += fetchMs + parseMs
                val stats = after.entries.joinToString("") { (key, value) ->
                    ",\"$key\":${value - (before[key] ?: 0)},\"${key}After\":$value"
                }
                "{$header,\"mb\":$mb,\"run\":${run + 1},\"fetchMs\":$fetchMs,\"parseMs\":$parseMs," +
                    "\"totalMs\":${fetchMs + parseMs},\"elements\":$elements$stats,${FrameGaps.end()}}"
            } catch (e: CancellationException) {
                FrameGaps.end()
                throw e
            } catch (e: Throwable) {
                // An OutOfMemoryError is the result being measured: it is logged, not rethrown.
                "{$header,\"mb\":$mb,\"run\":${run + 1},\"error\":\"${(e::class.simpleName + ": " + e.message).replace("\"", "'")}\",${FrameGaps.end()}}"
            }
            sampleLog(line)
        }
        val sorted = millis.sorted()
        val median = if (sorted.isEmpty()) -1 else sorted[sorted.size / 2]
        val summary = "{$header,\"mb\":$mb,\"runs\":${spec.runs},\"ok\":${sorted.size},\"medianTotalMs\":$median," +
            "\"minTotalMs\":${sorted.firstOrNull() ?: -1},\"maxTotalMs\":${sorted.lastOrNull() ?: -1}}"
        sampleLog(summary)
        totals += "$mb MB: median $median ms (${sorted.size}/${spec.runs} ok)"
    }
    if (spec.holdSeconds > 0) {
        // The ticker keeps drawing; one line per window says how the main thread fared.
        FrameGaps.begin()
        var left = spec.holdSeconds
        var window = 0
        while (left > 0) {
            val step = minOf(left, spec.windowSeconds)
            delay(step * 1_000L)
            left -= step
            sampleLog("{$header,\"hold\":${++window},\"seconds\":$step,${FrameGaps.end(keepActive = left > 0)}}")
            if (left > 0) FrameGaps.begin()
        }
    }
    sampleLog("{$header,\"done\":true}")
    return "Giant ${spec.shape}${if (spec.chunked) " chunked" else ""}${if (spec.post) " POST" else ""}: " + totals.joinToString("; ")
}

/** A minified JSON array of about [mb] MiB, built in the app as a real upload would be. */
private fun giantPayload(mb: Int): ByteArray {
    val target = mb * 1024 * 1024
    val out = StringBuilder(target + 256)
    out.append('[')
    var i = 0
    while (out.length < target - 200) {
        if (i > 0) out.append(',')
        out.append("{\"id\":").append(10_000_000 + i).append(",\"note\":\"uploaded record ").append(i)
            .append(" lorem ipsum dolor sit amet, consectetur adipiscing elit\"}")
        i++
    }
    out.append(']')
    return out.toString().encodeToByteArray()
}

/** How many values the parsed tree holds, walked iteratively (it may be deep). */
private fun countElements(root: JsonElement): Long {
    var count = 0L
    val stack = ArrayList<JsonElement>()
    stack += root
    while (stack.isNotEmpty()) {
        val element = stack.removeAt(stack.size - 1)
        count++
        when (element) {
            is JsonObject -> stack.addAll(element.values)
            is JsonArray -> stack.addAll(element)
            else -> Unit
        }
    }
    return count
}

/** A diagnostics action that runs [runGiant] with the [GiantSpec] text [spec]; for platform hosts. */
public fun giantJsonAction(spec: String): PlatformAction {
    val parsed = GiantSpec.parse(spec)
    return PlatformAction("Giant JSON") { runGiant(SampleHttp.client, parsed) }
}
