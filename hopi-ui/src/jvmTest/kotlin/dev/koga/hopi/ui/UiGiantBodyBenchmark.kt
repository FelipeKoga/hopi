package dev.koga.hopi.ui

import dev.koga.hopi.bench.GiantBodies
import dev.koga.hopi.bench.GiantBodies.MB
import dev.koga.hopi.bench.Measure
import dev.koga.hopi.bench.Report
import dev.koga.hopi.bench.Shape
import dev.koga.hopi.bench.iterationsFor
import dev.koga.hopi.bench.mb
import dev.koga.hopi.bench.measure
import dev.koga.hopi.bench.ms
import dev.koga.hopi.bench.ratio
import dev.koga.hopi.core.body.prettyPrintJson
import dev.koga.hopi.core.body.textOrNull
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.ui.detail.BodyView
import dev.koga.hopi.ui.detail.DetailFormatter
import dev.koga.hopi.ui.format.splitText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The detail screen's body formatting for giant bodies, off the main thread: the chunker alone
 * (on the raw and the pretty-printed text) and the whole [DetailFormatter] body view, with the
 * characters and heap the view keeps while the screen shows it.
 *
 * Opt-in: `./gradlew :hopi-ui:jvmGiantBodyBenchmark` (excluded from `jvmTest`).
 */
class UiGiantBodyBenchmark {
    private class Case(val bodyBytes: Int, val capturedBytes: Int) {
        val label: String
            get() = if (bodyBytes == capturedBytes) GiantBodies.label(bodyBytes) else "${GiantBodies.label(capturedBytes)} of ${GiantBodies.label(bodyBytes)}"
    }

    private val cases: List<Case> = buildList {
        for (size in listOf(256 * GiantBodies.KB, MB, 5 * MB, 16 * MB)) add(Case(size, size))
        add(Case(20 * MB, 16 * MB))
    }

    private fun captured(shape: Shape, case: Case): CapturedBody {
        val all = GiantBodies.bytes(shape, case.bodyBytes)
        val kept = if (case.bodyBytes != case.capturedBytes) all.copyOf(case.capturedBytes) else all
        return CapturedBody(kept, all.size.toLong(), truncated = kept.size < all.size, "application/json", null)
    }

    @Test
    fun bodyFormatting() {
        val report = Report(
            "ui-formatting",
            "Detail body formatting off the main thread (median / p95 ms; allocation per call as a multiple of the captured size)",
            "shape", "captured", "splitText(raw)", "splitText(pretty)", "pieces", "longest piece", "DetailFormatter body view", "alloc", "view chars", "view retained heap (MB)",
        )
        for (shape in Shape.entries) {
            for (case in cases) {
                val body = captured(shape, case)
                val size = body.bytes.size.toLong()
                val text = checkNotNull(body.textOrNull())
                val pretty = prettyPrintJson(text)
                val runs = iterationsFor(body.bytes.size, budgetBytes = 96L * MB, min = 3, max = 30)
                val raw = guarded(runs) { splitText(text) }
                val printed = guarded(runs) { splitText(pretty) }
                val pieces = splitText(pretty)
                val longest = pieces.pieces.maxOf { it.length }
                assertTrue(longest <= 4_096, "a piece is at most 4096 chars, not $longest")
                var view: BodyView? = null
                val format = guarded(runs) { view = runBlocking { DetailFormatter().bodyView(body, "response") } }
                val shown = view as BodyView.Text
                val retained = retainedBy { runBlocking { DetailFormatter().bodyView(body, "response") } }
                report.row(
                    shape.label, case.label, raw.first, printed.first, shown.chunks.size.toString(), longest.toString(),
                    format.first, format.second?.let { ratio(it, size) } ?: "-", shown.pieces.length.toString(), mb(retained),
                )
            }
        }
        report.note("splitText(pretty) runs on prettyPrintJson's output, which is the raw text when that is invalid (truncated) or nested beyond 256.")
        report.note("The body view pretty-prints straight into its pieces (PieceWriter), never through prettyPrintJson's string; a truncated body is printed only when it ends a container, else split raw.")
        report.note("A case slower than 20 s per call runs once and is marked '(1 run)'.")
        report.note("View retained heap: heap still reachable through the view after a full GC (pieces plus the per-piece flags), minus the heap before; the captured bytes are not counted.")
        report.save()
    }

    /** Median / p95 of [block] and its median allocation, or a single timing when one call is slower than 20 s. */
    private inline fun guarded(runs: Int, crossinline block: () -> Unit): Pair<String, Long?> {
        val start = System.nanoTime()
        block()
        val first = System.nanoTime() - start
        if (first > 20_000_000_000L) return "${ms(first)} (1 run)" to null
        val samples = measure(runs, warmup = 1, threadOnly = true) { block() }
        return "${ms(samples.medianNanos)} / ${ms(samples.p95Nanos)}" to samples.medianAllocated
    }

    private fun retainedBy(make: () -> Any): Long {
        val before = Measure.retainedHeap()
        val kept = make()
        val after = Measure.retainedHeap()
        java.lang.ref.Reference.reachabilityFence(kept)
        return after - before
    }
}
