package dev.koga.hopi.urlsession

// No URLSession here: start() returns at once, so common code can call it.
internal actual fun platformStart(config: HopiUrlSessionConfig): Boolean = false
