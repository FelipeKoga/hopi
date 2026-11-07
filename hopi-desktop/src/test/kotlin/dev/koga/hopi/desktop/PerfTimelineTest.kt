package dev.koga.hopi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PerfTimelineTest {
    @Test
    fun theTimelineFollowsTheAppUntilASelectionHoldsIt() {
        val state = PerfPanelState()
        assertEquals(PerfSpan(700_000, 1_000_000), state.domain(1_000_000))
        assertEquals(PerfSpan(710_000, 1_010_000), state.domain(1_010_000))

        state.select(PerfSpan(800_000, 810_000), endMs = 1_010_000)
        assertEquals(PerfSpan(710_000, 1_010_000), state.domain(1_090_000), "a selection stays under the pointer")

        state.live()
        assertEquals(PerfSpan(790_000, 1_090_000), state.domain(1_090_000))
        assertFalse(state.held)
    }

    @Test
    fun zoomFramesTheSelectionWithRoomAroundIt() {
        val state = PerfPanelState()
        state.focus(PerfSpan(10_000_000, 10_012_000), latestMs = 20_000_000)
        assertTrue(state.zoomed)
        assertEquals(PerfSpan(9_995_000, 10_017_000), state.domain(20_000_000), "any distance back, past the range shown")
        state.zoomOut()
        assertEquals(PerfSpan(19_700_000, 20_000_000), state.domain(30_000_000))
        assertNotNull(state.selection)
    }

    @Test
    fun aShortDragStillCoversTwoSamples() {
        val state = PerfPanelState()
        state.select(PerfSpan(1_000, 1_100), endMs = 9_000)
        assertEquals(2_000, state.selection?.ms)
    }

    @Test
    fun aSpanMapsPixelsToMoments() {
        val span = PerfSpan(1_000, 3_000)
        assertEquals(2_000, span.at(0.5f))
        assertEquals(1_000, span.at(-1f))
        assertEquals(0.25f, span.fraction(1_500))
    }
}
