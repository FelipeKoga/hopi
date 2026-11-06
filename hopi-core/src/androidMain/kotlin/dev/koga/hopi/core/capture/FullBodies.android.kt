package dev.koga.hopi.core.capture

internal actual fun platformFullBodyCap(): Int = fullBodyCapForHeap(Runtime.getRuntime().maxMemory())
