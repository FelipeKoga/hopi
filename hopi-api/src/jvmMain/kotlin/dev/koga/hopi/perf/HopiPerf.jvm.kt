package dev.koga.hopi.perf

// On the JVM the perf runtime registers at hopi-start's Hopi.start (the JVM entry) unless its perf
// is false: a start before or without it is dropped and writes nothing here, and the start's
// notice counts it (docs/ARCHITECTURE.md, "Start sequence"). Hopi's own tests also register one.
internal actual fun perfStartDroppedWithoutRuntime() {}
