package dev.koga.hopi.ktor

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin

/**
 * The noop twin of `hopi-ktor`'s plugin: installing it runs the configuration block and
 * registers no hook, so the client behaves exactly as without it. Select it for release builds.
 */
public val Hopi: ClientPlugin<HopiConfig> = createClientPlugin("Hopi", ::HopiConfig) {}
