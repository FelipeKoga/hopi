package dev.koga.hopi.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.koga.hopi.perf.HopiMonitor
import dev.koga.hopi.perf.HopiPerf
import kotlin.coroutines.resume
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Blocks the calling thread for [ms] (Android `Thread.sleep`, iOS `usleep`). */
internal expect fun labSleep(ms: Long)

/** Wall clock, epoch milliseconds: the clock of Hopi's perf events, so the host can line them up. */
internal expect fun labWallMs(): Long

/** Runs [block] as its own unit of work on the main thread: a Looper message, a main-queue block. */
internal expect fun labPostMain(block: () -> Unit)

/** Starts one thread per name that spins for [ms] and ends; returns at once. */
internal expect fun labBurnThreads(names: List<String>, ms: Long)

/** Allocates [bytes] outside the managed heap and writes every page; [labFreeNative] gives it back. */
internal expect fun labAllocNative(bytes: Int): Any

internal expect fun labFreeNative(block: Any)

/**
 * Raises the OS's own memory warning inside the app where that can be done from the app (iOS posts
 * `UIApplicationDidReceiveMemoryWarningNotification`, the notification the memory monitor
 * observes); false where only the host can (Android: `adb shell am send-trim-memory`).
 */
internal expect fun labMemoryWarning(): Boolean

/**
 * The runtime's latest garbage collection as a JSON object: its number and its stop-the-world
 * pause on the wall clock (iOS, Kotlin/Native's `GC.lastGCInfo`); null where there is none to read.
 * A hang line carries it when a collection ran during the hang, so a main thread held at a
 * safepoint after the injected block is told apart from the block itself.
 */
internal expect fun labLastGc(): Pair<Long, String>?

/**
 * The injections of the perf lab (docs/design/perf-monitors.md, "Device verification plan"), each
 * a plain function so that a captured main-thread stack names it: `PerfLab.hang` on Android,
 * `PerfLab#hang` on iOS. They block or spin the calling thread on purpose; call them on the main
 * thread only from [PerfLabRun] or a lab button.
 */
public object PerfLab {
    /** Written by the spin loops so the compiler keeps them. */
    public var sink: Long = 0
        private set

    /** Sleeps the main thread for [ms]: a hang with an idle CPU. */
    public fun hang(ms: Long) {
        labSleep(ms)
    }

    /** Spins the main thread for [ms]: a hang with a busy CPU. */
    public fun burn(ms: Long) {
        sink += spin(ms)
    }

    /** Spins for 600 ms inside a click handler (input dispatch on Android, a touch on iOS). */
    public fun clickBurn() {
        sink += spin(CLICK_BURN_MS)
    }

    /** Blocks one frame callback for [ms]: a slow or janky frame. */
    public fun jankFrame(ms: Long) {
        labSleep(ms)
    }

    /** Spins a worker thread for [ms]: the CPU step's burner threads (the sink's races do not matter). */
    internal fun burnOffMain(ms: Long) {
        sink = sink xor spin(ms)
    }

    internal const val CLICK_BURN_MS: Long = 600

    private fun spin(ms: Long): Long {
        val mark = TimeSource.Monotonic.markNow()
        var x = 1L
        while (mark.elapsedNow().inWholeMilliseconds < ms) {
            repeat(1_000) { x = x * 6364136223846793005L + 1442695040888963407L }
        }
        return x
    }
}

/**
 * The whole lab in sequence, one `{"perfLab":…}` line per injection under `HopiSample` with its
 * wall-clock window, ending with `{"perfLab":"done"}`: Android `--es perfVerify all`, iOS
 * `-verifyPerf`. It starts every monitor first (code owner), and leaves them running so the host
 * can read the result (`hopi perf snapshot --json`, `hopi hangs --json`) and compare it with the
 * injected durations and its own references (benchmark/perf/). [only] limits the run to the named
 * steps (`jank`, `hang`, `dialog`, `cpu`, `alloc`, `memory`); the pseudo-step `heartbeat` runs the
 * Android hang monitor on its heartbeat instead of the Looper printer.
 */
