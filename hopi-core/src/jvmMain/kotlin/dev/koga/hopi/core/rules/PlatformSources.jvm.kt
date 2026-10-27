package dev.koga.hopi.core.rules

// Only the Ktor plugin records on a plain JVM: the OkHttp and HttpURLConnection capture is Android's.
internal actual val platformSources: Set<RuleSource> = setOf(RuleSource.KTOR)
