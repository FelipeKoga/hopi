package dev.koga.hopi.perf.internal

import android.app.Activity
import android.app.Application
import dev.koga.hopi.core.perf.CurrentActivity
import dev.koga.hopi.core.perf.PerfMonitor
import dev.koga.hopi.core.perf.PerfOwner
import dev.koga.hopi.core.perf.PerfStartRequest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A frames start with a resumed activity attaches with one main-thread message, and until that
 * message ran frames' details say `attaching`, so the start's reply does not report the attach as
 * missing (the CLI's and the macOS viewer's "attach when an activity next starts" hint). On the
 * host the message never runs (`Handler` is a stub), so this sees the pending half only: reported
 * while frames runs, not once it stopped. That the posted block clears it, also for a frames
 * stopped and started again, is checked on a device (the runtime-split plan's P5 check).
 */
class FramesAttachingTest {
    @Test
    fun aPendingAttachIsReportedOnlyWhileFramesRuns() {
        AndroidBackend.application(Application())
        val activity = Activity()
        CurrentActivity.attach(activity)
        val controller = PerfRuntime.controller()
        try {
            controller.start(PerfStartRequest(setOf(PerfMonitor.FRAMES)), PerfOwner.Code)
            val status = controller.status()
            val details = status.running.single { it.monitor == PerfMonitor.FRAMES }.details.toMap()
            assertEquals(false, details["attached"], "no window reports frames yet")
            assertEquals(true, details["attaching"], "the attach is one main-thread message away")
            assertEquals(emptyList(), status.notices, "no 'attach when an activity next starts' notice")
            controller.stop(setOf(PerfMonitor.FRAMES))
            assertEquals(false, AndroidBackend.instance.details(PerfMonitor.FRAMES).toMap()["attaching"], "not while frames is off")
        } finally {
            controller.stop(emptySet())
            CurrentActivity.detach(activity)
        }
    }
}
