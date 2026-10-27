package dev.koga.hopi.core.rules

// URLSession capture, and the Ktor plugin: it rules every engine URLSession capture does not cover.
internal actual val platformSources: Set<RuleSource> = setOf(RuleSource.KTOR, RuleSource.URL_SESSION)
