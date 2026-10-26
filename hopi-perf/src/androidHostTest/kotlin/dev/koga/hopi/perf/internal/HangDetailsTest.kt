package dev.koga.hopi.perf.internal

import dev.koga.hopi.core.perf.PerfMonitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `perf.status` states the input bracket's reach: activity windows only. A Compose `Dialog`,
 * `ModalBottomSheet` or `Popup` dispatches touches through its own window's callback, which no
 * public API lets Hopi wrap, so a click that blocks there is not a hang unless the heartbeat runs.
 */
class HangDetailsTest {
    @Test
    fun hangsStatusSaysInputIsBracketedInActivityWindowsOnly() {
        val details = AndroidBackend.instance.details(PerfMonitor.HANGS).toMap()
        assertEquals("activities only", details["inputWindows"])
        assertTrue("input" in details, details.toString())
    }
}
