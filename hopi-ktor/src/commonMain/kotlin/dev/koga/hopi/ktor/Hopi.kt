package dev.koga.hopi.ktor

import dev.koga.hopi.api.HopiRuntimes
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin

/**
 * Records the client's exchanges into the process-wide Hopi store, for the platform log and the
 * agent bridge (the CLI and the desktop viewer), only where a debug build's network runtime
 * registered its recorder: on Android the provider's network tool, on the JVM `hopi-start`'s
 * `Hopi.start`. Otherwise it installs nothing, so the client is exactly as without it: a release
 * build, iOS (URLSession capture records the Darwin engine there; another engine is not recorded),
 * a JVM process before `Hopi.start`. The recorder is read once, when the client is built, so on a
 * client built before the start it installs nothing either.
 */
public val Hopi: ClientPlugin<HopiConfig> = createClientPlugin("Hopi", ::HopiConfig) {
    if (!pluginConfig.enabled) return@createClientPlugin
    (HopiRuntimes.ktorHooks as? HopiKtorHooks)?.let { installHooks(it, this) }
}
