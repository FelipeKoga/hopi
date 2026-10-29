package hopitest

import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ktor.HopiConfig
import dev.koga.hopi.ktor.HopiKtorHooks
import dev.koga.hopi.network.internal.ktor.KtorRecorder
import io.ktor.client.plugins.api.ClientPluginBuilder

/**
 * The Ktor plugin's recorder bound to the scenario's [store], with no start installation: the
 * network tool's KtorHooks less the start's settings. Loaded child-first with the scenarios, so its
 * recorder is the scenario loader's, over the test's one store class.
 */
internal class ScenarioKtorHooks(private val store: HopiStore) : HopiKtorHooks {
    override fun install(plugin: ClientPluginBuilder<HopiConfig>) {
        KtorRecorder.install(plugin, null, true, store)
    }
}
