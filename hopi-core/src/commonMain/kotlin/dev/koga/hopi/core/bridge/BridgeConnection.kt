package dev.koga.hopi.core.bridge

import dev.koga.hopi.core.HopiInternalApi

/**
 * One accepted bridge connection: a blocking byte stream in both directions. A transport adapts
 * its socket to this; everything above it ([BridgeSession]) is platform-neutral.
 */
@HopiInternalApi
public interface BridgeConnection {
    /** Blocks until bytes arrive; returns how many were read into [buffer], or -1 at end of input. */
    public fun read(buffer: ByteArray): Int

    /** Blocks until all [length] bytes of [bytes] are written. Throws once the connection is shut down. */
    public fun write(bytes: ByteArray, length: Int)

    /**
     * Ends both directions, unblocking a [read] or [write] parked on another thread. Idempotent and
     * safe from any thread. The underlying handle stays allocated until [close].
     */
    public fun shutdown()

    /** Releases the handle. Called once, after every thread using the connection has left it. */
    public fun close()
}

/** Accepts bridge connections. */
@HopiInternalApi
public interface BridgeListener {
    /**
     * Blocks until an authorized peer connects (a transport that checks peer identity closes the
     * others itself and keeps waiting). Returns null once [close] was called.
     */
    public fun accept(): BridgeConnection?

    /** Stops accepting and unblocks [accept]. */
    public fun close()
}
