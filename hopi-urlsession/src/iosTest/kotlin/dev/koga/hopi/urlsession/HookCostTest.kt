package dev.koga.hopi.urlsession

import dev.koga.hopi.core.internal.urlsession.hopi_us_test_hook_calls
import dev.koga.hopi.core.internal.urlsession.hopi_us_test_hook_ns
import dev.koga.hopi.core.internal.urlsession.hopi_us_test_main_thread_connection_calls
import dev.koga.hopi.core.internal.urlsession.hopi_us_test_timing
import dev.koga.hopi.urlsession.fixtures.HopiFxCompletion
import dev.koga.hopi.urlsession.fixtures.HopiFxRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.dataTaskWithURL

/**
 * What Hopi's own part of each hook costs (its observe code only, timed inside the library), over
 * sequential loopback requests, and that the connection hooks never run on the main thread even
 * for a session whose delegate queue is the main queue.
 */
class HookCostTest {
    init {
        Capture.ensureStarted()
    }

    @Test
    fun hookCostPerCallAndNoMainThreadWork() {
        for (size in listOf(1024, 64 * 1024, 1024 * 1024)) {
            hopi_us_test_timing(true)
            val requests = if (size >= 1024 * 1024) 60 else 300
            repeat(requests) {
                val done = HopiFxCompletion()
                NSURLSession.sharedSession.dataTaskWithURL(nsUrl(Capture.url("/bytes?n=$size")), done.dataHandler()!!).resume()
                done.awaitDone()
            }
            val main = HopiFxRecorder()
            val session = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration, main, NSOperationQueue.mainQueue)
            repeat(requests / 3) {
                session.dataTaskWithURL(nsUrl(Capture.url("/bytes?n=$size"))).resume()
                main.awaitDone()
            }
            session.finishTasksAndInvalidate()
            val names = listOf("resume", "data", "finish")
            val report = names.indices.joinToString(", ") { hook ->
                val calls = hopi_us_test_hook_calls(hook)
                "${names[hook]} ${if (calls == 0L) "-" else (hopi_us_test_hook_ns(hook) / calls).toString()} ns x $calls"
            }
            println("hook cost, $size-byte bodies, $requests+${requests / 3} requests: $report")
            assertEquals(0L, hopi_us_test_main_thread_connection_calls(), "a connection hook ran on the main thread")
            // Generous bounds against a stalled simulator; the measured figures are in the output.
            for (hook in names.indices) {
                val calls = hopi_us_test_hook_calls(hook)
                assertTrue(calls > 0, names[hook])
                assertTrue(hopi_us_test_hook_ns(hook) / calls < 200_000, "${names[hook]}: ${hopi_us_test_hook_ns(hook) / calls} ns per call")
            }
            hopi_us_test_timing(false)
        }
    }
}
