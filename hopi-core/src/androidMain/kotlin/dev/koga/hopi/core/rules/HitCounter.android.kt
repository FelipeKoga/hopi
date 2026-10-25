package dev.koga.hopi.core.rules

internal actual fun newHitCounter(): HitCounter = AtomicHitCounter()
