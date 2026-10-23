package dev.koga.hopi.sample

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun platformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp, configure)
