package dev.koga.hopi.ui

import dev.koga.hopi.ui.platform.HOPI_FONT_DIR
import dev.koga.hopi.ui.platform.HOPI_FONT_FILES
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Open Runde and its OFL license travel in the jar. hopiSans() falls back to the platform sans when
 * a file is missing, so the viewer keeps working; this test is what notices.
 */
class HopiFontsTest {
    @Test
    fun openRundeAndItsLicenseAreOnTheClasspath() {
        val loader = Thread.currentThread().contextClassLoader
        for (file in HOPI_FONT_FILES.map { it.first } + "OPEN_RUNDE_LICENSE.txt") {
            assertNotNull(loader.getResource("$HOPI_FONT_DIR/$file"), "$HOPI_FONT_DIR/$file")
        }
    }
}
