package dev.koga.hopi.perf

// The provider starts Hopi before Application.onCreate, so nothing to report: the start's notice
// counts a start made earlier, and a release build, or one without the plugin's perf block, never
// registers the runtime.
internal actual fun perfStartDroppedWithoutRuntime() {}
