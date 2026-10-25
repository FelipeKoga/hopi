package dev.koga.hopi.core.concurrent

/**
 * Starts a daemon thread for Hopi's own background work (the store's consumer) at a priority
 * below the app's request threads: on Android nice 5, above `THREAD_PRIORITY_BACKGROUND` so the
 * thread keeps the app's scheduling policy and cgroup, on iOS the utility quality of service.
 * Under load the app's threads (and adbd, which carries the device's traffic to the host) win the
 * CPU; the work itself stays bounded by the store's queues. An exception escaping [body] is
 * reported as a notice line, never thrown into the app.
 */
internal expect fun startBackgroundThread(name: String, body: () -> Unit)
