package dev.koga.hopi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.koga.hopi.bench.GiantBodies
import dev.koga.hopi.bench.GiantBodies.MB
import dev.koga.hopi.bench.Report
import dev.koga.hopi.bench.Samples
import dev.koga.hopi.bench.Shape
import dev.koga.hopi.bench.ms
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.redact.Redactor
import dev.koga.hopi.ui.detail.BodyView
import dev.koga.hopi.ui.detail.DetailActions
import dev.koga.hopi.ui.detail.DetailFormatter
import dev.koga.hopi.ui.detail.DetailModel
import dev.koga.hopi.ui.detail.DetailTab
import dev.koga.hopi.ui.detail.TransactionDetailPane
import dev.koga.hopi.ui.theme.HopiTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The detail screen showing a giant response body on the main thread, headless (Compose desktop
 * test host, no window): the first frame of the Response tab, frames while a scroll animation
 * runs through the body, and a jump to its end. Formatting is done before, off the main thread,
 * and timed separately, as the screen does it on `Dispatchers.Default`.
 *
 * "Frame" is composition, measure and layout of one `advanceTimeByFrame`; "draw" adds rendering
 * the whole window to an image, which the headless host does only on request.
 *
 * Opt-in: `./gradlew :hopi-ui:jvmGiantBodyBenchmark` (excluded from `jvmTest`).
 */
@OptIn(ExperimentalTestApi::class)
class DetailComposeGiantBodyBenchmark {
    private class Case(val label: String, val shape: Shape, val bodyBytes: Int, val capturedBytes: Int)

    private val cases = buildList {
        for (size in listOf(MB, 8 * MB, 16 * MB)) {
            val name = GiantBodies.label(size)
            add(Case("pretty $name", Shape.Pretty, size, size))
            add(Case("minified $name (pretty-printed)", Shape.Minified, size, size))
            add(Case("minified $name of ${GiantBodies.label(size + size / 4)} (truncated, one line)", Shape.Minified, size + size / 4, size))
            add(Case("one $name string (one line)", Shape.HugeString, size, size))
        }
    }

    @Test
    fun openAndScrollTheResponseBody() {
        val report = Report(
            "ui-compose",
            "Detail screen, Response tab, 400x800 dp headless (ms; frame = composition + layout)",
            "body", "format (off main)", "pieces", "longest piece", "first frame", "first draw", "scroll frames", "frame median / p95 / max", "jump to end",
        )
        // Warm-up: class loading and JIT of the whole detail screen, discarded.
        repeat(2) {
            val warm = HttpTransaction.pending(1, 0, "GET", "https://api.example.com/warm", emptyList(), null)
                .completed(200, emptyList(), CapturedBody(GiantBodies.bytes(Shape.Pretty, MB), null, false, "application/json", null), 10, "HTTP/1.1")
            measureScreen(runBlocking { DetailFormatter().model(warm, Redactor.Default) })
        }
        for (case in cases) {
            val all = GiantBodies.bytes(case.shape, case.bodyBytes)
            val kept = if (case.bodyBytes != case.capturedBytes) all.copyOf(case.capturedBytes) else all
            val body = CapturedBody(kept, all.size.toLong(), kept.size < all.size, "application/json", null)
            val transaction = HttpTransaction.pending(1, 0, "GET", "https://api.example.com/large", emptyList(), null)
                .completed(200, listOf("Content-Type" to "application/json"), body, 10, "HTTP/1.1")
            val formatStart = System.nanoTime()
            val model = runBlocking { DetailFormatter().model(transaction, Redactor.Default) }
            val format = System.nanoTime() - formatStart
            val text = model.response.body as BodyView.Text
            val longest = text.chunks.maxOf { it.length }
            assertTrue(longest <= 4_096, "${case.label}: a piece of $longest chars reaches one text layout")
            val (first, draw, frames, jump) = measureScreen(model)
            report.row(
                case.label, ms(format), text.chunks.size.toString(), longest.toString(), ms(first), ms(draw),
                frames.count.toString(), "${ms(frames.medianNanos)} / ${ms(frames.p95Nanos)} / ${ms(frames.maxNanos)}", ms(jump),
            )
        }
        report.note("Scroll: one animated ScrollBy of 40 000 px through the semantics action, stepped frame by frame until the offset stops moving; only frames that moved are counted.")
        report.note("Headless JVM numbers: a lower bound of the work, not a device frame time; the emulator numbers in ARCHITECTURE.md are the device reference.")
        report.save()
    }

    private class ScreenTimes(val first: Long, val draw: Long, val frames: FrameSamples, val jump: Long) {
        operator fun component1() = first
        operator fun component2() = draw
        operator fun component3() = frames
        operator fun component4() = jump
    }

    private class FrameSamples {
        private val samples = Samples(4_096)
        var maxNanos = 0L
            private set
        val count: Int get() = samples.count
        val medianNanos: Long get() = samples.medianNanos
        val p95Nanos: Long get() = samples.p95Nanos

        fun add(nanos: Long) {
            samples.add(nanos, 0)
            if (nanos > maxNanos) maxNanos = nanos
        }
    }

    private fun scrollOffset(list: androidx.compose.ui.test.SemanticsNodeInteraction): Float =
        list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun measureScreen(model: DetailModel): ScreenTimes {
        var result: ScreenTimes? = null
        runComposeUiTest {
            mainClock.autoAdvance = false
            var shown by mutableStateOf(false)
            val actions = DetailActions(copyCurl = {}, copyUrl = {}, copyBody = {}, share = null)
            setContent {
                HopiTheme {
                    Box(Modifier.size(400.dp, 800.dp)) {
                        if (shown) TransactionDetailPane(model, missing = false, tab = DetailTab.Response, onTab = {}, onBack = {}, actions = actions)
                    }
                }
            }
            waitForIdle()
            val firstStart = System.nanoTime()
            shown = true
            mainClock.advanceTimeByFrame()
            waitForIdle()
            val first = System.nanoTime() - firstStart
            val drawStart = System.nanoTime()
            onRoot().captureToImage()
            val draw = System.nanoTime() - drawStart

            val list = onNode(hasScrollToIndexAction())
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 40_000f) }
            val frames = FrameSamples()
            var last = scrollOffset(list)
            var still = 0
            // Until the animation has ended: the offset did not move for three frames.
            while (still < 3 && frames.count < 2_000) {
                val start = System.nanoTime()
                mainClock.advanceTimeByFrame()
                waitForIdle()
                val elapsed = System.nanoTime() - start
                val offset = scrollOffset(list)
                if (offset == last) still++ else still = 0
                if (still == 0) frames.add(elapsed)
                last = offset
            }
            check(last > 0f) { "the body did not scroll" }
            val body = model.response.body as BodyView.Text
            // Items: headers title, header rows (or one message), body title, notice, pieces, end.
            val items = 1 + maxOf(1, model.response.headers.size) + 1 + (if (body.notice != null) 1 else 0) + body.chunks.size + 1
            val jumpStart = System.nanoTime()
            list.performScrollToIndex(items - 1)
            mainClock.advanceTimeByFrame()
            waitForIdle()
            val jump = System.nanoTime() - jumpStart
            result = ScreenTimes(first, draw, frames, jump)
        }
        return checkNotNull(result)
    }
}
