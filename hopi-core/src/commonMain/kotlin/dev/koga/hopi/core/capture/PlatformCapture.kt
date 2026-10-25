package dev.koga.hopi.core.capture

import dev.koga.hopi.core.HopiInternalApi
import kotlin.concurrent.atomics.AtomicReference

/**
 * The HTTP stacks a platform recorder (URLSession capture on iOS) records by itself, so the Ktor
 * plugin does not record the same request a second time: a client whose engine is covered sends
 * through the platform stack, and the platform record is the one kept (one record per request).
 * Read on every request by the plugin, so a recorder started after a client was built takes effect
 * at that client's next request.
 */
@HopiInternalApi
public object PlatformCapture {
    /**
     * The request header the Ktor plugin adds to a request of a covered engine that it records
     * itself after all: one whose body the platform stack hands over encoded, because the request
     * names its own `Accept-Encoding` (Ktor's `ContentEncoding`, or the app), and that Ktor then
     * decodes. A recorder that honors it ([coverWithPassThrough]) removes it before the request is
     * sent and records nothing for that request.
     */
    public const val PASS_THROUGH_HEADER: String = "X-Hopi-Pass-Through"

    /**
     * True once the Ktor plugin added [PASS_THROUGH_HEADER] to a request ([markPassThrough]): until
     * then a recorder looks for no marker, and a request costs it no header scan.
     */
    @kotlin.concurrent.Volatile
    public var passThroughMarked: Boolean = false
        private set

    /** Called by the Ktor plugin before it adds [PASS_THROUGH_HEADER] to a request. */
    public fun markPassThrough() {
        if (!passThroughMarked) passThroughMarked = true
    }

    private val covered = AtomicReference<List<String>>(emptyList())
    private val passThrough = AtomicReference<List<String>>(emptyList())

    /** Marks Ktor engines whose class name starts with [enginePackage] as recorded by the platform. */
    public fun cover(enginePackage: String) {
        add(covered, enginePackage)
    }

    /** [cover], by a recorder that honors [PASS_THROUGH_HEADER] (Android's OkHttp and `URLConnection` capture). */
    public fun coverWithPassThrough(enginePackage: String) {
        add(passThrough, enginePackage)
        add(covered, enginePackage)
    }

    /** True when the recorder covering an engine of class [engineClassName] honors [PASS_THROUGH_HEADER]. */
    public fun honorsPassThrough(engineClassName: String?): Boolean {
        if (engineClassName == null) return false
        return passThrough.load().any { engineClassName.startsWith(it) }
    }

    /** True when an engine of class [engineClassName] sends through a stack a platform recorder records. */
    public fun covers(engineClassName: String?): Boolean {
        if (engineClassName == null) return false
        val current = covered.load()
        if (current.isEmpty()) return false
        return current.any { engineClassName.startsWith(it) }
    }

    /** Test support: forgets every [cover] call. */
    public fun resetForTests() {
        covered.store(emptyList())
        passThrough.store(emptyList())
        passThroughMarked = false
    }

    private fun add(list: AtomicReference<List<String>>, enginePackage: String) {
        while (true) {
            val current = list.load()
            if (enginePackage in current) return
            if (list.compareAndSet(current, current + enginePackage)) return
        }
    }
}
