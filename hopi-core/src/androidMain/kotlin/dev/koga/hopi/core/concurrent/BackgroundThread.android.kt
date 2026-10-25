package dev.koga.hopi.core.concurrent

import android.os.Process
import dev.koga.hopi.core.bridge.emitBridgeNotice
import kotlin.concurrent.thread

/**
 * Nice 5: a third of a default-priority thread's CPU share under contention, so the app's threads
 * win, but in the app's own scheduling group. `THREAD_PRIORITY_BACKGROUND` (nice 10) and below
 * also switch the thread's scheduling policy (libutils applies the `SCHED_SP_BACKGROUND` task
 * profile, on many devices the background cpu cgroup with its small CPU share), where a busy
 * foreground app could starve the store's only consumer, and with it every summary line and
 * bridge event, for seconds.
 */
internal const val STORE_THREAD_PRIORITY: Int = Process.THREAD_PRIORITY_DEFAULT + 5 * Process.THREAD_PRIORITY_LESS_FAVORABLE

internal actual fun startBackgroundThread(name: String, body: () -> Unit) {
    thread(isDaemon = true, name = name) {
        try {
            Process.setThreadPriority(STORE_THREAD_PRIORITY)
            body()
        } catch (e: Throwable) {
            emitBridgeNotice("$name thread failed", e)
        }
    }
}
