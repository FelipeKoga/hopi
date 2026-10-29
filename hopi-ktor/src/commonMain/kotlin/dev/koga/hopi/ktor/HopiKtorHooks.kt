package dev.koga.hopi.ktor

import dev.koga.hopi.api.HopiInternalApi
import io.ktor.client.plugins.api.ClientPluginBuilder

/**
 * The recorder a debug build's network runtime registers in hopi-api's Ktor slot
 * (HopiRuntimes.registerKtorHooks): hopi-network's, on Android and the JVM. [Hopi] reads the slot
 * once per installation and hands it its builder; with nothing registered (a release build, iOS, a
 * JVM process before Hopi.start, a client built before the start) it installs nothing. Only
 * hopi-network implements it, at this module's version.
 */
@HopiInternalApi
public interface HopiKtorHooks {
    /** Installs one client's hooks on [plugin] (its pluginConfig, client and on(…)); never with `enabled = false`. */
    public fun install(plugin: ClientPluginBuilder<HopiConfig>)
}

/** Hands [plugin] to [hooks]; on Android and the JVM a [hooks] of another version installs nothing. */
internal expect fun installHooks(hooks: HopiKtorHooks, plugin: ClientPluginBuilder<HopiConfig>)
