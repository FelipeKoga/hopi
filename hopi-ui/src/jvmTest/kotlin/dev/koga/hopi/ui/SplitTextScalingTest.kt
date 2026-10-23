package dev.koga.hopi.ui

import dev.koga.hopi.ui.format.splitText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * A body of one long line (minified JSON cut by the capture cap, one huge string) is split into
 * thousands of pieces. Searching for the next newline from each piece to the end of the text made
 * that quadratic: 1.6 s for 16M chars on the development Mac, longer on a phone, during which the
 * detail stays empty. The bound only catches that order of magnitude, since machines vary.
 */
class SplitTextScalingTest {
    @Test
    fun aOneLineBodyIsSplitInLinearTime() {
        val text = "x".repeat(24 * 1024 * 1024)
        splitText("y".repeat(1 shl 20)) // warm-up
        val mark = TimeSource.Monotonic.markNow()
        val pieces = splitText(text)
        val elapsed = mark.elapsedNow()
        assertEquals(6_144, pieces.pieces.size)
        assertTrue(elapsed.inWholeMilliseconds < 1_000, "splitText of 24M chars on one line took $elapsed")
    }
}
