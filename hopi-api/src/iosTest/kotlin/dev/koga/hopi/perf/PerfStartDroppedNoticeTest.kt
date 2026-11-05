package dev.koga.hopi.perf

import dev.koga.hopi.api.HopiRuntimes
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The forgotten-start line of an iOS Debug binary, which a test binary is, for `HopiPerf`: the
 * first start that finds no runtime writes it, once per process, and a start a runtime took writes
 * nothing. Its count is read here; the line itself goes to the system log, which the sample app's
 * simulator check reads. The count is never reset, so the runtime's part runs first: in this binary
 * no other test drops a perf start before it.
 */
class PerfStartDroppedNoticeTest {
    @AfterTest
    fun tearDown() {
        HopiRuntimes.resetForTests()
    }

    @OptIn(ExperimentalNativeApi::class)
    @Test
    fun theFirstDroppedStartWritesOneLineAndARegisteredRuntimeNone() {
        assertTrue(Platform.isDebugBinary, "a debug test binary")
        HopiRuntimes.resetForTests()
        val before = perfStartDroppedNoticeLines
        val runtime = FakePerfRuntime()
        assertTrue(HopiRuntimes.registerPerf(runtime))
        HopiPerf.start { hangs = true }
        HopiPerf.stop()
        HopiPerf.screen("Checkout")
        assertEquals(1, runtime.starts.size)
        assertEquals(before, perfStartDroppedNoticeLines, "a start the runtime took writes nothing")

        HopiRuntimes.resetForTests()
        HopiPerf.start { frames = true }
        assertEquals(1, perfStartDroppedNoticeLines, "the first dropped start writes the line")
        HopiPerf.start { cpu = true }
        HopiPerf.stop()
        assertEquals(1, perfStartDroppedNoticeLines, "once per process")
        assertEquals(2, HopiRuntimes.droppedPerfStarts)
    }
}
