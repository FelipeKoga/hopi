package dev.koga.hopi.urlsession

import dev.koga.hopi.core.capture.PlatformCapture
import kotlin.test.Test
import kotlin.test.assertFalse

class HopiUrlSessionJvmTest {
    @Test
    fun startDoesNothingWithoutUrlSession() {
        HopiUrlSession.start { agentBridge = false }
        assertFalse(HopiUrlSession.isStarted)
        // Nothing is covered, so the Ktor plugin keeps recording every engine.
        assertFalse(PlatformCapture.covers("io.ktor.client.engine.darwin.DarwinClientEngine"))
    }

    @Test
    fun disabledStartsNothing() {
        HopiUrlSession.start { enabled = false }
        assertFalse(HopiUrlSession.isStarted)
    }
}
