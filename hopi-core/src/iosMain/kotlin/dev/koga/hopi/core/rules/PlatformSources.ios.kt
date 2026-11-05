package dev.koga.hopi.core.rules

// URLSession capture only: the Ktor plugin installs nothing on iOS (its recorder is hopi-network's
// on Android and the JVM), and URLSession capture records and rules the Darwin engine.
internal actual val platformSources: Set<RuleSource> = setOf(RuleSource.URL_SESSION)
