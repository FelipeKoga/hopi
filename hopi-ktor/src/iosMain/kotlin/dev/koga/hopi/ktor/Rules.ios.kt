package dev.koga.hopi.ktor

import dev.koga.hopi.core.rules.FailKind
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlinx.io.IOException

internal actual fun platformFailure(kind: FailKind, host: String): Throwable = when (kind) {
    FailKind.UNKNOWN_HOST -> IOException("Could not resolve host: $host")
    else -> IOException("Connection reset by peer")
}

@OptIn(ExperimentalNativeApi::class)
internal actual fun <T : Any> weakReference(value: T): () -> T? {
    val reference = WeakReference(value)
    return { reference.get() }
}
