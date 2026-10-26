package dev.koga.hopi.ui

import dev.koga.hopi.ui.perf.perfPaneModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Perf tab's rebuild costs what changed, not the retained history: no hang's stacks are
 * formatted until its row is opened, and an unchanged hang keeps its row.
 */
class PerfPaneModelTest {
    @Test
    fun aRebuildFormatsNoStackAndReusesUnchangedRows() {
        val controller = FakePerfController()
        val hangs = (1..60).map { controller.hang(300 + it.toLong()) }
        val first = perfPaneModel(controller, threads = false)
        assertEquals(60, first.hangs.size)
        assertTrue(first.hangs.none { it.stacksFormatted }, "a rebuild formatted stacks nobody opened")

        val ongoing = controller.hang(250, ended = false)
        val second = perfPaneModel(controller, threads = false, previous = first)
        assertEquals(61, second.hangs.size)
        for (row in second.hangs.drop(1)) assertSame(first.hangs.single { it.seq == row.seq }, row)
        assertTrue(second.hangs.none { it.stacksFormatted })

        // The ongoing hang ends: a new event instance, so a new row; the others stay.
        controller.data.updateHang(ongoing.copy(ended = true, durationMs = 900))
        val third = perfPaneModel(controller, threads = false, previous = second)
        assertNotSame(second.hangs.first(), third.hangs.first())
        assertTrue(third.hangs.first().title.startsWith("900 ms"))
        assertSame(second.hangs[1], third.hangs[1])

        // Opening a row formats that row's stacks only.
        val opened = third.hangs.first { it.seq == hangs.first().seq }
        assertTrue(opened.stacks.isNotEmpty())
        assertTrue(opened.stacksFormatted)
        assertFalse(third.hangs.first().stacksFormatted)
    }
}
