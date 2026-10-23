package dev.koga.hopi.core.bridge

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.store.HopiStore
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/** Starts the device-local agent bridge serving [HopiStore] transactions. */
@HopiInternalApi
public fun interface AgentBridgeStarter {
    public fun start(store: HopiStore)
}

/**
 * The single point where the agent bridge implementation plugs in. Until a [AgentBridgeStarter] is
 * registered, a requested bridge is not started.
 */
@HopiInternalApi
public object AgentBridgeHook {
    private val starter = AtomicReference<AgentBridgeStarter?>(null)
    private val started = AtomicBoolean(false)

    public fun register(starter: AgentBridgeStarter) {
        this.starter.store(starter)
    }

    /**
     * Starts the bridge for [store] the first time it is called with a starter registered; later
     * calls do nothing. Returns false when no starter is registered.
     */
    public fun requestStart(store: HopiStore): Boolean {
        val current = starter.load() ?: return false
        if (started.compareAndSet(false, true)) current.start(store)
        return true
    }
}
