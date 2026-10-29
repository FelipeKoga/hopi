package dev.koga.hopi.network.internal.ktor

import dev.koga.hopi.api.HopiRuntimes
import dev.koga.hopi.core.start.StartConfig
import dev.koga.hopi.ktor.HopiConfig
import dev.koga.hopi.ktor.HopiKtorHooks
import io.ktor.client.plugins.api.ClientPluginBuilder

/**
 * The forwarder registered in hopi-api's Ktor slot: it names the recorder, and nothing of the
 * recorder runs at registration (on ART this class's verification loads, without initializing, the
 * classes [install] names, KtorRecorder among them). Each client's install makes the [start]'s
 * installation first, then the client's own ([KtorRecorder.install]); [rules] is the network tool's
 * own switch.
 */
internal class KtorHooks(val start: StartConfig, val rules: Boolean) : HopiKtorHooks {
    override fun install(plugin: ClientPluginBuilder<HopiConfig>) {
        KtorRecorder.install(plugin, start, rules, null)
    }
}

/**
 * Registers the recorder when this app has hopi-ktor, for the network tool: false without it, or
 * when some are registered. A direct reference inside `try`, not a lookup by name: R8 renames both
 * sides of a reference, never a string, and a verification failure stays this function's class's.
 */
internal fun registerKtorHooks(start: StartConfig, rules: Boolean): Boolean = try {
    HopiRuntimes.registerKtorHooks(KtorHooks(start, rules))
} catch (_: LinkageError) {
    // NoClassDefFoundError: no hopi-ktor (HopiKtorHooks) in this app, or an older one without it.
    false
}
