package dev.koga.hopi.core.capture

import dev.koga.hopi.core.store.HopiStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CaptureInstallationTest {
    @Test
    fun invalidCapsNamesTheFirstBadOne() {
        assertNull(CaptureInstallation.invalidCaps(1, 1, 0))
        assertNull(CaptureInstallation.invalidCaps(500, 64L shl 20, Int.MAX_VALUE.toLong()))
        assertEquals("maxEntries must be positive", CaptureInstallation.invalidCaps(0, 1, 0))
        assertEquals("maxTotalBytes must be positive", CaptureInstallation.invalidCaps(1, 0, 0))
        assertEquals("maxTotalBytes must be positive", CaptureInstallation.invalidCaps(1, -1, 0))
        assertEquals("maxBodyBytes must be in 0..${Int.MAX_VALUE}", CaptureInstallation.invalidCaps(1, 1, -1))
        assertEquals("maxBodyBytes must be in 0..${Int.MAX_VALUE}", CaptureInstallation.invalidCaps(1, 1, Int.MAX_VALUE + 1L))
    }

    @Test
    fun installThrowsWhatInvalidCapsReports() {
        // URLSession capture checks invalidCaps first and never reaches this throw (it is called from Swift).
        val error = assertFailsWith<IllegalArgumentException> {
            CaptureInstallation.install(
                maxEntries = 1,
                maxTotalBytes = 0,
                maxBodyBytes = 0,
                redactedHeaders = emptyList(),
                redactedQueryParameters = emptyList(),
                redactionDisabled = false,
                summaryLog = false,
                agentBridge = false,
                injectedStore = HopiStore(1, 1),
            )
        }
        assertEquals("maxTotalBytes must be positive", error.message)
    }
}
