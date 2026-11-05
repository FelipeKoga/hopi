package dev.koga.hopi.rules

import dev.koga.hopi.api.HopiRuntimes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** `HopiRules` with no runtime registered, as in a release build: every call inert, the builders still keeping what they get. */
class HopiRulesWithoutRuntimeTest {
    @BeforeTest
    fun setUp() {
        HopiRuntimes.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        HopiRuntimes.resetForTests()
    }

    private fun HopiPredicate.parts() = Triple(name, value, absent)

    @Test
    fun everyCallIsInert() {
        var ran = false
        assertEquals(-1, HopiRules.mock("GET */users/*") {
            status = 500
            header("Content-Type", "application/json")
            body("{}")
            delay(beforeResponse = 2.seconds)
            times = 1
            ran = true
        })
        assertTrue(ran, "the block runs, as with a runtime")
        assertEquals(-1, HopiRules.fail("*/a", HopiFailure.Timeout))
        assertEquals(-1, HopiRules.delay("*/a") { beforeRequest = 1.seconds })
        assertEquals(-1, HopiRules.edit("*/a") { request { path = "/b" } })
        assertEquals(emptyList(), HopiRules.list())
        assertFalse(HopiRules.setEnabled(1, true))
        assertFalse(HopiRules.remove(1))
        HopiRules.clear()
    }

    @Test
    fun builderPropertiesReadBackWhatWasSet() {
        HopiRules.mock("*/a") {
            status = 418
            label = "teapot"
            assertEquals(418, status)
            assertEquals("teapot", label)
            // Nothing set: no sources (every source), no delay, no body, no record.
            assertNull(ruleSources)
            assertNull(delayBeforeRequest)
            assertNull(delayBeforeResponse)
            assertNull(bodyBytes)
            assertNull(fromTransactionId)
            sources(HopiSource.OkHttp)
            sources(HopiSource.UrlSession, HopiSource.Ktor)
            assertEquals(listOf(HopiSource.UrlSession, HopiSource.Ktor), ruleSources, "sources() replaces")
            query("page", "2")
            hasQuery("debug")
            noQuery("trace")
            matchHeader("X-Env", "stag*")
            hasHeader("X-Present")
            noHeader("X-Absent")
            assertEquals(listOf(Triple("page", "2", false), Triple("debug", null, false), Triple("trace", null, true)), queryPredicates.map { it.parts() })
            assertEquals(
                listOf(Triple("X-Env", "stag*", false), Triple("X-Present", null, false), Triple("X-Absent", null, true)),
                headerPredicates.map { it.parts() },
            )
            header("Set-Cookie", "kept: the runtime validates")
            header("X-A", "1")
            assertEquals(listOf("Set-Cookie" to "kept: the runtime validates", "X-A" to "1"), headerList)
            val bytes = byteArrayOf(1, 2, 3)
            body(bytes)
            bytes[0] = 9
            assertContentEquals(byteArrayOf(1, 2, 3), bodyBytes, "body(bytes) copies")
            body("hé")
            assertContentEquals("hé".encodeToByteArray(), bodyBytes)
            from(42)
            assertEquals(42, fromTransactionId)
            delay(beforeResponse = 2.seconds)
            assertEquals(Duration.ZERO, delayBeforeRequest)
            assertEquals(2.seconds, delayBeforeResponse)
            // No validation here: a negative delay is kept for the runtime to refuse.
            delay(beforeRequest = (-1).seconds)
            assertEquals((-1).seconds, delayBeforeRequest)
            assertEquals(Duration.ZERO, delayBeforeResponse)
        }
        HopiRules.delay("*/a") {
            beforeRequest = 1.seconds
            assertEquals(1.seconds, beforeRequest)
            assertNull(delayBeforeRequest, "the vars are not delay()")
        }
        HopiRules.edit("*/a") {
            assertNull(requestEdit)
            assertNull(responseEdit)
            request {
                path = "/b"
                setQuery("q", "1")
                removeQuery("r")
                setHeader("X-Set", "1")
                addHeader("X-Add", "2")
                removeHeader("X-Gone")
            }
            val first = requestEdit
            request {
                method = "POST"
                setHeader("X-Set", "3")
                body("x")
            }
            response { status = 503 }
            response {
                setHeader("X-R", "1")
                addHeader("X-R2", "2")
                removeHeader("X-R3")
                body(byteArrayOf(7))
            }
            val request = assertNotNull(requestEdit)
            assertSame(first, request, "request { } adds to one edit")
            assertEquals("/b", request.path)
            assertEquals("POST", request.method)
            assertEquals(listOf("q" to "1"), request.querySets)
            assertEquals(listOf("r"), request.queryRemovals)
            assertEquals(listOf("X-Set" to "1", "X-Set" to "3"), request.headerSets)
            assertEquals(listOf("X-Add" to "2"), request.headerAdds)
            assertEquals(listOf("X-Gone"), request.headerRemovals)
            assertContentEquals("x".encodeToByteArray(), request.bodyBytes)
            val response = assertNotNull(responseEdit)
            assertEquals(503, response.status, "response { } adds to one edit")
            assertEquals(listOf("X-R" to "1"), response.headerSets)
            assertEquals(listOf("X-R2" to "2"), response.headerAdds)
            assertEquals(listOf("X-R3"), response.headerRemovals)
            assertContentEquals(byteArrayOf(7), response.bodyBytes)
        }
    }

    @Test
    fun onlyTheAddsMadeWhileNoRuntimeIsRegisteredAreCounted() {
        assertEquals(0, HopiRuntimes.droppedRules)
        HopiRules.mock("*/a")
        HopiRules.fail("*/a", HopiFailure.Reset)
        HopiRules.delay("*/a") { beforeRequest = 1.seconds }
        HopiRules.edit("*/a") { response { status = 503 } }
        // A test's teardown clears before the start: none of these is counted.
        HopiRules.list()
        HopiRules.setEnabled(1, false)
        HopiRules.remove(1)
        HopiRules.clear()
        assertEquals(4, HopiRuntimes.droppedRules, "the four adds only")
        assertTrue(HopiRuntimes.registerRules(FakeRulesRuntime()))
        assertEquals(1, HopiRules.mock("*/b"))
        assertEquals(4, HopiRuntimes.droppedRules, "an add the runtime took is not counted")
    }
}
