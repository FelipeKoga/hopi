package dev.koga.hopi.ktor

import dev.koga.hopi.core.rules.FailKind
import java.lang.ref.WeakReference
import java.net.SocketException
import java.net.UnknownHostException

internal actual fun platformFailure(kind: FailKind, host: String): Throwable = when (kind) {
    FailKind.UNKNOWN_HOST -> UnknownHostException(host)
    else -> SocketException("Connection reset")
}

internal actual fun <T : Any> weakReference(value: T): () -> T? {
    val reference = WeakReference(value)
    return { reference.get() }
}
