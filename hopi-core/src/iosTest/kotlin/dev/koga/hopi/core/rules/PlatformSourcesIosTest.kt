package dev.koga.hopi.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** iOS's own rule sources, pinned (the JVM's are in `RuleEngineTest`; `CapabilitiesTest` checks only their consistency). */
class PlatformSourcesIosTest {
    @Test
    fun urlSessionCaptureIsTheOnlySourceOnIos() {
        // The Ktor plugin installs nothing on iOS: its recorder is hopi-network's on Android and the JVM.
        assertEquals(setOf(RuleSource.URL_SESSION), platformSources)
        // So a response edit that names no sources applies nowhere: URLSession excludes it.
        val responseEdit = EditSpec(response = ResponseEdit(status = 503))
        assertTrue(Capabilities.appliesNowhere(RuleSpec(MatchShorthand.parse("*/a"), edit = responseEdit)))
    }
}
