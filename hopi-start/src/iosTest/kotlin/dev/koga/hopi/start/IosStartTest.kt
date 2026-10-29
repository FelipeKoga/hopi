package dev.koga.hopi.start

import kotlin.test.Test
import kotlin.test.assertFalse

/** The one start of the iOS tests, which never gets past the start sequence's own checks. */
class IosStartTest {
    @Test
    fun aDisabledStartAndInvalidCapsStartNothingAndThrowNothing() {
        Hopi.start { enabled = false }
        assertFalse(Hopi.isStarted)
        // One notice line each in the platform log; Swift could not catch an exception.
        Hopi.start { maxEntries = 0 }
        Hopi.start { maxBodyBytes = -1 }
        assertFalse(Hopi.isStarted)
    }
}
