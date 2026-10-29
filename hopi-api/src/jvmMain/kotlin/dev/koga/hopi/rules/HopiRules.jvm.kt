package dev.koga.hopi.rules

// Nothing starts Hopi on the JVM before the runtime-split plan's stage 10 (its JVM entry), so no
// runtime registers there and HopiRules returns -1, reported by nothing (docs/ARCHITECTURE.md,
// "Start sequence"). Only Hopi's own tests register one: core's ApiRules is internal, and its
// HopiStart is @HopiInternalApi.
internal actual fun ruleDroppedWithoutRuntime() {}
