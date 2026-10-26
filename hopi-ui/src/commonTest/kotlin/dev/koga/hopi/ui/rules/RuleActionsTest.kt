package dev.koga.hopi.ui.rules

import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.ui.complete
import dev.koga.hopi.ui.pending
import dev.koga.hopi.ui.textBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RuleActionsTest {
    @Test
    fun theExactMatchIsMethodSchemeHostPortAndPathWithoutTheQuery() {
        val match = assertNotNull(exactMatchOf(complete(1, method = "POST", url = "https://API.example.com:8443/v1/users?page=2")))
        assertEquals("POST https://api.example.com:8443/v1/users", match.describe())
        assertEquals("GET http://example.com/", assertNotNull(exactMatchOf(complete(1, url = "http://example.com"))).describe())
        assertNull(exactMatchOf(complete(1, url = "ftp://example.com/x")))
    }

    @Test
    fun mockNeedsTheResponseBodyHeldInFull() {
        assertNull(mockRefusal(complete(1)))
        assertEquals("no response to mock", mockRefusal(pending(1)))
        assertEquals("response body not held in full", mockRefusal(complete(1, responseBody = textBody("{", truncated = true, total = 10))))
        assertEquals("response body not held in full", mockRefusal(complete(1, responseBody = CapturedBody.gone(10))))
        // A host viewer's summary: the app holds what `resHeld` says.
        assertNull(mockRefusal(complete(1, responseBody = CapturedBody.notFetched(10))))
        assertEquals("response body not held in full", mockRefusal(complete(1, responseBody = CapturedBody.notFetched(10, heldBytes = 4))))
    }
}
