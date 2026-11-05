package dev.koga.hopi.ktor

import io.ktor.client.plugins.api.ClientPluginBuilder

internal actual fun installHooks(hooks: HopiKtorHooks, plugin: ClientPluginBuilder<HopiConfig>) {
    try {
        hooks.install(plugin)
    } catch (e: ExceptionInInitializerError) {
        // A recorder (or core) whose static initializer threw an exception: a bug, never a version
        // skew, whose missing class or member arrives as its own LinkageError even from an
        // initializer (the JVM wraps exceptions there, not errors). It fails the client.
        throw e
    } catch (_: LinkageError) {
        // A hopi-network of another version than this module (README: one version for every Hopi
        // artifact): its recorder reads this configuration before it installs anything, so the
        // client stays unrecorded instead of failing to build. Invalid caps still throw.
    }
}
