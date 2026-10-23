package dev.koga.hopi.ui

import dev.koga.hopi.bench.GiantBodies
import dev.koga.hopi.bench.Shape
import dev.koga.hopi.core.body.prettyPrintJson
import dev.koga.hopi.core.body.textOrNull
import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.ui.detail.BodyView
import dev.koga.hopi.ui.detail.DetailFormatter
import dev.koga.hopi.ui.format.splitText
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The detail view of a large minified JSON body is pretty-printed straight into its pieces. It
 * used to build a builder sized 1.25x the text, grow it to 2.5x (minified JSON grows 1.6x), copy it
 * into a string and then into pieces: on ART and Kotlin/Native, where a builder holds 2 bytes per
 * char, that peak sat next to the app's own heap. The bound is on allocation, which on the JVM's
 * Latin-1 strings is the text, one batch string at a time and the pieces.
 */
class PrettyBodyMemoryTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Test
    fun aMinifiedBodyIsPrettyPrintedIntoItsPiecesWithoutAWholeCopy() {
        val bytes = GiantBodies.bytes(Shape.Minified, 4 * GiantBodies.MB)
        val body = CapturedBody(bytes, bytes.size.toLong(), truncated = false, "application/json", null)
        runBlocking { DetailFormatter().bodyView(body, "response") } // warm-up
        val before = threads.getThreadAllocatedBytes(Thread.currentThread().id)
        val view = runBlocking { DetailFormatter().bodyView(body, "response") }
        val allocated = threads.getThreadAllocatedBytes(Thread.currentThread().id) - before
        val text = assertIs<BodyView.Text>(view)
        val expected = splitText(prettyPrintJson(checkNotNull(body.textOrNull())))
        assertEquals(expected.pieces, text.pieces.pieces)
        assertEquals(expected.join(), text.pieces.join())
        // Text (1x), each 32K batch once as a string (1.6x, dropped batch by batch) and the pieces
        // (1.6x); printing whole and then splitting allocated 8x, with a 4x builder live at once.
        assertTrue(allocated < bytes.size * 5L, "the view allocated ${allocated.toDouble() / bytes.size}x the body")
    }

    @Test
    fun aTruncatedJsonBodyCutInsideAValueIsShownRaw() {
        val all = GiantBodies.bytes(Shape.Minified, 2 * GiantBodies.MB)
        val kept = all.copyOf(GiantBodies.MB)
        val body = CapturedBody(kept, all.size.toLong(), truncated = true, "application/json", null)
        val view = assertIs<BodyView.Text>(runBlocking { DetailFormatter().bodyView(body, "response") })
        assertEquals(splitText(checkNotNull(body.textOrNull())).pieces, view.pieces.pieces)
        assertTrue(view.isJson)
    }
}
