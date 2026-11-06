package dev.koga.hopi.network.internal.ktor

import dev.koga.hopi.core.start.StartConfig
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ktor.HopiConfig
import dev.koga.hopi.ktor.HopiLogSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The thin plugin's defaults are literals (hopi-ktor has no core to read them from): they must stay
 * core's, so a client that sets nothing reads back what its installation will get.
 */
class HopiConfigDefaultsTest {
    @Test
    fun theDefaultsAreCores() {
        val config = HopiConfig()
        val start = StartConfig()
        assertEquals(HopiStore.DEFAULT_MAX_ENTRIES, config.maxEntries)
        assertEquals(HopiStore.DEFAULT_MAX_TOTAL_BYTES, config.maxTotalBytes)
        assertEquals(start.maxBodyBytes, config.maxBodyBytes)
        // The start serves the bridge by default; a Ktor client only requests it when asked, since
        // the start (Android's provider, Hopi.start) already did.
        assertTrue(start.agentBridge)
        assertFalse(config.agentBridge)
        assertEquals(HopiLogSink.Summary, config.logSink)
        assertTrue(config.rules)
    }
}
