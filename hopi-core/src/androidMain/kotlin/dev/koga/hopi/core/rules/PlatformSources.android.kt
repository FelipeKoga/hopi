package dev.koga.hopi.core.rules

// The Ktor plugin, and hopi-android's OkHttp interceptor and HttpURLConnection wrappers.
internal actual val platformSources: Set<RuleSource> = setOf(RuleSource.KTOR, RuleSource.OKHTTP, RuleSource.URL_CONNECTION)
