package dev.koga.hopi.core.concurrent

import dev.koga.hopi.core.bridge.emitBridgeNotice
import platform.Foundation.NSQualityOfServiceUtility
import platform.Foundation.NSThread

internal actual fun startBackgroundThread(name: String, body: () -> Unit) {
    val thread = NSThread {
        // An exception escaping a Kotlin/Native thread terminates the app; report it instead.
        try {
            body()
        } catch (e: Throwable) {
            emitBridgeNotice("$name thread failed", e)
        }
    }
    thread.name = name
    thread.qualityOfService = NSQualityOfServiceUtility
    thread.start()
}
