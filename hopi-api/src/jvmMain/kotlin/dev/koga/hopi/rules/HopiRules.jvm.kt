package dev.koga.hopi.rules

// On the JVM the rules runtime registers at hopi-start's Hopi.start (the JVM entry): an add before
// or without it returns -1 and writes nothing here, and the start's dropped-rules notice counts it
// (docs/ARCHITECTURE.md, "Start sequence"). Hopi's own tests also register one directly.
internal actual fun ruleDroppedWithoutRuntime() {}