internal class PerfLabRun(
    private val tick: MutableIntState,
    private val dialog: MutableState<Boolean>,
    private val only: Set<String>,
    private val report: (String) -> Unit,
) {
    private fun line(json: String) {
        sampleLog(json)
        report(json)
    }

    private val heartbeat = HEARTBEAT in only
    private val steps = only - HEARTBEAT

    private fun wants(step: String) = steps.isEmpty() || step in steps

    suspend fun run(): String {
        HopiPerf.start {
            frames = true
            hangs = true
            cpu = true
            memory = true
            hangHeartbeat = heartbeat
        }
        // The frames monitor attaches with one main-thread message, the sampler takes its first point.
        delay(SETTLE_MS)
        line("{\"perfLab\":\"start\",\"build\":\"${if (SampleBuild.realHopi) "real" else "nohopi"}\",\"heartbeat\":$heartbeat,\"running\":\"${HopiPerf.running.joinToString(",")}\"}")
        if (wants("jank")) {
            for (ms in listOf(20L, 50L, 100L)) {
                val from = labWallMs()
                repeat(JANK_FRAMES) {
                    // The state write makes the frame draw; the block makes it slow.
                    withFrameNanos {
                        tick.intValue++
                        PerfLab.jankFrame(ms)
                    }
                }
                line("{\"perfLab\":\"jank\",\"blockMs\":$ms,\"frames\":$JANK_FRAMES,\"from\":$from,\"to\":${labWallMs()}}")
                delay(GAP_MS)
            }
        }
        if (wants("hang")) {
            for (ms in listOf(200L, 300L, 1_000L, 3_000L)) {
                onMain("hang", ms) { PerfLab.hang(ms) }
                delay(GAP_MS)
            }
            onMain("burn", BURN_MS) { PerfLab.burn(BURN_MS) }
            delay(GAP_MS)
        }
        if (wants("dialog")) {
            val from = labWallMs()
            dialog.value = true
            delay(DIALOG_MS)
            dialog.value = false
            line("{\"perfLab\":\"dialog\",\"ms\":$DIALOG_MS,\"from\":$from,\"to\":${labWallMs()}}")
            delay(GAP_MS)
        }
        if (wants("cpu")) {
            val from = labWallMs()
            labBurnThreads(listOf("perf-burn-1", "perf-burn-2"), CPU_MS)
            delay(CPU_MS + 500)
            line("{\"perfLab\":\"cpu\",\"threads\":2,\"ms\":$CPU_MS,\"from\":$from,\"to\":${labWallMs()}}")
            delay(GAP_MS)
        }
        if (wants("alloc")) {
            val from = labWallMs()
            val heap = ByteArray(ALLOC_BYTES).also { array -> for (i in array.indices step 4096) array[i] = 1 }
            delay(HOLD_MS)
            line("{\"perfLab\":\"alloc\",\"kind\":\"heap\",\"mb\":${ALLOC_BYTES shr 20},\"held\":${heap.size},\"from\":$from,\"to\":${labWallMs()}}")
            delay(GAP_MS)
            val nativeFrom = labWallMs()
            val native = labAllocNative(ALLOC_BYTES)
            delay(HOLD_MS)
            // Logged while still held, as the heap step's line is, so the host's reference sees it.
            line("{\"perfLab\":\"alloc\",\"kind\":\"native\",\"mb\":${ALLOC_BYTES shr 20},\"from\":$nativeFrom,\"to\":${labWallMs()}}")
            labFreeNative(native)
            delay(GAP_MS)
        }
        if (wants("memory")) {
            val at = labWallMs()
            val posted = labMemoryWarning()
            line("{\"perfLab\":\"memory\",\"posted\":$posted,\"at\":$at}")
            delay(GAP_MS)
        }
        // A final second with frames, so the last janky second is closed when the host reads it.
        repeat(3) { withFrameNanos { tick.intValue++ } }
        val done = "{\"perfLab\":\"done\",\"running\":\"${HopiPerf.running.joinToString(",")}\",\"at\":${labWallMs()}}"
        line(done)
        return done
    }

    /** [block] as one posted main-thread unit, timed from inside it. */
    private suspend fun onMain(name: String, ms: Long, block: () -> Unit) {
        val gcBefore = labLastGc()?.first
        val (from, to, back) = suspendCancellableCoroutine { continuation ->
            labPostMain {
                val from = labWallMs()
                block()
                val to = labWallMs()
                // A unit posted behind it: when it runs, the main thread finished whatever else ran
                // in the same unit of work as the block (the time the app could not respond).
                labPostMain { continuation.resume(Triple(from, to, labWallMs())) }
            }
        }
        val gc = labLastGc()?.takeIf { it.first != gcBefore }?.let { ",\"gc\":${it.second}" } ?: ""
        line("{\"perfLab\":\"hang\",\"fn\":\"$name\",\"ms\":$ms,\"actualMs\":${to - from},\"mainBackMs\":${back - from},\"from\":$from,\"to\":$to$gc}")
    }

    companion object {
        const val SETTLE_MS = 2_000L
        const val GAP_MS = 1_500L
        const val JANK_FRAMES = 30
        const val BURN_MS = 600L
        const val DIALOG_MS = 5_000L
        const val CPU_MS = 5_000L
        const val HOLD_MS = 4_000L
        const val ALLOC_BYTES = 64 shl 20
        const val HEARTBEAT = "heartbeat"
    }
}

