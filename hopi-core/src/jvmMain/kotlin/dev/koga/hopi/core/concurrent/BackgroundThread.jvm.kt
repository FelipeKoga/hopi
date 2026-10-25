package dev.koga.hopi.core.concurrent

import dev.koga.hopi.core.bridge.emitBridgeNotice
import kotlin.concurrent.thread

internal actual fun startBackgroundThread(name: String, body: () -> Unit) {
    thread(isDaemon = true, name = name, priority = Thread.NORM_PRIORITY - 1) {
        try {
            body()
        } catch (e: Throwable) {
            emitBridgeNotice("$name thread failed", e)
        }
    }
}
