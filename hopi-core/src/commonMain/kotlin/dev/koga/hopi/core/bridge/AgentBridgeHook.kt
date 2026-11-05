package dev.koga.hopi.core.bridge

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.store.HopiStore
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/** Starts the device-local agent bridge serving [HopiStore] transactions. */
@HopiInternalApi
public fun interface AgentBridgeStarter {
    public fun start(store: HopiStore)
}

/**
 * The single point where the agent bridge plugs in. The starter is core's own platform bridge
 * (Android `LocalServerSocket`, iOS and JVM loopback plus token); [register] replaces it, which
 * tests use to observe a request without binding a socket.
 */
@HopiInternalApi
public object AgentBridgeHook {
    private val starter = AtomicReference(AgentBridgeStarter(::startPlatformAgentBridge))
    private val started = AtomicBoolean(false)

    public fun register(starter: AgentBridgeStarter) {
        this.starter.store(starter)
    }

    /**
     * Starts the bridge for [store] the first time it is called; later calls do nothing. The
     * platform starter binds and accepts on its own background thread, so this returns at once.
     * Returns true when a starter is registered (always, unless replaced by one that is not).
     */
    public fun requestStart(store: HopiStore): Boolean {
        val current = starter.load()
        if (started.compareAndSet(false, true)) current.start(store)
        return true
    }
}
