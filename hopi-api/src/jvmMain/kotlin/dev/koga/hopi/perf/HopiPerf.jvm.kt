package dev.koga.hopi.perf

// Nothing starts Hopi on the JVM before the runtime-split plan's stage 10 (its JVM entry), so no
// perf runtime registers there and HopiPerf does nothing, reported by nothing (docs/ARCHITECTURE.md,
// "Start sequence"). Only Hopi's own tests register one: hopi-perf's ApiPerf is internal, and its
// JVM PerfEntry is @HopiInternalApi.
internal actual fun perfStartDroppedWithoutRuntime() {}
