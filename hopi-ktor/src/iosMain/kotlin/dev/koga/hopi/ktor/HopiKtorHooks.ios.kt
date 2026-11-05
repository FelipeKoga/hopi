package dev.koga.hopi.ktor

import io.ktor.client.plugins.api.ClientPluginBuilder

// Nothing registers a recorder on iOS (URLSession capture records the Darwin engine), so this never
// runs there; Kotlin/Native has no LinkageError to catch.
internal actual fun installHooks(hooks: HopiKtorHooks, plugin: ClientPluginBuilder<HopiConfig>) {
    hooks.install(plugin)
}