/**
 * The perf lab screen: one button per injection, the 600 ms click burn first and full width (a host
 * taps it with `adb shell input tap`, which goes through the window's input dispatch), and the lab's
 * own lines. With [verify] set it runs [PerfLabRun] when shown and calls [onVerifyStarted]. Labels the screen `PerfLab`
 * for the monitors while it is shown.
 */
@Composable
internal fun PerfLabScreen(verify: Set<String>?, onVerifyStarted: () -> Unit, onBack: () -> Unit) {
    val tick = remember { mutableIntStateOf(0) }
    val dialog = remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        HopiPerf.screen("PerfLab")
        onDispose { HopiPerf.screen(null) }
    }
    fun launchRun(only: Set<String>) {
        if (busy) return
        busy = true
        lines.clear()
        scope.launch {
            try {
                PerfLabRun(tick, dialog, only) { lines.add(it) }.run()
            } catch (e: CancellationException) {
                throw e
            } finally {
                busy = false
            }
        }
    }
    if (verify != null) {
        LaunchedEffect(Unit) {
            launchRun(verify)
            onVerifyStarted()
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Perf lab", onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SampleButton(
                "Click burn (600 ms in onClick)",
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics { testTag = "perf-click-burn" },
                onClick = {
                    val from = labWallMs()
                    PerfLab.clickBurn()
                    val to = labWallMs()
                    val json = "{\"perfLab\":\"hang\",\"fn\":\"clickBurn\",\"ms\":${PerfLab.CLICK_BURN_MS},\"actualMs\":${to - from},\"from\":$from,\"to\":$to}"
                    sampleLog(json)
                    lines.add(json)
                },
            )
            Caption("Monitors running: ${HopiPerf.running.joinToString().ifEmpty { "none" }} · frame ${tick.intValue}")
            SampleButton("Run every injection", enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { launchRun(emptySet()) })
            for ((label, step) in LAB_STEPS) {
                SampleButton(label, primary = false, enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { launchRun(setOf(step)) })
            }
            SampleButton("Stop every monitor", primary = false, modifier = Modifier.fillMaxWidth(), onClick = { HopiPerf.stop() })
            Caption(
                "Each run starts every monitor (frames, hangs, CPU, memory) and leaves them running. " +
                    "Read the result in the desktop viewer's Perf panel, with `hopi perf snapshot` or `hopi hangs`.",
            )
            lines.forEach { Body(it) }
        }
    }
    if (dialog.value) {
        Dialog(onDismissRequest = { dialog.value = false }) {
            Box(Modifier.background(colors.surface).padding(24.dp), contentAlignment = Alignment.Center) {
                BasicText("A dialog for 5 s: its window is not the activity's")
            }
        }
    }
}

private val LAB_STEPS = listOf(
    "Janky frames (30 × 20, 50, 100 ms)" to "jank",
    "Hangs (200, 300, 1000, 3000 ms sleep; 600 ms spin)" to "hang",
    "Dialog for 5 s" to "dialog",
    "Two threads at 100 % for 5 s" to "cpu",
    "Allocate 64 MB heap, then 64 MB native" to "alloc",
    "Memory warning" to "memory",
)

/** `all` or a comma-separated subset of the lab's steps, with `heartbeat` as an option (`all,heartbeat`). */
internal fun parsePerfLabSteps(spec: String): Set<String> =
    spec.split(',').map { it.trim() }.filterTo(mutableSetOf()) { it.isNotEmpty() && it != "all" }

/** Lets [HopiMonitor] names on the lab's command lines read as the bridge's (`frames`, `hangs`, `cpu`, `memory`). */
internal fun parseMonitors(spec: String?): Set<HopiMonitor> = when (spec?.trim()) {
    null, "", "none" -> emptySet()
    "all" -> HopiMonitor.entries.toSet()
    else -> spec.split(',').mapNotNullTo(mutableSetOf()) { name -> HopiMonitor.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } }
}
