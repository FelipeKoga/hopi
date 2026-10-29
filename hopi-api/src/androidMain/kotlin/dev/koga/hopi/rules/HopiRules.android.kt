package dev.koga.hopi.rules

// The provider starts Hopi before Application.onCreate, so nothing to report: the start's notice
// counts a rule added earlier, and a release build never starts.
internal actual fun ruleDroppedWithoutRuntime() {}
