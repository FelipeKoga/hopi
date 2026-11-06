package dev.koga.hopi.core.capture

import platform.Foundation.NSProcessInfo

/** iOS has no heap limit to read: a share of the device's memory, as on a JVM heap of a sixteenth of it. */
internal actual fun platformFullBodyCap(): Int = fullBodyCapForHeap(NSProcessInfo.processInfo.physicalMemory.toLong() / 16)
